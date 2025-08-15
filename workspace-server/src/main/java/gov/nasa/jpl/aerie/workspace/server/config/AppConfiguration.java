package gov.nasa.jpl.aerie.workspace.server.config;

import javax.json.JsonObject;
import java.nio.file.Path;
import java.util.Objects;

public record AppConfiguration (
    int httpPort,
    boolean enableJavalinDevLogging,
    Path workspaceFileStore,
    JsonObject jwtSecret,
    String hasuraAdminSecret,
    Store store
) {
  public AppConfiguration {
    Objects.requireNonNull(workspaceFileStore);
    Objects.requireNonNull(store);
  }
}
