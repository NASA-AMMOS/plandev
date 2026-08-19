package gov.nasa.ammos.plandev.merlin.server.services;

import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationDatasetId;

public record ConstraintRequestConfiguration(
    PlanId planId,
    SimulationDatasetId simulationDatasetId,
    boolean force,
    String requestingUser
) {}
