package gov.nasa.jpl.plandev.merlin.driver;

import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.types.MissionModelId;

import java.time.Instant;
import java.util.Map;

public record SimulationEngineConfiguration(
    Map<String, SerializedValue> simulationConfiguration,
    Instant simStartTime,
    MissionModelId missionModelId
) {}
