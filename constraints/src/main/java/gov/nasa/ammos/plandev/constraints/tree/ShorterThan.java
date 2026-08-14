package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Objects;
import java.util.Set;

public final class ShorterThan implements Expression<Windows> {
  public final Expression<Windows> windows;
  public final Expression<Duration> duration;

  public ShorterThan(final Expression<Windows> left, final Expression<Duration> right) {
    this.windows = left;
    this.duration = right;
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var windows = this.windows.evaluate(results, bounds, environment);
    return windows.filterByDuration(Duration.ZERO, duration.evaluate(results, bounds, environment));
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.windows.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(duration-of %s shorter than %s)",
        prefix,
        this.windows.prettyPrint(prefix + "  "),
        this.duration.toString()
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ShorterThan)) return false;
    final var o = (ShorterThan)obj;

    return Objects.equals(this.windows, o.windows) &&
           Objects.equals(this.duration, o.duration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.windows, this.duration);
  }
}
