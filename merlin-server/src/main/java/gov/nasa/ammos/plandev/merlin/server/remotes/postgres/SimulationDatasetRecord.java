package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;


import gov.nasa.ammos.plandev.types.Timestamp;

public record SimulationDatasetRecord(
    long simulationId,
    long datasetId,
    SimulationStateRecord state,
    boolean canceled,
    Timestamp simulationStartTime,
    Timestamp simulationEndTime,
    long simulationDatasetId) {}
