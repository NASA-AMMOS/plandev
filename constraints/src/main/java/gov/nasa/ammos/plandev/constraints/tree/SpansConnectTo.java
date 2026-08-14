package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Set;
import java.util.stream.StreamSupport;

public record SpansConnectTo(Expression<Spans> from, Expression<Spans> to) implements Expression<Spans> {

  @Override
  public Spans evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var from = this.from.evaluate(results, bounds, environment);
    final var sortedFrom = StreamSupport.stream(from.spliterator(), true).sorted((l, r) -> l.interval().compareEnds(r.interval())).toList();
    final var to = this.to.evaluate(results, bounds, environment);
    final var sortedTo = StreamSupport.stream(to.spliterator(), true).sorted((l, r) -> l.interval().compareStarts(r.interval())).toList();
    final var result = new Spans();
    var toIndex = 0;
    for (final var span: sortedFrom) {
      final var startTime = span.interval().end;
      while (toIndex < sortedTo.size() && span.interval().compareEndToStart(sortedTo.get(toIndex).interval()) == 1) {
        toIndex++;
      }
      final Duration endTime;
      final Interval.Inclusivity endInclusivity;
      if (toIndex == sortedTo.size()) {
        endTime = bounds.end;
        endInclusivity = bounds.endInclusivity;
      }
      else {
        endTime = sortedTo.get(toIndex).interval().start;
        endInclusivity = Interval.Inclusivity.Inclusive;
      }
      result.add(
          Interval.between(
              startTime,
              Interval.Inclusivity.Inclusive,
              endTime,
              endInclusivity
          ),
          span.value()
      );
    }
    return result;
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.from.extractResources(names);
    this.to.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(connect from %s to %s)",
        prefix,
        this.from.prettyPrint(),
        this.to.prettyPrint()
    );
  }
}
