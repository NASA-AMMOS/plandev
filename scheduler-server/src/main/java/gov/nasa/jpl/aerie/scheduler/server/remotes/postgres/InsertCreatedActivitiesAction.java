package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.ActivityDirectiveId;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;

/*package-local*/ final class InsertCreatedActivitiesAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into scheduler.scheduling_goal_analysis_created_activities (analysis_id, goal_invocation_id, activity_id)
    values (?, ?, ?)
    """;

  private final PreparedStatement statement;

  public InsertCreatedActivitiesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(
      final long analysisId,
      final Map<GoalId, Collection<ActivityDirectiveId>> createdActivities
  ) throws SQLException {
    for (final var entry : createdActivities.entrySet()) {
      final var goal = entry.getKey();
      for (final var activityId : entry.getValue()) {
        this.statement.setLong(1, analysisId);
        this.statement.setLong(2, goal.goalInvocationId().get());
        this.statement.setLong(3, activityId.id());
        this.statement.addBatch();
      }
    }

    final var resultSet = this.statement.executeBatch();
    for (final var result : resultSet) {
      if (result == Statement.EXECUTE_FAILED) throw new FailedInsertException("scheduling_goal_analysis_created_activities");
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
