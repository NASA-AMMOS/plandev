package gov.nasa.jpl.aerie.types;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;

public record ActivityDirective(
    Duration startOffset,
    SerializedActivity serializedActivity,
    ActivityDirectiveId anchorId, // anchorId can be null
    boolean anchoredToStart
) {
  public ActivityDirective(
      final Duration startOffset,
      final String type,
      final Map<String, SerializedValue> arguments,
      final ActivityDirectiveId anchorId,
      final boolean anchoredToStart) {
    this(startOffset,
         new SerializedActivity(type, (arguments != null) ? Map.copyOf(arguments) : null),
         anchorId,
         anchoredToStart);
  }
}
