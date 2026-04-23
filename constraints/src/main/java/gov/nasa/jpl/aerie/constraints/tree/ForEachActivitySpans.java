package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import org.apache.commons.lang3.function.TriFunction;

import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

public record ForEachActivitySpans(
    TriFunction<ActivityInstance, SimulationResults, EvaluationEnvironment, Boolean> activityPredicate,
    String alias,
    Expression<Spans> expression
) implements Expression<Spans> {

  public ForEachActivitySpans(
      final String activityType,
      final String alias,
      final Expression<Spans> expression
  ) {
    this(new MatchType(activityType), alias, expression);
  }

  @Override
  public Spans evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var spans = new Spans();
    for (final var activity : results.activities) {
      if (this.activityPredicate.apply(activity, results, environment)) {
        final var newEnvironment = new EvaluationEnvironment(
            new HashMap<>(environment.activityInstances()),
            environment.spansInstances(),
            environment.intervals(),
            environment.realExternalProfiles(),
            environment.discreteExternalProfiles(),
            environment.externalEventsByDerivationGroup()
        );
        newEnvironment.activityInstances().put(this.alias, activity);

        final var expressionSpans = this.expression.evaluate(results, bounds, newEnvironment);
        spans.addAll(expressionSpans);
      }
    }
    return spans;
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expression.extractResources(names);
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(for-each-activity %s %s %s)",
        prefix,
        this.activityPredicate,
        this.alias,
        this.expression.prettyPrint(prefix + "  ")
    );
  }

  /**
   * A helper class for activity predicates that only match on type.
   *
   * This exists to provide an equality check between predicates. You could make an anonymous function that implements
   * {@link TriFunction}, but tests will fail because all such objects are considered non-equal, even when in reality they
   * have identical behavior.
   *
   * @param type activity type to match on.
   */
  public record MatchType(String type) implements TriFunction<ActivityInstance, SimulationResults, EvaluationEnvironment, Boolean> {
    @Override
    public Boolean apply(ActivityInstance act, SimulationResults results, EvaluationEnvironment env) {
      return Objects.equals(act.type(), type);
    }
  }
}
