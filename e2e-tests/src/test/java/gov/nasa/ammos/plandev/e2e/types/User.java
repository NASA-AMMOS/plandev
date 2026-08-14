package gov.nasa.jpl.aerie.e2e.types;

import javax.json.Json;
import javax.json.JsonObject;
import java.util.Map;

public record User(String name, String defaultRole, String[] allowedRoles, Map<String, String> session) {
  public User(String name, String defaultRole, String[] allowedRoles) {
    this(name, defaultRole, allowedRoles, Map.of("x-hasura-role", defaultRole, "x-hasura-user-id", name));
  }

  public JsonObject getSession() {
    final var builder =  Json.createObjectBuilder();
    session.forEach(builder::add);
    return builder.build();
  }
}
