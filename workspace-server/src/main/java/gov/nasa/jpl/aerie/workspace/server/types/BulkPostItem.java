package gov.nasa.jpl.aerie.workspace.server.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;

public record BulkPostItem(Path currentLocation, Path newPath) {
  public static BulkPostItem fromJson(ObjectNode item, Path destinationPath) {
    final Path curLoc = Path.of(item.get("path").textValue());
    final String newName = item.has("renameTo") && !item.get("renameTo").isNull()
        ? item.get("renameTo").textValue()
        : curLoc.getFileName().toString();
    return new BulkPostItem(curLoc, destinationPath.resolve(newName));
  }
}
