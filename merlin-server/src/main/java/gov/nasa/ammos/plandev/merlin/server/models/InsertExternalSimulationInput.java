package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.Timestamp;

import java.util.Map;

public record InsertExternalSimulationInput(
    PlanId planId,
    int resultsFileId,
    Timestamp planStartTime,
    Timestamp simulationStartTime,
    Duration simulationDuration,
    Map<String, SerializedValue> simulationArguments)
{}
