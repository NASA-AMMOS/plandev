package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import java.util.List;
import java.util.Optional;

public record ConstraintResult(
    List<String> resourceIds,
    List<ConstraintResult.ConstraintViolation> violations,
    List<ConstraintResult.Interval> gaps
) {
  public record ConstraintViolation(List<Integer> activityInstanceIds, List<Interval> windows, Optional<String> message) {

    public static ConstraintViolation fromJSON(JsonObject json) {
      return new ConstraintViolation(
          json.getJsonArray("activityInstanceIds")
              .getValuesAs(JsonNumber::intValue),
          json.getJsonArray("windows")
              .getValuesAs(Interval::fromJSON),
          Optional.ofNullable(json.getString("message", null))
      );
    }
  }

  public record Interval(long start, long end) {
    public static Interval fromJSON(JsonObject json) {
      return new Interval(json.getJsonNumber("start").longValue(), json.getJsonNumber("end").longValue());
    }
  }

  public static ConstraintResult fromJSON(JsonObject json) {
    final var resourceIds = json.getJsonArray("resourceIds").getValuesAs(JsonString::getString);
    final var gaps = json.getJsonArray("gaps").getValuesAs(Interval::fromJSON);
    final var violations = json.getJsonArray("violations").getValuesAs(ConstraintViolation::fromJSON);

    return new ConstraintResult(
        resourceIds,
        violations,
        gaps
    );
  }
};
