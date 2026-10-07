package gov.nasa.ammos.plandev.workspace.server.types;

import java.util.Set;

public enum MetadataKeys {
  version,
  /** The file's stable identity (a UUID), carried by renames within a workspace. System-managed, never user-editable. */
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
      fileId.name(),
      createdBy.name(), createdAt.name(),
      lastEditedBy.name(), lastEditedAt.name(),
      readOnly.name(), user.name());
  public static final Set<String> mandatoryKeys = Set.of(version.name(),
                                                         createdBy.name(), createdAt.name(),
                                                         lastEditedBy.name(), lastEditedAt.name());

}
