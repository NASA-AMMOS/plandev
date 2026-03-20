package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

public record ConstraintActionResponse(
    int requestId,
    List<ConstraintRecord> constraintsRun
) {
  public record ConstraintRecord(
      boolean success,
      int constraintInvocationId,
      int constraintId,
      int constraintRevision,
      String constraintName,
      Optional<ConstraintResult> result,
      List<ConstraintError> errors
  ) {
    public static ConstraintRecord fromJSON(ObjectNode json) {
      return new ConstraintRecord(
          json.get("success").booleanValue(),
          json.get("constraintInvocationId").intValue(),
          json.get("constraintId").intValue(),
          json.get("constraintRevision").intValue(),
          json.get("constraintName").textValue(),
          json.get("results").isEmpty()
              ? Optional.empty()
              : Optional.of(ConstraintResult.fromJSON((ObjectNode) json.get("results"))),
          StreamSupport.stream(json.get("errors").spliterator(), false).map(e -> ConstraintError.fromJSON((ObjectNode) e)).toList());
    }
  }

  public static ConstraintActionResponse fromJson(ObjectNode json) {
    return new ConstraintActionResponse(
        json.get("requestId").intValue(),
        StreamSupport.stream(json.get("constraintsRun").spliterator(), false).map(c -> ConstraintRecord.fromJSON((ObjectNode) c)).toList()
    );
  }
}

