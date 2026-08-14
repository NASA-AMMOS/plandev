package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.Objects;
import java.util.Set;

public class KeepTrueSegment implements Expression<Windows> {

  public final Expression<Windows> expression;
  public final int i;

  public KeepTrueSegment(final Expression<Windows> expression, final int i) {
    this.expression = expression;
    this.i = i;
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return this.expression.evaluate(results, bounds, environment).keepTrueSegment(i);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(%s[%d])",
        prefix,
        this.expression.prettyPrint(prefix + "  "),
        i
    );  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof final KeepTrueSegment o)) return false;

    return Objects.equals(this.expression, o.expression) && this.i == o.i;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.expression, this.i);
  }

}
