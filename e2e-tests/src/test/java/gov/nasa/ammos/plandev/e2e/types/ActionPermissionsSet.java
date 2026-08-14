package gov.nasa.jpl.aerie.e2e.types;

import javax.json.Json;
import javax.json.JsonObject;
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

    public static ActionPermissionsSet fromJSON(JsonObject json) {
      final var permissions = new HashMap<ActionKey, Permission>(9);
      json.forEach((k, __) ->
        permissions.put(ActionKey.valueOf(k), Permission.valueOf(json.getString(k))));
      return new ActionPermissionsSet(permissions);
    }
    public JsonObject toJSON(){
      final var jsonBuilder = Json.createObjectBuilder();
      permissions.forEach((k, v) -> jsonBuilder.add(k.name(), v.name()));
      return jsonBuilder.build();
    }
  }
