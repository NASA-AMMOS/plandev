package gov.nasa.jpl.aerie.types;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;
import java.util.function.Supplier;

public record ActivityDirective<T>(
    Duration startOffset,
    T thing,
    Supplier<SerializedActivity> thunk,
    ActivityDirectiveId anchorId, // anchorId can be null
    boolean anchoredToStart,
    String name
) {
  private ActivityDirective(
      final Duration startOffset,
      final SerializedActivity serializedActivity,
      final ActivityDirectiveId anchorId,
      final boolean anchoredToStart,
      final String name) {
    this(startOffset,
         (T) serializedActivity,
         () -> serializedActivity,
         anchorId,
         anchoredToStart,
         name);
  }

  public ActivityDirective(
      final Duration startOffset,
      final String type,
      final Map<String, SerializedValue> arguments,
      final ActivityDirectiveId anchorId,
      final boolean anchoredToStart,
      String name) {
    this(startOffset,
         new SerializedActivity(type, (arguments != null) ? Map.copyOf(arguments) : null),
         anchorId,
         anchoredToStart,
         name);
  }

  public SerializedActivity serializedActivity() {
    return this.thunk.get();
  }
}
