package gov.nasa.jpl.aerie.workspace.server.postgres;

import org.intellij.lang.annotations.Language;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class GetExtensionMappingsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select file_extension, content_type
    from ui.file_extension_content_type;
    """;

  private final PreparedStatement statement;

  public GetExtensionMappingsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<String, RenderType> get() throws SQLException {
    try(final var res = statement.executeQuery()) {
      final var extensionsMapping = new HashMap<String, RenderType>();
      while(res.next()) {
        final String extension = res.getString("file_extension");
        final RenderType renderType = RenderType.valueOf(res.getString("content_type").toUpperCase());
        extensionsMapping.put(extension, renderType);
      }
      return extensionsMapping;
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
