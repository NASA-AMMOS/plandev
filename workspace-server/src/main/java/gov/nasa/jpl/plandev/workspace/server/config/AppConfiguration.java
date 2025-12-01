package gov.nasa.jpl.plandev.workspace.server.config;

import javax.json.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public record AppConfiguration (
    int httpPort,
    boolean enableJavalinDevLogging,
    Path workspaceFileStore,
    JsonObject jwtSecret,
    URI hasuraGraphqlURI,
    String hasuraAdminSecret,
    Store store
) {
  public AppConfiguration {
    Objects.requireNonNull(workspaceFileStore);
    Objects.requireNonNull(store);
  }
}
