package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/*package-local*/ final class GetPlanRevisionDataAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      select
          m.revision as model_revision,
          p.revision as plan_revision,
          s.revision as sim_revision,
          t.revision as template_revision
        from merlin.plan as p
        left join merlin.mission_model as m
          on p.model_id = m.id
        left join merlin.simulation as s
          on p.id = s.plan_id
        left join merlin.simulation_template as t
          on s.simulation_template_id = t.id
        where p.id = ?
      """;

  private final PreparedStatement statement;

  public GetPlanRevisionDataAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<PostgresPlanRevisionData> get(final long planId) throws SQLException {
    this.statement.setLong(1, planId);

    final var results = this.statement.executeQuery();
    if (!results.next()) return Optional.empty();

    final var modelRevision = results.getLong(1);
    final var planRevision = results.getLong(2);
    final var simulationRevision = results.getLong(3);
    final var templateRevision$ =
        results.getObject(4) == null ?
            Optional.<Long>empty() :
            Optional.of(results.getLong(4));

    return Optional.of(new PostgresPlanRevisionData(
        modelRevision,
        planRevision,
        simulationRevision,
        templateRevision$));
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
