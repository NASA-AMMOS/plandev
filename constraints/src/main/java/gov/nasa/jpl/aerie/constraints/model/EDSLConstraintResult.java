package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;

import javax.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.edslConstraintResultP;

/**
 * A ConstraintResult that is created from evaluating an EDSL Constraint.
 */
public final class EDSLConstraintResult implements ConstraintResult {
  // These two will be initialized during constraint AST evaluation.
  public final List<Violation> violations;
  public final List<Interval> gaps;

  // The rest will be initialized after AST evaluation by the constraints action.
  public List<String> resourceIds;
  public Long constraintId;
  public Long constraintRevision;
  public String constraintName;

  public EDSLConstraintResult() {
    this(List.of(), List.of());
  }

  public EDSLConstraintResult(List<Violation> violations, List<Interval> gaps) {
    this.violations = violations;
    this.gaps = gaps;
  }

  public EDSLConstraintResult(
      final List<Violation> violations,
      final List<Interval> gaps,
      final List<String> resourceIds,
      final Long constraintId,
      final Long constraintRevision,
      final String constraintName
  ) {
    this.violations = violations;
    this.gaps = gaps;
    this.resourceIds = resourceIds;
    this.constraintId = constraintId;
    this.constraintRevision = constraintRevision;
    this.constraintName = constraintName;
  }

  public boolean isEmpty() {
    return violations.isEmpty() && gaps.isEmpty();
  }

  /**
   * Merges two results of violations and gaps into a single result.
   *
   * This function is to be called during constraint AST evaluation, before the
   * extra metadata fields are populated. All fields besides violations and gaps
   * are ignored and lost.
   */
  public static EDSLConstraintResult merge(EDSLConstraintResult l1, EDSLConstraintResult l2) {
    final var violations = new ArrayList<>(l1.violations);
    violations.addAll(l2.violations);

    final var gaps = new ArrayList<>(l1.gaps);
    gaps.addAll(l2.gaps);

    return new EDSLConstraintResult(violations, gaps);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    EDSLConstraintResult that = (EDSLConstraintResult) o;
    return violations.equals(that.violations)
           && gaps.equals(that.gaps)
           && Objects.equals(resourceIds, that.resourceIds)
           && Objects.equals(constraintId, that.constraintId)
           && Objects.equals(constraintRevision, that.constraintRevision)
           && Objects.equals(constraintName, that.constraintName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(violations, gaps, resourceIds, constraintId, constraintRevision, constraintName);
  }

  @Override
  public JsonObject toJSON() {
    return edslConstraintResultP.unparse(this).asJsonObject();
  }
}
