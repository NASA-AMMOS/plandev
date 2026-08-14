package gov.nasa.ammos.plandev.merlin.driver;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;
import gov.nasa.ammos.plandev.types.ActivityInstanceId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record UnfinishedActivity(
  String type,
  Map<String, SerializedValue> arguments,
  Instant start,
  ActivityInstanceId parentId,
  List<ActivityInstanceId> childIds,
  Optional<ActivityDirectiveId> directiveId
) {
  public UnfinishedActivity withDirectiveId(ActivityDirectiveId directiveId) {
    return new UnfinishedActivity(
        type,
        arguments,
        start,
        parentId,
        childIds,
        Optional.of(directiveId)
    );
  }
}
