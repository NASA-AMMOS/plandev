package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchConstraintException;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintType;
import gov.nasa.jpl.aerie.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.remotes.ConstraintRepository;
import gov.nasa.jpl.aerie.merlin.server.services.ConstraintRequestConfiguration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class PostgresConstraintRepository implements ConstraintRepository {
  private final DataSource dataSource;

  public PostgresConstraintRepository(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public int insertConstraintRuns(
      final ConstraintRequestConfiguration requestConfiguration,
      final Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> constraintToResultsMap
  ) {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var insertConstraintRunsAction = new InsertConstraintResultsAction(connection)) {
        return insertConstraintRunsAction.postResults(requestConfiguration, constraintToResultsMap);
      }
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to save constraint run", ex);
    }
  }

  @Override
  public Map<ConstraintRecord, DBConstraintResult> getValidConstraintRuns(
      List<ConstraintRecord> constraints,
      SimulationDatasetId simulationDatasetId
  ) {
    try (final var connection = this.dataSource.getConnection();
         final var validConstraintRunAction = new GetValidConstraintRunsAction(connection, constraints, simulationDatasetId)) {
      return validConstraintRunAction.get();
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get constraint runs", ex);
    }
  }

  @Override
  public ConstraintType getConstraintType(final long constraintId, final long revision) throws NoSuchConstraintException {
    try(final var connection = this.dataSource.getConnection();
        final var getConstraintAction = new GetConstraintTypeAction(connection)) {
      return getConstraintAction.get(constraintId, revision)
                                .orElseThrow(() -> new NoSuchConstraintException(constraintId, revision));
    } catch (SQLException ex) {
      throw new DatabaseException("Failed to get constraint.", ex);
    }
  }

  @Override
  public void updateConstraintParameterSchema(final long constraintId, final long revision, final ValueSchema schema) {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var updateConstraintAction = new UpdateConstraintParametersAction(connection)) {
        updateConstraintAction.update(constraintId, revision, schema);
      }
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get constraint revision data", ex);
    }
  }
}
