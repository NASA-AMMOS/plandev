package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;

import java.util.Set;

public class WindowsWrapperExpression implements Expression<Windows> {
  public final Windows windows;

  public WindowsWrapperExpression(final Windows windows) { this.windows = windows; }

  @Override
  public Windows evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    return windows;
  }

  public String prettyPrint(final String prefix) {
    return String.format(
      "%s(expression-wrapping %s)",
      prefix,
      this.windows.toString()
    );
  }
  /** Add the resources referenced by this expression to the given set. **/
  public void extractResources(Set<String> names) { }
}
