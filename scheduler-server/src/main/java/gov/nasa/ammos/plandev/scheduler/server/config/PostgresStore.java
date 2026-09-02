package gov.nasa.ammos.plandev.scheduler.server.config;

import java.util.Objects;

public record PostgresStore(
    String database,
    String server,
    Integer port,
    String user,
    String password
) implements Store {
  public PostgresStore {
    Objects.requireNonNull(database);
    Objects.requireNonNull(server);
    Objects.requireNonNull(port);
    Objects.requireNonNull(user);
    Objects.requireNonNull(password);
  }
}
