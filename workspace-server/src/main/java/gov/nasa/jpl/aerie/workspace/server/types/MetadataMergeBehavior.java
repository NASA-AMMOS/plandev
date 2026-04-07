package gov.nasa.jpl.aerie.workspace.server.types;

public enum MetadataMergeBehavior {
  deepMerge, shallowMerge, overwrite;

  public static MetadataMergeBehavior of(String type) {
    return switch (type) {
      case "deepMerge", "deep" -> deepMerge;
      case "shallowMerge", "shallow" -> shallowMerge;
      case "overwrite" -> overwrite;
      case null -> throw new IllegalArgumentException("'mergeType' must be provided and be one of 'deep', 'shallow', or 'overwrite'.");
      default -> throw new IllegalArgumentException("Invalid type provided: " + type
                                                    + ". 'mergeType' must be one of 'deep', 'shallow', or 'overwrite'");
    };
  }
}
