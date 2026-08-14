package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;

import java.util.Optional;

public record PlanDatasetRecord(
    long planId,
    long datasetId,
    Optional<SimulationDatasetId> simulationDatasetId,
    Duration offsetFromPlanStart) {}
