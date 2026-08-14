package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.Profile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;

import java.util.Objects;
import java.util.Set;

public final class ProfileExpression<P extends Profile<P>> implements Expression<Profile<P>> {
  public final Expression<P> expression;

  public ProfileExpression(final Expression<P> profile) {
    this.expression = profile;
  }

  @Override
  public Profile<P> evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return this.expression.evaluate(results, bounds, environment);
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
    if (!(obj instanceof ProfileExpression)) return false;
    final var o = (ProfileExpression<?>) obj;

    return Objects.equals(this.expression, o.expression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.expression);
  }
}
