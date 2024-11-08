package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/*package-local*/ public class UploadExternalSourceTypeAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    insert into merlin.external_source_type
    values (?, ?);
    """;

  private final PreparedStatement statement;

  public UploadExternalSourceTypeAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void upload(final String name, final String schema) throws SQLException {
    this.statement.setString(1, name);
    this.statement.setString(2, schema);

    final var count = this.statement.executeUpdate();
    if (count < 1) throw new Error("Failed to upload source type.");
    if (count > 1) throw new Error("More than one row affected by dataset update by primary key. Is the database corrupted?");
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
