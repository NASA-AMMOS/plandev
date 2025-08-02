package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.constraints.model.EDSLConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.http.ResponseSerializers;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.ProceduralConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.services.ConstraintRequestConfiguration;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.constraintArgumentsP;

/* package local */ class InsertConstraintResultsAction implements AutoCloseable {
  private static final @Language("SQL") String insertRequestRecord = """
    insert into merlin.constraint_request(plan_id, simulation_dataset_id, force_rerun, requested_by)
    values (?, ?, ?, ?)
    returning id;
  """;

  private static final @Language("SQL") String insertResults = """
   -- Only input repeated inputs once
   with inputs (cid, revision, sdid, args, results, errors)  as (
       values (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)),
   -- Deduplicate identical results that are already in the DB
   existing_result as (
    select cr.id
    from merlin.constraint_results cr
    inner join inputs i
    on constraint_id = i.cid
    and constraint_revision = i.revision
    and simulation_dataset_id = i.sdid
    and arguments = i.args
    and cr.results = i.results
    and cr.errors = i.errors),
   inserted_result as (
    insert into merlin.constraint_results(constraint_id, constraint_revision, simulation_dataset_id, arguments, results, errors)
    select cid, revision, sdid, args, results, errors
    from inputs
    where not exists(select from existing_result)
    returning id),
  coalesced_result as (
    select id from inserted_result
    union all
    select id from existing_result)
  -- Associate result with run
  insert into merlin.constraint_run (request_id, constraint_invocation_id, constraint_results_id, priority)
  select ?, ?, id, ?
  from coalesced_result
  """;

  private static final @Language("SQL") String associateResultsToRequest = """
    insert into merlin.constraint_run (request_id, constraint_invocation_id, constraint_results_id, priority)
    values (?, ?, ?, ?);
  """;

  private final PreparedStatement insertRequestStatement;
  private final PreparedStatement insertResultsStatement;
  private final PreparedStatement associateResultsStatement;

  public InsertConstraintResultsAction(final Connection connection) throws SQLException {
    this.insertRequestStatement = connection.prepareStatement(insertRequestRecord, PreparedStatement.RETURN_GENERATED_KEYS);
    this.insertResultsStatement = connection.prepareStatement(insertResults);
    this.associateResultsStatement = connection.prepareStatement(associateResultsToRequest);
  }

  public int postResults(
      ConstraintRequestConfiguration configuration,
      Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> constraintToResultsMap
  ) throws SQLException {
    final int requestId = createRequestRecord(
        configuration.planId().id(),
        configuration.simulationDatasetId().id(),
        configuration.force(),
        configuration.requestingUser());
    insertConstraintResults(
        requestId,
        configuration.simulationDatasetId().id(),
        constraintToResultsMap
    );
    return requestId;
  }

  private int createRequestRecord(
     long planId,
     long simulationDatasetId,
     boolean forceRerun,
     String requestingUser
  ) throws SQLException {
    insertRequestStatement.setLong(1, planId);
    insertRequestStatement.setLong(2, simulationDatasetId);
    insertRequestStatement.setBoolean(3, forceRerun);
    insertRequestStatement.setString(4, requestingUser);

    int rowsInserted = insertRequestStatement.executeUpdate();
    try(final var results = insertRequestStatement.getGeneratedKeys()) {
      if (!results.next()) {
        throw new SQLException("Failed to insert new request into DB.");
      }
      if(rowsInserted > 1) {
        throw new SQLException("Too many rows inserted into the DB.");
      }
      return results.getInt("id");
    }
  }

  private void insertConstraintResults(
      long requestId,
      long simulationDatasetId,
      Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> constraintToResultsMap
  ) throws SQLException {
    for (final var entry : constraintToResultsMap.entrySet()) {
      final var constraint = entry.getKey();
      final var fallible = entry.getValue();

      if (!fallible.isFailure()) {
        final var results = fallible.get();
        if (results instanceof EDSLConstraintResult || results instanceof ProceduralConstraintResult) {
          insertResultsStatement.setLong(1, constraint.constraintId());
          insertResultsStatement.setLong(2, constraint.revision());
          insertResultsStatement.setLong(3, simulationDatasetId);
          insertResultsStatement.setString(4, constraintArgumentsP.unparse(constraint.arguments()).toString());
          insertResultsStatement.setString(5, results.toJSON().toString());
          insertResultsStatement.setString(6, "{}");

          insertResultsStatement.setLong(7, requestId);
          insertResultsStatement.setLong(8, constraint.invocationId());
          insertResultsStatement.setLong(9, constraint.priority());

          // Add to batch
          insertResultsStatement.addBatch();
        } else if (results instanceof DBConstraintResult db) {
          // If the result is already in the DB, we only need to insert the association
          associateResultsStatement.setLong(1, requestId);
          associateResultsStatement.setLong(2, constraint.invocationId());
          associateResultsStatement.setLong(3, db.id());
          associateResultsStatement.setLong(4, constraint.priority());
          associateResultsStatement.addBatch();
        } else {
          throw new IllegalArgumentException("Unrecognized ConstraintResults type: " + results.getClass());
        }
      } else {
        final var errorsArray = ResponseSerializers.serializeConstraintErrors(fallible.getFailureOptional().orElse(List.of()));

        insertResultsStatement.setLong(1, constraint.constraintId());
        insertResultsStatement.setLong(2, constraint.revision());
        insertResultsStatement.setLong(3, simulationDatasetId);
        insertResultsStatement.setString(4, constraintArgumentsP.unparse(constraint.arguments()).toString());
        insertResultsStatement.setString(5, "{}");
        insertResultsStatement.setString(6, errorsArray.toString());

        insertResultsStatement.setLong(7, requestId);
        insertResultsStatement.setLong(8, constraint.invocationId());
        insertResultsStatement.setLong(9, constraint.priority());

        // Add to batch
        insertResultsStatement.addBatch();
      }
    }
    // Execute batch
    insertResultsStatement.executeBatch();
    associateResultsStatement.executeBatch();
  }

  @Override
  public void close() throws SQLException {
    this.insertRequestStatement.close();
    this.insertResultsStatement.close();
    this.associateResultsStatement.close();
  }
}
