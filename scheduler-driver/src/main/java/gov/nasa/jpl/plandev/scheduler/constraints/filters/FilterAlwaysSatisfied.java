package gov.nasa.jpl.plandev.scheduler.constraints.filters;

import gov.nasa.jpl.plandev.constraints.model.SimulationResults;
import gov.nasa.jpl.plandev.constraints.time.Interval;
import gov.nasa.jpl.plandev.constraints.time.Windows;
import gov.nasa.jpl.plandev.constraints.tree.Expression;
import gov.nasa.jpl.plandev.scheduler.model.Plan;

public class FilterAlwaysSatisfied extends FilterFunctional {

  private final Expression<Windows> expr;

  public FilterAlwaysSatisfied(final Expression<Windows> expr) {
    this.expr = expr;
  }

  @Override
  public boolean shouldKeep(final SimulationResults simulationResults, final Plan plan, final Interval range) {
    var valid = expr.evaluate(simulationResults);
    return valid.equals(new Windows(range, true));
  }
}
