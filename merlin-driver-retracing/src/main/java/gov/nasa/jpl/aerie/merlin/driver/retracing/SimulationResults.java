package gov.nasa.jpl.aerie.merlin.driver.retracing;

import gov.nasa.jpl.aerie.merlin.driver.retracing.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.retracing.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

public final class SimulationResults {
  public final Instant startTime;
  public final Duration duration;
  public final Map<String, Pair<ValueSchema, List<ProfileSegment<RealDynamics>>>> realProfiles;
  public final Map<String, Pair<ValueSchema, List<ProfileSegment<SerializedValue>>>> discreteProfiles;
  public final Map<SimulatedActivityId, SimulatedActivity> simulatedActivities;
  public final Map<SimulatedActivityId, UnfinishedActivity> unfinishedActivities;
  public final List<Triple<Integer, String, ValueSchema>> topics;
  public final Map<Duration, List<EventGraph<Pair<Integer, SerializedValue>>>> events;

    public SimulationResults(
        final Map<String, Pair<ValueSchema, List<ProfileSegment<RealDynamics>>>> realProfiles,
        final Map<String, Pair<ValueSchema, List<ProfileSegment<SerializedValue>>>> discreteProfiles,
        final Map<SimulatedActivityId, SimulatedActivity> simulatedActivities,
        final Map<SimulatedActivityId, UnfinishedActivity> unfinishedActivities,
        final Instant startTime,
        final Duration duration,
        final List<Triple<Integer, String, ValueSchema>> topics,
        final SortedMap<Duration, List<EventGraph<Pair<Integer, SerializedValue>>>> events)
  {
    this.startTime = startTime;
    this.duration = duration;
    this.realProfiles = realProfiles;
    this.discreteProfiles = discreteProfiles;
    this.topics = topics;
    this.simulatedActivities = simulatedActivities;
    this.unfinishedActivities = unfinishedActivities;
    this.events = events;
  }

  @Override
  public String toString() {
    return
        "SimulationResults "
        + "{ startTime=" + this.startTime
        + ", realProfiles=" + this.realProfiles
        + ", discreteProfiles=" + this.discreteProfiles
        + ", simulatedActivities=" + this.simulatedActivities
        + ", unfinishedActivities=" + this.unfinishedActivities
        + " }";
  }
}
