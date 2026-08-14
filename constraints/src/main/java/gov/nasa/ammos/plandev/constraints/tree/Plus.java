package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;

import java.util.Objects;
import java.util.Set;

public final class Plus implements Expression<LinearProfile> {
  public final Expression<LinearProfile> left;
  public final Expression<LinearProfile> right;

  public Plus(final Expression<LinearProfile> left, final Expression<LinearProfile> right) {
    this.left = left;
    this.right = right;
  }

  @Override
  public LinearProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return left.evaluate(results, bounds, environment)
               .plus(right.evaluate(results, bounds, environment));
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.left.extractResources(names);
    this.right.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(+ %s %s)",
        prefix,
        this.left.prettyPrint(prefix + "  "),
        this.right.prettyPrint(prefix + "  ")
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Plus)) return false;
    final var o = (Plus)obj;

    return Objects.equals(this.left, o.left) &&
           Objects.equals(this.right, o.right);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.left, this.right);
  }
}
