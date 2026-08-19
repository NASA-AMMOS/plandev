package gov.nasa.ammos.plandev.constraints.tree;

import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Windows;

import java.util.Optional;
import java.util.Set;

public record WindowsValue(boolean value, Optional<Expression<Interval>> interval) implements Expression<Windows> {

  public WindowsValue(boolean value) {
    this(value, Optional.empty());
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final Interval interval = this.interval.map(i -> i.evaluate(results, bounds, environment)).orElse(Interval.FOREVER);
    return new Windows(Interval.intersect(bounds, interval), value);
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
