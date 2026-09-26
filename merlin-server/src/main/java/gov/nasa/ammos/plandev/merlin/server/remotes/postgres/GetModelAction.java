package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/*package-local*/ final class GetModelAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      m.mission as mission,
      m.name as name,
      m.version as version,
      m.owner as owner,
      encode(f.path, 'escape') as path,
      m.is_executable as executable
    from merlin.mission_model AS m
    inner join merlin.uploaded_file AS f
      on m.definition_file_id = f.id
    where m.id = ?
    """;

  private final PreparedStatement statement;

  public GetModelAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<MissionModelRecord> get(final long modelId) throws SQLException {
    this.statement.setLong(1, modelId);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return Optional.empty();


      final var mission = results.getString("mission");
      final var name = results.getString("name");
      final var version = results.getString("version");
      final var owner = results.getString("owner");
      final var path = Path.of(results.getString("path"));
      final var executable = results.getBoolean("executable");

      return Optional.of(new MissionModelRecord(
              mission,
              name,
              version,
              owner,
              path,
              executable));
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
