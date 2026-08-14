package gov.nasa.ammos.plandev.workspace.server.types;

import javax.json.JsonObject;
import java.nio.file.Path;

public record BulkPostItem(Path currentLocation, Path newPath) {
  public static BulkPostItem fromJson(JsonObject item, Path destinationPath) {
    final Path curLoc = Path.of(item.getString("path"));
    final String newName = item.getString("renameTo", curLoc.getFileName().toString());
    return new BulkPostItem(curLoc, destinationPath.resolve(newName));
  }
}
