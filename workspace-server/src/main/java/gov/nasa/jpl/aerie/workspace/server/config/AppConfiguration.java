package gov.nasa.jpl.aerie.workspace.server.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public record AppConfiguration (
    int httpPort,
    boolean enableJavalinDevLogging,
    Path workspaceFileStore,
    ObjectNode jwtSecret,
    URI hasuraGraphqlURI,
    String hasuraAdminSecret,
    Store store
) {
  public AppConfiguration {
    Objects.requireNonNull(workspaceFileStore);
    Objects.requireNonNull(store);
  }
}
