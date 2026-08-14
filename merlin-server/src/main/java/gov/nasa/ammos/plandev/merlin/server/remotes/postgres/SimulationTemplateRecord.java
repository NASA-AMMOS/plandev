package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;

import java.util.Map;

public record SimulationTemplateRecord(
    long id,
    long revision,
    long modelId,
    String description,
    Map<String, SerializedValue> arguments
    ) {}

