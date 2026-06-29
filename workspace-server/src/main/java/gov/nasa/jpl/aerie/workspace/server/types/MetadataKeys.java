package gov.nasa.jpl.aerie.workspace.server.types;

import java.util.Set;

public enum MetadataKeys {
  version,
  fileId,
  createdBy,
  createdAt,
  lastEditedBy,
  lastEditedAt,
  readOnly,
  user;

  public static final Set<String> whitelist = Set.of(readOnly.name(), user.name());
  public static final Set<String> keySet = Set.of(
      version.name(),
      createdBy.name(), createdAt.name(),
      lastEditedBy.name(), lastEditedAt.name(),
      readOnly.name(), user.name());
  public static final Set<String> mandatoryKeys = Set.of(version.name(),
                                                         createdBy.name(), createdAt.name(),
                                                         lastEditedBy.name(), lastEditedAt.name());

}
