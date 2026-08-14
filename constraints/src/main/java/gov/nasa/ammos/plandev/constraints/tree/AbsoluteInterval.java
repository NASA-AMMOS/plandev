package gov.nasa.ammos.plandev.constraints.tree;

import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

public record AbsoluteInterval(
    Optional<Instant> start,
    Optional<Instant> end,
    Optional<Interval.Inclusivity> startInclusivity,
    Optional<Interval.Inclusivity> endInclusivity
) implements Expression<Interval> {

  @Override
  public Interval evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final Duration relativeStart = start
        .map(instant -> Duration.of(results.planStart.until(instant, ChronoUnit.MICROS), Duration.MICROSECOND))
        .orElse(results.bounds.start);
    final Duration relativeEnd = end
        .map(instant -> Duration.of(results.planStart.until(instant, ChronoUnit.MICROS), Duration.MICROSECOND))
        .orElse(results.bounds.end);
    return Interval.between(
        relativeStart,
        startInclusivity.orElse(start.isPresent() ? Interval.Inclusivity.Inclusive : results.bounds.startInclusivity),
        relativeEnd,
        endInclusivity.orElse(end.isPresent() ? Interval.Inclusivity.Inclusive : results.bounds.endInclusivity)
    );
  }

  @Override
  public void extractResources(final Set<String> names) {}

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(absolute-interval %s, %s, %s, %s)",
        prefix,
        this.start,
        this.startInclusivity,
        this.end,
        this.endInclusivity
    );
  }
}
