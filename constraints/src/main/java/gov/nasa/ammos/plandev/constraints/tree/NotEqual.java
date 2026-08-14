package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.Profile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.Objects;
import java.util.Set;

public final class NotEqual<P extends Profile<P>> implements Expression<Windows> {
  public final Expression<P> left;
  public final Expression<P> right;

  public NotEqual(final Expression<P> left, final Expression<P> right) {
    this.left = left;
    this.right = right;
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var leftProfile = this.left.evaluate(results, bounds, environment);
    final var rightProfile = this.right.evaluate(results, bounds, environment);

    return leftProfile.notEqualTo(rightProfile).select(bounds);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.left.extractResources(names);
    this.right.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(!= %s %s)",
        prefix,
        this.left.prettyPrint(prefix + "  "),
        this.right.prettyPrint(prefix + "  ")
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof NotEqual)) return false;
    final var o = (NotEqual<?>)obj;

    return Objects.equals(this.left, o.left) &&
           Objects.equals(this.right, o.right);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.left, this.right);
  }
}
