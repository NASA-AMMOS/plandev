package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

public record ConstraintRequest(
    int requestId,
    int planId,
    int simDatasetId,
    List<ConstraintRun> constraintsRun
) {
  public record CachedConstraintResult(
      int id,
      int constraintId,
      int constraintRevision,
      int simulationDatasetId,
      ObjectNode arguments,
      Optional<ConstraintResult> results,
      Optional<ConstraintError> error
  )
  {
    public static CachedConstraintResult fromJSON(ObjectNode json) {
      return new CachedConstraintResult(
          json.get("id").intValue(),
          json.get("constraint_id").intValue(),
          json.get("constraint_revision").intValue(),
          json.get("simulation_dataset_id").intValue(),
          (ObjectNode) json.get("arguments"),
          json.get("results").isEmpty() ? Optional.empty() : Optional.of(ConstraintResult.fromJSON((ObjectNode) json.get("results"))),
          json.get("errors").isEmpty() ? Optional.empty() : Optional.of(ConstraintError.fromJSON((ObjectNode) json.get("errors")))
      );
    }
  }

  public record ConstraintRun(
      int constraintInvocationId,
      int priority,
      CachedConstraintResult results
  ) {
    public static ConstraintRun fromJSON(ObjectNode json) {
      return new ConstraintRun(
          json.get("constraint_invocation_id").intValue(),
          json.get("order").intValue(),
          CachedConstraintResult.fromJSON((ObjectNode) json.get("results"))
      );
    }
  }

  public static ConstraintRequest fromJSON(ObjectNode json) {
    return new ConstraintRequest(
        json.get("id").intValue(),
        json.get("plan_id").intValue(),
        json.get("simulation_dataset_id").intValue(),
        StreamSupport.stream(json.get("constraints_run").spliterator(), false).map(c -> ConstraintRun.fromJSON((ObjectNode) c)).toList()
    );
  }
}
