package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Objects;
import java.util.Set;

public final class LongerThan implements Expression<Windows> {
  public final Expression<Windows> windows;
  public final Expression<Duration> duration;

  public LongerThan(final Expression<Windows> left, final Expression<Duration> duration) {
    this.windows = left;
    this.duration = duration;
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var windows = this.windows.evaluate(results, bounds, environment);
    return windows.filterByDuration(this.duration.evaluate(results, bounds, environment), Duration.MAX_VALUE);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.windows.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(duration-of %s larger than %s)",
        prefix,
        this.windows.prettyPrint(prefix + "  "),
        this.duration.toString()
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof LongerThan)) return false;
    final var o = (LongerThan)obj;

    return Objects.equals(this.windows, o.windows) &&
           Objects.equals(this.duration, o.duration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.windows, this.duration);
  }
}
