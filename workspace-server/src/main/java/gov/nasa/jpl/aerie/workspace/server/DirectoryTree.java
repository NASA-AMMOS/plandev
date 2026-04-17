package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataKeys;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A tree representing the contents of a directory on the file system.
 *
 * Used to generate a JSON object for the listFiles endpoint.
 *
 * Does not use a preexisting implementation like Apache's FileUtils, as the focus of this class is to convert
 * the results of `Files.walk` into a JSON Object.
 */
public class DirectoryTree {
  private final DirectoryNode root;

  /**
   * Generate a DirectoryTree.
   *
   * @param root the root directory of the DirectoryTree.
   * @param inputList a list of Paths contained within the root directory
   * @param extensionMappings a map of file extensions to RenderTypes.
   *    Used to determine the RenderType of file paths
   */
  public DirectoryTree(Path root, List<Path> inputList, Map<String, RenderType> extensionMappings, boolean withMetadata) {
    if(!root.toFile().isDirectory()) {
      throw new IllegalArgumentException("Cannot create a DirectoryTree from a file.");
    }
    this.root = new DirectoryNode(root);

    for(final var path : inputList){
      if(path.toFile().isDirectory()) {
        this.root.addChild(new DirectoryNode(path));
      } else {
        final var rType = RenderType.getRenderType(path.getFileName().toString(), extensionMappings);
        this.root.addChild(new FileNode(path, rType, withMetadata));
      }
    }
  }

  private static class FileNode {
    final RenderType renderType;
    final String name;
    final Path path;

    final Optional<JsonObject> metadata;
    final Optional<MetadataStatus> metadataStatus;

    final boolean readOnly;

    private enum MetadataStatus {
      ok, // Metadata file exists and is valid
      missing, // Metadata file is absent
      malformed  // Metadata JSON is invalid
    }

    FileNode(Path path, RenderType renderType) {
      this.path = path;
      this.renderType = renderType;
      this.name = path.getFileName().toString();
      this.metadata = Optional.empty();
      this.metadataStatus = Optional.empty();
      this.readOnly = false;
    }

    FileNode(Path path, RenderType renderType, boolean getMetadata) {
      this.path = path;
      this.renderType = renderType;
      this.name = path.getFileName().toString();

      // Check if we even need to get the file's metadata
      if (!getMetadata || renderType == RenderType.METADATA) {
        this.metadata = Optional.empty();
        this.metadataStatus = Optional.empty();
        this.readOnly = false;
        return;
      }

      // Check if the metadata file exists
      final var metadataFile = this.path.resolveSibling(RenderType.toMetadataFileName(name)).toFile();
      if (!metadataFile.exists() || metadataFile.isDirectory()) {
        this.metadata = Optional.empty();
        this.metadataStatus = Optional.of(MetadataStatus.missing);
        this.readOnly = false;
        return;
      }

      // Attempt to read the metadata file
      final JsonObject fileContents;
      try(final var reader = Json.createReader(new FileReader(metadataFile))){
        fileContents = reader.readObject();
      } catch (FileNotFoundException fnf) {
        this.metadata = Optional.empty();
        this.metadataStatus = Optional.of(MetadataStatus.missing);
        this.readOnly = false;
        return;
      } catch (JsonException je) {
        this.metadata = Optional.empty();
        this.metadataStatus = Optional.of(MetadataStatus.malformed);
        this.readOnly = false;
        return;
      }

      // Validate metadata file
      if(!validateMetadataFile(fileContents)) {
        this.metadata = Optional.empty();
        this.metadataStatus = Optional.of(MetadataStatus.malformed);
        this.readOnly = false;
        return;
      }

      this.metadata = Optional.of(fileContents);
      this.metadataStatus = Optional.of(MetadataStatus.ok);

      // Set "readOnly", if it's present in the metadata
      readOnly = fileContents.getBoolean("readOnly", false);
    }

