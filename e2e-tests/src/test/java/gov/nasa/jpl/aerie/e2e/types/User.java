package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

public record User(String name, String defaultRole, String[] allowedRoles, Map<String, String> session) {
  public ObjectNode getSession() {
    final var builder =  JsonNodeFactory.instance.objectNode();
    session.forEach(builder::put);
    return builder;
  }
}
