package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Triple;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

/*package-local*/ final class InsertSimulationTopicsAction implements AutoCloseable {
  @Language("SQL") private static final String sql = """
      insert into merlin.topic (dataset_id, topic_index, name, value_schema)
      values (?, ?, ?, ?::jsonb)
    """;

  private final PreparedStatement statement;

  public InsertSimulationTopicsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(
      final long datasetId,
      final List<Triple<Integer, String, ValueSchema>> topics
  ) throws SQLException {
    for (final var topic : topics) {
      this.statement.setLong(1, datasetId);
      this.statement.setInt(2, topic.getLeft());
      this.statement.setString(3, topic.getMiddle());
      this.statement.setString(4, valueSchemaP.unparse(topic.getRight()).toString());
      this.statement.addBatch();
    }
    this.statement.executeBatch();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
