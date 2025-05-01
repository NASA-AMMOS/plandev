package gov.nasa.jpl.aerie.workspace.server.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class GetExtensionMappingsAction implements AutoCloseable {
  private static final @Language("SQL") String getMappingSql = """
    select file_extension, content_type
    from ui.file_extension_content_type;
    """;

  private static final @Language("SQL") String getListSql = """
    select file_extension
    from ui.file_extension_content_type
    where content_type = ?::ui.supported_content_types;
    """;

  private final PreparedStatement mappingStatement;
  private final PreparedStatement listStatement;

  public GetExtensionMappingsAction(final Connection connection) throws SQLException {
    this.mappingStatement = connection.prepareStatement(getMappingSql);
    this.listStatement = connection.prepareStatement(getListSql);
  }

  /**
   * Get a complete map of recognized extensions to their corresponding RenderType.
   */
  public Map<String, RenderType> get() throws SQLException {
    try(final var res = mappingStatement.executeQuery()) {
      final var extensionsMapping = new HashMap<String, RenderType>();
      while(res.next()) {
        final String extension = res.getString("file_extension");
        final RenderType renderType = RenderType.valueOf(res.getString("content_type").toUpperCase());
        extensionsMapping.put(extension, renderType);
      }
      return extensionsMapping;
    }
  }

  /**
   * Get the list of extensions with the specified RenderType.
   * @param filter the RenderType to filter on
   */
  public List<String> get(RenderType filter) throws SQLException {
    if(filter.dbName() == null) {
      throw new IllegalArgumentException("Invalid filter: type " + filter.name() + " does not exist in the DB.");
    }
    listStatement.setString(1, filter.dbName());
    try(final var res = listStatement.executeQuery()) {
      final var extensions = new ArrayList<String>();
      while(res.next()) {
        extensions.add(res.getString("file_extension"));
      }
      return extensions;
    }
  }

  @Override
  public void close() throws SQLException {
    this.mappingStatement.close();
    this.listStatement.close();
  }
}
