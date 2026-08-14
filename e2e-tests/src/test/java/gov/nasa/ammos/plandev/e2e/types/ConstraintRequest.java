package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import java.util.List;
import java.util.Optional;

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
      JsonObject arguments,
      Optional<ConstraintResult> results,
      Optional<ConstraintError> error
  )
  {
    public static CachedConstraintResult fromJSON(JsonObject json) {
      return new CachedConstraintResult(
          json.getInt("id"),
          json.getInt("constraint_id"),
          json.getInt("constraint_revision"),
          json.getInt("simulation_dataset_id"),
          json.getJsonObject("arguments"),
          json.getJsonObject("results").isEmpty() ? Optional.empty() : Optional.of(ConstraintResult.fromJSON(json.getJsonObject("results"))),
          json.getJsonObject("errors").isEmpty() ? Optional.empty() : Optional.of(ConstraintError.fromJSON(json.getJsonObject("errors")))
      );
    }
  }

  public record ConstraintRun(
      int constraintInvocationId,
      int priority,
      CachedConstraintResult results
  ) {
    public static ConstraintRun fromJSON(JsonObject json) {
      return new ConstraintRun(
          json.getInt("constraint_invocation_id"),
          json.getInt("order"),
          CachedConstraintResult.fromJSON(json.getJsonObject("results"))
      );
    }
  }

  public static ConstraintRequest fromJSON(JsonObject json) {
    return new ConstraintRequest(
        json.getInt("id"),
        json.getInt("plan_id"),
        json.getInt("simulation_dataset_id"),
        json.getJsonArray("constraints_run").getValuesAs(c -> ConstraintRun.fromJSON(c.asJsonObject()))
    );
  }
}
