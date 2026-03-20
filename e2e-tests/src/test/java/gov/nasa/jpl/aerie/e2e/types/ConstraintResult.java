package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.StreamSupport;

public record ConstraintResult(
    List<String> resourceIds,
    List<ConstraintResult.ConstraintViolation> violations,
    List<ConstraintResult.Interval> gaps
) {
  public record ConstraintViolation(List<Integer> activityInstanceIds, List<Interval> windows) {

    public static ConstraintViolation fromJSON(ObjectNode json) {
      return new ConstraintViolation(
          StreamSupport.stream(json.get("activityInstanceIds").spliterator(), false)
              .map(JsonNode::intValue)
              .toList(),
          StreamSupport.stream(json.get("windows").spliterator(), false)
              .map(e -> Interval.fromJSON((ObjectNode) e))
              .toList());
    }
  }

  public record Interval(long start, long end) {
    public static Interval fromJSON(ObjectNode json) {
      return new Interval(json.get("start").longValue(), json.get("end").longValue());
    }
  }

  public static ConstraintResult fromJSON(ObjectNode json) {
    final var resourceIds = StreamSupport.stream(json.get("resourceIds").spliterator(), false).map(JsonNode::textValue).toList();
    final var gaps = StreamSupport.stream(json.get("gaps").spliterator(), false).map(e -> Interval.fromJSON((ObjectNode) e)).toList();
    final var violations = StreamSupport.stream(json.get("violations").spliterator(), false).map(e -> ConstraintViolation.fromJSON((ObjectNode) e)).toList();

    return new ConstraintResult(
        resourceIds,
        violations,
        gaps
    );
  }
}
