package gov.nasa.ammos.plandev.scheduler.constraints.filters;

import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.constraints.tree.Expression;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

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
