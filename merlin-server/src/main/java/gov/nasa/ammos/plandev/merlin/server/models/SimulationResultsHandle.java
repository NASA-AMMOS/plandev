package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.types.ActivityInstance;
import gov.nasa.ammos.plandev.types.ActivityInstanceId;
import gov.nasa.ammos.plandev.merlin.driver.SimulationResults;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface SimulationResultsHandle {
  SimulationDatasetId getSimulationDatasetId();

  Instant startTime();

  Duration duration();

  SimulationResults getSimulationResults();

  ProfileSet getProfiles(final List<String> profileNames);

  Map<ActivityInstanceId, ActivityInstance> getSimulatedActivities();
}
