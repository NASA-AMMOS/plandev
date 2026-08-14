package gov.nasa.ammos.plandev.e2e.types.workspaces;


import com.microsoft.playwright.options.FilePayload;

import javax.json.Json;
import javax.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A well-formatted input for one item to be created via the bulk-put Workspace Server endpoint.
 * If testing malformed inputs, generate the request in the test itself.
 */
public sealed interface BulkPutItem {
  JsonObject toJson();
  Path getPath();

  /**
   * Input to create a file
   * @param filePath Where to upload the file to in the workspace
   * @param fileContents What to put in the file
   * @param inputFileName If provided, the file contents will be added to the request under this name instead of the file name.
   * @param overwrite If provided, what to set the `overwrite` flag to.
   */
  record FileBulkPutItem(Path filePath, String fileContents, Optional<String> inputFileName, Optional<Boolean> overwrite) implements BulkPutItem {
    public FileBulkPutItem(Path filePath, String contents) {
      this(filePath, contents, Optional.empty(), Optional.empty());
    }

    public FileBulkPutItem(String filePath, String contents) {
      this(Path.of(filePath), contents, Optional.empty(), Optional.empty());
    }

    public FileBulkPutItem(Path filePath, String contents, String inputFileName) {
      this(filePath, contents, Optional.ofNullable(inputFileName), Optional.empty());
    }

    public FileBulkPutItem(Path filePath, String contents, boolean overwrite) {
      this(filePath, contents, Optional.empty(), Optional.of(overwrite));
    }

    public FileBulkPutItem(Path filePath, String contents, String inputFileName, boolean overwrite) {
      this(filePath, contents, Optional.of(inputFileName), Optional.of(overwrite));
    }

    public FilePayload generateFilePayload() {
      if (inputFileName().isPresent()) {
        return new FilePayload(
            inputFileName.get(),
            "text/plain",
            fileContents.getBytes(StandardCharsets.UTF_8)
        );
      }
      return new FilePayload(
          filePath.getFileName().toString(),
          "text/plain",
          fileContents.getBytes(StandardCharsets.UTF_8)
      );
    }

    public JsonObject toJson() {
      final var obj = Json.createObjectBuilder()
                          .add("path", filePath.toString())
                          .add("type", "file");

      inputFileName.ifPresent(i -> obj.add("input_file_name", i));
      overwrite.ifPresent(o -> obj.add("overwrite", o));

      return obj.build();
    }

    public Path getPath() {return filePath;}
  }

  /**
   * Input to create a directory
   * @param dirPath Path to create the folder at
   * @param useFolder If true, uses 'folder' as the type instead of 'directory'. Defaults to false.
   */
  record DirectoryBulkPutItem(Path dirPath, boolean useFolder) implements BulkPutItem {
    public DirectoryBulkPutItem(Path dirPath) {
      this(dirPath, false);
    }

    public DirectoryBulkPutItem(String dirPath) {
      this(Path.of(dirPath), false);
    }

    public JsonObject toJson() {
      final var obj = Json.createObjectBuilder()
                          .add("path", dirPath.toString());

      if(useFolder) {
        obj.add("type", "folder");
      } else {
        obj.add("type", "directory");
      }

      return obj.build();
    }

    public Path getPath() {return dirPath;}
  }
}
