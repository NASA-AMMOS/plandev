package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

public record IntervalAlias(
    String alias
) implements Expression<Interval> {

  @Override
  public Interval evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    if (!environment.intervals().containsKey(alias)) {
      throw new InputMismatchException("interval alias not found: " + alias);
    }
    return environment.intervals().get(alias);
  }

  @Override
  public void extractResources(final Set<String> names) {}

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(interval-alias %s)",
        prefix,
        this.alias
    );
  }
}
