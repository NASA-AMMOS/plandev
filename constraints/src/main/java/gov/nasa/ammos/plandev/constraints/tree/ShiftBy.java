package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.Profile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Set;

public record ShiftBy<P extends Profile<P>>(
    Expression<P> expression,
    Expression<Duration> duration) implements Expression<P> {

  @Override
  public P evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    // bounds aren't shifted here because duration expressions don't care about them; durations don't exist on the timeline.
    final var duration = this.duration.evaluate(results, bounds, environment);

    final var shiftedBounds = bounds.shiftBy(Duration.negate(duration));
    final var originalProfile = this.expression.evaluate(results, shiftedBounds, environment);

    return originalProfile.shiftBy(duration);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
    this.duration.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(shiftBy %s %s)",
        prefix,
        this.expression.prettyPrint(prefix + "  "),
        this.duration.prettyPrint(prefix + "  ")
    );
  }
}
