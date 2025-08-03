package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SimulationResultsInterface {

  default String makeString() {
    return
        "SimulationResults "
        + "{ startTime=" + this.getStartTime()
        + ", realProfiles=" + this.getRealProfiles()
        + ", discreteProfiles=" + this.getDiscreteProfiles()
        + ", simulatedActivities=" + this.getSimulatedActivities()
        + ", unfinishedActivities=" + this.getUnfinishedActivities()
        + " }";
  }

  Instant getStartTime();

  Duration getDuration();

  Map<String, ResourceProfile<RealDynamics>> getRealProfiles();

  Map<String, ResourceProfile<SerializedValue>> getDiscreteProfiles();

  Map<ActivityInstanceId, ActivityInstance> getSimulatedActivities();

  Set<ActivityInstanceId> getRemovedActivities();

  Map<ActivityInstanceId, UnfinishedActivity> getUnfinishedActivities();

  List<Triple<Integer, String, ValueSchema>> getTopics();

  Map<Duration, List<EventGraph<EventRecord>>> getEvents();
  SimulationResultsInterface replaceIds(Map<ActivityDirectiveId, ActivityDirectiveId> map);
}
