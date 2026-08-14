package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity;
import gov.nasa.jpl.aerie.constraints.time.IntervalContainer;
import gov.nasa.jpl.aerie.constraints.time.Spans;

import java.util.Objects;
import java.util.Set;

public final class Split<I extends IntervalContainer<?>> implements Expression<Spans> {
  public final Expression<I> intervals;
  public final int numberOfSubIntervals;
  public final Inclusivity internalStartInclusivity;
  public final Inclusivity internalEndInclusivity;

  public Split(final Expression<I> intervals,
               final int numberOfSubIntervals,
               final Inclusivity internalStartInclusivity,
               final Inclusivity internalEndInclusivity) {
    this.intervals = intervals;
    this.numberOfSubIntervals = numberOfSubIntervals;
    this.internalStartInclusivity = internalStartInclusivity;
    this.internalEndInclusivity = internalEndInclusivity;
  }

  @Override
  public Spans evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var intervals = this.intervals.evaluate(results, bounds, environment);
    return intervals.split(bounds, this.numberOfSubIntervals, this.internalStartInclusivity, this.internalEndInclusivity);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.intervals.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(split %s into %s)",
        prefix,
        this.intervals.prettyPrint(prefix + "  "),
        this.numberOfSubIntervals
    );
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Split<?> split = (Split<?>) o;
    return numberOfSubIntervals == split.numberOfSubIntervals && Objects.equals(intervals, split.intervals);
  }

  @Override
  public int hashCode() {
    return Objects.hash(intervals, numberOfSubIntervals);
  }
}
