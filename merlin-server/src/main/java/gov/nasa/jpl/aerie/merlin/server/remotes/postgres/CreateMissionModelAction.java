package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Inserts a non-JAR ("external") mission_model row (jar_id null) and returns its id. */
/*package-local*/ final class CreateMissionModelAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into merlin.mission_model (mission, name, version, model_type, external_backend_url)
    values (?, ?, ?, 'external', ?)
    returning id;
    """;

  private final PreparedStatement statement;

  public CreateMissionModelAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public long apply(final String mission, final String name, final String version, final String backendUrl)
  throws SQLException {
    this.statement.setString(1, mission);
    this.statement.setString(2, name);
    this.statement.setString(3, version);
    this.statement.setString(4, backendUrl);
    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) throw new SQLException("Insert into merlin.mission_model returned no id");
      return results.getLong("id");
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
