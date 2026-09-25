package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class MarkPlanReadOnlyAction implements AutoCloseable {
  private static final @Language("SQL") String preCheckSql = """
    select m.is_executable as executable
    from merlin.plan p
    inner join merlin.mission_model m on p.model_id = m.id
    where p.id = ?;
    """;

  private static final @Language("SQL") String updateSql = """
    update merlin.plan
    set is_read_only = true
    where id = ?;
    """;

  private final PreparedStatement preCheckStatement;
  private final PreparedStatement updateStatement;

  public MarkPlanReadOnlyAction(final Connection connection) throws SQLException {
    this.preCheckStatement = connection.prepareStatement(preCheckSql);
    this.updateStatement = connection.prepareStatement(updateSql);
  }

  public void apply(final long planId) throws SQLException, NoSuchPlanException {
    // Pre Check: Only plans with Non-Executable mission models can be readonly
    this.preCheckStatement.setLong(1, planId);
    try(final var results = this.preCheckStatement.executeQuery()) {
      if(!results.next()) throw new NoSuchPlanException(new PlanId(planId));
      if(results.getBoolean("executable")) {
        throw new SQLException("Cannot mark plan %s read only. ".formatted(planId)
                               + "Only plans with non-executable models may be marked read only");
      }
    }

    // Perform update
    this.updateStatement.setLong(1, planId);
    final int affectedRows = this.updateStatement.executeUpdate();
    if(affectedRows != 1) throw new FailedUpdateException("merlin.plan");
  }

  @Override
  public void close() throws SQLException {
    this.preCheckStatement.close();
    this.updateStatement.close();
  }

}
