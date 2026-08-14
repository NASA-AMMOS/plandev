package gov.nasa.ammos.plandev.constraints.tree;

import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import java.util.Set;

public record DurationLiteral(
    Duration duration
) implements Expression<Duration> {

  @Override
  public Duration evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return duration;
  }

  @Override
  public void extractResources(final Set<String> names) {}

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(duration %s)",
        prefix,
        this.duration
    );
  }
}
