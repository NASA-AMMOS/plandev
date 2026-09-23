package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.types.MissionModelId;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class CreateModelAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into merlin.mission_model (mission, name, version, owner, definition_file_id, is_executable)
    values ('', ?, current_time::text, ?, ?, false)
    returning id;
  """;

  private final PreparedStatement statement;

  public CreateModelAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public MissionModelId apply(final String modelName, final String owner, final int definitionFileId) throws SQLException {
    this.statement.setString(1, modelName);
    this.statement.setString(2, owner);
    this.statement.setInt(3, definitionFileId);

    final var results = this.statement.executeQuery();
    if(!results.next()) {
      throw new SQLException("Unable to insert mission model");
    }
    return new MissionModelId(results.getInt("id"));
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
