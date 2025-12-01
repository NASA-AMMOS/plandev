package gov.nasa.jpl.plandev.merlin.server.remotes.postgres;

import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;

import java.util.Map;

public record ActivityDirectiveRecord(
    long id,
    String type,
    long startOffsetInMicros,
    Map<String, SerializedValue> arguments,
    Integer anchorId, // anchorId can be null (representing Plan)
    boolean anchoredToStart
) {}
