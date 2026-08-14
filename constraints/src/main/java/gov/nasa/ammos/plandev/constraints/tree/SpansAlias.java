package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.Objects;
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
