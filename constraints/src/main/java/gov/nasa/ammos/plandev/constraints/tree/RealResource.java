package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RealResource implements Expression<LinearProfile> {
  public final String name;

  public RealResource(final String name) {
    this.name = name;
  }

  @Override
  public LinearProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    if (results.realProfiles.containsKey(this.name)) {
      return results.realProfiles.get(this.name);
    } else if (results.discreteProfiles.containsKey(this.name)) {
      return convertDiscreteProfile(results.discreteProfiles.get(this.name));
    } else if (environment.realExternalProfiles().containsKey(this.name)) {
      return environment.realExternalProfiles().get(this.name);
    } else if (environment.discreteExternalProfiles().containsKey(this.name)) {
      return convertDiscreteProfile(environment.discreteExternalProfiles().get(this.name));
    }

    throw new InputMismatchException(String.format("%s is not a valid resource", this.name));
  }

  private LinearProfile convertDiscreteProfile(final DiscreteProfile profile) {
    return new LinearProfile(profile.profilePieces.map(
        $ -> new LinearEquation(Duration.ZERO, $.asReal().orElseThrow(
            () -> new InputMismatchException("Discrete profile of non-real type cannot be converted to linear")
        ), 0.0)
    ));
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
    if (!(obj instanceof RealResource)) return false;
    final var o = (RealResource)obj;

    return Objects.equals(this.name, o.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.name);
  }
}
