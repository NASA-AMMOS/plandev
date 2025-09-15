package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.intellij.lang.annotations.Language;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

/*package-private*/ final class InsertResourceTypesAction implements AutoCloseable{
    private static final @Language("SQL") String sql = """
    insert into merlin.resource_type (model_id, name, schema)
    values (?, ?, ?::json)
    on conflict (model_id, name) do update
    set schema = excluded.schema
    """;

  private final PreparedStatement statement;

  public InsertResourceTypesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(final int modelId, Map<String, ValueSchema> resourceTypes)
  throws SQLException, FailedInsertException
  {
    final var connection = statement.getConnection();
    try {
      // From the docs (https://docs.oracle.com/javase/tutorial/jdbc/basics/retrieving.html#batch_updates):
      // "To allow for correct error handling, you should always disable auto-commit mode before beginning a batch update."
      connection.setAutoCommit(false);

      statement.setInt(1, modelId);
      for(final var resource : resourceTypes.entrySet()){
        statement.setString(2, resource.getKey());
        statement.setString(3, valueSchemaP.unparse(resource.getValue()).toString());
        statement.addBatch();
      }

      final int[] results = statement.executeBatch();
      for (int i : results) {
        if (i == Statement.EXECUTE_FAILED) {
          connection.rollback();
          throw new FailedInsertException("merlin.resource_type");
        }
        connection.commit();
      }
    } catch (BatchUpdateException bue){
      throw new FailedInsertException("merlin.resource_type");
    } finally {
      this.statement.getConnection().setAutoCommit(true);
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
