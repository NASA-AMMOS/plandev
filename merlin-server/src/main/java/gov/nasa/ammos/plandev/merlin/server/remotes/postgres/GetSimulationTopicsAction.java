package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Triple;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

/*package-local*/ final class GetSimulationTopicsAction implements AutoCloseable {
  @Language("SQL") private final String sql = """
        select
          e.topic_index,
          e.name,
          e.value_schema
        from merlin.topic as e
        where
          e.dataset_id = ?
      """;

  private final PreparedStatement statement;

  public GetSimulationTopicsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(this.sql);
  }

  public List<Triple<Integer, String, ValueSchema>> get(
      final long datasetId) throws SQLException
  {
    this.statement.setLong(1, datasetId);
    try (final var resultSet = this.statement.executeQuery()) {
      final var topics = new ArrayList<Triple<Integer, String, ValueSchema>>();
      while (resultSet.next()) {
        topics.add(
            Triple.of(
                resultSet.getInt(1),
                resultSet.getString(2),
                getJsonColumn(resultSet, "value_schema", valueSchemaP)
                  .getSuccessOrThrow($ -> new Error("Corrupt activity type required parameters cannot be parsed: "
                                                  + $.reason()))
            )
        );
      }
      return topics;
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
