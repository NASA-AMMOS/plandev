package gov.nasa.jpl.aerie.permissions.exceptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;


public class ExceptionSerializers {
  public static JsonNode serializeNoSuchPlanException(final NoSuchPlanException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "no such plan");
    node.put("plan_id", ex.id.id());
    return node;
  }

  public static JsonNode serializeNoSuchSchedulingSpecificationException(final NoSuchSchedulingSpecificationException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "no such scheduling specification");
    node.put("plan_id", ex.id.id());
    return node;
  }

  public static JsonNode serializePermissionsServiceException(final PermissionsServiceException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "error in response");
    node.set("errors", ex.errors);
    return node;
  }

  public static JsonNode serializeForbiddenException(final Forbidden ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", ex.getMessage());
    return node;
  }

  public static JsonNode serializeIOException(final IOException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "error fetching permissions data");
    node.put("cause", ex.getMessage());
    return node;
  }
}
