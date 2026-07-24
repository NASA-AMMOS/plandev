package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/*package-local*/ final class GetModelAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select m.mission, m.name, m.version, m.owner, m.model_type, m.external_backend, m.external_model_key, encode(f.path, 'escape')
    from merlin.mission_model AS m
    left join merlin.uploaded_file AS f
      on m.jar_id = f.id
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


      final var mission = results.getString(1);
      final var name = results.getString(2);
      final var version = results.getString(3);
      final var owner = results.getString(4);
      final var modelType = results.getString(5);
      final var externalBackend = results.getString(6);
      final var externalModelKey = results.getString(7);
      final var pathString = results.getString(8);
      final var path = (pathString == null) ? null : Path.of(pathString);

      return Optional.of(new MissionModelRecord(
              mission,
              name,
              version,
              owner,
              modelType,
              externalBackend,
              externalModelKey,
              path));
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
