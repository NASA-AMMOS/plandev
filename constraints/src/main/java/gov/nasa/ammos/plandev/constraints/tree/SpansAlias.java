package gov.nasa.ammos.plandev.constraints.tree;

import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Spans;

import java.util.Set;

public record SpansAlias(String spansAlias) implements Expression<Spans> {

  @Override
  public Spans evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return environment.spansInstances().get(this.spansAlias);
  }

  @Override
  public void extractResources(final Set<String> names) {}

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(spansAlias %s)",
        prefix,
        this.spansAlias
    );
  }
}
