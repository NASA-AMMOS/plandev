package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalRecord;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalSource;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalType;
import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/*package-local*/ final class GetSchedulingGoalAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      select gd.goal_id, gd.revision, gm.name, gd.definition, gd.type, encode(f.path, 'escape') as path
      from scheduler.scheduling_goal_definition gd
      left join scheduler.scheduling_goal_metadata gm on gd.goal_id = gm.id
      left join merlin.uploaded_file f on gd.uploaded_jar_id = f.id
      where gd.goal_id = ? and gd.revision = ?;
      """;

  private final PreparedStatement statement;

  public GetSchedulingGoalAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<GoalRecord> get(final GoalId goalId) throws SQLException {
    this.statement.setLong(1, goalId.id());
    this.statement.setLong(2, goalId.revision());
    final var resultSet = this.statement.executeQuery();

    if (!resultSet.next()) return Optional.empty();

    final var name = resultSet.getString("name");
    final var definition = resultSet.getString("definition");
    final var type = resultSet.getString("type");
    final var path = resultSet.getString("path");

    return Optional.of(new GoalRecord(
          goalId,
          name,
          type.equals("JAR") ? new GoalType.JAR(Path.of(path), "" /* TODO this is a property of the specification, not the goal */) : new GoalType.EDSL(definition),
          true // TODO this is not a property of the goal, but rather of the specification
      ));
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