    private boolean validateMetadataFile(JsonObject metadata) throws JsonException {
      // Check that there's the right amount of keys
      if(metadata.size() > MetadataKeys.values().length || metadata.size() < MetadataKeys.mandatoryKeys.size()) {
        return false;
      }

      // Check that all the keys are real keys and the mandatory keys are present
      if(!MetadataKeys.keySet.containsAll(metadata.keySet()) || !metadata.keySet().containsAll(MetadataKeys.mandatoryKeys)) {
        return false;
      }

      // Validate that the keys have the correct types
      try {
        final var version = metadata.getString("version");
        // Version should be a recognized version
        if (!version.equals("1")) {
          return false;
        }

        metadata.getString("createdBy");

        final var createdAt = metadata.getString("createdAt");
        Instant.parse(createdAt);

        metadata.getString("lastEditedBy");
        final var lastEditedAt = metadata.getString("lastEditedAt");
        Instant.parse(lastEditedAt);


        // Validate that the optional keys have the correct type, if they're present
        if (metadata.containsKey("readOnly")) {
          metadata.getBoolean("readOnly");
        }
        if(metadata.containsKey("user")) {
          metadata.getJsonObject("user");
        }
      } catch (Exception cce) {
        return false;
      }
      return true;
    }

    boolean isReadOnly() {
      return readOnly;
    }

    JsonObjectBuilder toJsonBuilder() {
      final var builder = Json.createObjectBuilder()
                              .add("name", name)
                              .add("type", renderType.name());

      metadataStatus.ifPresent(status -> {
        builder.add("metadataStatus", status.name());
        if(status == MetadataStatus.ok && metadata.isPresent()) {
          builder.add("metadata", metadata.get());
        } else {
          builder.add("metadata", JsonValue.NULL);
        }
      });

      return builder;
    }
  }

  private static class DirectoryNode extends FileNode {
    private final Map<String, FileNode> children;

    DirectoryNode(Path path){
      super(path, RenderType.DIRECTORY);
      children = new TreeMap<>();
    }

    void addChild(FileNode child) {
      final var rpath = this.path.relativize(child.path);

      // If the file is at the root of this directory
      if(rpath.getNameCount() == 1) {
        children.putIfAbsent(child.name, child);
      } else {
        // Create subdirectory if it does not exist
        final var subdir = rpath.getName(0);
        children.putIfAbsent(subdir.toString(), new DirectoryNode(this.path.resolve(subdir)));

        // Add this node to that child node, recursively
        if(children.get(subdir.toString()) instanceof DirectoryNode dn) {
          dn.addChild(child);
        } else {
          throw new IllegalArgumentException("Cannot add subfile to non-directory file "+subdir);
        }
      }
    }

    private List<Path> readOnlyNodes() {
      final var nodeList = new ArrayList<Path>();
      children.forEach((key, child) -> {
        if(child instanceof DirectoryNode subDir) {
          for(final Path subPath : subDir.readOnlyNodes()) {
            nodeList.add(this.path.resolve(subPath));
          }
        } else {
          // Skip Metadata files
          if(!RenderType.isAerieMetadataFile(child.name)) {
            if(child.isReadOnly()) {
              nodeList.add(path.resolve(child.path));
            }
          }
        }
      });
      return nodeList;
    }

    @Override
    JsonObjectBuilder toJsonBuilder() {
      final var contentsArray = Json.createArrayBuilder();
      children.forEach((key, child) -> {
        // Skip Metadata files by default
        if(child.renderType != RenderType.METADATA) {
          contentsArray.add(child.toJsonBuilder());
        }
      });
      return Json.createObjectBuilder()
                 .add("name", name)
                 .add("type", renderType.name())
                 .add("contents", contentsArray);
    }
  }

  public List<Path> readOnlyNodes() {
    return root.readOnlyNodes();
  }

  /**
   * Build a JsonArray representing the contents of this DirectoryTree.
   * By default, skips METADATA type files.
   */
  public JsonArray toJson() {
    final var contentsArray = Json.createArrayBuilder();
    root.children.forEach((key, child) -> {
      // Skip Metadata files
      if(child.renderType != RenderType.METADATA) {
        contentsArray.add(child.toJsonBuilder());
      }
    });
    return contentsArray.build();
  }
}
