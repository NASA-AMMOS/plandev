package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EDSLConstraintResult;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.model.Violation;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

public final class ViolationsOfWindows implements Expression<EDSLConstraintResult> {
  public final Expression<Windows> expression;

  public ViolationsOfWindows(Expression<Windows> expression) {
    this.expression = expression;
  }

  @Override
  public EDSLConstraintResult evaluate(SimulationResults results, final Interval bounds, EvaluationEnvironment environment) {
    final var windows = this.expression.evaluate(results, bounds, environment);
    return new EDSLConstraintResult(
        StreamSupport.stream(windows.iterateEqualTo(false).spliterator(), false)
                     .map(i -> new Violation(List.of(i), new ArrayList<>()))
                     .toList(),
        StreamSupport.stream(
            windows.notEqualTo(windows).assignGaps(new Windows(true)).iterateEqualTo(true).spliterator(),
            false
        ).map($ -> Interval.intersect($, bounds)).filter($ -> !$.isEmpty()).toList()
    );
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return this.expression.prettyPrint(prefix);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ViolationsOfWindows)) return false;
    final var o = (ViolationsOfWindows)obj;

    return Objects.equals(this.expression, o.expression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.expression);
  }
}
