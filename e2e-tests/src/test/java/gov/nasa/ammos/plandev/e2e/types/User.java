package gov.nasa.ammos.plandev.e2e.types;

import javax.json.Json;
import javax.json.JsonObject;
import java.util.Map;

public record User(String name, String defaultRole, String[] allowedRoles, Map<String, String> session) {
  // Standard users to share between the tests
  public static final User admin = new User(
      "bindings_admin_user",
      "aerie_admin",
      new String[]{"aerie_admin", "viewer"},
      Map.of("x-hasura-role", "aerie_admin", "x-hasura-user-id", "bindings_admin_user"));
  public static final User owner = new User(
      "ws_bindings_owner",
      "user",
      new String[] {"user"},
      Map.of("x-hasura-role", "user", "x-hasura-user-id", "ws_bindings_owner"));
  public static final User nonOwner = new User(
      "bindings_not_owner",
      "user",
      new String[]{"user", "viewer"},
      Map.of("x-hasura-role", "user", "x-hasura-user-id", "bindings_not_owner"));
  public static final User viewer = new User(
      "bindings_viewer",
      "viewer",
      new String[]{"viewer"},
      Map.of("x-hasura-role", "viewer", "x-hasura-user-id", "bindings_viewer"));

  public JsonObject getSession() {
    final var builder =  Json.createObjectBuilder();
    session.forEach(builder::add);
    return builder.build();
  }
}
