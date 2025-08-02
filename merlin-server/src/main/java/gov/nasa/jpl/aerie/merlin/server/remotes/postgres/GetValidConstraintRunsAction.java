package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import org.intellij.lang.annotations.Language;

import javax.json.Json;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.constraintArgumentsP;

final class GetValidConstraintRunsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      res.id, res.results
    from merlin.constraint_run run
    inner join merlin.constraint_results res on run.constraint_results_id = res.id
    where res.errors = '{}'::jsonb -- Do not load results that had errors
    and run.constraint_invocation_id = ?
    -- Only load results that have the same inputs (id, revision, sim dataset, arguments)
    and res.constraint_id = ?
    and res.constraint_revision = ?
    and res.arguments = ?::jsonb
    and res.simulation_dataset_id = ?
    -- Only load the latest result
    order by run.request_id desc
    limit 1;
  """;

  private final PreparedStatement statement;
  private final List<ConstraintRecord> constraints;
  private final SimulationDatasetId simulationDatasetId;

  public GetValidConstraintRunsAction(
      final Connection connection,
      final List<ConstraintRecord> constraints,
      final SimulationDatasetId simulationDatasetId
  ) throws SQLException {
    this.statement = connection.prepareStatement(sql);
    this.constraints = constraints;
    this.simulationDatasetId = simulationDatasetId;
  }


  public Map<ConstraintRecord, DBConstraintResult> get() throws SQLException {
    final var cachedResults = new HashMap<ConstraintRecord, DBConstraintResult>(constraints.size());

    // Sim Dataset id is set ahead of time, as it does not change
    this.statement.setLong(5, this.simulationDatasetId.id());

    for(final var constraint : constraints) {
      this.statement.setLong(1, constraint.invocationId());
      this.statement.setLong(2, constraint.constraintId());
      this.statement.setLong(3, constraint.revision());
      this.statement.setString(4, constraintArgumentsP.unparse(constraint.arguments()).toString());

      try(final var results = this.statement.executeQuery()) {
        if(results.next()) {
          try(final var resultsReader = new StringReader(results.getString("results"));
              final var resultsParser = Json.createParser(resultsReader)) {
            resultsParser.next();
            cachedResults.put(constraint, new DBConstraintResult(results.getLong("id"), resultsParser.getObject()));
          }
        }
      }
    }

    return cachedResults;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
