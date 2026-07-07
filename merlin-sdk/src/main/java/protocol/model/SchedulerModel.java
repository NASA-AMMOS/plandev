package protocol.model;

import protocol.types.Duration;
import protocol.types.DurationType;
import protocol.types.SerializedValue;

import java.util.Map;

public interface SchedulerModel {
  Map<String, DurationType> getDurationTypes();
  SerializedValue serializeDuration(final Duration duration);
  Duration deserializeDuration(final SerializedValue serializedValue);
  Map<String, Duration> getMaximumDurations();
}
