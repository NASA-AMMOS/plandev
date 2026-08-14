package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*package-local*/ final class InsertSubsystemsAction implements AutoCloseable {
  private static final @Language("SQL") String insertSQL = """
    insert into tags.tags (name, owner) values (?, 'Mission Model')
    on conflict (name) do nothing;
""";

  private static final @Language("SQL") String findTagsSQL = """
    select name, id
    from tags.tags as t
    where t.name = any (?::text[]);
    """;

  private final PreparedStatement insertStatement;
  private final PreparedStatement findStatement;


  public InsertSubsystemsAction(final Connection connection) throws SQLException {
    this.insertStatement = connection.prepareStatement(insertSQL);
    this.findStatement = connection.prepareStatement(findTagsSQL);
  }

  public Map<String, Integer> apply(List<String> subsystems)
  throws SQLException, FailedInsertException
  {
    try {
      for (final var subsystem : subsystems) {
        insertStatement.setString(1, subsystem);
        insertStatement.addBatch();
      }
      insertStatement.executeBatch();

      var sqlArray = findStatement.getConnection().createArrayOf("text", subsystems.toArray());
      findStatement.setArray(1, sqlArray);
      try (final var resultSet = findStatement.executeQuery()) {
        Map<String, Integer> subsystemIds = new HashMap<>();
        while (resultSet.next()) {
          subsystemIds.put(resultSet.getString("name"), resultSet.getInt("id"));
        }
        return subsystemIds;
      }
    } catch (BatchUpdateException bue){
      throw new FailedInsertException("merlin.activity_type");
    }
  }

  @Override
  public void close() throws SQLException {
    this.insertStatement.close();
    this.findStatement.close();
  }
}
