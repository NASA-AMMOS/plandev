package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.parameterRecordP;

/*package-local*/ final class GetModelParametersAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select parameters
    from merlin.mission_model_parameters
    where model_id = ?
    """;

  private final PreparedStatement statement;

  public GetModelParametersAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public List<Parameter> get(final long modelId) throws SQLException {
    this.statement.setLong(1, modelId);
    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return List.of();
      return getJsonColumn(results, "parameters", parameterRecordP)
          .getSuccessOrThrow(failureReason ->
              new Error("Corrupt mission model parameters cannot be parsed: " + failureReason.reason()))
          .entrySet()
          .stream()
          .sorted(Comparator.comparingInt(e -> e.getValue().getKey()))
          .map(e -> new Parameter(e.getKey(), e.getValue().getValue()))
          .toList();
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
