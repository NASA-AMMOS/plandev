package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;
import org.intellij.lang.annotations.Language;

/*package-local*/ final class GetGoalSatisfactionAction implements AutoCloseable {
  private final static @Language("SQL") String sql = """
    select
      goal.goal_id,
      goal.goal_invocation_id,
      goal.goal_revision,
      goal.satisfied
    from scheduler.scheduling_goal_analysis as goal
    where goal.analysis_id = ?
    """;

  private final PreparedStatement statement;

  public GetGoalSatisfactionAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<GoalId, Boolean> get(final long analysisId) throws SQLException {
    this.statement.setLong(1, analysisId);
    final var resultSet = this.statement.executeQuery();

    final var goals = new HashMap<GoalId, Boolean>();
    while (resultSet.next()) {
      goals.put(
          new GoalId(
              resultSet.getLong("goal_id"),
              resultSet.getLong("goal_revision"),
              resultSet.getLong("goal_invocation_id")
          ),
          resultSet.getBoolean("satisfied")
      );
    }

    return goals;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
