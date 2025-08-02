package gov.nasa.jpl.aerie.merlin.driver.resources;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;
import java.util.Set;

public interface SimulationResourceManager {

  /**
   * Compute all ProfileSegments stored in this resource manager
   * @param elapsedDuration the amount of time elapsed since the start of simulation.
   *
   * This method blocks until all profile segments have finished being processed
   */
  ResourceProfiles computeProfiles(final Duration elapsedDuration);

  /**
   * Compute a subset of the ProfileSegments stored in this resource manager
   * @param elapsedDuration the amount of time elapsed since the start of simulation.
   * @param resources the set of names of the resources to be computed
   */
  ResourceProfiles computeProfiles(final Duration elapsedDuration, Set<String> resources);

  /**
   * Process resource updates for a given time.
   * @param elapsedTime the amount of time elapsed since the start of simulation. Must be monotonically increasing on subsequent calls.
   * @param realResourceUpdates the set of updates to real resources. Up to one update per resource is permitted.
   * @param discreteResourceUpdates the set of updates to discrete resources. Up to one update per resource is permitted.
   */
  void acceptUpdates(
      final Duration elapsedTime,
      final Map<String, Pair<ValueSchema, RealDynamics>> realResourceUpdates,
      final Map<String, Pair<ValueSchema, SerializedValue>> discreteResourceUpdates
  );
}
