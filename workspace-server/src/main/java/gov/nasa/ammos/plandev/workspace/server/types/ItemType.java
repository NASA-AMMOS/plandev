package gov.nasa.ammos.plandev.workspace.server.types;

public enum ItemType {
  file, directory;

  public static ItemType of(String type) {
    return switch (type) {
      case "file" -> file;
      case "directory", "folder" -> directory;
      case null -> throw new IllegalArgumentException("'type' must be provided and be one of 'file', 'folder', or 'directory'.");
      default -> throw new IllegalArgumentException("Invalid type provided: " + type
                                                    + ". 'type' must be one of 'file', 'folder', or 'directory'");
    };
  }
}
