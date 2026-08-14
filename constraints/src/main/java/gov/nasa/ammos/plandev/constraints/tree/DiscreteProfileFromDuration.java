package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Optional;
import java.util.Set;

public record DiscreteProfileFromDuration(
    Expression<Duration> duration
) implements Expression<DiscreteProfile> {

  @Override
  public DiscreteProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final Duration duration = this.duration.evaluate(results, bounds, environment);
    return new DiscreteProfile(Segment.of(bounds, SerializedValue.of(duration.in(Duration.MICROSECOND))));
  }

  @Override
  public void extractResources(final Set<String> names) {}

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(discrete-profile-of-duration %s)",
        prefix,
        this.duration
    );
  }
}
