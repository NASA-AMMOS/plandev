package gov.nasa.jpl.aerie.workspace.server.types;

import java.util.Set;

public enum MetadataKeys {
  version,
  createdBy,
  createdAt,
  lastEditedBy,
  lastEditedAt,
  readOnly,
  user;

  public static final Set<String> whitelist = Set.of("readOnly", "user");
}
