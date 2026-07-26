package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Record the identity an external backend reported for a model.
 *
 * <p>The {@code is distinct from} guard is the whole point: every update to {@code mission_model} bumps
 * its {@code revision} (increment_revision_mission_model_update), and that revision is what stamps
 * results onto {@code simulation_dataset.model_revision} and what invalidates cached simulations. So a
 * re-introspection that finds the same hash must be a genuine no-op -- writing the same value back
 * would churn the revision on every refresh and invalidate the cache for no reason. Writing a
 * *different* value is exactly when we want all of that to happen.
 *
 * <p>Null-safe by construction: {@code is distinct from} treats null and a value as different, so the
 * first introspection of a model registered before this column existed does update it (and bumps the
 * revision once, correctly -- we genuinely did not know what it was running against before).
 */
/*package-local*/ final class UpdateExternalIdentityHashAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
      update merlin.mission_model
      set external_identity_hash = ?
      where id = ?
        and external_identity_hash is distinct from ?
      """;

  private final PreparedStatement statement;

  public UpdateExternalIdentityHashAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  /** @return true if the stored hash actually changed (and the model revision therefore moved). */
  public boolean apply(final long missionModelId, final String identityHash) throws SQLException {
    this.statement.setString(1, identityHash);
    this.statement.setLong(2, missionModelId);
    this.statement.setString(3, identityHash);
    return this.statement.executeUpdate() > 0;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
