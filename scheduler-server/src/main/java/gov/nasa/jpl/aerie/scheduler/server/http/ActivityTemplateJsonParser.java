package gov.nasa.jpl.aerie.scheduler.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import gov.nasa.jpl.aerie.constraints.tree.StructExpressionAt;
import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.scheduler.server.models.ActivityType;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingDSL;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinDatabaseService;

import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.profileExpressionP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.structExpressionF;

public class ActivityTemplateJsonParser implements JsonParser<SchedulingDSL.ActivityTemplate> {

  private final Map<String, ActivityType> activityTypesByName = new HashMap<>();

  public ActivityTemplateJsonParser(MerlinDatabaseService.MissionModelTypes activityTypes){
    activityTypes.activityTypes().forEach((actType)-> activityTypesByName.put(actType.name(), actType));
  }

  @Override
  public ObjectNode getSchema(final SchemaCache anchors) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("type", "object");
    return node;
  }

  @Override
  public JsonParseResult<SchedulingDSL.ActivityTemplate> parse(final JsonNode json) {
    if (!(json instanceof ObjectNode)) return JsonParseResult.failure("expected object");
    final var asObject = (ObjectNode) json;
    if(!asObject.has("activityType") || !asObject.has("args")) return JsonParseResult.failure("expected elements activityType and args");
    final var activityType = asObject.get("activityType").textValue();
    final var args = (ObjectNode) asObject.get("args");
    if(args.isEmpty()){
      return JsonParseResult.success(new SchedulingDSL.ActivityTemplate(activityType, new StructExpressionAt(Map.of())));
    } else{
      final var map = structExpressionF(profileExpressionP).parse(args);
      return JsonParseResult.success(new SchedulingDSL.ActivityTemplate(activityType,map.getSuccessOrThrow()));
    }
  }

  @Override
  public JsonNode unparse(final SchedulingDSL.ActivityTemplate activityTemplate) {
    final var builder = JsonNodeFactory.instance.objectNode();
    builder.put("activityType", activityTemplate.activityType());
    builder.set("args", structExpressionF(profileExpressionP)
        .unparse(activityTemplate.arguments()));
    return builder;
  }
}
