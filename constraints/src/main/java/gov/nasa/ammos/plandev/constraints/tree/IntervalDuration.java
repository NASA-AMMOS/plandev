package gov.nasa.ammos.plandev.constraints.tree;

import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import java.util.Set;

public record IntervalDuration(
    Expression<Interval> interval
) implements Expression<Duration> {

  @Override
  public Duration evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return interval.evaluate(results, bounds, environment).duration();
  }

  @Override
  public void extractResources(final Set<String> names) {}

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(interval-duration %s)",
        prefix,
        this.interval.prettyPrint(prefix)
    );
  }
}
