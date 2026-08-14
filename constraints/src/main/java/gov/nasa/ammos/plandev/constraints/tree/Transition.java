package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Objects;
import java.util.Set;

public final class Transition implements Expression<Windows> {
  public final Expression<DiscreteProfile> profile;
  public final SerializedValue oldState;
  public final SerializedValue newState;

  public Transition(final Expression<DiscreteProfile> profile, final SerializedValue oldState, final SerializedValue newState) {
    this.profile = profile;
    this.oldState = oldState;
    this.newState = newState;
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return this.profile.evaluate(results, bounds, environment).transitions(oldState, newState).select(bounds);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.profile.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
      "\n%s(transition %s\n%s  %s\n%s  %s)",
      prefix,
      this.profile.prettyPrint(prefix + "  "),
      prefix,
      oldState,
      prefix,
      newState
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Transition)) return false;
    final var o = (Transition)obj;

    return Objects.equals(this.profile, o.profile) &&
           Objects.equals(this.oldState, o.oldState) &&
           Objects.equals(this.newState, o.newState);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.profile, this.oldState, this.newState);
  }
}
