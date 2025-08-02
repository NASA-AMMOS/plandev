package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class UpdateSchedulingGoalParameterSchemaAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      update scheduler.scheduling_goal_definition gd
      set parameter_schema=?::jsonb
      where gd.goal_id = ? and gd.revision = ?;
      """;

  private final PreparedStatement statement;

  public UpdateSchedulingGoalParameterSchemaAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void update(final GoalId goalId, final ValueSchema schema) throws SQLException {
    this.statement.setString(1, new ValueSchemaJsonParser().unparse(schema).toString());
    this.statement.setLong(2, goalId.id());
    this.statement.setLong(3, goalId.revision());
    this.statement.executeUpdate();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
