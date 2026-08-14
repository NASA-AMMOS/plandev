package gov.nasa.jpl.aerie.merlin.driver.resources;

import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A variant of a SimulationResourceManager that streams resources as needed in order to conserve memory.
 * The way it streams resources is determined by the Consumer passed to it during construction
 */
public class StreamingSimulationResourceManager implements SimulationResourceManager {
  private final HashMap<String, ResourceSegments<RealDynamics>> realResourceSegments;
  private final HashMap<String, ResourceSegments<SerializedValue>> discreteResourceSegments;

  private final AsyncConsumer<ResourceProfiles> streamer;

  private Duration lastReceivedTime;

  // The threshold controls how many segments the longest resource must have before all completed segments are streamed.
  // When streaming occurs, all completed profile segments are streamed,
  //   not just those belonging to the resource that crossed the threshold.
  private static final int DEFAULT_THRESHOLD = 1024;
  private final int threshold;

  public StreamingSimulationResourceManager(final AsyncConsumer<ResourceProfiles> streamer) {
    this(streamer, DEFAULT_THRESHOLD);
  }

  public StreamingSimulationResourceManager(final AsyncConsumer<ResourceProfiles> streamer, int threshold) {
    realResourceSegments = new HashMap<>();
    discreteResourceSegments = new HashMap<>();
    this.threshold = threshold;
    this.streamer = streamer;
    this.lastReceivedTime = Duration.ZERO;
  }

  /**
   * Compute all ProfileSegments stored in this resource manager, and stream them to the database
   * @param elapsedDuration the amount of time elapsed since the start of simulation.
   */
  @Override
  public ResourceProfiles computeProfiles(final Duration elapsedDuration) {
    final var profiles = computeProfiles();

    // Compute final segment for real profiles
    for(final var resource : realResourceSegments.entrySet()) {
      final var name = resource.getKey();
      final var segments = resource.getValue().segments();
      final var finalSegment = segments.getFirst();

      profiles.realProfiles()
              .get(name)
              .segments()
              .add(new ProfileSegment<>(elapsedDuration.minus(finalSegment.startOffset()), finalSegment.dynamics()));

      // Remove final segment
      segments.clear();
    }

    // Compute final segment for discrete profiles
    for(final var resource : discreteResourceSegments.entrySet()) {
      final var name = resource.getKey();
      final var segments = resource.getValue().segments();
      final var finalSegment = segments.getFirst();

      profiles.discreteProfiles()
              .get(name)
              .segments()
              .add(new ProfileSegment<>(elapsedDuration.minus(finalSegment.startOffset()), finalSegment.dynamics()));

      // Remove final segment
      segments.clear();
    }

    streamer.accept(profiles);
    streamer.close(); // Wait for streamer to finish before continuing
    return profiles;
  }

  /**
   * This class streams all resources it has as it accepts updates,
   * so it cannot only compute a subset of ProfileSegments.
   * @throws UnsupportedOperationException
   */
  @Override
  public ResourceProfiles computeProfiles(final Duration elapsedDuration, Set<String> resources) {
    throw new UnsupportedOperationException("StreamingSimulationResourceManager streams ALL resources");
  }

  /**
   * Compute only the completed profile segments and remove them from internal ResourceSegment maps
   * This is intended to be called while simulation is executing.
   */
  private ResourceProfiles computeProfiles() {
    final var profiles = new ResourceProfiles(new HashMap<>(), new HashMap<>());

    // Compute Real Profiles
    for(final var resource : realResourceSegments.entrySet()) {
      final var name = resource.getKey();
      final var schema = resource.getValue().valueSchema();
      final var segments = resource.getValue().segments();

      profiles.realProfiles().put(name, new ResourceProfile<>(schema, new ArrayList<>(threshold)));
      final var profile = profiles.realProfiles().get(name).segments();

      for(int i = 0; i < segments.size()-1; i++) {
        final var segment = segments.get(i);
        final var nextSegment = segments.get(i+1);
        profile.add(new ProfileSegment<>(nextSegment.startOffset().minus(segment.startOffset()), segment.dynamics()));
      }

      // Remove the completed segments, leaving only the final (incomplete) segment in the current set
      final var finalSegment = segments.getLast();
      segments.clear();
      segments.add(finalSegment);
    }

    // Compute Discrete Profiles
    for(final var resource : discreteResourceSegments.entrySet()) {
      final var name = resource.getKey();
      final var schema = resource.getValue().valueSchema();
      final var segments = resource.getValue().segments();

      profiles.discreteProfiles().put(name, new ResourceProfile<>(schema, new ArrayList<>(threshold)));
      final var profile = profiles.discreteProfiles().get(name).segments();

      for(int i = 0; i < segments.size()-1; i++) {
        final var segment = segments.get(i);
        final var nextSegment = segments.get(i+1);
        profile.add(new ProfileSegment<>(nextSegment.startOffset().minus(segment.startOffset()), segment.dynamics()));
      }

      // Remove the completed segments, leaving only the final (incomplete) segment in the current set
      final var finalSegment = segments.getLast();
      segments.clear();
      segments.add(finalSegment);
    }

    return profiles;
  }


  /**
   * Add new segments to this manager's internal store of segments.
   * Will stream all held segments should any resource's number of stored segments exceed the streaming threshold.
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
    boolean readyToStream = false;

    for(final var e : realResourceUpdates.entrySet()) {
      final var resourceName = e.getKey();
      final var resourceSegment = e.getValue();

      realResourceSegments
          .computeIfAbsent(
              resourceName,
              r -> new ResourceSegments<>(resourceSegment.getLeft(), threshold))
          .segments()
          .add(new ResourceSegments.Segment<>(elapsedTime, resourceSegment.getRight()));

      if(realResourceSegments.get(resourceName).segments().size() >= threshold) {
        readyToStream = true;
      }
    }

    for(final var e : discreteResourceUpdates.entrySet()) {
      final var resourceName = e.getKey();
      final var resourceSegment = e.getValue();

      discreteResourceSegments
          .computeIfAbsent(
              resourceName,
              r -> new ResourceSegments<>(resourceSegment.getLeft(), threshold))
          .segments()
          .add(new ResourceSegments.Segment<>(elapsedTime, resourceSegment.getRight()));

      if(discreteResourceSegments.get(resourceName).segments().size() >= threshold) {
        readyToStream = true;
      }
    }

    // If ANY resource met the size threshold, stream ALL currently held profiles
    if(readyToStream) {
      streamer.accept(computeProfiles());
    }
  }
}
