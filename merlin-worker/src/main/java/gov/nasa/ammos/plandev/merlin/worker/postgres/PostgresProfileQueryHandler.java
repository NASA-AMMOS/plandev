package gov.nasa.jpl.aerie.merlin.worker.postgres;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfiles;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.DatabaseException;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.FailedInsertException;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.FailedUpdateException;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PreparedStatements;
import org.apache.commons.lang3.tuple.Pair;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.discreteProfileTypeP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.realProfileTypeP;

/**
 * Utility class to handle upload of resource profiles to the database.
 * */
public class PostgresProfileQueryHandler implements AutoCloseable {
  private final Connection connection;
  private final HashMap<String, Integer> profileIds;
  private final HashMap<String, Duration> profileDurations;

  private final PreparedStatement postProfileStatement;
  private final PreparedStatement postSegmentsStatement;
  private final PreparedStatement updateDurationStatement;

  public PostgresProfileQueryHandler(DataSource dataSource, long datasetId) throws SQLException {
    connection = dataSource.getConnection();
    profileIds = new HashMap<>();
    profileDurations = new HashMap<>();

    final String postProfilesSql =
        //language=sql
        """
        insert into merlin.profile (dataset_id, name, type, duration)
        values (%d, ?, ?::jsonb, ?::interval)
        on conflict (dataset_id, name) do nothing
        """.formatted(datasetId);
    final String postSegmentsSql =
        //language=sql
        """
        insert into merlin.profile_segment (dataset_id, profile_id, start_offset, dynamics, is_gap)
         values (%d, ?, ?::interval, ?::jsonb, false)
        """.formatted(datasetId);
    final String updateDurationSql =
        //language=SQL
        """
        update merlin.profile
        set duration = ?::interval
        where (dataset_id, id) = (%d, ?);
        """.formatted(datasetId);

    postProfileStatement = connection.prepareStatement(postProfilesSql, PreparedStatement.RETURN_GENERATED_KEYS);
    postSegmentsStatement = connection.prepareStatement(postSegmentsSql, PreparedStatement.NO_GENERATED_KEYS);
    updateDurationStatement = connection.prepareStatement(updateDurationSql, PreparedStatement.NO_GENERATED_KEYS);
  }

  /**
   * Upload profiles, profile segments, and corresponding profile durations to the database.
   * */
  public void uploadResourceProfiles(final ResourceProfiles resourceProfiles) {
    try {
      // Add new profiles to DB
      for (final var realEntry : resourceProfiles.realProfiles().entrySet()) {
        if (!profileIds.containsKey(realEntry.getKey())) {
          addRealProfileToBatch(realEntry.getKey(), realEntry.getValue());
        }
      }
      for (final var discreteEntry : resourceProfiles.discreteProfiles().entrySet()) {
        if (!profileIds.containsKey(discreteEntry.getKey())) {
          addDiscreteProfileToBatch(discreteEntry.getKey(), discreteEntry.getValue());
        }
      }
      postProfiles();

      // Post Segments
      for (final var realEntry : resourceProfiles.realProfiles().entrySet()) {
        addProfileSegmentsToBatch(realEntry.getKey(), realEntry.getValue(), realDynamicsP);
      }
      for (final var discreteEntry : resourceProfiles.discreteProfiles().entrySet()) {
        addProfileSegmentsToBatch(discreteEntry.getKey(), discreteEntry.getValue(), serializedValueP);
      }

      postProfileSegments();
      updateProfileDurations();
    } catch (SQLException ex) {
      throw new DatabaseException("Exception occurred while posting profiles.", ex);
    }
  }

  private void addRealProfileToBatch(final String name, ResourceProfile<RealDynamics> profile) throws SQLException {
    postProfileStatement.setString(1, name);
    postProfileStatement.setString(2, realProfileTypeP.unparse(Pair.of("real", profile.schema())).toString());
    PreparedStatements.setDuration(this.postProfileStatement, 3, Duration.ZERO);

    postProfileStatement.addBatch();

    profileDurations.put(name, Duration.ZERO);
  }

  private void addDiscreteProfileToBatch(final String name, ResourceProfile<SerializedValue> profile) throws SQLException {
    postProfileStatement.setString(1, name);
    postProfileStatement.setString(2, discreteProfileTypeP.unparse(Pair.of("discrete", profile.schema())).toString());
    PreparedStatements.setDuration(this.postProfileStatement, 3, Duration.ZERO);

    postProfileStatement.addBatch();

    profileDurations.put(name, Duration.ZERO);
  }

  /**
   * Insert the batched profiles and cache their ids for future use.
   *
   * This method takes advantage of the fact that we're using the Postgres JDBC,
   * which returns all columns when executing batches with `getGeneratedKeys`.
   */
  private void postProfiles() throws SQLException {
    final var results = this.postProfileStatement.executeBatch();
    for (final var result : results) {
      if (result == Statement.EXECUTE_FAILED) throw new FailedInsertException("merlin.profile_segment");
    }

    final var resultSet = this.postProfileStatement.getGeneratedKeys();
    while(resultSet.next()){
      profileIds.put(resultSet.getString("name"), resultSet.getInt("id"));
    }
  }

  private void postProfileSegments() throws SQLException {
    final var results = this.postSegmentsStatement.executeBatch();
    for (final var result : results) {
      if (result == Statement.EXECUTE_FAILED) throw new FailedInsertException("merlin.profile_segment");
    }
  }

  private void updateProfileDurations() throws SQLException {
    final var results = this.updateDurationStatement.executeBatch();
    for (final var result : results) {
      if (result == Statement.EXECUTE_FAILED) throw new FailedUpdateException("merlin.profile");
    }
  }

  private <T> void addProfileSegmentsToBatch(final String name, ResourceProfile<T> profile, JsonParser<T> dynamicsP) throws SQLException {
    final var id = profileIds.get(name);
    this.postSegmentsStatement.setLong(1, id);

    var newDuration = profileDurations.get(name);
    for (final var segment : profile.segments()) {
      PreparedStatements.setDuration(this.postSegmentsStatement, 2, newDuration);
      final var dynamics = dynamicsP.unparse(segment.dynamics()).toString();
      this.postSegmentsStatement.setString(3, dynamics);
      this.postSegmentsStatement.addBatch();

      newDuration = newDuration.plus(segment.extent());
    }

    this.updateDurationStatement.setLong(2, id);
    PreparedStatements.setDuration(this.updateDurationStatement, 1, newDuration);
    this.updateDurationStatement.addBatch();

    profileDurations.put(name, newDuration);
  }

  @Override
  public void close() throws SQLException {
    this.postProfileStatement.close();
    this.postSegmentsStatement.close();
    this.updateDurationStatement.close();
    this.connection.close();
  }
}
