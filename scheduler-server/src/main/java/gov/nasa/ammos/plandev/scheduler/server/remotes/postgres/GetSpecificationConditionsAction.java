package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingConditionId;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingConditionRecord;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingConditionSource;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/*package-local*/ final class GetSpecificationConditionsAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
    select s.condition_id, cd.revision, cm.name, cd.definition
      from scheduler.scheduling_specification_conditions s
      left join scheduler.scheduling_condition_definition cd using (condition_id)
      left join scheduler.scheduling_condition_metadata cm on s.condition_id = cm.id
      where s.specification_id = ?
      and s.enabled
      and ((s.condition_revision is not null and s.condition_revision = cd.revision)
      or (s.condition_revision is null and cd.revision = (select def.revision
                                                      from scheduler.scheduling_condition_definition def
                                                      where def.condition_id = s.condition_id
                                                      order by def.revision desc limit 1)));
    """;

  private final PreparedStatement statement;

  public GetSpecificationConditionsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public List<SchedulingConditionRecord> get(final long specificationId) throws SQLException {
    this.statement.setLong(1, specificationId);
    final var resultSet = this.statement.executeQuery();

    final var conditions = new ArrayList<SchedulingConditionRecord>();
    while (resultSet.next()) {
      final var id = resultSet.getLong("condition_id");
      final var revision = resultSet.getLong("revision");
      final var name = resultSet.getString("name");
      final var definition = resultSet.getString("definition");
      conditions.add(new SchedulingConditionRecord(new SchedulingConditionId(id), revision, name, new SchedulingConditionSource(definition)));
    }

    return conditions;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
