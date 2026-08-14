package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.engine.SpanException;
import gov.nasa.jpl.aerie.merlin.driver.engine.SpanId;
import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MAX_VALUE;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.min;

public class CheckpointSimulationDriver {
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckpointSimulationDriver.class);

  /**
   * Selects the best cached engine for simulating a given plan.
   * @param schedule the schedule/plan
   * @param cachedEngines a list of cached engines
   * @return the best cached engine as well as the map of corresponding activity ids for this engine
   */
  public static Optional<Pair<CachedSimulationEngine, Map<ActivityDirectiveId, ActivityDirectiveId>>> bestCachedEngine(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final List<CachedSimulationEngine> cachedEngines,
      final Duration planDuration
  ) {
    Optional<CachedSimulationEngine> bestCandidate = Optional.empty();
    final Map<ActivityDirectiveId, ActivityDirectiveId> correspondenceMap = new HashMap<>();
    final var minimumStartTimes = getMinimumStartTimes(schedule, planDuration);
    for (final var cachedEngine : cachedEngines) {
      if (bestCandidate.isPresent() && cachedEngine.endsAt().noLongerThan(bestCandidate.get().endsAt()))
        continue;

      final var activityDirectivesInCache = new HashMap<>(cachedEngine.activityDirectives());
      // Find the invalidation time
      var invalidationTime = Duration.MAX_VALUE;
      final var scheduledActivities = new HashMap<>(schedule);
      for (final var activity : scheduledActivities.entrySet()) {
        final var entryToRemove = activityDirectivesInCache.entrySet()
                                                           .stream()
                                                           .filter(e -> e.getValue().equals(activity.getValue()))
                                                           .findFirst();
        if (entryToRemove.isPresent()) {
          final var entry = entryToRemove.get();
          activityDirectivesInCache.remove(entry.getKey());
          correspondenceMap.put(activity.getKey(), entry.getKey());
        } else {
          invalidationTime = min(invalidationTime, minimumStartTimes.get(activity.getKey()));
        }
      }
      final var allActs = new HashMap<ActivityDirectiveId, ActivityDirective>();
      allActs.putAll(cachedEngine.activityDirectives());
      allActs.putAll(scheduledActivities);
      final var minimumStartTimeOfActsInCache = getMinimumStartTimes(allActs, planDuration);
      for (final var activity : activityDirectivesInCache.entrySet()) {
        invalidationTime = min(invalidationTime, minimumStartTimeOfActsInCache.get(activity.getKey()));
      }
      // (1) cachedEngine ends strictly after bestCandidate as per first line of this loop
      // and they both end  before the invalidation time: (2) the bestCandidate has already passed its invalidation time
      // test below (3) cacheEngine is before its invalidation time too per the test below.
      // (1) + (3) -> cachedEngine is strictly better than bestCandidate
      if (cachedEngine.endsAt().shorterThan(invalidationTime)) {
        bestCandidate = Optional.of(cachedEngine);
      }
    }

    bestCandidate.ifPresent(cachedSimulationEngine -> LOGGER.info("Re-using simulation engine at "
                                                                  + cachedSimulationEngine.endsAt()));
    return bestCandidate.map(cachedSimulationEngine -> Pair.of(cachedSimulationEngine, correspondenceMap));
  }



  public static Function<SimulationState, Boolean> desiredCheckpoints(final List<Duration> desiredCheckpoints) {
    return simulationState -> {
      for (final var desiredCheckpoint : desiredCheckpoints) {
        if (simulationState.currentTime().noLongerThan(desiredCheckpoint) && simulationState.nextTime().longerThan(
            desiredCheckpoint)) {
          return true;
        }
      }
      return false;
    };
  }

  public static Function<SimulationState, Boolean> checkpointAtEnd(Function<SimulationState, Boolean> stoppingCondition) {
    return simulationState -> stoppingCondition.apply(simulationState) || simulationState.nextTime.equals(MAX_VALUE);
  }

  private static Map<ActivityDirectiveId, Duration> getMinimumStartTimes(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Duration planDuration)
  {
    //For an anchored activity, it's minimum invalidationTime would be the sum of all startOffsets in its anchor chain
    // (plus or minus the plan duration depending on whether the root is anchored to plan start or plan end).
    // If it's a start anchor chain (as in, all anchors have anchoredToStart set to true),
    // this will give you its exact start time, but if there are any end-time anchors, this will give you the minimum time the activity could start at.
    final var minimumStartTimes = new HashMap<ActivityDirectiveId, Duration>();
    for (final var activity : schedule.entrySet()) {
      var curInChain = activity;
      var curSum = ZERO;
      while (true) {
        if (curInChain.getValue().anchorId() == null) {
          curSum = curSum.plus(curInChain.getValue().startOffset());
          curSum = !curInChain.getValue().anchoredToStart() ? curSum.plus(planDuration) : curSum;
          minimumStartTimes.put(activity.getKey(), curSum);
          break;
        } else {
          curSum = curSum.plus(curInChain.getValue().startOffset());
          curInChain = Map.entry(curInChain.getValue().anchorId(), schedule.get(curInChain.getValue().anchorId()));
        }
      }
    }
    return minimumStartTimes;
  }

  public record SimulationState(
      Duration currentTime,
      Duration nextTime,
      SimulationEngine simulationEngine,
      Map<ActivityDirectiveId, ActivityDirective> schedule,
      Map<ActivityDirectiveId, SpanId> activityDirectiveIdSpanIdMap
  ) {}

  /**
   * Simulates a plan/schedule while using and creating simulation checkpoints.
   * @param missionModel the mission model
   * @param schedule the plan/schedule
   * @param simulationStartTime the start time of the simulation
   * @param simulationDuration the simulation duration
   * @param planStartTime the plan overall start time
   * @param planDuration the plan overall duration
   * @param simulationExtentConsumer consumer to report simulation progress
   * @param simulationCanceled provider of an external stop signal
   * @param cachedEngine the simulation engine that is going to be used
   * @param shouldTakeCheckpoint a function from state of the simulation to boolean deciding when to take checkpoints
   * @param stopConditionOnPlan a function from state of the simulation to boolean deciding when to stop simulation
   * @param cachedEngineStore a store for simulation engine checkpoints taken. If capacity is 1, the simulation will
   *     behave like a resumable simulation.
   * @param configuration the simulation configuration
   * @return all the information to compute simulation results if needed
   */
  public static <Model> SimulationResultsComputerInputs simulateWithCheckpoints(
      final MissionModel<Model> missionModel,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Consumer<Duration> simulationExtentConsumer,
      final Supplier<Boolean> simulationCanceled,
      final CachedSimulationEngine cachedEngine,
      final Function<SimulationState, Boolean> shouldTakeCheckpoint,
      final Function<SimulationState, Boolean> stopConditionOnPlan,
      final CachedEngineStore cachedEngineStore,
      final SimulationEngineConfiguration configuration
  ) {
    final boolean duplicationIsOk = cachedEngineStore.capacity() > 1;
    final var activityToSpan = new HashMap<ActivityDirectiveId, SpanId>();
    final var activityTopic = cachedEngine.activityTopic();
    var engine = duplicationIsOk ? cachedEngine.simulationEngine().duplicate() : cachedEngine.simulationEngine();
    final var resourceManager = duplicationIsOk ? new InMemorySimulationResourceManager(cachedEngine.resourceManager()) : cachedEngine.resourceManager();
    engine.unscheduleAfter(cachedEngine.endsAt());

    /* The current real time. */
    var elapsedTime = Duration.max(ZERO, cachedEngine.endsAt());

    simulationExtentConsumer.accept(elapsedTime);

    try {
      // Get all activities as close as possible to absolute time
      // Using HashMap explicitly because it allows `null` as a key.
      // `null` key means that an activity is not waiting on another activity to finish to know its start time
      HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved = new StartOffsetReducer(
          planDuration,
          schedule).compute();
      if (!resolved.isEmpty()) {
        resolved.put(
            null,
            StartOffsetReducer.adjustStartOffset(
                resolved.get(null),
                Duration.of(
                    planStartTime.until(simulationStartTime, ChronoUnit.MICROS),
                    Duration.MICROSECONDS)));
      }
      // Filter out activities that are before simulationStartTime
      resolved = StartOffsetReducer.filterOutStartOffsetBefore(
          resolved,
          Duration.max(ZERO, cachedEngine.endsAt().plus(MICROSECONDS)));

      // Schedule all activities.
      final var toSchedule = new LinkedHashSet<ActivityDirectiveId>();
      toSchedule.add(null);
      final var activitiesToBeScheduledNow = new HashMap<ActivityDirectiveId, ActivityDirective>();
      if (resolved.get(null) != null) {
        for (final var r : resolved.get(null)) {
          activitiesToBeScheduledNow.put(r.getKey(), schedule.get(r.getKey()));
        }
      }
      var toCheckForDependencyScheduling = scheduleActivities(
          toSchedule,
          activitiesToBeScheduledNow,
          resolved,
          missionModel,
          engine,
          elapsedTime,
          activityToSpan,
          activityTopic);

      // Drive the engine until we're out of time.
      // TERMINATION: Actually, we might never break if real time never progresses forward.
      engineLoop:
      while (!simulationCanceled.get()) {
        final var nextTime = engine.peekNextTime().orElse(Duration.MAX_VALUE);
        if (duplicationIsOk && shouldTakeCheckpoint.apply(new SimulationState(
            elapsedTime,
            nextTime,
            engine,
            schedule,
            activityToSpan))
        ) {
            LOGGER.info("Saving a simulation engine in memory at time "
                        + elapsedTime
                        + " (next time: "
                        + nextTime
                        + ")");

            final var newCachedEngine = new CachedSimulationEngine(
                elapsedTime,
                schedule,
                engine,
                activityTopic,
                missionModel,
                new InMemorySimulationResourceManager(resourceManager)
            );

            newCachedEngine.freeze();
            cachedEngineStore.save(
                newCachedEngine,
                configuration);

            engine = engine.duplicate();
        }

        //break before changing the state of the engine
        if (simulationCanceled.get()) break;

        if (stopConditionOnPlan.apply(new SimulationState(elapsedTime, nextTime, engine, schedule, activityToSpan))) {
          if (!duplicationIsOk) {
            final var newCachedEngine = new CachedSimulationEngine(
                elapsedTime,
                schedule,
                engine,
                activityTopic,
                missionModel,
                resourceManager);
            cachedEngineStore.save(
                newCachedEngine,
                configuration);
          }
          break;
        }

        final var status = engine.step(simulationDuration);
        switch (status) {
          case SimulationEngine.Status.NoJobs noJobs: break engineLoop;
          case SimulationEngine.Status.AtDuration atDuration: break engineLoop;
          case SimulationEngine.Status.Nominal nominal:
            elapsedTime = nominal.elapsedTime();
            resourceManager.acceptUpdates(elapsedTime, nominal.realResourceUpdates(), nominal.dynamicResourceUpdates());
            toCheckForDependencyScheduling.putAll(scheduleActivities(
                getSuccessorsToSchedule(engine, toCheckForDependencyScheduling),
                schedule,
                resolved,
                missionModel,
                engine,
                elapsedTime,
                activityToSpan,
                activityTopic));
            break;
        }
        simulationExtentConsumer.accept(elapsedTime);
      }
    } catch (SpanException ex) {
      elapsedTime = engine.getElapsedTime();
      // Swallowing the spanException as the internal `spanId` is not user meaningful info.
      final var topics = missionModel.getTopics();
      final var directiveDetail = engine.getDirectiveDetailsFromSpan(activityTopic, topics, ex.spanId);
      if (directiveDetail.directiveId().isPresent()) {
        throw new SimulationException(
            elapsedTime,
            simulationStartTime,
            directiveDetail.directiveId().get(),
            directiveDetail.activityStackTrace(),
            ex.cause);
      }
      throw new SimulationException(elapsedTime, simulationStartTime, ex.cause);
    } catch (Throwable ex) {
      elapsedTime = engine.getElapsedTime();
      throw new SimulationException(elapsedTime, simulationStartTime, ex);
    }
    return new SimulationResultsComputerInputs(
        engine,
        simulationStartTime,
        activityTopic,
        missionModel.getTopics(),
        activityToSpan,
        resourceManager);
  }


  private static Set<ActivityDirectiveId> getSuccessorsToSchedule(
      final SimulationEngine engine,
      final Map<ActivityDirectiveId, SpanId> toCheckForDependencyScheduling
  ) {
    final var toSchedule = new LinkedHashSet<ActivityDirectiveId>();
    final var iterator = toCheckForDependencyScheduling.entrySet().iterator();
    while (iterator.hasNext()) {
      final var taskToCheck = iterator.next();
      if (engine.spanIsComplete(taskToCheck.getValue())) {
        toSchedule.add(taskToCheck.getKey());
        iterator.remove();
      }
    }
    return toSchedule;
  }

  private static <Model> Map<ActivityDirectiveId, SpanId> scheduleActivities(
      final Set<ActivityDirectiveId> toScheduleNow,
      final Map<ActivityDirectiveId, ActivityDirective> completeSchedule,
      final HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved,
      final MissionModel<Model> missionModel,
      final SimulationEngine engine,
      final Duration curTime,
      final Map<ActivityDirectiveId, SpanId> activityToTask,
      final Topic<ActivityDirectiveId> activityTopic
  ) {
    final var toCheckForDependencyScheduling = new HashMap<ActivityDirectiveId, SpanId>();
    for (final var predecessor : toScheduleNow) {
      if (!resolved.containsKey(predecessor)) continue;
      for (final var directivePair : resolved.get(predecessor)) {
        final var offset = directivePair.getRight();
        final var directiveIdToSchedule = directivePair.getLeft();
        final var serializedDirective = completeSchedule.get(directiveIdToSchedule).serializedActivity();
        final TaskFactory<?> task;
        try {
          task = missionModel.getTaskFactory(serializedDirective);
        } catch (final InstantiationException ex) {
          // All activity instantiations are assumed to be validated by this point
          throw new Error("Unexpected state: activity instantiation %s failed with: %s"
                              .formatted(serializedDirective.getTypeName(), ex.toString()));
        }
        Duration computedStartTime = offset;
        if (predecessor != null) {
          computedStartTime = (curTime.equals(Duration.MIN_VALUE) ? Duration.ZERO : curTime).plus(offset);
        }
        final var taskId = engine.scheduleTask(
            computedStartTime,
            executor ->
                Task.run(scheduler -> scheduler.emit(directiveIdToSchedule, activityTopic))
                    .andThen(task.create(executor)));
        activityToTask.put(directiveIdToSchedule, taskId);
        if (resolved.containsKey(directiveIdToSchedule)) {
          toCheckForDependencyScheduling.put(directiveIdToSchedule, taskId);
        }
      }
    }
    return toCheckForDependencyScheduling;
  }

  public static Function<SimulationState, Boolean> onceAllActivitiesAreFinished() {
    return simulationState -> simulationState.activityDirectiveIdSpanIdMap()
                                             .values()
                                             .stream()
                                             .allMatch(simulationState.simulationEngine()::spanIsComplete);
  }

  public static Function<SimulationState, Boolean> noCondition() {
    return simulationState -> false;
  }

  public static Function<SimulationState, Boolean> stopOnceActivityHasFinished(final ActivityDirectiveId activityDirectiveId) {
    return simulationState -> (simulationState.activityDirectiveIdSpanIdMap().containsKey(activityDirectiveId)
                               && simulationState.simulationEngine.spanIsComplete(simulationState
                                                                                      .activityDirectiveIdSpanIdMap()
                                                                                      .get(activityDirectiveId)));
  }
}
