package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObjectBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
  public DirectoryTree(Path root, List<Path> inputList, Map<String, RenderType> extensionMappings) {
    if(!root.toFile().isDirectory()) {
      throw new IllegalArgumentException("Cannot create a DirectoryTree from a file.");
    }
    this.root = new DirectoryNode(root);

    for(final var path : inputList){
      if(path.toFile().isDirectory()) {
        this.root.addChild(new DirectoryNode(path));
      } else {
        this.root.addChild(new FileNode(path, RenderType.getRenderType(path.getFileName().toString(), extensionMappings)));
      }
    }
  }

  private static class FileNode {
    final RenderType renderType;
    final String name;
    final Path path;

    FileNode(Path path, RenderType renderType) {
      this.path = path;
      this.renderType = renderType;
      this.name = path.getFileName().toString();
    }

    JsonObjectBuilder toJsonBuilder() {
      return Json.createObjectBuilder()
                 .add("name", name)
                 .add("type", renderType.name());
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

    @Override
    JsonObjectBuilder toJsonBuilder() {
      final var contentsArray = Json.createArrayBuilder();
      children.forEach((key, child) -> contentsArray.add(child.toJsonBuilder()));
      return Json.createObjectBuilder()
                 .add("name", name)
                 .add("type", renderType.name())
                 .add("contents", contentsArray);
    }
  }

  /**
   * Build a JsonArray representing the contents of this DirectoryTree
   */
  public JsonArray toJson() {
    final var contentsArray = Json.createArrayBuilder();
    root.children.forEach((key, child) -> contentsArray.add(child.toJsonBuilder()));
    return contentsArray.build();
  }
}
