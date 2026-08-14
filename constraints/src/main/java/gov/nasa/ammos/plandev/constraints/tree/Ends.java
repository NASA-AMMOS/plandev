package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalContainer;

import java.util.Objects;
import java.util.Set;

public final class Ends<I extends IntervalContainer<I>> implements Expression<I> {
  public final Expression<I> expression;

  public Ends(final Expression<I> expression) {
    this.expression = expression;
  }

  @Override
  public I evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var expression = this.expression.evaluate(results, bounds, environment);
    return expression.ends();
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(ends-of %s)",
        prefix,
        this.expression.prettyPrint(prefix + "  ")
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof final Ends o)) return false;

    return Objects.equals(this.expression, o.expression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.expression);
  }
}
