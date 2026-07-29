package gov.nasa.ammos.plandev.merlin.driver;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.MissionModelId;

import java.time.Instant;
import java.util.Map;

public record SimulationEngineConfiguration(
    Map<String, SerializedValue> simulationConfiguration,
    Instant simStartTime,
    MissionModelId missionModelId
) {}
