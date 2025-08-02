package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.constraints.model.Violation;

import javax.json.JsonObject;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.server.http.ConstraintParsers.proceduralConstraintResultP;

/**
 * A ConstraintResult that is created from the output of running a Procedural Constraint.
 * @param violations A List of EDSL Violations
 * @param constraintId The constraint's metadata id
 * @param constraintRevision The revision the constraint was on
 * @param constraintName The name of the constraint
 */
public record ProceduralConstraintResult(
    List<Violation> violations,
    long constraintId,
    long constraintRevision,
    String constraintName
) implements ConstraintResult {
  ProceduralConstraintResult(List<Violation> violations, ConstraintRecord constraint) {
    this(violations, constraint.constraintId(), constraint.revision(), constraint.name());
  }

  @Override
  public JsonObject toJSON() {
    return proceduralConstraintResultP.unparse(this).asJsonObject();
  }
}
