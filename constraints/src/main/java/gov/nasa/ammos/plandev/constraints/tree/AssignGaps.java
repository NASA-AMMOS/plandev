package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.Profile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;

import java.util.Objects;
import java.util.Set;

public record AssignGaps<P extends Profile<P>>(
    Expression<P> originalProfile,
    Expression<P> defaultProfile) implements Expression<P> {

  @Override
  public P evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var originalProfile = this.originalProfile.evaluate(results, bounds, environment);
    final var defaultProfile = this.defaultProfile.evaluate(results, bounds, environment);

    return originalProfile.assignGaps(defaultProfile);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.originalProfile.extractResources(names);
    this.defaultProfile.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(assignGaps %s %s)",
        prefix,
        this.originalProfile.prettyPrint(prefix + "  "),
        this.defaultProfile.prettyPrint(prefix + "  ")
    );
  }
}
