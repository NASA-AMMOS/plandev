package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.engine.SpanException;
import gov.nasa.jpl.aerie.merlin.driver.engine.SpanId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public final class SimulationDriver {
  public static <Model> SimulationResults simulate(
      final MissionModel<Model> missionModel,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Supplier<Boolean> simulationCanceled
  ) {
    simulate(
        missionModel,
        schedule,
        simulationStartTime,
        simulationDuration,
        planStartTime,
        planDuration,
        simulationCanceled,
        new Reporter() {
          @Override
          public void report(final Message message) {
            switch (message) {
              case Message.AdvanceTime m -> {
              }
              case Message.Error m -> {
              }
              case Message.UpdateProfile m -> {
              }
              case Message.UpdateSpan m -> {
              }
              case Message.DeclareProfile m -> {
              }
              case Message.DeclareTopic m -> {
              }
              case Message.Events m -> {
              }
              case Message.Finish m -> {

              }
            }
          }

          @Override
          public void close() throws Exception {

          }
        });
    return null;
  }

  public static class SpanState {
    public final SpanId id;
    public final Duration startOffset;

    public SpanState(final SpanId id, final Duration startOffset) {
      this.id = id;
      this.startOffset = startOffset;
    }
  }

  public static <Model> void simulate(
      final MissionModel<Model> missionModel,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration,
      final Supplier<Boolean> simulationCanceled,
      final Reporter reporter
  ) {
    try (final var engine = new SimulationEngine(missionModel.getInitialCells())) {

      /* The current real time. */
      reporter.report(new Reporter.Message.AdvanceTime(Duration.ZERO));

      // Specify a topic on which tasks can log the activity they're associated with.
      final var activityTopic = new Topic<ActivityDirectiveId>();

      try {
        engine.init(missionModel.getResources(), missionModel.getDaemon());

        for (final var entry : missionModel.getResources().entrySet()) {
          reporter.report(new Reporter.Message.DeclareProfile(entry.getKey(), entry.getValue().getOutputType().getSchema()));
        }

        final var serializableTopicToId = new HashMap<MissionModel.SerializableTopic<?>, Integer>();
        for (final var entry : missionModel.getTopics()) {
          final int topicId = serializableTopicToId.size();
          serializableTopicToId.put(entry, topicId);
          reporter.report(new Reporter.Message.DeclareTopic(topicId, entry.name(), entry.outputType().getSchema()));
        }

        // Get all activities as close as possible to absolute time
        // Schedule all activities.
        // Using HashMap explicitly because it allows `null` as a key.
        // `null` key means that an activity is not waiting on another activity to finish to know its start time
        HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> resolved = new StartOffsetReducer(planDuration, schedule).compute();
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

        Map<SpanId, SpanState> openSpans = new LinkedHashMap<>();
        SimulationEngine.SpanInfo spanInfo = new SimulationEngine.SpanInfo();
        final var spanInfoTrait = new SimulationEngine.SpanInfo.Trait(missionModel.getTopics(), activityTopic);
        final var usedActivityInstanceIds = new HashSet<>();

        // Drive the engine until we're out of time or until simulation is canceled.
        // TERMINATION: Actually, we might never break if real time never progresses forward.
        engineLoop:
        while (!simulationCanceled.get()) {
          if(simulationCanceled.get()) break;
          final var status = engine.step(simulationDuration);
          switch (status) {
            case SimulationEngine.Status.NoJobs noJobs: break engineLoop;
            case SimulationEngine.Status.AtDuration atDuration: break engineLoop;
            case SimulationEngine.Status.Nominal nominal:
              for (var commit : nominal.commits()) {
                commit.evaluate(spanInfoTrait, spanInfoTrait::atom).accept(spanInfo);
              }
              for (var commit : nominal.commits()) {
                reporter.report(new Reporter.Message.Events(engine.getElapsedTime(), engine.serializeEventGraph(
                    missionModel.getTopics(),
                    engine.spanToSimulatedActivities(spanInfo, usedActivityInstanceIds),
                    serializableTopicToId,
                    commit
                )));
              }
              for (final var spanUpdate : nominal.spanUpdates()) {
                switch (spanUpdate) {
                  case SimulationEngine.SpanUpdate.StartSpan s -> {

                    SerializedActivity input = spanInfo.input().get(s.id());
                    final SerializedValue payload = SerializedValue.of(input.getArguments());

                    final SpanState spanState = new SpanState(s.id(), engine.getElapsedTime());
                    openSpans.put(s.id(), spanState);

                    reporter.report(new Reporter.Message.UpdateSpan(
                        s.id().id(),
                        Optional.ofNullable(spanInfo.getDirective(s.id())).map(ActivityDirectiveId::id),
                        Optional.empty(),
                        engine.getElapsedTime(),
                        Optional.empty(),
                        input.getTypeName(),
                        payload));
                  }
                  case SimulationEngine.SpanUpdate.FinishSpan s -> {
                    final SpanState spanState = openSpans.get(s.id());
                    if (spanState != null) {
                      SerializedActivity input = spanInfo.input().get(s.id());
                      final SerializedValue payload = SerializedValue.of(input.getArguments());
                      reporter.report(new Reporter.Message.UpdateSpan(
                          s.id().id(),
                          Optional.ofNullable(spanInfo.getDirective(s.id())).map(ActivityDirectiveId::id),
                          Optional.empty(),
                          spanState.startOffset,
                          Optional.of(engine.getElapsedTime().minus(spanState.startOffset)),
                          input.getTypeName(),
                          payload));
                    }
                  }
                }
              }
              reporter.acceptUpdates(nominal.elapsedTime(), nominal.realResourceUpdates(), nominal.dynamicResourceUpdates());
              break;
          }
          reporter.report(new Reporter.Message.AdvanceTime(engine.getElapsedTime()));
        }
        reporter.report(new Reporter.Message.AdvanceTime(engine.getElapsedTime())); // Report the final simulation time
        reporter.report(new Reporter.Message.Finish());
      } catch (SpanException ex) {
        // Swallowing the spanException as the internal `spanId` is not user meaningful info.
        final var topics = missionModel.getTopics();
        final var directiveDetail = engine.getDirectiveDetailsFromSpan(activityTopic, topics, ex.spanId);
        if (directiveDetail.directiveId().isPresent()) {
          reporter.report(new Reporter.Message.Error(new SimulationException(
              engine.getElapsedTime(),
              simulationStartTime,
              directiveDetail.directiveId().get(),
              directiveDetail.activityStackTrace(),
              ex.cause)));
          return;
        }
        reporter.report(new Reporter.Message.Error(new SimulationException(engine.getElapsedTime(), simulationStartTime, ex.cause)));
        return;
      } catch (Throwable ex) {
        reporter.report(new Reporter.Message.Error(new SimulationException(engine.getElapsedTime(), simulationStartTime, ex)));
        return;
      }

//      final var topics = missionModel.getTopics();
//      return engine.computeResults(simulationStartTime, activityTopic, topics, reporter);
    }
  }

  // This method is used as a helper method for executing unit tests
  public static <Model, Return>
  void simulateTask(final MissionModel<Model> missionModel, final TaskFactory<Return> task) {
    try (final var engine = new SimulationEngine(missionModel.getInitialCells())) {
      // Track resources and kick off daemon tasks
      try {
        engine.init(missionModel.getResources(), missionModel.getDaemon());
      } catch (Throwable t) {
        throw new RuntimeException("Exception thrown while starting daemon tasks", t);
      }

      // Schedule the task.
      final var spanId = engine.scheduleTask(Duration.ZERO, task);

      // Drive the engine until the scheduled task completes.
      while (!engine.getSpan(spanId).isComplete()) {
        try {
          engine.step(Duration.MAX_VALUE);
        } catch (Throwable t) {
          throw new RuntimeException("Exception thrown while simulating tasks", t);
        }
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
      final var serializedDirective = schedule.get(directiveId).serializedActivity();

      final TaskFactory<?> task = deserializeActivity(missionModel, serializedDirective);

      engine.scheduleTask(startOffset, makeTaskFactory(
          directiveId,
          task,
          schedule,
          resolved,
          missionModel,
          activityTopic
      ));
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

    return executor -> {
      final var task = taskFactory.create(executor);
      return Task
          .callingWithSpan(
              Task.emitting(directiveId, activityTopic)
                  .andThen(task))
          .andThen(
              Task.spawning(
                  dependents
                      .stream()
                      .map(
                          dependent ->
                              TaskFactory.delaying(dependent.offset())
                                         .andThen(dependent.task()))
                      .toList()));
    };
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
}
