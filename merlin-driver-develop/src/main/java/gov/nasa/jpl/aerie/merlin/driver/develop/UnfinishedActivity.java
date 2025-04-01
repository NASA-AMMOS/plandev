package gov.nasa.jpl.aerie.merlin.driver.develop;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;

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
) { }
