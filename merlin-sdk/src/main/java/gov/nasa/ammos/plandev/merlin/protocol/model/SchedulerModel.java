package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;

public interface SchedulerModel {
  Map<String, DurationType> getDurationTypes();
  SerializedValue serializeDuration(final Duration duration);
  Duration deserializeDuration(final SerializedValue serializedValue);
  Map<String, Duration> getMaximumDurations();
}
