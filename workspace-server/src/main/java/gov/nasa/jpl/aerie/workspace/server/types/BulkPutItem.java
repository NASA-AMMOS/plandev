package gov.nasa.jpl.aerie.workspace.server.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Optional;

public record BulkPutItem(
    Path path,
    ItemType uploadType,
    Optional<String> inputFileName,
    boolean overwrite
) {

  public static BulkPutItem fromJson(ObjectNode item) {
    final Path path;
    final ItemType type;
    final Optional<String> inputFileName;
    final boolean overwrite;

    if(!item.has("path")) {
      throw new IllegalArgumentException("Missing required parameter for input object: path");
    }
    path = Path.of(item.get("path").textValue());

    if(!item.has("type")) {
      throw new IllegalArgumentException("Missing required parameter for input object: type");
    }
    type = ItemType.of(item.get("type").textValue());

    if(type == ItemType.directory) {
      if(item.has("input_file_name")) {
        throw new IllegalArgumentException("Unsupported key 'input_file_name' provided in 'directory'-type upload");
      }
      if(item.has("overwrite")) {
        throw new IllegalArgumentException("Unsupported key 'overwrite' provided in 'directory'-type upload");
      }
      return new BulkPutItem(path, type, Optional.empty(), false);
    }

    inputFileName = item.has("input_file_name") && !item.get("input_file_name").isNull()
        ? Optional.of(item.get("input_file_name").textValue())
        : Optional.empty();
    overwrite = item.has("overwrite") && item.get("overwrite").asBoolean(false);

    return new BulkPutItem(path, type, inputFileName, overwrite);
  }
}
