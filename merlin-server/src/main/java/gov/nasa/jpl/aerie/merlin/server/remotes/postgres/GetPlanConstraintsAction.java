package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidEntityException;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintType;
import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.parseJson;

/*package local*/ final class GetPlanConstraintsAction implements AutoCloseable {
  // We left join through the plan table in order to distinguish
  //   a plan without any enabled constraints from a non-existent plan.
  // A plan without any enabled constraints will produce a placeholder row with nulls.
  private static final @Language("SQL") String sql = """
    select
      c.priority as priority,
      c.invocation_id as iid,
      c.constraint_id as cid,
      c.revision as revision,
      c.name as name,
      c.description as description,
      c.type as type,
      c.definition as definition,
      c.path as path,
      c.arguments as args
    from merlin.plan p
    left join (select
                 cs.plan_id,
                 cs.invocation_id,
                 cs.constraint_id,
                 cd.revision,
                 cm.name,
                 cm.description,
                 cd.type,
                 cd.definition,
                 cs.priority,
                 encode(f.path, 'escape') as path,
                 cs.arguments
               from merlin.constraint_specification cs
                 left join merlin.constraint_definition cd using (constraint_id)
                 left join merlin.constraint_metadata cm on cs.constraint_id = cm.id
                 left join merlin.uploaded_file f on cd.uploaded_jar_id = f.id
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
    where p.id = ?
    order by c.invocation_id;
    """;

  private final PreparedStatement statement;
  private final Path jarFilePath;

  public GetPlanConstraintsAction(final Connection connection, Path jarFilePath) throws SQLException {
    this.statement = connection.prepareStatement(sql);
    this.jarFilePath = jarFilePath;
  }

  public Optional<List<ConstraintRecord>> get(final long planId) throws SQLException {
    this.statement.setLong(1, planId);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return Optional.empty();

      final var constraints = new ArrayList<ConstraintRecord>();
      do {
        if (results.getObject(1) == null) continue;

        final var typeString =  results.getString("type");
        final ConstraintType type;
        switch (typeString) {
          case "EDSL" -> type = new ConstraintType.EDSL(results.getString("definition"));
          case "JAR" -> type = new ConstraintType.JAR(Path.of(jarFilePath.toString(), results.getString("path")));
          case null, default -> throw new IllegalArgumentException("Type `%s` is not a valid type of Aerie Constraint"
                                                                       .formatted(typeString));
        }

        final var constraint = new ConstraintRecord(
            results.getLong("priority"),
            results.getLong("iid"),
            results.getLong("cid"),
            results.getLong("revision"),
            results.getString("name"),
            results.getString("description"),
            type,
            parseJson(results.getString("args"), new SerializedValueJsonParser()).asMap().orElse(Map.of())
        );

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
