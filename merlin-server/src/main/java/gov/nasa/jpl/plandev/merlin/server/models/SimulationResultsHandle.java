package gov.nasa.jpl.plandev.merlin.server.models;

import gov.nasa.jpl.plandev.types.ActivityInstance;
import gov.nasa.jpl.plandev.types.ActivityInstanceId;
import gov.nasa.jpl.plandev.merlin.driver.SimulationResults;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

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
