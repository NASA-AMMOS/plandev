package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Record what an external backend says PlanDev may do with a model.
 *
 * <p>Same shape and the same reasoning as {@link UpdateExternalIdentityHashAction}: every update to
 * {@code mission_model} bumps its {@code revision}, which stamps results onto
 * {@code simulation_dataset.model_revision} and invalidates cached simulations, so a re-introspection
 * finding no change must be a genuine no-op.
 *
 * <p>The comparison is on {@code jsonb}, not on text, and that distinction is load-bearing here in a way
 * it is not for the hash. Postgres compares two {@code jsonb} values by their parsed content: key order
 * and insignificant whitespace do not count. A backend that serializes its capabilities with a different
 * key order between two deployments -- which Python dict ordering makes entirely possible -- would
 * otherwise look like a change on every refresh and churn the revision each time.
 */
/*package-local*/ final class UpdateExternalCapabilitiesAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
      update merlin.mission_model
      set external_capabilities = ?::jsonb
      where id = ?
        and external_capabilities is distinct from ?::jsonb
      """;

  private final PreparedStatement statement;

  public UpdateExternalCapabilitiesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  /** @return true if the stored capabilities actually changed (and the model revision therefore moved). */
  public boolean apply(final long missionModelId, final String capabilitiesJson) throws SQLException {
    final var value = new PGobject();
    value.setType("jsonb");
    value.setValue(capabilitiesJson);
    this.statement.setObject(1, value);
    this.statement.setLong(2, missionModelId);
    this.statement.setObject(3, value);
    return this.statement.executeUpdate() > 0;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
