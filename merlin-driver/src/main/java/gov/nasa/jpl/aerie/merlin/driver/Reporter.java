package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;
import java.util.Optional;

public interface Reporter extends AutoCloseable {
  void report(Message message);

  default void acceptUpdates(Duration startOffset, Map<String, Pair<ValueSchema, RealDynamics>> realUpdates, Map<String, Pair<ValueSchema, SerializedValue>> discreteUpdates) {
    for (var entry: realUpdates.entrySet()) {
      final RealDynamics dynamics = entry.getValue().getValue();
      this.report(new Message.UpdateProfile(entry.getKey(), startOffset, SerializedValue.of(Map.of("initial", SerializedValue.of(dynamics.initial), "rate", SerializedValue.of(dynamics.rate)))));
    }
    for (var entry: discreteUpdates.entrySet()) {
      this.report(new Message.UpdateProfile(entry.getKey(), startOffset, entry.getValue().getValue()));
    }
    this.report(new Message.AdvanceTime(startOffset));
  }

  sealed interface Message {
    record AdvanceTime(Duration startOffset) implements Message {}

    record UpdateSpan(String spanId, Optional<Long> directiveId, Optional<String> parentSpanId, Duration startOffset, Optional<Duration> duration, String type, SerializedValue payload) implements Message {}

    record DeclareProfile(String profileName, ValueSchema schema) implements Message {}
    record UpdateProfile(String profileName, Duration startOffset, SerializedValue value) implements Message {}

    record DeclareTopic(long topicId, String topicName, ValueSchema schema) implements Message {}
    record Events(Duration startOffset, EventGraph<EventRecord> events) implements Message {}

    record Error(SimulationException exception) implements Message {}
    record Finish() implements Message {}
  }
}
