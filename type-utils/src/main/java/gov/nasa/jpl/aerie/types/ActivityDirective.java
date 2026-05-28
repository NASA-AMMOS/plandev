package gov.nasa.jpl.aerie.types;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.List;
import java.util.Map;

public record ActivityDirective(
    Duration startOffset,
    SerializedActivity serializedActivity,
    ActivityDirectiveId anchorId, // anchorId can be null
    boolean anchoredToStart,
    List<ActivitySource<?>> sourceList
) {
  public ActivityDirective(
      final Duration startOffset,
      final String type,
      final Map<String, SerializedValue> arguments,
      final ActivityDirectiveId anchorId,
      final boolean anchoredToStart,
      final List<ActivitySource<?>> sourceList)
  {
    this(
        startOffset,
        new SerializedActivity(type, (arguments != null) ? Map.copyOf(arguments) : null),
        anchorId,
        anchoredToStart,
        sourceList
    );
  }

  public ActivityDirective(
      final Duration startOffset,
      final SerializedActivity serializedActivity,
      final ActivityDirectiveId anchorId,
      final boolean anchoredToStart)
  {
    this(
        startOffset,
        serializedActivity,
        anchorId,
        anchoredToStart,
        List.of()
    );
  }

  public ActivityDirective(
      final Duration startOffset,
      final String type,
      final Map<String, SerializedValue> arguments,
      final ActivityDirectiveId anchorId,
      final boolean anchoredToStart)
  {
    this(
        startOffset,
        new SerializedActivity(type, (arguments != null) ? Map.copyOf(arguments) : null),
        anchorId,
        anchoredToStart,
        List.of()
    );
  }
}
