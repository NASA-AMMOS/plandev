package gov.nasa.ammos.aerie.simulation.protocol;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Results(
        Instant startTime,
        Duration duration,
        Map<String, ResourceProfile<RealDynamics>> realProfiles,
        Map<String, ResourceProfile<SerializedValue>> discreteProfiles,
        Map<Long, SimulatedActivity> simulatedActivities
//        Map<SimulatedActivityId, UnfinishedActivity> unfinishedActivities,
//        List<Triple<Integer, String, ValueSchema>> topics,
//        Map<Duration, List<EventGraph<Pair<Integer, SerializedValue>>>> events
) {

  static Results empty() {
    return new Results(Instant.EPOCH, Duration.ZERO, Map.of(), Map.of(), Map.of());
  }

  public Instant getStartTime() {
      return this.startTime;
  }

  public Duration getDuration() {
      return this.duration;
  }

  public Map<String, ResourceProfile<RealDynamics>> getRealProfiles() {
      return this.realProfiles;
  }

  public Map<String, ResourceProfile<SerializedValue>> getDiscreteProfiles() {
      return this.discreteProfiles;
  }

  public Map<Long, SimulatedActivity> getSimulatedActivities() {
      return this.simulatedActivities;
  }


//  Set<Long> getRemovedActivities() {
//      return this.removedActivities;
//  }
//
//  Map<Long, UnfinishedActivity> getUnfinishedActivities() {
//      return this.unfinishedActivities;
//  }

//  List<Triple<Integer, String, ValueSchema>> getTopics() {
//      return this.topics;
//  }
//
//  Map<Duration, List<EventGraph<EventRecord>>> getEvents() {
//      return this.events;
//  }
}
