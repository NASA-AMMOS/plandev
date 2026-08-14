package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.Profile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.Objects;
import java.util.Set;

public final class Changes<P extends Profile<P>> implements Expression<Windows> {
  public final ProfileExpression<P> expression;

  public Changes(final ProfileExpression<P> expression) {
    this.expression = expression;
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return this.expression.evaluate(results, bounds, environment).changePoints().select(bounds);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(changed %s)",
        prefix,
        this.expression.prettyPrint(prefix + "  ")
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Changes)) return false;
    final var o = (Changes<?>)obj;

    return Objects.equals(this.expression, o.expression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.expression);
  }
}
