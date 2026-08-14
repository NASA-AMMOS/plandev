package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.Set;

public record WindowsFromSpans(Expression<Spans> expression) implements Expression<Windows> {

  @Override
  public Windows evaluate(SimulationResults results, final Interval bounds, EvaluationEnvironment environment) {
    final var spans = this.expression.evaluate(results, bounds, environment);
    return spans.intoWindows();
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(windows-from %s)",
        prefix,
        this.expression.prettyPrint(prefix + "  ")
    );
  }
}
