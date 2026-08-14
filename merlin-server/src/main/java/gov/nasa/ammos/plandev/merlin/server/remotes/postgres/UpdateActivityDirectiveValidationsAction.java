package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.server.models.ActivityDirectiveForValidation;
import org.apache.commons.lang3.tuple.Pair;
import org.intellij.lang.annotations.Language;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.BulkArgumentValidationResponse;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/*package-local*/ final class UpdateActivityDirectiveValidationsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    update merlin.activity_directive_validations
    set validations = ?::jsonb,
        status = 'complete'
    where (directive_id, plan_id, last_modified_arguments_at) = (?, ?, ?)
  """;

  private final PreparedStatement statement;

  public UpdateActivityDirectiveValidationsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
    connection.setAutoCommit(false);
  }

  public void apply(List<Pair<ActivityDirectiveForValidation, BulkArgumentValidationResponse>> updates) throws SQLException {
    try {
      for (final var update : updates) {
        final var directive = update.getLeft();
        final var validation = update.getRight();

        PreparedStatements.setValidationResponse(statement, 1, validation);
        statement.setLong(2, directive.id().id());
        statement.setLong(3, directive.planId().id());
        statement.setTimestamp(4, directive.argumentsModifiedTime());

        statement.addBatch();
      }

      statement.executeBatch(); // throws BatchUpdateException if any statement fails
      statement.getConnection().commit();
    } catch (BatchUpdateException e) {
      statement.getConnection().rollback();
      throw e;
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
