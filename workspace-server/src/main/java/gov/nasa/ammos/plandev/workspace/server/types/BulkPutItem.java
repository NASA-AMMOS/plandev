package gov.nasa.ammos.plandev.workspace.server.types;

import javax.json.JsonException;
import javax.json.JsonObject;
import java.nio.file.Path;
import java.util.Optional;

public record BulkPutItem(
    Path path,
    ItemType uploadType,
    Optional<String> inputFileName,
    boolean overwrite
) {

  public static BulkPutItem fromJson(JsonObject item) throws JsonException {
    final Path path;
    final ItemType type;
    final Optional<String> inputFileName;
    final boolean overwrite;

    if(!item.containsKey("path")) {
      throw new JsonException("Missing required parameter for input object: path");
    }
    path = Path.of(item.getString("path"));

    if(!item.containsKey("type")) {
      throw new JsonException("Missing required parameter for input object: type");
    }
    type = ItemType.of(item.getString("type"));

    if(type == ItemType.directory) {
      if(item.containsKey("input_file_name")) {
        throw new JsonException("Unsupported key 'input_file_name' provided in 'directory'-type upload");
      }
      if(item.containsKey("overwrite")) {
        throw new JsonException("Unsupported key 'overwrite' provided in 'directory'-type upload");
      }
      return new BulkPutItem(path, type, Optional.empty(), false);
    }

    inputFileName = Optional.ofNullable(item.getString("input_file_name", null));
    overwrite = item.getBoolean("overwrite", false);

    return new BulkPutItem(path, type, inputFileName, overwrite);
  }
}
