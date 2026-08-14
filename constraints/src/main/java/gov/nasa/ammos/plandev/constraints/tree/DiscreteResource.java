package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;

import java.util.Objects;
import java.util.Set;

public final class DiscreteResource implements Expression<DiscreteProfile> {
  public final String name;

  public DiscreteResource(final String name) {
    this.name = name;
  }

  @Override
  public DiscreteProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    if (results.discreteProfiles.containsKey(this.name)) {
      return results.discreteProfiles.get(this.name);
    } else if (environment.discreteExternalProfiles().containsKey(this.name)) {
      return environment.discreteExternalProfiles().get(this.name);
    } else if (results.realProfiles.containsKey(this.name) || environment.realExternalProfiles().containsKey(this.name)) {
      throw new InputMismatchException(String.format("%s is a real resource, cannot interpret as discrete", this.name));
    }

    throw new InputMismatchException(String.format("%s is not a valid resource", this.name));
  }

  @Override
  public void extractResources(final Set<String> names) {
    names.add(this.name);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(resource %s)",
        prefix,
        this.name
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof DiscreteResource)) return false;
    final var o = (DiscreteResource)obj;

    return Objects.equals(this.name, o.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.name);
  }
}
