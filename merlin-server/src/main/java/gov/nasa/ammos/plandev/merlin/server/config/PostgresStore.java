package gov.nasa.jpl.aerie.merlin.server.config;

import java.util.Objects;

public record PostgresStore(
    String server,
    String user,
    Integer port,
    String password,
    String database
) implements Store {
  public PostgresStore {
    Objects.requireNonNull(server);
    Objects.requireNonNull(user);
    Objects.requireNonNull(port);
    Objects.requireNonNull(password);
    Objects.requireNonNull(database);
  }
}
