package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.constraintArgumentsP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.constraintResultP;

/* package local */ class InsertConstraintRunsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into merlin.constraint_run (constraint_id, constraint_revision, constraint_invocation_id, arguments, simulation_dataset_id, results)
    values (?, ?, ?, ?::jsonb, ?, ?::jsonb)
  """;

  private final PreparedStatement statement;

  public InsertConstraintRunsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(
      Map<Long, ConstraintRecord> constraintMap,
      Map<Long, ConstraintResult> results,
      Long simulationDatasetId) throws SQLException {
    for (ConstraintRecord constraint : constraintMap.values()) {
      statement.setLong(1, constraint.id());
      statement.setLong(2, constraint.revision());
      statement.setLong(3, constraint.invocationId());
      statement.setString(4, constraintArgumentsP.unparse(constraint.arguments()).toString());
      statement.setLong(5, simulationDatasetId);

      if (results.get(constraint.id()) != null) {
        statement.setString(6, constraintResultP.unparse(results.get(constraint.id())).toString());
      } else {
        statement.setString(6, "{}");
      }

      this.statement.addBatch();
    }

    this.statement.executeBatch();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
