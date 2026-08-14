package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;

/*package-local*/ final class ProfileRepository {
  static ProfileSet getProfiles(
      final Connection connection,
      final long datasetId
  ) throws SQLException {
    final var realProfiles = new HashMap<String, ResourceProfile<Optional<RealDynamics>>> ();
    final var discreteProfiles = new HashMap<String, ResourceProfile<Optional<SerializedValue>>>();

    final var profileRecords = getProfileRecords(connection, datasetId);
    for (final var record : profileRecords) {
      switch (record.type().getLeft()) {
        case "real" -> realProfiles.put(
          record.name(),
          ResourceProfile.of(
              record.type().getRight(),
              getRealProfileSegments(connection, record.datasetId(), record.id(), record.duration())
          )
        );
        case "discrete" -> discreteProfiles.put(
            record.name(),
            ResourceProfile.of(
                record.type().getRight(),
                getDiscreteProfileSegments(connection, record.datasetId(), record.id(), record.duration())
            )
        );
        default -> throw new Error("Unrecognized profile type");
      }
    }

    return new ProfileSet(realProfiles, discreteProfiles);
  }

  static ProfileSet getProfiles(
      final Connection connection,
      final long datasetId,
      final List<String> names
  ) throws SQLException {
    final var realProfiles = new HashMap<String, ResourceProfile<Optional<RealDynamics>>>();
    final var discreteProfiles = new HashMap<String, ResourceProfile<Optional<SerializedValue>>>();

    final var profileRecords = getProfileRecords(connection, datasetId, names);
    for (final var record : profileRecords) {
      switch (record.type().getLeft()) {
        case "real" -> realProfiles.put(
            record.name(),
            ResourceProfile.of(
                record.type().getRight(),
                getRealProfileSegments(connection, record.datasetId(), record.id(), record.duration())
            )
        );
        case "discrete" -> discreteProfiles.put(
            record.name(),
            ResourceProfile.of(
                record.type().getRight(),
                getDiscreteProfileSegments(connection, record.datasetId(), record.id(), record.duration())
            )
        );
        default -> throw new Error("Unrecognized profile type");
      }
    }

    return new ProfileSet(realProfiles, discreteProfiles);
  }

  static Map<String, ValueSchema> getProfileSchemas(
      final Connection connection,
      final long datasetId
  ) throws SQLException {
    final var results = new HashMap<String, ValueSchema>();

    final var profileRecords = getProfileRecords(connection, datasetId);
    for (final var record : profileRecords) {
      results.put(record.name(), record.type().getRight());
    }

    return results;
  }

  static List<PlanDatasetRecord> getPlanDatasetsForPlan(
      final Connection connection,
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId) throws SQLException {
    try (final var getPlanDatasetsAction = new GetPlanDatasetsAction(connection)) {
      return getPlanDatasetsAction.get(planId, simulationDatasetId);
    }
  }

  static List<ProfileRecord> getProfileRecords(
      final Connection connection,
      final long datasetId
  ) throws SQLException {
    try (final var getProfilesAction = new GetProfilesAction(connection)) {
      return getProfilesAction.get(datasetId);
    }
  }

  static List<ProfileRecord> getProfileRecords(
      final Connection connection,
      final long datasetId,
      final List<String> names
  ) throws SQLException {
    try (final var getProfilesAction = new GetProfilesByNameAction(connection)) {
      return getProfilesAction.get(datasetId, names);
    }
  }

  static List<ProfileSegment<Optional<RealDynamics>>> getRealProfileSegments(
      final Connection connection,
      final long datasetId,
      final long profileId,
      final Duration profileDuration
  ) throws SQLException {
    try (final var getProfileSegmentsAction = new GetProfileSegmentsAction(connection)) {
      return getProfileSegmentsAction.get(datasetId, profileId, profileDuration, realDynamicsP);
    }
  }

  static List<ProfileSegment<Optional<SerializedValue>>> getDiscreteProfileSegments(
      final Connection connection,
      final long datasetId,
      final long profileId,
      final Duration profileDuration
  ) throws SQLException {
    try (final var getProfileSegmentsAction = new GetProfileSegmentsAction(connection)) {
      return getProfileSegmentsAction.get(datasetId, profileId, profileDuration, serializedValueP);
    }
  }

  static void postResourceProfiles(
      final Connection connection,
      final long datasetId,
      final ProfileSet profileSet
  ) throws SQLException
  {
    try (final var postProfilesAction = new PostProfilesAction(connection)) {
      final var profileRecords = postProfilesAction.apply(
          datasetId,
          profileSet.realProfiles(),
          profileSet.discreteProfiles());
      postProfileSegments(
          connection,
          datasetId,
          profileRecords,
          profileSet);
    }
  }

  static void appendResourceProfiles(
      final Connection connection,
      final long datasetId,
      final ProfileSet profileSet
  ) throws SQLException
  {
    final Map<String, ProfileRecord> profileRecords;
    try (final var getProfilesAction = new GetProfilesAction(connection)) {
      profileRecords = getProfilesAction.get(datasetId)
                                        .stream()
                                        .collect(Collectors.toMap(ProfileRecord::name, $ -> $));
    }
    final var newProfiles = new ProfileSet(new HashMap<>(), new HashMap<>());
    final var oldProfiles = new ProfileSet(new HashMap<>(), new HashMap<>());
    for (final var entry : profileSet.realProfiles().entrySet()) {
      final var profileName = entry.getKey();
      final var profileRecord = Optional.ofNullable(profileRecords.get(profileName));
      if (profileRecord.isPresent()) {
        oldProfiles.realProfiles().put(profileName, entry.getValue());
      } else {
        newProfiles.realProfiles().put(profileName, entry.getValue());
      }
    }
    for (final var entry : profileSet.discreteProfiles().entrySet()) {
      final var profileName = entry.getKey();
      final var profileRecord = Optional.ofNullable(profileRecords.get(profileName));
      if (profileRecord.isPresent()) {
        oldProfiles.discreteProfiles().put(profileName, entry.getValue());
      } else {
        newProfiles.discreteProfiles().put(profileName, entry.getValue());
      }
    }

    try (final var postProfilesAction = new PostProfilesAction(connection)) {
      final var newProfileRecords = postProfilesAction.apply(
          datasetId,
          newProfiles.realProfiles(),
          newProfiles.discreteProfiles());
      postProfileSegments(
          connection,
          datasetId,
          newProfileRecords,
          profileSet);
    }

    appendProfileSegments(
        connection,
        datasetId,
        profileRecords,
        oldProfiles);
  }

  private static void postProfileSegments(
      final Connection connection,
      final long datasetId,
      final Map<String, ProfileRecord> records,
      final ProfileSet profileSet
  ) throws SQLException {
    final var realProfiles = profileSet.realProfiles();
    final var discreteProfiles = profileSet.discreteProfiles();
    for (final var entry : records.entrySet()) {
      final ProfileRecord record =  entry.getValue();
      final var resource =  entry.getKey();
      switch (record.type().getLeft()) {
        case "real" -> postRealProfileSegments(
            connection,
            datasetId,
            record,
            realProfiles.get(resource).segments());
        case "discrete" -> postDiscreteProfileSegments(
            connection,
            datasetId,
            record,
            discreteProfiles.get(resource).segments());
        default -> throw new Error("Unrecognized profile type " + record.type().getLeft());
      }
    }
  }

  private static void appendProfileSegments(
      final Connection connection,
      final long datasetId,
      final Map<String, ProfileRecord> records,
      final ProfileSet profileSet
  ) throws SQLException {
    final var realProfiles = profileSet.realProfiles();
    final var discreteProfiles = profileSet.discreteProfiles();
    for (final var entry : profileSet.realProfiles().entrySet()) {
      final var resource = entry.getKey();
      final var record = records.get(resource);
      try (
          final var appendProfileSegmentsAction = new AppendProfileSegmentsAction(connection);
          final var updateProfileDurationAction = new UpdateProfileDurationAction(connection)) {
        final var newProfileDuration = appendProfileSegmentsAction.apply(
            datasetId,
            record,
            realProfiles.get(resource).segments(),
            realDynamicsP);
        updateProfileDurationAction.apply(datasetId, record.id(), newProfileDuration);
      }
    }
    for (final var entry : profileSet.discreteProfiles().entrySet()) {
      final var resource = entry.getKey();
      final var record = records.get(resource);
      try (
          final var appendProfileSegmentsAction = new AppendProfileSegmentsAction(connection);
          final var updateProfileDurationAction = new UpdateProfileDurationAction(connection)) {
        final var newProfileDuration = appendProfileSegmentsAction.apply(
            datasetId,
            record,
            discreteProfiles.get(resource).segments(),
            serializedValueP);
        updateProfileDurationAction.apply(datasetId, record.id(), newProfileDuration);
      }
    }
  }

  private static void postRealProfileSegments(
      final Connection connection,
      final long datasetId,
      final ProfileRecord profileRecord,
      final List<ProfileSegment<Optional<RealDynamics>>> segments
  ) throws SQLException {
    try (final var postProfileSegmentsAction = new PostProfileSegmentsAction(connection)) {
      postProfileSegmentsAction.apply(datasetId, profileRecord, segments, realDynamicsP);
    }
  }

  private static void postDiscreteProfileSegments(
      final Connection connection,
      final long datasetId,
      final ProfileRecord profileRecord,
      final List<ProfileSegment<Optional<SerializedValue>>> segments
  ) throws SQLException {
    try (final var postProfileSegmentsAction = new PostProfileSegmentsAction(connection)) {
      postProfileSegmentsAction.apply(datasetId, profileRecord, segments, serializedValueP);
    }
  }
}
