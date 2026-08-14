package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/*package-local*/ final class GetAllModelsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select m.id, m.mission, m.name, m.version, m.owner, f.path
    from merlin.mission_model as m
    inner join merlin.uploaded_file as f on m.jar_id = f.id
    """;

  private final PreparedStatement statement;

  public GetAllModelsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<Long, MissionModelRecord> get() throws SQLException {
    try (final var results = this.statement.executeQuery()) {
      final var missionModels = new HashMap<Long, MissionModelRecord>();

      while (results.next()) {
        final var id = results.getLong(1);
        final var mission = results.getString(2);
        final var name = results.getString(3);
        final var version = results.getString(4);
        final var owner = results.getString(5);
        final var path = Path.of(results.getString(6));

        missionModels.put(
            id,
            new MissionModelRecord(
                mission,
                name,
                version,
                owner,
                path));
      }

      return missionModels;
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
