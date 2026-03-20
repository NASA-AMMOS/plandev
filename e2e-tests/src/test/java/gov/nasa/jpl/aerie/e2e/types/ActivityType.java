package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

public record ActivityType(String name, Map<String, Parameter> parameters, ValueSchema computedAttributes, String subsystem, String description) {
  /**
   * Create an ActivityType with an empty computed attributes value schema.
   */
  public ActivityType(final String name, final Map<String, Parameter> parameters, final String subsystem) {
    this(name, parameters, new ValueSchema.ValueSchemaStruct(Map.of()), subsystem, null);
  }

  /**
   * Create an ActivityType with an empty computed attributes value schema and no description.
   */
  public ActivityType(final String name, final Map<String, Parameter> parameters) {
    this(name, parameters, new ValueSchema.ValueSchemaStruct(Map.of()), null, null);
  }

  public ActivityType(final String name, final Map<String, Parameter> parameters, final ValueSchema computedAttributes, final String subsystem) {
    this(name, parameters, computedAttributes, subsystem, null);
  }

  public static ActivityType fromJSON(ObjectNode json) {
    final var parameters = json.get("parameters");
    final var parameterMap = new HashMap<String, Parameter>();
    final var fieldIter = parameters.fieldNames();
    while (fieldIter.hasNext()) {
      final var parameterName = fieldIter.next();
      parameterMap.put(parameterName, Parameter.fromJSON((ObjectNode) parameters.get(parameterName)));
    }
    final var subsystem = (json.get("subsystem") == null || json.get("subsystem").isNull()) ? null : json.get("subsystem").get("name").textValue();
    final var description = (json.get("description") == null || json.get("description").isNull()) ? null : json.get("description").textValue();
    return new ActivityType(json.get("name").textValue(),
                            parameterMap,
                            ValueSchema.fromJSON(json.get("computed_attributes_value_schema")),
                            subsystem,
                            description);
  }

  public record Parameter(int order, ValueSchema schema) {
    public static Parameter fromJSON(ObjectNode json) {
      return new Parameter(json.get("order").intValue(), ValueSchema.fromJSON(json.get("schema")));
    }
  }
}
