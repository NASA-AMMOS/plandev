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
    select m.id, m.mission, m.name, m.version, m.owner, m.model_type, m.external_backend, m.external_model_key, m.external_identity_hash, m.external_capabilities::text, f.path
    from merlin.mission_model as m
    left join merlin.uploaded_file as f on m.jar_id = f.id
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
        final var modelType = results.getString(6);
        final var externalBackend = results.getString(7);
        final var externalModelKey = results.getString(8);
        final var externalIdentityHash = results.getString(9);
        final var externalCapabilities = results.getString(10);
        final var pathString = results.getString(11);
        final var path = (pathString == null) ? null : Path.of(pathString);

        missionModels.put(
            id,
            new MissionModelRecord(
                mission,
                name,
                version,
                owner,
                modelType,
                externalBackend,
                externalModelKey,
                externalIdentityHash,
                externalCapabilities,
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
