package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraAction;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GetSimulationResultsAction {
  public sealed interface Response {
    record Pending(long simulationDatasetId) implements Response {}
    record Incomplete(long simulationDatasetId) implements Response {}
    record Failed(long simulationDatasetId, SimulationFailure reason) implements Response {}
    record Complete(long simulationDatasetId) implements Response {}
  }

  private final PlanService planService;
  private final SimulationService simulationService;

  public GetSimulationResultsAction(
      final PlanService planService,
      final SimulationService simulationService
  ) {
    this.planService = Objects.requireNonNull(planService);
    this.simulationService = Objects.requireNonNull(simulationService);
  }

  public Response run(final PlanId planId, final boolean forceResim, final HasuraAction.Session session)
  throws NoSuchPlanException, MissionModelService.NoSuchMissionModelException {
    final var revisionData = this.planService.getPlanRevisionData(planId);

    final var response = this.simulationService.getSimulationResults(planId, forceResim, revisionData, session.hasuraUserId());

    return switch (response) {
      case ResultsProtocol.State.Pending r -> new Response.Pending(r.simulationDatasetId());
      case ResultsProtocol.State.Incomplete r -> new Response.Incomplete(r.simulationDatasetId());
      case ResultsProtocol.State.Failed r -> new Response.Failed(r.simulationDatasetId(), r.reason());
      case ResultsProtocol.State.Success r -> new Response.Complete(r.simulationDatasetId());
      default -> throw new UnexpectedSubtypeError(ResultsProtocol.State.class, response);
    };
  }

  public Map<String, List<Pair<Duration, SerializedValue>>> getResourceSamples(final PlanId planId)
  throws NoSuchPlanException
  {
    final var revisionData = this.planService.getPlanRevisionData(planId);
    final var simulationResultsHandle$ = this.simulationService.get(planId, revisionData);
    if (simulationResultsHandle$.isEmpty()) return Collections.emptyMap();
    final var simulationResults = simulationResultsHandle$.get().getSimulationResults();

    final var samples = new HashMap<String, List<Pair<Duration, SerializedValue>>>();

    simulationResults.getRealProfiles().forEach((name, p) -> {
      var elapsed = Duration.ZERO;
      var profile = p.segments();

      final var timeline = new ArrayList<Pair<Duration, SerializedValue>>();
      for (final var piece : profile) {
        final var extent = piece.extent();
        final var dynamics = piece.dynamics();

        timeline.add(Pair.of(elapsed, SerializedValue.of(
            dynamics.initial)));
        elapsed = elapsed.plus(extent);
        timeline.add(Pair.of(elapsed, SerializedValue.of(
            dynamics.initial + dynamics.rate * extent.ratioOver(Duration.SECONDS))));
      }

      samples.put(name, timeline);
    });
    simulationResults.getDiscreteProfiles().forEach((name, p) -> {
      var elapsed = Duration.ZERO;
      var profile = p.segments();

      final var timeline = new ArrayList<Pair<Duration, SerializedValue>>();
      for (final var piece : profile) {
        final var extent = piece.extent();
        final var value = piece.dynamics();

        timeline.add(Pair.of(elapsed, value));
        elapsed = elapsed.plus(extent);
        timeline.add(Pair.of(elapsed, value));
      }

      samples.put(name, timeline);
    });

    return samples;
  }
}
