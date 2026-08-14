package gov.nasa.jpl.aerie.e2e.types;


import javax.json.JsonObject;
import java.util.List;

public record Plan(
    int id,
    String name,
    String startTime,
    String duration,
    int revision,
    List<ActivityDirective> activityDirectives
) {
  public record ActivityDirective(
      int id,
      int planId,
      String type,
      String startOffset,
      JsonObject arguments,
      String name,
      Integer anchorId,
      boolean anchoredToStart
  ) {
    public static ActivityDirective fromJSON(JsonObject json){
      return new ActivityDirective(
          json.getInt("id"),
          json.getInt("plan_id"),
          json.getString("type"),
          json.getString("startOffset"),
          json.getJsonObject("arguments"),
          json.getString("name"),
          json.isNull("anchorId") ? null : json.getInt("anchorId"),
          json.getBoolean("anchoredToStart")
      );
    }
  }

  public static Plan fromJSON(JsonObject json) {
    final var activities = json.getJsonArray("activity_directives").getValuesAs(ActivityDirective::fromJSON);
    return new Plan(
        json.getInt("id"),
        json.getString("name"),
        json.getString("startTime"),
        json.getString("duration"),
        json.getInt("revision"),
        activities
    );
  }
}
