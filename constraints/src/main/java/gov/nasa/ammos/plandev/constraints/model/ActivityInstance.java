package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.Map;
import java.util.Optional;

public record ActivityInstance(
    ActivityInstanceId instanceId,
    String type,
    Map<String, SerializedValue> parameters,
    Interval interval,
    Optional<ActivityDirectiveId> directiveId
) {
  public ActivityInstance(
      long id,
      String type,
      Map<String, SerializedValue> parameters,
      Interval interval
  ) {
    this(id, type, parameters, interval, Optional.empty());
  }

  public ActivityInstance(
      long id,
      String type,
      Map<String, SerializedValue> parameters,
      Interval interval,
      Optional<ActivityDirectiveId> directiveId
  ) {
    this(new ActivityInstanceId(id), type, parameters, interval, directiveId);
  }

  public long id() {
    return this.instanceId().id();
  }

  public ActivityInstance withDirectiveId(final ActivityDirectiveId directiveId) {
    return new ActivityInstance(
        instanceId,
        type,
        parameters,
        interval,
        Optional.of(directiveId)
    );
  }
}
