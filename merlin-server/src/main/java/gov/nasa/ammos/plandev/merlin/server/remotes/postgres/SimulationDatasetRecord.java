package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;


import gov.nasa.jpl.aerie.types.Timestamp;

public record SimulationDatasetRecord(
    long simulationId,
    long datasetId,
    SimulationStateRecord state,
    boolean canceled,
    Timestamp simulationStartTime,
    Timestamp simulationEndTime,
    long simulationDatasetId) {}
