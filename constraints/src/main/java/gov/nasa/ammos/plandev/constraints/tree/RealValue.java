package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Optional;
import java.util.Set;

public record RealValue(double value, double rate, Optional<Expression<Interval>> interval) implements Expression<LinearProfile> {

  public RealValue(final double value) {
    this(value, 0.0, Optional.empty());
  }

  @Override
  public LinearProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final Interval interval = this.interval.map(i -> i.evaluate(results, bounds, environment)).orElse(Interval.FOREVER);
    return new LinearProfile(
        Segment.of(Interval.intersect(bounds, interval), new LinearEquation(Duration.ZERO, value, rate))
    );
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
