package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import org.intellij.lang.annotations.Language;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

/*package-local*/ final class InsertActivityTypesAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into merlin.activity_type (model_id, name, parameters, required_parameters, computed_attributes_value_schema, subsystem, description)
    values (?, ?, ?::json, ?::json, ?::json, ?, ?)
    on conflict (model_id, name) do update
      set parameters = excluded.parameters,
      required_parameters = excluded.required_parameters,
      computed_attributes_value_schema = excluded.computed_attributes_value_schema,
      subsystem = excluded.subsystem,
      description = excluded.description
    """;

  private final PreparedStatement statement;

  public InsertActivityTypesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(final int modelId, Collection<ActivityType> activityTypes, Map<String, Integer> mapSubsystemsToIds)
  throws SQLException, FailedInsertException
  {
    final var connection = statement.getConnection();
    try {
      // From the docs (https://docs.oracle.com/javase/tutorial/jdbc/basics/retrieving.html#batch_updates):
      // "To allow for correct error handling, you should always disable auto-commit mode before beginning a batch update."
      connection.setAutoCommit(false);

      statement.setInt(1, modelId);
      for(final var activityType : activityTypes){
        final var valueSchemaString = valueSchemaP.unparse(activityType.computedAttributesValueSchema()).toString();

        statement.setString(2, activityType.name());
        PreparedStatements.setParameters(statement, 3, activityType.parameters());
        PreparedStatements.setRequiredParameters(this.statement, 4, activityType.requiredParameters());
        this.statement.setString(5, valueSchemaString);


        if (activityType.subsystem().isPresent()) {
          Integer subsystemIndex = mapSubsystemsToIds.get(activityType.subsystem().get());
          if (subsystemIndex == null) {
            throw new FailedInsertException("Unknown subsystem '" + activityType.subsystem().get() + "' for model_id=" + modelId + " in merlin.activity_type");
          }
          this.statement.setInt(6, subsystemIndex);
        } else {
          this.statement.setObject(6, null);
        }

        if (activityType.description().isPresent()) {
          this.statement.setString(7, activityType.description().get());
        } else {
          this.statement.setString(7, null);
        }

        statement.addBatch();
      }

      final int[] results = statement.executeBatch();
      for (int i : results) {
        if (i == Statement.EXECUTE_FAILED) {
          connection.rollback();
          throw new FailedInsertException("merlin.activity_type");
        }
        connection.commit();
      }
    } catch (BatchUpdateException bue){
      throw new FailedInsertException("merlin.activity_type");
    } finally {
      this.statement.getConnection().setAutoCommit(true);
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
