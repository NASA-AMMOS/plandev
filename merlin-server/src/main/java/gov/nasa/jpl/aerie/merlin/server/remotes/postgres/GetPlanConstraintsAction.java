package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidEntityException;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonException;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.parseJson;

/*package local*/ final class GetPlanConstraintsAction implements AutoCloseable {
  // We left join through the plan table in order to distinguish
  //   a plan without any enabled constraints from a non-existent plan.
  // A plan without any enabled constraints will produce a placeholder row with nulls.
  private static final @Language("SQL") String sql = """
    select c.constraint_id, c.revision, c.invocation_id, c.name, c.description, c.definition, c.arguments
    from merlin.plan p
      left join (select cs.plan_id, cs.constraint_id, cs.invocation_id, cs.arguments, cd.revision, cm.name, cm.description, cd.definition
                 from merlin.constraint_specification cs
                   left join merlin.constraint_definition cd using (constraint_id)
                   left join merlin.constraint_metadata cm on cs.constraint_id = cm.id
                 where cs.enabled
                   and ((cs.constraint_revision is not null
                           and cs.constraint_revision = cd.revision)
                          or (cs.constraint_revision is null
                                and cd.revision = (select def.revision
                                                   from merlin.constraint_definition def
                                                   where def.constraint_id = cs.constraint_id
                                                   order by def.revision desc limit 1)))
                   ) c
        on p.id = c.plan_id
    where p.id = ?;
    """;

  private final PreparedStatement statement;

  public GetPlanConstraintsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<List<ConstraintRecord>> get(final long planId) throws SQLException {
    this.statement.setLong(1, planId);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return Optional.empty();

      final var constraints = new ArrayList<ConstraintRecord>();
      do {
        if (results.getObject(1) == null) continue;

        final var constraint = new ConstraintRecord(
            results.getLong(1),
            results.getLong(2),
            results.getLong(3),
            results.getString(4),
            results.getString(5),
            results.getString(6),
            parseJson(results.getString(7), new SerializedValueJsonParser()).asMap().get());

        constraints.add(constraint);
      } while (results.next());

      return Optional.of(constraints);
    } catch (InvalidJsonException | InvalidEntityException e) {
      throw new SQLException(e);
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
