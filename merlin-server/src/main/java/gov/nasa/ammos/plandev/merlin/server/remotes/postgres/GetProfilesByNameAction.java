package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.profileTypeP;

/*package-local*/ final class GetProfilesByNameAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      select
        p.id,
        p.name,
        p.type,
        p.duration
      from merlin.profile as p
      where
        p.dataset_id = ?
        and p.name = any(?)
    """;

  private final PreparedStatement statement;

  public GetProfilesByNameAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public List<ProfileRecord> get(final long datasetId, final List<String> names) throws SQLException {

    this.statement.setLong(1, datasetId);
    this.statement.setArray(2, this.statement.getConnection().createArrayOf("varchar", names.toArray()));

    final var records = new ArrayList<ProfileRecord>();
    PreparedStatements.setIntervalStyle(statement.getConnection(), PreparedStatements.PGIntervalStyle.ISO8601);
    final var resultSet = statement.executeQuery();
    while (resultSet.next()) {
      final var profileId = resultSet.getLong(1);
      final var resourceName = resultSet.getString(2);
      final var type = getJsonColumn(resultSet, "type", profileTypeP).getSuccessOrThrow(
          failureReason -> new Error(
              "Corrupt profile type: " + failureReason.reason()));
      final var duration = PostgresParsers.parseDurationISO8601(resultSet.getString(4));
      records.add(new ProfileRecord(profileId, datasetId, resourceName, type, duration));
    }

    return records;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
