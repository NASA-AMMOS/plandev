package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.JobSchedule;
import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.engine.SpanException;
import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SubInstantDuration;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.apache.commons.lang3.tuple.Pair;
import java.util.ArrayList;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SimulationDriver<Model> {

  private static boolean debug = false;

  public SubInstantDuration curTime() {
    if (engine == null) {
      return SubInstantDuration.ZERO;
    }
    return engine.curTime();
  }

  public void setCurTime(SubInstantDuration time) {
    this.engine.setCurTime(time);
  }

  public void setCurTime(Duration time) {
    this.engine.setCurTime(time);
  }


  private SimulationEngine engine;
  private final MissionModel<Model> missionModel;
  private Instant startTime;
  private final Duration planDuration;
  private JobSchedule.Batch<SimulationEngine.JobId> batch;

  private static final Topic<ActivityDirectiveId> activityTopic = SimulationEngine.defaultActivityTopic;

  private Topic<Topic<?>> queryTopic = new Topic<>();

  /** Whether we're rerunning the simulation, in which case we reuse past results and have an old SimulationEngine */
  private boolean rerunning = false;

  public SimulationDriver(
      MissionModel<Model> missionModel, Instant startTime, Duration planDuration)
  {
    this.missionModel = missionModel;
    this.startTime = startTime;
    this.planDuration = planDuration;
    initSimulation(planDuration);
    batch = null;
  }


  public void initSimulation(final Duration simDuration) {
    if (debug) System.out.println("SimulationDriver.initSimulation()");
    // If rerunning the simulation, reuse the existing SimulationEngine to avoid redundant computation
    this.rerunning = this.engine != null && this.engine.timeline.commitsByTime.size() > 1;
    if (this.engine != null) this.engine.close();
    SimulationEngine oldEngine = rerunning ? this.engine : null;
    if (oldEngine != null && oldEngine.failed) {
      oldEngine = oldEngine.oldEngine;
      this.rerunning = this.rerunning && oldEngine != null;
    }

    this.engine = new SimulationEngine(startTime, missionModel, oldEngine);

    engine.init(rerunning);

    // The sole purpose of this task is to make sure the simulation has "stuff to do" until the simulationDuration.
//    engine.scheduleTask(
//        simDuration,
//        executor -> $ -> TaskStatus.completed(Unit.UNIT),
//        null); // TODO: skip this if rerunning? and end time is same?
  }


  public static <Model> SimulationResultsInterface simulate(
      final MissionModel<Model> missionModel,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Supplier<Boolean> simulationCanceled
  ) {
    return simulate(
        missionModel,
        schedule,
        simulationStartTime,
        simulationDuration,
        planStartTime,
        planDuration,
        simulationCanceled,
        $ -> {},
        new InMemorySimulationResourceManager());
  }

  public static <Model> SimulationResultsInterface simulate(
      final MissionModel<Model> missionModel,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Supplier<Boolean> simulationCanceled,
      final Consumer<Duration> simulationExtentConsumer,
      final SimulationResourceManager resourceManager
  )
  {
    var driver = new SimulationDriver<>(
        missionModel, simulationStartTime, simulationDuration);
    return driver.simulate(
        schedule, simulationStartTime, simulationDuration,
        planStartTime, planDuration, true,
        simulationCanceled, simulationExtentConsumer,
        resourceManager);
  }

  public SimulationResultsInterface simulate(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration
  ) {
    return simulate(
        schedule, simulationStartTime, simulationDuration,
        planStartTime, planDuration,
        true, () -> false, $ -> {},
        new InMemorySimulationResourceManager());
  }

  public SimulationResultsInterface simulate(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Supplier<Boolean> simulationCanceled,
      final Consumer<Duration> simulationExtentConsumer
  ) {
    return simulate(
        schedule, simulationStartTime, simulationDuration,
        planStartTime, planDuration,
        true, simulationCanceled, simulationExtentConsumer,
        new InMemorySimulationResourceManager());
  }

  public SimulationResultsInterface simulate(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final boolean doComputeResults,
      final Supplier<Boolean> simulationCanceled,
      final Consumer<Duration> simulationExtentConsumer,
      final SimulationResourceManager resourceManager
  ) {
      if (debug) System.out.println("SimulationDriver.simulate(" + schedule + ")");

      if (engine.scheduledDirectives == null) {
        engine.scheduledDirectives = new LinkedHashMap<>(schedule);
      }

      /* The current real time. */
      simulationExtentConsumer.accept(curTime().duration());

      try {
        // Get all activities as close as possible to absolute time
        // Schedule all activities.
        // Using HashMap explicitly because it allows `null` as a key.
        // `null` key means that an activity is not waiting on another activity to finish to know its start time
        HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved = new StartOffsetReducer(planDuration, getEngine().scheduledDirectives).compute();
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
        resolved = StartOffsetReducer.filterOutNegativeStartOffset(resolved);

        scheduleActivities(
            schedule,
            resolved,
            missionModel,
            engine,
            activityTopic
        );

        // Drive the engine until we're out of time or until simulation is canceled.
        // TERMINATION: Actually, we might never break if real time never progresses forward.
//        Duration t = Duration.ZERO;
//        while (!simulationCanceled.get() && (engine.hasJobsScheduledThrough(simulationDuration) || t.noLongerThan(simulationDuration))) {
        engineLoop:
        while (!simulationCanceled.get()) {
          if(simulationCanceled.get()) break;
          final var status = engine.step(simulationDuration,simulationExtentConsumer);
          switch (status) {
            case SimulationEngine.Status.NoJobs noJobs: break engineLoop;
            case SimulationEngine.Status.AtDuration atDuration: break engineLoop;
            case SimulationEngine.Status.Nominal nominal:
              resourceManager.acceptUpdates(nominal.elapsedTime(), nominal.realResourceUpdates(), nominal.dynamicResourceUpdates());
              break;
          }
          simulationExtentConsumer.accept(engine.getElapsedTime());
        }

        simulationExtentConsumer.accept(engine.getElapsedTime()); // Report the final simulation time

      } catch (SpanException ex) {
        // Swallowing the spanException as the internal `spanId` is not user meaningful info.
        final var topics = missionModel.getTopics();
        final var directiveDetail = engine.getDirectiveDetailsFromSpan(activityTopic, topics, ex.spanId);
        if(directiveDetail.directiveId().isPresent()) {
          throw new SimulationException(
              engine.getElapsedTime(),
              simulationStartTime,
              directiveDetail.directiveId().get(),
              directiveDetail.activityStackTrace(),
              ex.cause);
        }
        throw new SimulationException(engine.getElapsedTime(), simulationStartTime, ex.cause);
      } catch (Throwable ex) {
        throw new SimulationException(engine.getElapsedTime(), simulationStartTime, ex);
      }

      // A query depends on an event if
      // - that event has the same topic as the query
      // - that event occurs causally before the query

      // Let A be an event or query issued by task X, and B be either an event or query issued by task Y
      // A flows to B if B is causally after A and
      // - X = Y
      // - X spawned Y causally after A
      // - Y called X, and emitted B after X terminated
      // - Transitively: if A flows to C and C flows to B, A flows to B
      // still not enough...?

      if (doComputeResults) {
        final var topics = missionModel.getTopics();
        return engine.computeResults(
            simulationStartTime, engine.getElapsedTime(), activityTopic, topics, resourceManager);
      } else {
        return null;
      }
  }


  public SimulationResultsInterface diffAndSimulate(
      Map<ActivityDirectiveId, ActivityDirective> activityDirectives,
      Instant simulationStartTime,
      Duration simulationDuration,
      Instant planStartTime,
      Duration planDuration) {
    return diffAndSimulate(
        activityDirectives, simulationStartTime, simulationDuration,
        planStartTime, planDuration,
        true, () -> false, $ -> {},
        new InMemorySimulationResourceManager());
  }

  public SimulationResultsInterface diffAndSimulate(
      Map<ActivityDirectiveId, ActivityDirective> activityDirectives,
      Instant simulationStartTime,
      Duration simulationDuration,
      Instant planStartTime,
      Duration planDuration,
      boolean doComputeResults,
      final Supplier<Boolean> simulationCanceled,
      final Consumer<Duration> simulationExtentConsumer,
      final SimulationResourceManager resourceManager) {
    Map<ActivityDirectiveId, ActivityDirective> directives = activityDirectives;
    engine.scheduledDirectives = new HashMap<>(activityDirectives);  // was null before this
    if (engine.oldEngine != null) {
      engine.directivesDiff = engine.oldEngine.diffDirectives(activityDirectives);
      if (debug) System.out.println("SimulationDriver: engine.directivesDiff = " + engine.directivesDiff);
      engine.oldEngine.scheduledDirectives = null;  // only keep the full schedule for the current engine to save space
      directives = new HashMap<>(engine.directivesDiff.get("added"));
      directives.putAll(engine.directivesDiff.get("modified"));
      engine.directivesDiff.get("modified").forEach((k, v) -> engine.removeTaskHistory(engine.getTaskIdForDirectiveId(k), SubInstantDuration.MIN_VALUE, null));
      // FIXME? -- Above, modified directives have their task history removed, but the new activities/tasks will have new TaskIds, SpanIds, etc.  Don't we assume they stay the same, or does it not matter?
      //engine.directivesDiff.get("removed").forEach((k, v) -> engine.removeTaskHistory(engine.oldEngine.getTaskIdForDirectiveId(k)));
      engine.directivesDiff.get("removed").forEach((k, v) -> engine.removeActivity(k));
    }
    return this.simulate(
        directives, simulationStartTime, simulationDuration, planStartTime, planDuration,
        doComputeResults, simulationCanceled, simulationExtentConsumer, resourceManager);
  }

  private void startDaemons(Duration time) throws Throwable {
    if (!this.rerunning) {
      engine.scheduleTask(Duration.ZERO, missionModel.getDaemon(), null);
      engine.step(Duration.MAX_VALUE, $ -> {});
    }
  }

  private void trackResources() {
    // Begin tracking any resources that have not already been simulated.
    for (final var entry : missionModel.getResources().entrySet()) {
      final var name = entry.getKey();
      final var resource = entry.getValue();
      engine.trackResource(name, resource, Duration.ZERO);
    }
  }

  // This method is used as a helper method for executing unit tests
  public <Return>
  void simulateTask(final TaskFactory<Return> task) {
    if (debug) System.out.println("SimulationDriver.simulateTask(" + task + ")");

      // Schedule the task.
      final var spanId = engine.scheduleTask(curTime().duration(), task, null);

      // Drive the engine until the scheduled task completes.
      while (!engine.getSpan(spanId).isComplete()) {
        try {
          engine.step(Duration.MAX_VALUE, $->{});
        } catch (Throwable t) {
          throw new RuntimeException("Exception thrown while simulating tasks", t);
        }
      }
  }

  private static <Model> void scheduleActivities(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved,
      final MissionModel<Model> missionModel,
      final SimulationEngine engine,
      final Topic<ActivityDirectiveId> activityTopic
  ) {
    if (resolved.get(null) == null) {
      // Nothing to simulate
      return;
    }
    for (final Pair<ActivityDirectiveId, Duration> directivePair : resolved.get(null)) {
      final var directiveId = directivePair.getLeft();
      final var startOffset = directivePair.getRight();
      ActivityDirective d = schedule.get(directiveId);
      if (d == null) continue;
      final var serializedDirective = d.serializedActivity();

      final TaskFactory<?> task = deserializeActivity(missionModel, serializedDirective);

      engine.scheduleTask(
          startOffset,
          makeTaskFactory(
              directiveId,
              task,
              schedule,
              resolved,
              missionModel,
              activityTopic
              ),
          null
      );
    }
  }

  private static <Model, Output> TaskFactory<Unit> makeTaskFactory(
      final ActivityDirectiveId directiveId,
      final TaskFactory<Output> taskFactory,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved,
      final MissionModel<Model> missionModel,
      final Topic<ActivityDirectiveId> activityTopic
  ) {
    record Dependent(Duration offset, TaskFactory<?> task) {}

    final List<Dependent> dependents = new ArrayList<>();
    for (final var pair : resolved.getOrDefault(directiveId, List.of())) {
      dependents.add(new Dependent(
          pair.getRight(),
          makeTaskFactory(
              pair.getLeft(),
              deserializeActivity(missionModel, schedule.get(pair.getLeft()).serializedActivity()),
              schedule,
              resolved,
              missionModel,
              activityTopic)));
    }

    return executor -> scheduler0 ->
        TaskStatus.calling(
              InSpan.Fresh,
              (TaskFactory<Output>) (executor1 -> scheduler1 -> {
                scheduler1.startDirective(directiveId, activityTopic);
                return taskFactory.create(executor1).step(scheduler1);
              }),
              Task.spawning(
                  dependents
                      .stream()
                      .map(
                          dependent ->
                              TaskFactory.delaying(dependent.offset())
                                         .andThen(dependent.task()))
                      .toList()));
  }

  private static <Model> TaskFactory<?> deserializeActivity(MissionModel<Model> missionModel, SerializedActivity serializedDirective) {
    final TaskFactory<?> task;
    try {
      task = missionModel.getTaskFactory(serializedDirective);
    } catch (final InstantiationException ex) {
      // All activity instantiations are assumed to be validated by this point
      throw new Error("Unexpected state: activity instantiation %s failed with: %s"
                          .formatted(serializedDirective.getTypeName(), ex.toString()));
    }
    return task;
  }

  public SimulationResultsInterface computeResults(Instant startTime, Duration simDuration) {
    final var topics = missionModel.getTopics();
    return engine.computeResults(
        startTime, simDuration, activityTopic, topics, new InMemorySimulationResourceManager());
  }

  public SimulationEngine getEngine() {
    return engine;
  }

  public MissionModel<Model> getMissionModel() {
    return missionModel;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public Duration getPlanDuration() {
    return planDuration;
  }
}
