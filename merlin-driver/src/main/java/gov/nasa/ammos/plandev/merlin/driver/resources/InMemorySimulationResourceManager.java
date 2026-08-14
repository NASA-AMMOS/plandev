package gov.nasa.jpl.aerie.merlin.driver.resources;

import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A variant of the SimulationResourceManager that keeps all segments in memory
 */
public class InMemorySimulationResourceManager implements SimulationResourceManager {
  private final HashMap<String, ResourceSegments<RealDynamics>> realResourceSegments;
  private final HashMap<String, ResourceSegments<SerializedValue>> discreteResourceSegments;

  private Duration lastReceivedTime;

  public InMemorySimulationResourceManager() {
    this.realResourceSegments = new HashMap<>();
    this.discreteResourceSegments = new HashMap<>();
    lastReceivedTime = Duration.ZERO;
  }

  public InMemorySimulationResourceManager(InMemorySimulationResourceManager other) {
    this.realResourceSegments = new HashMap<>(other.realResourceSegments.size());
    this.discreteResourceSegments = new HashMap<>(other.discreteResourceSegments.size());

    this.lastReceivedTime = other.lastReceivedTime;

    // Deep copy the resource maps
    for(final var entry : other.realResourceSegments.entrySet()) {
      final var segments = entry.getValue().deepCopy();
      realResourceSegments.put(entry.getKey(), segments);
    }
    for(final var entry : other.discreteResourceSegments.entrySet()) {
      final var segments = entry.getValue().deepCopy();
      discreteResourceSegments.put(entry.getKey(), segments);
    }
  }

  /**
   * Clear out the Resource Manager's cache of Resource Segments
   */
  public void clear() {
    realResourceSegments.clear();
    discreteResourceSegments.clear();
  }

  /**
   * Compute all ProfileSegments stored in this resource manager.
   * @param elapsedDuration the amount of time elapsed since the start of simulation.
   */
  @Override
  public ResourceProfiles computeProfiles(final Duration elapsedDuration) {
    final var keySet = new HashSet<>(realResourceSegments.keySet());
    keySet.addAll(discreteResourceSegments.keySet());
    return computeProfiles(elapsedDuration, keySet);
  }

  /**
   * Compute a subset of the ProfileSegments stored in this resource manager
   * @param elapsedDuration the amount of time elapsed since the start of simulation.
   * @param resources the set of names of the resources to be computed
   */
  @Override
  public ResourceProfiles computeProfiles(final Duration elapsedDuration, Set<String> resources) {
    final var profiles = new ResourceProfiles(new HashMap<>(), new HashMap<>());

    // Compute Real Profiles
    for(final var resource : realResourceSegments.entrySet()) {
      final var name = resource.getKey();
      final var schema = resource.getValue().valueSchema();
      final var segments = resource.getValue().segments();

      if(!resources.contains(name)) continue;

      profiles.realProfiles().put(name, new ResourceProfile<>(schema, new ArrayList<>()));
      final var profile = profiles.realProfiles().get(name).segments();

      for(int i = 0; i < segments.size()-1; i++) {
        final var segment = segments.get(i);
        final var nextSegment = segments.get(i+1);
        profile.add(new ProfileSegment<>(nextSegment.startOffset().minus(segment.startOffset()), segment.dynamics()));
      }

      // Process final segment
      final var finalSegment = segments.getLast();
      profile.add(new ProfileSegment<>(elapsedDuration.minus(finalSegment.startOffset()), finalSegment.dynamics()));
    }

    // Compute Discrete Profiles
    for(final var resource : discreteResourceSegments.entrySet()) {
      final var name = resource.getKey();
      final var schema = resource.getValue().valueSchema();
      final var segments = resource.getValue().segments();

      if(!resources.contains(name)) continue;

      profiles.discreteProfiles().put(name, new ResourceProfile<>(schema, new ArrayList<>()));
      final var profile = profiles.discreteProfiles().get(name).segments();

      for(int i = 0; i < segments.size()-1; i++) {
        final var segment = segments.get(i);
        final var nextSegment = segments.get(i+1);
        profile.add(new ProfileSegment<>(nextSegment.startOffset().minus(segment.startOffset()), segment.dynamics()));
      }

      // Process final segment
      final var finalSegment = segments.getLast();
      profile.add(new ProfileSegment<>(elapsedDuration.minus(finalSegment.startOffset()), finalSegment.dynamics()));
    }

    return profiles;
  }

  /**
   * Add new segments to this manager's internal store of segments.
   * @param elapsedTime the amount of time elapsed since the start of simulation.  Must be monotonically increasing on subsequent calls.
   * @param realResourceUpdates the set of updates to real resources. Up to one update per resource is permitted.
   * @param discreteResourceUpdates the set of updates to discrete resources. Up to one update per resource is permitted.
   */
  @Override
  public void acceptUpdates(
      final Duration elapsedTime,
      final Map<String, Pair<ValueSchema, RealDynamics>> realResourceUpdates,
      final Map<String, Pair<ValueSchema, SerializedValue>> discreteResourceUpdates
  ) {
    if(elapsedTime.shorterThan(lastReceivedTime)) {
      throw new IllegalArgumentException(("elapsedTime must be monotonically increasing between calls.\n"
                                          + "\telaspedTime: %s,\tlastReceivedTme: %s")
                                             .formatted(elapsedTime, lastReceivedTime));
    }
    lastReceivedTime = elapsedTime;

    for(final var e : realResourceUpdates.entrySet()) {
      final var resourceName = e.getKey();
      final var resourceSegment = e.getValue();

      realResourceSegments
          .computeIfAbsent(
              resourceName,
              r -> new ResourceSegments<>(resourceSegment.getLeft(), new ArrayList<>()))
          .segments()
          .add(new ResourceSegments.Segment<>(elapsedTime, resourceSegment.getRight()));
    }

    for(final var e : discreteResourceUpdates.entrySet()) {
      final var resourceName = e.getKey();
      final var resourceSegment = e.getValue();

      discreteResourceSegments
          .computeIfAbsent(
              resourceName,
              r -> new ResourceSegments<>(resourceSegment.getLeft(), new ArrayList<>()))
          .segments()
          .add(new ResourceSegments.Segment<>(elapsedTime, resourceSegment.getRight()));
    }

  }
}
