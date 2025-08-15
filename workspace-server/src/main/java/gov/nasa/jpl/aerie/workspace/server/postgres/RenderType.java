package gov.nasa.jpl.aerie.workspace.server.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Content types that the Aerie UI supports
 */
public enum RenderType {
  TEXT ("Text"),
  BINARY ("Binary"),
  JSON ("JSON"),
  SEQUENCE ("Sequence"),
  METADATA ("Metadata"), // Aerie metadata file
  UNKNOWN (null), // If the filetype is unknown to the Aerie system
  DIRECTORY (null);

  private final String dbName;
  RenderType(String dbName) {
    this.dbName = dbName;
  }

  /**
   * Get the name of this type in the DB, if it exists.
   */
  public String dbName() { return dbName; }

  /**
   * Return the RenderType of the file.
   *
   * Resolves by continually removing the leftmost part of the extension until it either finds a match
   * or has no part of the extension left to search.
   *
   * For example, let's say that the function is given the filename: myfile.json.aerie
   * It would first try to match on ".json.aerie"
   * Assuming that failed, it would then try to match on ".aerie"
   * If that failed, it would return "Unknown"
   *
   * @param fileName the name of the file
   * @param extensionMappings A mapping of file extensions to RenderType
   * @return the RenderType of the extension, or "Unknown" if the extension is not registered in the system.
   */
  public static RenderType getRenderType(String fileName, Map<String, RenderType> extensionMappings) {
    final var extensionsList = new ArrayList<>(List.of(fileName.split("\\.")));
    extensionsList.removeFirst(); // remove the non-extension section of the file name

    while(!extensionsList.isEmpty()) {
      final var extension = "." + String.join(".", extensionsList);

      if(extensionMappings.containsKey(extension)) {
        return extensionMappings.get(extension);
      }
      // Remove the leftmost part of the extension
      extensionsList.removeFirst();
    }

    return RenderType.UNKNOWN;
  }
}
