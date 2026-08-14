package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalContainer;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Objects;
import java.util.Set;

public record AccumulatedDuration<I extends IntervalContainer<?>>(
        Expression<I> intervals,
        Expression<Duration> unit) implements Expression<LinearProfile> {

  @Override
  public LinearProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var intervals = this.intervals.evaluate(results, bounds, environment);
    return intervals.accumulatedDuration(unit.evaluate(results, bounds, environment));
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.intervals.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
            "\n%s(accumulated-duration %s over %s)",
            prefix,
            this.intervals.prettyPrint(prefix + "  "),
            this.unit
    );
  }
}
