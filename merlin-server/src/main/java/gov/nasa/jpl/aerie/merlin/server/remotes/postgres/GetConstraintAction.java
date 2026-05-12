package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidEntityException;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintId;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintType;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.parseJson;

/**
 * Gets a constraint from its id and revision
 */
public class GetConstraintAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      cd.type,
      cd.definition,
      cs.priority,
      cs.invocation_id ,
      cm.name,
      cm.description,
      cd.type,
      cs.arguments,
      encode(f.path, 'escape') as path
    from merlin.constraint_metadata as cm
    left join merlin.constraint_definition cd on cm.id = cd.constraint_id
    left join merlin.constraint_specification cs on cm.id = cs.constraint_id
    left join merlin.uploaded_file f on cd.uploaded_jar_id = f.id
    where cm.id = ?
    and cd.revision = ?;
  """;

  private final PreparedStatement statement;

  public GetConstraintAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<ConstraintId, ConstraintRecord> get(List<ConstraintId> ids) throws SQLException {
    final var constraints = new HashMap<ConstraintId, ConstraintRecord>();
    for(var id: ids) {
      this.statement.setLong(1, id.id());
      this.statement.setLong(2, id.revision());

      try (final var results = this.statement.executeQuery()) {
        if (!results.next()) return constraints;
        final var constraintTypeString = results.getString("type");
        Optional<ConstraintType> type = Optional.empty();
        switch (constraintTypeString) {
          case "EDSL" -> {
            type = Optional.of(new ConstraintType.EDSL(results.getString("definition")));
          }
          case "JAR" -> {
            type = Optional.of(new ConstraintType.JAR(results.getString("path")));
          }
          default -> throw new SQLException("Invalid value in 'type' column of 'constraint_definition': "
                                            + constraintTypeString);
        }
        final var c = new ConstraintRecord(
            results.getLong("priority"),
            results.getLong("invocation_id"),
            id.id(),
            id.revision(),
            results.getString("name"),
            results.getString("description"),
            type.get(),
            parseJson(results.getString("arguments"), new SerializedValueJsonParser()).asMap().orElse(Map.of())
        );
        constraints.put(id, c);
      } catch (InvalidJsonException | InvalidEntityException e) {
        throw new SQLException(e);
      }
    }
    return constraints;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
