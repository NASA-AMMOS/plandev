package gov.nasa.jpl.aerie.scheduler.server.http;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
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
  public JsonObject getSchema(final SchemaCache anchors) {
    return Json
        .createObjectBuilder()
        .add("type", "object")
        .build();
  }

  @Override
  public JsonParseResult<SchedulingDSL.ActivityTemplate> parse(final JsonValue json) {
    if (!(json instanceof JsonObject)) return JsonParseResult.failure("expected object");
    final var asObject = json.asJsonObject();
    if(!asObject.containsKey("activityType") || !asObject.containsKey("args")) return JsonParseResult.failure("expected elements activityType and args");
    final var activityType = asObject.getString("activityType");
    final var args = asObject.getJsonObject("args");
    if(args.isEmpty()){
      return JsonParseResult.success(new SchedulingDSL.ActivityTemplate(activityType, new StructExpressionAt(Map.of())));
    } else{
      final var map = structExpressionF(profileExpressionP).parse(args);
      return JsonParseResult.success(new SchedulingDSL.ActivityTemplate(activityType,map.getSuccessOrThrow()));
    }
  }

  @Override
  public JsonValue unparse(final SchedulingDSL.ActivityTemplate activityTemplate) {
    final var builder = Json.createObjectBuilder();
    builder.add("activityType", activityTemplate.activityType());
    builder.add("args", structExpressionF(profileExpressionP)
        .unparse(activityTemplate.arguments()));
    return builder.build();
  }
}
