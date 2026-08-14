package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;
import java.util.Optional;

public record ActivityAttributesRecord(
    Optional<Long> directiveId,
    Map<String, SerializedValue> arguments,
    Optional<SerializedValue> computedAttributes
) {}
