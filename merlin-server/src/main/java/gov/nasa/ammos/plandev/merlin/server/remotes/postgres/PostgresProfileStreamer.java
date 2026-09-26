package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.driver.engine.ProfileSegment;
import gov.nasa.ammos.plandev.merlin.driver.resources.ResourceProfile;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.RealDynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.ammos.plandev.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.ammos.plandev.merlin.server.http.ProfileParsers.realDynamicsP;

public final class PostgresProfileStreamer implements AutoCloseable {
  // Buffer information
  private final static int DEFAULT_THRESHOLD = 4096;
  private final int THRESHOLD;

  // Profile Information
  private final HashMap<String, ProfileRecord> profileRecords;

  // Buffers
  private final HashMap<String, ResourceProfile<Optional<RealDynamics>>> realResourceBuffer;
  private final HashMap<String, ResourceProfile<Optional<SerializedValue>>> discreteResourceBuffer;

  // Request information
  private final Connection connection;
  private final long datasetId;

  public PostgresProfileStreamer(final Connection connection, final long datasetId) {
    this.connection = connection;
    this.datasetId = datasetId;

    THRESHOLD = DEFAULT_THRESHOLD;
    profileRecords = new HashMap<>();
    realResourceBuffer = HashMap.newHashMap(THRESHOLD);
    discreteResourceBuffer = HashMap.newHashMap(THRESHOLD);
  }

  public void acceptRealSegment(
      final String profileName,
      final ValueSchema profileSchema,
      ProfileSegment<Optional<RealDynamics>> segment
  ) throws SQLException {
    if(totalSegments() == THRESHOLD) {
      postProfileSegments();
    }
    realResourceBuffer.putIfAbsent(profileName, new ResourceProfile<>(profileSchema, new ArrayList<>()));
    realResourceBuffer.get(profileName).segments().add(segment);
  }

  public void acceptDiscreteSegment(
      final String profileName,
      final ValueSchema profileSchema,
      ProfileSegment<Optional<SerializedValue>> segment
  ) throws SQLException {
    if(totalSegments() == THRESHOLD) {
      postProfileSegments();
    }
    discreteResourceBuffer.putIfAbsent(profileName, new ResourceProfile<>(profileSchema, new ArrayList<>()));
    discreteResourceBuffer.get(profileName).segments().add(segment);
  }


  /**
   * Post profiles that don't exist in the database yet.
   */
  private void postProfiles() throws SQLException {
    final Map<String, ResourceProfile<Optional<RealDynamics>>> realProfilesToAdd = new HashMap<>();
    final Map<String, ResourceProfile<Optional<SerializedValue>>> discreteProfilesToAdd = new HashMap<>();

    for(final var realEntry : realResourceBuffer.entrySet()) {
      if(!profileRecords.containsKey(realEntry.getKey())) {
        realProfilesToAdd.put(realEntry.getKey(), realEntry.getValue());
      }
    }

    for (final var discreteEntry : discreteResourceBuffer.entrySet()) {
      if (!profileRecords.containsKey(discreteEntry.getKey())) {
        discreteProfilesToAdd.put(discreteEntry.getKey(), discreteEntry.getValue());
      }
    }

    // If there are no new profiles, return
    if(realProfilesToAdd.isEmpty() && discreteProfilesToAdd.isEmpty()) {
      return;
    }

    // Else, add them to the db and store their mappings
    try(final var postProfilesAction = new PostProfilesAction(connection)) {
      final var mappings = postProfilesAction.apply(
          datasetId,
          realProfilesToAdd,
          discreteProfilesToAdd
      );
      profileRecords.putAll(mappings);
    }
  }

  /**
   * Extend the duration of existing profiles
   */
  private void extendProfileDurations(Map<Long, Duration> updatedDurations) throws SQLException {
    try(final var extendProfiles = new UpdateProfileDurationBulkAction(connection)) {
      extendProfiles.apply(datasetId, updatedDurations);
    }
  }

  private void postProfileSegments() throws SQLException {
    // Add any new profiles to DB
    postProfiles();

    final var updatedProfileDurations = new HashMap<Long, Duration>();

    try(final var appendProfileSegmentsAction = new AppendProfileSegmentsAction(connection)) {
      for(final var entry : realResourceBuffer.entrySet()) {
        final var profileRecord = profileRecords.get(entry.getKey());
        final var segments = entry.getValue().segments();

        final var updatedDuration = appendProfileSegmentsAction.apply(datasetId, profileRecord, segments, realDynamicsP);
        updatedProfileDurations.put(profileRecord.id(), updatedDuration);
      }

      for(final var entry : discreteResourceBuffer.entrySet()) {
        final var profileRecord = profileRecords.get(entry.getKey());
        final var segments = entry.getValue().segments();

        final var updatedDuration = appendProfileSegmentsAction.apply(datasetId, profileRecord, segments, serializedValueP);
        updatedProfileDurations.put(profileRecord.id(), updatedDuration);
      }
    }

    extendProfileDurations(updatedProfileDurations);

    // Clear queue
    realResourceBuffer.clear();
    discreteResourceBuffer.clear();
  }

  private int totalSegments() {
    return realResourceBuffer.size()+discreteResourceBuffer.size();
  }

  @Override
  public void close() throws SQLException {
    postProfileSegments();
  }
}
