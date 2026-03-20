package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

public record ActionPermissionsSet(Map<ActionKey, Permission> permissions){
    public enum ActionKey {
      check_constraints,
      create_expansion_rule,
      create_expansion_set,
      expand_all_activities,
      expand_all_templates,
      assign_activities_by_filter,
      insert_ext_dataset,
      resource_samples,
      schedule,
      sequence_seq_json_bulk,
      simulate
    }
    public enum Permission {
      NO_CHECK,
      OWNER,
      MISSION_MODEL_OWNER,
      PLAN_OWNER,
      PLAN_COLLABORATOR,
      PLAN_OWNER_COLLABORATOR
    }

    public static ActionPermissionsSet fromJSON(ObjectNode json) {
      final var permissions = new HashMap<ActionKey, Permission>(9);
      json.fields().forEachRemaining(entry ->
        permissions.put(ActionKey.valueOf(entry.getKey()), Permission.valueOf(entry.getValue().textValue())));
      return new ActionPermissionsSet(permissions);
    }
    public ObjectNode toJSON(){
      final var jsonBuilder = JsonNodeFactory.instance.objectNode();
      permissions.forEach((k, v) -> jsonBuilder.put(k.name(), v.name()));
      return jsonBuilder;
    }
  }
