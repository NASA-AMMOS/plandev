package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;
import java.util.Optional;

/** A simulated span (activity instance) supplied by a foreign model backend. */
public record ExternalSpan(
    long spanId,
    Optional<Long> parentId,
    String type,
    Duration startOffset,                        // offset from simulation start
    Optional<Duration> duration,                 // empty => unfinished span
    Optional<Long> directiveId,
    Map<String, SerializedValue> arguments,
    Optional<SerializedValue> computedAttributes
) {}
