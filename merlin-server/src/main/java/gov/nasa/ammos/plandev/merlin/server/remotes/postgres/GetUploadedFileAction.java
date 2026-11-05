package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class GetUploadedFileAction implements AutoCloseable {
  private static final String sql =
      //language=sql
      """
      select encode(path, 'escape') as path
      from merlin.uploaded_file
      where id = ?;
      """;

  private final PreparedStatement statement;

  public GetUploadedFileAction(Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Path get(int uploadedFileId) throws SQLException, NoSuchFileException {
    statement.setInt(1, uploadedFileId);
    final var results = this.statement.executeQuery();
    if(!results.next()){
      throw new NoSuchFileException("No such file exists with id %d".formatted(uploadedFileId));
    }
    return Path.of(results.getString("path"));
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }

}
