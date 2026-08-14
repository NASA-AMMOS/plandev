package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

/*package-local*/ final class GetProfileSegmentsAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      select
        seg.start_offset,
        seg.dynamics,
        seg.is_gap
      from merlin.profile_segment as seg
      where
        seg.dataset_id = ? and
        seg.profile_id = ?
      order by seg.start_offset asc
    """;
  private final PreparedStatement statement;

  public GetProfileSegmentsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public <Dynamics> List<ProfileSegment<Optional<Dynamics>>> get(
      final long datasetId,
      final long profileId,
      final Duration profileDuration,
      final JsonParser<Dynamics> dynamicsP
  ) throws SQLException {
    final var segments = new ArrayList<ProfileSegment<Optional<Dynamics>>>();
    PreparedStatements.setIntervalStyle(statement.getConnection(), PreparedStatements.PGIntervalStyle.ISO8601);
    this.statement.setLong(1, datasetId);
    this.statement.setLong(2, profileId);
    final var resultSet = statement.executeQuery();

    // Profile segments are stored with their start offset relative to simulation start
    // We must convert these to durations describing how long each segment lasts
    if (resultSet.next()) {
      var offset = PostgresParsers.parseDurationISO8601(resultSet.getString(1));
      Optional<Dynamics> dynamics;
      var isGap = resultSet.getBoolean("is_gap");
      if (!isGap) {
        dynamics = Optional.of(getJsonColumn(resultSet, "dynamics", dynamicsP)
            .getSuccessOrThrow(failureReason -> new Error("Corrupt profile dynamics: " + failureReason.reason())));
      } else {
        dynamics = Optional.empty();
      }

      while (resultSet.next()) {
        final var nextOffset = PostgresParsers.parseDurationISO8601(resultSet.getString(1));
        final var duration = nextOffset.minus(offset);
        segments.add(new ProfileSegment<>(duration, dynamics));
        offset = nextOffset;

        isGap = resultSet.getBoolean("is_gap");
        if (!isGap) {
          dynamics = Optional.of(getJsonColumn(resultSet, "dynamics", dynamicsP)
              .getSuccessOrThrow(
                  failureReason -> new Error("Corrupt profile dynamics: " + failureReason.reason())));
        } else {
          dynamics = Optional.empty();
        }
      }

      final var duration = profileDuration.minus(offset);
      segments.add(new ProfileSegment<>(duration, dynamics));
    } else {
      throw new Error("No profile segments found for `dataset_id` (%d) and `profile_id` (%d)".formatted(datasetId, profileId));
    }

    return segments;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
