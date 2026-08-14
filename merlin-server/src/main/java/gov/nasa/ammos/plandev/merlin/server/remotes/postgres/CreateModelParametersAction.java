package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/*package-local*/ final class CreateModelParametersAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into merlin.mission_model_parameters (model_id, parameters)
    values (?, ?::json)
    on conflict (model_id) do update set parameters = ?::json
    returning model_id
    """;

  private final PreparedStatement statement;

  public CreateModelParametersAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public long apply(final long modelId, final List<Parameter> parameters) throws SQLException, FailedInsertException {
    this.statement.setLong(1, modelId);
    PreparedStatements.setParameters(this.statement, 2, parameters);
    PreparedStatements.setParameters(this.statement, 3, parameters);

    try (final var results = statement.executeQuery()) {
      if (!results.next()) throw new FailedInsertException("merlin.mission_model_parameters");

      return results.getLong(1);
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
