package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.parameterRecordP;

/*package-local*/ public class GetExternalSourceSchemaJsonbAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      st.value_schema
    from merlin.external_source_type as st
    where st.name = ?
    """;

  private final PreparedStatement statement;

  public GetExternalSourceSchemaJsonbAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public List<String> get(final String sourceType) throws SQLException {
    this.statement.setString(1, sourceType);

    final var schemaJsonbs = new ArrayList<String>();
    try (final var results = this.statement.executeQuery()) {
      while (results.next()) {
        schemaJsonbs.add(results.getString("value_schema"));
      }
    }

    return schemaJsonbs;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
