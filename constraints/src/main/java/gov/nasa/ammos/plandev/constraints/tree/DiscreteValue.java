package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Optional;
import java.util.Set;

public record DiscreteValue(
    SerializedValue value,
    Optional<Expression<Interval>> interval
) implements Expression<DiscreteProfile> {

  public DiscreteValue(final SerializedValue value) {
    this(value, Optional.empty());
  }

  @Override
  public DiscreteProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final Interval interval = this.interval.map(i -> i.evaluate(results, bounds, environment)).orElse(Interval.FOREVER);
    return new DiscreteProfile(Segment.of(Interval.intersect(bounds, interval), this.value));
  }

  @Override
  public void extractResources(final Set<String> names) {}

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(value %s)",
        prefix,
        this.value
    );
  }
}
