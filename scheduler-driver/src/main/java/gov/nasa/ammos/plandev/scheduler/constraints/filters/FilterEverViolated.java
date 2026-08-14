package gov.nasa.jpl.aerie.scheduler.constraints.filters;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.scheduler.model.Plan;

import java.util.Map;

/**
 * filter in intervals if constraint expression @expr is ever violated during it
 */
public class FilterEverViolated extends FilterFunctional {

  private final Expression<Windows> expr;

  public FilterEverViolated(final Expression<Windows> expr) {
    this.expr = expr;
  }

  @Override
  public boolean shouldKeep(final SimulationResults simulationResults, final Plan plan, final Interval range) {
    return !(expr.evaluate(simulationResults, range, new EvaluationEnvironment()).equals(new Windows(range, true)));
  }
}
