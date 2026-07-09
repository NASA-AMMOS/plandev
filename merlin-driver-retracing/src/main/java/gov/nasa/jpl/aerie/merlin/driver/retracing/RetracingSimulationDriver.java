package gov.nasa.jpl.aerie.merlin.driver.retracing;

import gov.nasa.ammos.aerie.simulation.protocol.Schedule;
import gov.nasa.jpl.aerie.merlin.driver.retracing.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.retracing.engine.SpanException;
import gov.nasa.jpl.aerie.merlin.driver.retracing.timeline.LiveCells;
import gov.nasa.jpl.aerie.merlin.driver.retracing.timeline.TemporalEventSource;
import gov.nasa.jpl.aerie.merlin.driver.retracing.tracing.TracedTaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RetracingSimulationDriver {
  /** Mutable cache */
  public record Cache(MissionModel<?> model, Map<SerializedActivity, TaskFactory<?>> taskFactoryCache) {
    public static Cache init(MissionModel<?> model) {
      return new Cache(model, new LinkedHashMap<>());
    }
    public TaskFactory<?> getTaskFactory(SerializedActivity serializedDirective) throws InstantiationException {
      if (taskFactoryCache.containsKey(serializedDirective)) {
        return taskFactoryCache.get(serializedDirective);
      } else {
        final var taskFactory = new TracedTaskFactory<>(model.getTaskFactory(serializedDirective));
        taskFactoryCache.put(serializedDirective, taskFactory);
        return taskFactory;
      }
    }
  }

  public static <Model>
  SimulationResults simulate(
      final MissionModel<Model> missionModel,
      final Schedule schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Supplier<Boolean> simulationCanceled,
      final Cache cache
  )
  {
    return simulate(
        missionModel,
        schedule,
        simulationStartTime,
        simulationDuration,
        planStartTime,
        planDuration,
        simulationCanceled,
        $ -> {},
        cache);
  }

  public static <Model>
  SimulationResults simulate(
      final MissionModel<Model> missionModel,
      final Schedule schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Supplier<Boolean> simulationCanceled,
      final Consumer<Duration> simulationExtentConsumer,
      final Cache cache
  ) {
    try (final var engine = new SimulationEngine()) {
      /* The top-level simulation timeline. */
      var timeline = new TemporalEventSource();
      var cells = new LiveCells(timeline, missionModel.getInitialCells());
      /* The current real time. */
      var elapsedTime = Duration.ZERO;

      simulationExtentConsumer.accept(elapsedTime);

      // Begin tracking all resources.
      for (final var entry : missionModel.getResources().entrySet()) {
        final var name = entry.getKey();
        final var resource = entry.getValue();

        engine.trackResource(name, resource, elapsedTime);
      }

      // Specify a topic on which tasks can log the activity they're associated with.
      final var activityTopic = new Topic<ActivityDirectiveId>();

      try {
        // Start daemon task(s) immediately, before anything else happens.
        engine.scheduleTask(Duration.ZERO, missionModel.getDaemon());
        {
          final var batch = engine.extractNextJobs(Duration.MAX_VALUE);
          final var commit = engine.performJobs(batch.jobs(), cells, elapsedTime, Duration.MAX_VALUE);
          timeline.add(commit.getLeft());
          if(commit.getRight().isPresent()) {
            throw commit.getRight().get();
          }
        }

        // Get all activities as close as possible to absolute time
        // Schedule all activities.
        // Using HashMap explicitly because it allows `null` as a key.
        // `null` key means that an activity is not waiting on another activity to finish to know its start time
        HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved = new StartOffsetReducer(planDuration, adaptSchedule(schedule)).compute();
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
            adaptSchedule(schedule),
            resolved,
            engine,
            activityTopic,
            cache
        );

        // Drive the engine until we're out of time or until simulation is canceled.
        // TERMINATION: Actually, we might never break if real time never progresses forward.
        while (!simulationCanceled.get()) {
          final var batch = engine.extractNextJobs(simulationDuration);

          // Increment real time, if necessary.
          final var delta = batch.offsetFromStart().minus(elapsedTime);
          elapsedTime = batch.offsetFromStart();
          timeline.add(delta);
          // TODO: Advance a dense time counter so that future tasks are strictly ordered relative to these,
          //   even if they occur at the same real time.

          simulationExtentConsumer.accept(elapsedTime);

          if (simulationCanceled.get() ||
              (batch.jobs().isEmpty() && batch.offsetFromStart().isEqualTo(simulationDuration))) {
            break;
          }

          // Run the jobs in this batch.
          final var commit = engine.performJobs(batch.jobs(), cells, elapsedTime, simulationDuration);
          timeline.add(commit.getLeft());
          if (commit.getRight().isPresent()) {
            throw commit.getRight().get();
          }
        }
      } catch (SpanException ex) {
        // Swallowing the spanException as the internal `spanId` is not user meaningful info.
        final var topics = missionModel.getTopics();
        final var directiveId = SimulationEngine.getDirectiveIdFromSpan(engine, activityTopic, timeline, topics, ex.spanId);
        if(directiveId.isPresent()) {
          throw new SimulationException(elapsedTime, simulationStartTime, directiveId.get(), ex.cause);
        }
        throw new SimulationException(elapsedTime, simulationStartTime, ex.cause);
      } catch (Throwable ex) {
        throw new SimulationException(elapsedTime, simulationStartTime, ex);
      }

      final var topics = missionModel.getTopics();
      return SimulationEngine.computeResults(engine, simulationStartTime, elapsedTime, activityTopic, timeline, topics);
    }
  }

  private static Map<ActivityDirectiveId, ActivityDirective> adaptSchedule(Schedule schedule) {
    final var res = new HashMap<ActivityDirectiveId, ActivityDirective>();
    for (var entry : schedule.entries()) {
      res.put(new ActivityDirectiveId(entry.id()),
              new ActivityDirective(
                  entry.startOffset(),
                  entry.directive().type(),
                  entry.directive().arguments(),
                  null,
                  true));
    }
    return res;
  }

  // This method is used as a helper method for executing unit tests
  public static <Model, Return>
  void simulateTask(final MissionModel<Model> missionModel, final TaskFactory<Return> task) {
    try (final var engine = new SimulationEngine()) {
      /* The top-level simulation timeline. */
      var timeline = new TemporalEventSource();
      var cells = new LiveCells(timeline, missionModel.getInitialCells());
      /* The current real time. */
      var elapsedTime = Duration.ZERO;

      // Begin tracking all resources.
      for (final var entry : missionModel.getResources().entrySet()) {
        final var name = entry.getKey();
        final var resource = entry.getValue();

        engine.trackResource(name, resource, elapsedTime);
      }

      // Start daemon task(s) immediately, before anything else happens.
      engine.scheduleTask(Duration.ZERO, missionModel.getDaemon());
      {
        final var batch = engine.extractNextJobs(Duration.MAX_VALUE);
          final var commit = engine.performJobs(batch.jobs(), cells, elapsedTime, Duration.MAX_VALUE);
          timeline.add(commit.getLeft());
          if(commit.getRight().isPresent()) {
             throw new RuntimeException("Exception thrown while starting daemon tasks", commit.getRight().get());
          }
      }

      // Schedule all activities.
      final var spanId = engine.scheduleTask(elapsedTime, task);

      // Drive the engine until we're out of time.
      // TERMINATION: Actually, we might never break if real time never progresses forward.
      while (!engine.getSpan(spanId).isComplete()) {
        final var batch = engine.extractNextJobs(Duration.MAX_VALUE);

        // Increment real time, if necessary.
        final var delta = batch.offsetFromStart().minus(elapsedTime);
        elapsedTime = batch.offsetFromStart();
        timeline.add(delta);
        // TODO: Advance a dense time counter so that future tasks are strictly ordered relative to these,
        //   even if they occur at the same real time.

        // Run the jobs in this batch.
        final var commit = engine.performJobs(batch.jobs(), cells, elapsedTime, Duration.MAX_VALUE);
        timeline.add(commit.getLeft());
        if(commit.getRight().isPresent()) {
          throw new RuntimeException("Exception thrown while simulating tasks", commit.getRight().get());
        }
      }
    }
  }


  private static void scheduleActivities(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved,
      final SimulationEngine engine,
      final Topic<ActivityDirectiveId> activityTopic,
      final Cache cache
  )
  {
    if(resolved.get(null) == null) { return; } // Nothing to simulate

    for (final Pair<ActivityDirectiveId, Duration> directivePair : resolved.get(null)) {
      final var directiveId = directivePair.getLeft();
      final var startOffset = directivePair.getRight();
      final var serializedDirective = schedule.get(directiveId).serializedActivity();

      final TaskFactory<?> task;
      try {
        task = cache.getTaskFactory(serializedDirective);
      } catch (final InstantiationException ex) {
        // All activity instantiations are assumed to be validated by this point
        throw new Error("Unexpected state: activity instantiation %s failed with: %s"
                            .formatted(serializedDirective.getTypeName(), ex.toString()));
      }

      engine.scheduleTask(startOffset, makeTaskFactory(
          directiveId,
          task,
          schedule,
          resolved,
          activityTopic,
          cache
      ));
    }
  }

  private static <Output> TaskFactory<Unit> makeTaskFactory(
      final ActivityDirectiveId directiveId,
      final TaskFactory<Output> task,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved,
      final Topic<ActivityDirectiveId> activityTopic,
      final Cache cache
  )
  {
    // Emit the current activity (defined by directiveId)
    return executor -> scheduler0 -> TaskStatus.calling(InSpan.Fresh, (TaskFactory<Output>) (executor1 -> scheduler1 -> {
      scheduler1.emit(directiveId, activityTopic);
      return task.create(executor1).step(scheduler1);
    }), scheduler2 -> {
      // When the current activity finishes, get the list of the activities that needed this activity to finish to know their start time
      final List<Pair<ActivityDirectiveId, Duration>> dependents = resolved.get(directiveId) == null ? List.of() : resolved.get(directiveId);
      // Iterate over the dependents
      for (final var dependent : dependents) {
        scheduler2.spawn(InSpan.Parent, executor2 -> scheduler3 ->
            // Delay until the dependent starts
            TaskStatus.delayed(dependent.getRight(), scheduler4 -> {
              final var dependentDirectiveId = dependent.getLeft();
              final var serializedDependentDirective = schedule.get(dependentDirectiveId).serializedActivity();

              // Initialize the Task for the dependent
              final TaskFactory<?> dependantTask;
              try {
                dependantTask = cache.getTaskFactory(serializedDependentDirective);
              } catch (final InstantiationException ex) {
                // All activity instantiations are assumed to be validated by this point
                throw new Error("Unexpected state: activity instantiation %s failed with: %s"
                                    .formatted(serializedDependentDirective.getTypeName(), ex.toString()));
              }

              // Schedule the dependent
              // When it finishes, it will schedule the activities depending on it to know their start time
              scheduler4.spawn(InSpan.Parent, makeTaskFactory(
                  dependentDirectiveId,
                  dependantTask,
                  schedule,
                  resolved,
                  activityTopic,
                  cache
              ));
              return TaskStatus.completed(Unit.UNIT);
            }));
      }
      return TaskStatus.completed(Unit.UNIT);
    });
  }
}
