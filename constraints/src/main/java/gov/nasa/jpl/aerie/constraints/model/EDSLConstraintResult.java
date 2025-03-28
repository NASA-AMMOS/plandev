package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;

import java.util.ArrayList;
import java.util.List;

/**
 * A ConstraintResult that is created from evaluating an EDSL Constraint.
 */
public record EDSLConstraintResult(List<Violation> violations, List<Interval> gaps) {

  public EDSLConstraintResult() {
    this(List.of(), List.of());
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
}
