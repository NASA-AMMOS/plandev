package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Or implements Expression<Windows> {
  public final List<Expression<Windows>> expressions;

  public Or(final List<Expression<Windows>> expressions) {
    this.expressions = expressions;
  }

  @SafeVarargs
  public Or(final Expression<Windows>... expressions) {
    this(List.of(expressions));
  }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    Windows windows = new Windows(false);
    for (final var expression : this.expressions) {
      windows = windows.or(
          expression.evaluate(results, bounds, environment)
      );
    }
    return windows.select(bounds);
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.expressions.forEach(expression -> expression.extractResources(names));
  }

  @Override
  public String prettyPrint(final String prefix) {
    final var builder = new StringBuilder();
    builder.append("\n")
           .append(prefix)
           .append("(or ");

    final var iter = this.expressions.iterator();
    while (iter.hasNext()) {
      builder.append(iter.next().prettyPrint(prefix + "  "));
      if (iter.hasNext()) builder.append(" ");
    }

    builder.append(")");
    return builder.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Or)) return false;
    Or o = (Or)obj;

    return Objects.equals(this.expressions, o.expressions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.expressions);
  }
}
