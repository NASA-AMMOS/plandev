package gov.nasa.jpl.aerie.merlin.server.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record AppConfiguration (
    int httpPort,
    boolean enableJavalinDevLogging,
    Path merlinFileStore,
    Store store,
    Instant untruePlanStart,
    URI hasuraGraphqlURI,
    String hasuraGraphQlAdminSecret,
    boolean enableContinuousValidationThread,
    int validationThreadPollingPeriod
) {
  public AppConfiguration {
    Objects.requireNonNull(merlinFileStore);
    Objects.requireNonNull(store);
    Objects.requireNonNull(untruePlanStart);
  }
}
