package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/*package-local*/ final class GetAllModelsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      m.id as id,
      m.mission as mission,
      m.name as name,
      m.version as version,
      m.owner as owner,
      encode(f.path, 'escape') as path,
      m.is_executable as executable
    from merlin.mission_model AS m
    inner join merlin.uploaded_file AS f
      on m.definition_file_id = f.id;
    """;

  private final PreparedStatement statement;

  public GetAllModelsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<Long, MissionModelRecord> get() throws SQLException {
    try (final var results = this.statement.executeQuery()) {
      final var missionModels = new HashMap<Long, MissionModelRecord>();

      while (results.next()) {
        final var id = results.getLong("id");
        final var mission = results.getString("mission");
        final var name = results.getString("name");
        final var version = results.getString("version");
        final var owner = results.getString("owner");
        final var path = Path.of(results.getString("path"));
        final var executable = results.getBoolean("executable");

        missionModels.put(
            id,
            new MissionModelRecord(
                mission,
                name,
                version,
                owner,
                path,
                executable
            ));
      }

      return missionModels;
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
