package gov.nasa.jpl.plandev.e2e.types;

import javax.json.Json;
import javax.json.JsonObject;
import java.util.Map;

public record User(String name, String defaultRole, String[] allowedRoles, Map<String, String> session) {
  public JsonObject getSession() {
    final var builder =  Json.createObjectBuilder();
    session.forEach(builder::add);
    return builder.build();
  }
}
