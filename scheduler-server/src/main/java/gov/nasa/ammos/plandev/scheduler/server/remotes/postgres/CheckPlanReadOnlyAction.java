package gov.nasa.ammos.plandev.scheduler.server.remotes.postgres;

import gov.nasa.ammos.plandev.scheduler.server.models.PlanId;
import gov.nasa.ammos.plandev.types.MissionModelId;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class CheckPlanReadOnlyAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
    select
      m.id as model_id,
      m.is_executable as model_executable,
      p.id as plan_id,
      p.is_read_only as plan_read_only
    from merlin.mission_model m
    join merlin.plan p on m.id = p.model_id
    join scheduler.scheduling_specification ss on p.id = ss.plan_id
    where ss.id = ?;
    """;

  private final PreparedStatement statement;

  public CheckPlanReadOnlyAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public PlanReadOnlyCheckResult get(final long specificationId) throws SQLException {
    this.statement.setLong(1, specificationId);

    try(final var resultSet = this.statement.executeQuery()) {
      if (!resultSet.next()) throw new SQLException("Cannot find a mission model associated with specification " + specificationId);

      final var planId = new PlanId(resultSet.getInt("plan_id"));
      final var planReadOnly = resultSet.getBoolean("plan_read_only");

      final var modelId = new MissionModelId(resultSet.getInt("model_id"));
      final var executable = resultSet.getBoolean("model_executable");

      return new PlanReadOnlyCheckResult(planId, planReadOnly, modelId, executable);
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
