package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.driver.Reporter;
import gov.nasa.jpl.aerie.merlin.driver.SimulationException;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.http.ResponseSerializers;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.types.Plan;
import org.apache.commons.lang3.mutable.MutableBoolean;

import javax.json.Json;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public record SimulationAgent (
    PlanService planService,
    MissionModelService missionModelService,
    long simulationProgressPollPeriod
) {
  public void simulate(
      final PlanId planId,
      final RevisionData revisionData,
      final ResultsProtocol.WriterRole writer,
      final Supplier<Boolean> canceledListener,
      final Reporter reporter
  ) {
    final Plan plan;
    try {
      plan = this.planService.getPlanForSimulation(planId);

      // Validate plan revision
      final var currentRevisionData = this.planService.getPlanRevisionData(planId);
      final var validationResult = currentRevisionData.matches(revisionData);
      if (validationResult instanceof RevisionData.MatchResult.Failure failure) {
        writer.failWith(b -> b
            .type("SIMULATION_REQUEST_NOT_RELEVANT")
            .message("Simulation request no longer relevant: %s".formatted(failure.reason())));
        return;
      }
    } catch (final NoSuchPlanException ex) {
      writer.failWith(b -> b
          .type("NO_SUCH_PLAN")
          .message(ex.toString())
          .data(ResponseSerializers.serializeNoSuchPlanException(ex))
          .trace(ex));
      return;
    }

    MutableBoolean hadError = new MutableBoolean(false);
    try {
      // Validate plan activity construction
      final var failures = this.missionModelService.validateActivityInstantiations(
          plan.missionModelId(),
          plan.activityDirectives().entrySet().stream().collect(Collectors.toMap(
              Map.Entry::getKey,
              e -> e.getValue().serializedActivity())));

      if (!failures.isEmpty()) {
        writer.failWith(b -> b
            .type("PLAN_CONTAINS_UNCONSTRUCTABLE_ACTIVITIES")
            .message("Plan contains unconstructable activities")
            .data(ResponseSerializers.serializeUnconstructableActivityFailures(failures)));
        return;
      }

      try (final var extentListener = FixedRateListener.callAtFixedRate(
          writer::reportSimulationExtent,
          Duration.ZERO,
          simulationProgressPollPeriod)
      ) {
        this.missionModelService.runSimulation(
            plan,
            extentListener::updateValue,
            canceledListener,
            new Reporter() {
              @Override
              public void report(final Message message) {
                switch (message) {
                  case Message.AdvanceTime m -> {
                    extentListener.updateValue(m.startOffset());
                    reporter.report(m);
                  }
                  case Message.Error m -> {
                    hadError.setTrue();
                    var ex = m.exception();
                    final var errorMsgBuilder = Json.createObjectBuilder()
                      .add("elapsedTime", SimulationException.formatDuration(ex.elapsedTime))
                      .add("utcTimeDoy", SimulationException.formatInstant(ex.instant));
                    ex.directiveId.ifPresent(directiveId -> errorMsgBuilder.add("executingDirectiveId", directiveId.id()));
                    ex.activityType.ifPresent(activityType -> errorMsgBuilder.add("executingActivityType", activityType));
                    ex.activityStackTrace.ifPresent(activityStackTrace -> errorMsgBuilder.add("activityStackTrace", activityStackTrace));
                    writer.failWith(b -> b
                        .type("SIMULATION_EXCEPTION")
                        .message(ex.cause.getMessage())
                        .data(errorMsgBuilder.build())
                        .trace(ex.cause));
                    return;
                  }
                  case Message.UpdateProfile m -> {
                    reporter.report(m);
                  }
                  case Message.UpdateSpan m -> {
                    reporter.report(m);
                  }
                  case Message.DeclareProfile m -> {
                    reporter.report(m);
                  }
                }
              }

              @Override
              public void close() throws Exception {
                reporter.close();
              }
            });
      }
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      writer.failWith(b -> b
          .type("NO_SUCH_MISSION_MODEL")
          .message(ex.toString())
          .data(ResponseSerializers.serializeNoSuchMissionModelException(ex))
          .trace(ex));
      return;
    } catch (final MissionModelService.NoSuchActivityTypeException ex) {
      writer.failWith(b -> b
          .type("NO_SUCH_ACTIVITY_TYPE")
          .message("Activity of type `%s` could not be instantiated".formatted(ex.activityTypeId))
          .data(ResponseSerializers.serializeNoSuchActivityTypeException(ex))
          .trace(ex));
      return;
    }

    if(canceledListener.get()) {
//      writer.reportIncompleteResults(results);
    } else if (!hadError.booleanValue()) {
      writer.markSuccess();
    }
  }
}
