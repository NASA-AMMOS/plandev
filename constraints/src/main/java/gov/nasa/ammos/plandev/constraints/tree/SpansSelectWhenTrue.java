package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.ArrayList;
import java.util.Set;

public record SpansSelectWhenTrue(Expression<Spans> spans, Expression<Windows> windows) implements Expression<Spans> {

  @Override
  public Spans evaluate(SimulationResults results, final Interval bounds, EvaluationEnvironment environment) {
    final var spans = this.spans.evaluate(results, bounds, environment);
    final var windows = this.windows.evaluate(results, bounds, environment);
    final var trueSegments = new ArrayList<Interval>();
    for (final var window: windows.iterateEqualTo(true)) trueSegments.add(window);
    return spans.select(trueSegments.toArray(new Interval[] {}));
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.spans.extractResources(names);
    this.windows.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(spans-select-when-true %s %s)",
        prefix,
        this.spans.prettyPrint(prefix + "  "),
        this.windows.prettyPrint(prefix + "  ")
    );
  }
}
