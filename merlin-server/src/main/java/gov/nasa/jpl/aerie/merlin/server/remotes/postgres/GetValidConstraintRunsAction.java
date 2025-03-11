package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidEntityException;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.constraintResultP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.parseJson;

final class GetValidConstraintRunsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      cr.constraint_id,
      cr.constraint_revision,
      cr.simulation_dataset_id,
      cr.results,
      cr.constraint_invocation_id,
      cr.arguments
    from merlin.constraint_run as cr
    where cr.constraint_id = any(?)
    and cr.simulation_dataset_id = ?;
  """;

  private final PreparedStatement statement;
  private final Map<Long, ConstraintRecord> constraints;
  private final SimulationDatasetId simulationDatasetId;

  public GetValidConstraintRunsAction(final Connection connection, final Map<Long, ConstraintRecord> constraints, final SimulationDatasetId simulationDatasetId) throws SQLException {
    this.statement = connection.prepareStatement(sql);
    this.constraints = constraints;
    this.simulationDatasetId = simulationDatasetId;
  }

  public List<ConstraintRunRecord> get() throws SQLException {
    this.statement.setArray(1, this.statement.getConnection().createArrayOf("integer", constraints.keySet().toArray()));
    this.statement.setLong(2, simulationDatasetId.id());

    try (final var results = this.statement.executeQuery()) {
      final var constraintRuns = new ArrayList<ConstraintRunRecord>();

      while (results.next()) {
        final var constraintId = results.getLong("constraint_id");
        final var constraintInvocationId = results.getLong("constraint_invocation_id");
        final var constraintRevision = results.getLong("constraint_revision");

        final var constraintArguments = parseJson(results.getString("arguments"), new SerializedValueJsonParser()).asMap().get();

        // The cached result wasn't for the correct revision
        if(constraints.get(constraintId).revision() != constraintRevision) continue;

        final var resultString = results.getString("results");

        // The constraint run didn't have any violations
        if (resultString.equals("{}")) {
          constraintRuns.add(new ConstraintRunRecord(constraintId, constraintInvocationId, constraintArguments, null));
        } else {
          constraintRuns.add(new ConstraintRunRecord(
              constraintId,
              constraintInvocationId,
              constraintArguments,
              getJsonColumn(results, "results", constraintResultP)
                  .getSuccessOrThrow($ -> new Error("Corrupt results cannot be parsed: " + $.reason()))));
        }
      }

      return constraintRuns;
    } catch (InvalidJsonException | InvalidEntityException e) {
      throw new SQLException(e);
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
