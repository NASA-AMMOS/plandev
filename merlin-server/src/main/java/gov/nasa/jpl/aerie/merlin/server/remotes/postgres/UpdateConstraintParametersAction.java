package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class UpdateConstraintParametersAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    update merlin.constraint_definition
    set parameter_schema=?::jsonb
    where (constraint_id, revision)= (?,?);
    """;

  private final PreparedStatement statement;

  public UpdateConstraintParametersAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void update(final long constraintId, final long revision, final ValueSchema schema) throws SQLException, FailedInsertException {
    this.statement.setString(1, new ValueSchemaJsonParser().unparse(schema).toString());
    this.statement.setLong(2, constraintId);
    this.statement.setLong(3, revision);
    final var rowsUpdated = this.statement.executeUpdate();

    if (rowsUpdated != 1) {
      throw new FailedUpdateException("merlin.constraint_definition");
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
