package gov.nasa.jpl.aerie.constraints.tree;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;

import java.util.Set;

public record SpansWrapperExpression(Spans spans) implements Expression<Spans> {
  @Override
  public Spans evaluate(
      final SimulationResults results,
      final Interval bounds,
      final EvaluationEnvironment environment)
  {
    return spans;
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(spans-wrapper-of %s)",
        prefix,
        this.spans
    );  }

  @Override
  public void extractResources(final Set<String> names) { }
}
