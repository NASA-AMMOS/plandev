package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationDatasetId;

import java.util.Optional;

public record PlanDatasetRecord(
    long planId,
    long datasetId,
    Optional<SimulationDatasetId> simulationDatasetId,
    Duration offsetFromPlanStart) {}
