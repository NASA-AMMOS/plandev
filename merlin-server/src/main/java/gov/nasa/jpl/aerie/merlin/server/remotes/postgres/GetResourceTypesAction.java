package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

/*package-local*/ final class GetResourceTypesAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select name, schema
    from merlin.resource_type
    where model_id = ?
    """;

  private final PreparedStatement statement;

  public GetResourceTypesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<String, ValueSchema> get(final long modelId) throws SQLException {
    this.statement.setLong(1, modelId);
    final var result = new HashMap<String, ValueSchema>();
    try (final var results = this.statement.executeQuery()) {
      while (results.next()) {
        final var name = results.getString("name");
        final var schema = getJsonColumn(results, "schema", valueSchemaP)
            .getSuccessOrThrow(failureReason ->
                new Error("Corrupt resource type schema cannot be parsed: " + failureReason.reason()));
        result.put(name, schema);
      }
    }
    return result;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
