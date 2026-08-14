package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;

public record ConstraintRequestConfiguration(
    PlanId planId,
    SimulationDatasetId simulationDatasetId,
    boolean force,
    String requestingUser
) {}
