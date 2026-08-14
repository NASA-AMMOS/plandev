package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;

import java.util.Objects;
import java.util.Set;

public final class Times implements Expression<LinearProfile> {
  public final Expression<LinearProfile> profile;
  public final double multiplier;

  public Times(final Expression<LinearProfile> profile, final double multiplier) {
    this.profile = profile;
    this.multiplier = multiplier;
  }

  @Override
  public LinearProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return this.profile.evaluate(results, bounds, environment).times(this.multiplier);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.profile.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(* %s %s)",
        prefix,
        this.profile.prettyPrint(prefix + "  "),
        String.format("\n%s  %s", prefix, this.multiplier)
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Times)) return false;
    final var o = (Times)obj;

    return Objects.equals(this.profile, o.profile) &&
           Objects.equals(this.multiplier, o.multiplier);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.profile, this.multiplier);
  }
}
