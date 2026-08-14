package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalContainer;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Objects;
import java.util.Set;

public final class ShiftEdges<I extends IntervalContainer<I>> implements Expression<I> {
  public final Expression<I> expression;
  public final Expression<Duration> fromStart;
  public final Expression<Duration> fromEnd;

  public ShiftEdges(final Expression<I> left, final Expression<Duration> fromStart, final Expression<Duration> fromEnd) {
    this.expression = left;
    this.fromStart = fromStart;
    this.fromEnd = fromEnd;
  }

  @Override
  public I evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var shiftRising = this.fromStart.evaluate(results, bounds, environment);
    final var shiftFalling = this.fromEnd.evaluate(results, bounds, environment);

    final var newBounds = Interval.between(
        Duration.min(bounds.start.minus(shiftRising), bounds.start.minus(shiftFalling)),
        bounds.startInclusivity,
        Duration.max(bounds.end.minus(shiftRising), bounds.end.minus(shiftFalling)),
        bounds.endInclusivity
    );

    final var intervals = this.expression.evaluate(results, newBounds, environment);
    return intervals.shiftEdges(shiftRising, shiftFalling).select(bounds);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(shiftEdges %s by %s %s)",
        prefix,
        this.expression.prettyPrint(prefix + "  "),
        this.fromStart.toString(),
        this.fromEnd.toString()
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ShiftEdges)) return false;
    final var o = (ShiftEdges<?>)obj;

    return Objects.equals(this.expression, o.expression) &&
           Objects.equals(this.fromStart, o.fromStart) &&
           Objects.equals(this.fromEnd, o.fromEnd);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.expression, this.fromStart, this.fromEnd);
  }
}
