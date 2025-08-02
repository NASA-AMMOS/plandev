package gov.nasa.jpl.aerie.types;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ActivityInstance(
  String type,
  Map<String, SerializedValue> arguments,
  Instant start,
  Duration duration,
  ActivityInstanceId parentId,
  List<ActivityInstanceId> childIds,
  Optional<ActivityDirectiveId> directiveId,
  SerializedValue computedAttributes
) {
  public ActivityInstance withDirectiveId(ActivityDirectiveId directiveId) {
    return new ActivityInstance(
        type,
        arguments,
        start,
        duration,
        parentId,
        childIds,
        Optional.of(directiveId),
        computedAttributes
    );
  }
}
