package gov.nasa.jpl.aerie.workspace.server.config;

import java.nio.file.Path;
import java.util.Objects;

public record AppConfiguration (
    int httpPort,
    boolean enableJavalinDevLogging,
    Path workspacesFileStore,
    Store store
) {
  public AppConfiguration {
    Objects.requireNonNull(workspacesFileStore);
    Objects.requireNonNull(store);
  }
}
