package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.StreamSupport;

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
      ObjectNode arguments,
      String name,
      Integer anchorId,
      boolean anchoredToStart
  ) {
    public static ActivityDirective fromJSON(ObjectNode json){
      return new ActivityDirective(
          json.get("id").intValue(),
          json.get("plan_id").intValue(),
          json.get("type").textValue(),
          json.get("startOffset").textValue(),
          json.get("arguments"),
          json.get("name").textValue(),
          (json.get("anchorId") == null || json.get("anchorId").isNull()) ? null : json.get("anchorId").intValue(),
          json.get("anchoredToStart").booleanValue()
      );
    }
  }

  public static Plan fromJSON(ObjectNode json) {
    final var activities = StreamSupport.stream(json.get("activity_directives").spliterator(), false).map(e -> ActivityDirective.fromJSON((ObjectNode) e)).toList();
    return new Plan(
        json.get("id").intValue(),
        json.get("name").textValue(),
        json.get("startTime").textValue(),
        json.get("duration").textValue(),
        json.get("revision").intValue(),
        activities
    );
  }
}
