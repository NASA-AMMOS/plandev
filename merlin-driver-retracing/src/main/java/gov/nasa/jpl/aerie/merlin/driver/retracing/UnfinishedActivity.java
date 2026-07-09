package gov.nasa.jpl.aerie.merlin.driver.retracing;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record UnfinishedActivity(
  String type,
  Map<String, SerializedValue> arguments,
  Instant start,
  SimulatedActivityId parentId,
  List<SimulatedActivityId> childIds,
  Optional<ActivityDirectiveId> directiveId
) { }
