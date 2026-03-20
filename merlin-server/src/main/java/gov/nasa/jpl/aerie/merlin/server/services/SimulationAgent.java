package gov.nasa.jpl.aerie.merlin.server.services;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import gov.nasa.jpl.aerie.merlin.driver.SimulationException;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.http.ResponseSerializers;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.types.Plan;

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
      final SimulationResourceManager resourceManager
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

    final SimulationResults results;
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
        results = this.missionModelService.runSimulation(
           plan,
            extentListener::updateValue,
            canceledListener,
            resourceManager);
      }
    } catch (SimulationException ex) {
      final var errorMsgBuilder = JsonNodeFactory.instance.objectNode();
      errorMsgBuilder.put("elapsedTime", SimulationException.formatDuration(ex.elapsedTime));
      errorMsgBuilder.put("utcTimeDoy", SimulationException.formatInstant(ex.instant));
      ex.directiveId.ifPresent(directiveId -> errorMsgBuilder.put("executingDirectiveId", directiveId.id()));
      ex.activityType.ifPresent(activityType -> errorMsgBuilder.put("executingActivityType", activityType));
      ex.activityStackTrace.ifPresent(activityStackTrace -> errorMsgBuilder.put("activityStackTrace", activityStackTrace));
      writer.failWith(b -> b
          .type("SIMULATION_EXCEPTION")
          .message(ex.cause.getMessage())
          .data(errorMsgBuilder)
          .trace(ex.cause));
      return;
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
      writer.reportIncompleteResults(results);
    } else {
      writer.succeedWith(results);
    }
  }
}
