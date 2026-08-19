package gov.nasa.ammos.plandev.e2e.types;

import javax.json.JsonObject;
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

  public static ActivityType fromJSON(JsonObject json) {
    final var parameters = json.getJsonObject("parameters");
    final var parameterMap = new HashMap<String, Parameter>();
    for (final var parameterName : parameters.keySet()) {
      parameterMap.put(parameterName, Parameter.fromJSON(parameters.getJsonObject(parameterName)));
    }
    final var subsystem = json.isNull("subsystem") ? null : json.getJsonObject("subsystem").getString("name");
    final var description = json.isNull("description") ? null : json.getString("description");
    return new ActivityType(json.getString("name"),
                            parameterMap,
                            ValueSchema.fromJSON(json.getJsonObject("computed_attributes_value_schema")),
                            subsystem,
                            description);
  }

  public record Parameter(int order, ValueSchema schema) {
    public static Parameter fromJSON(JsonObject json) {
      return new Parameter(json.getInt("order"), ValueSchema.fromJSON(json.getJsonObject("schema")));
    }
  }
}
