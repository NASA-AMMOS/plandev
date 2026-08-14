package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.server.models.ConstraintType;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Gets the type of a Constraint's revision.
 */
public class GetConstraintTypeAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      cd.type,
      cd.definition,
      encode(f.path, 'escape') as path
    from merlin.constraint_metadata as cm
    left join merlin.constraint_definition cd on cm.id = cd.constraint_id
    left join merlin.uploaded_file f on cd.uploaded_jar_id = f.id
    where cm.id = ?
    and cd.revision = ?;
  """;

  private final PreparedStatement statement;

  public GetConstraintTypeAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<ConstraintType> get(long constraintId, long revision) throws SQLException {
    this.statement.setLong(1, constraintId);
    this.statement.setLong(2, revision);

    try (final var results = this.statement.executeQuery()) {
      if (!results.next()) return Optional.empty();
      final var constraintTypeString = results.getString("type");
      switch (constraintTypeString) {
        case "EDSL" -> {
          return Optional.of(new ConstraintType.EDSL(results.getString("definition")));
        }
        case "JAR" -> {
          return Optional.of(new ConstraintType.JAR(results.getString("path")));
        }
        default -> throw new SQLException("Invalid value in 'type' column of 'constraint_definition': "+constraintTypeString);
      }
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
