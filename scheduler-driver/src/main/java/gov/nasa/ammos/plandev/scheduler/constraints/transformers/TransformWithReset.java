package gov.nasa.jpl.aerie.scheduler.constraints.transformers;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.scheduler.constraints.TimeRangeExpression;
import gov.nasa.jpl.aerie.scheduler.model.Plan;


/**
 * this filter turns any filter into a filter with resets
 */
public class TransformWithReset implements TimeWindowsTransformer {


  public TransformWithReset(final TimeRangeExpression reset, final TimeWindowsTransformer filter) {
    this.transform = filter;
    this.resetExpr = reset;
  }

  private final TimeWindowsTransformer transform;
  private final TimeRangeExpression resetExpr;

  @Override
  public Windows transformWindows(final Plan plan, final Windows windowsToTransform, final SimulationResults simulationResults) {
    Windows ret = new Windows();
    int totalFiltered = 0;

    if (!windowsToTransform.isEmpty()) {

      var resetPeriods = resetExpr.computeRange(simulationResults, plan, new Windows(true));

      for (var window : resetPeriods.iterateEqualTo(true)) {
        // get windows to transform that are completely contained in reset period
        Windows cur = windowsToTransform.trueSubsetContainedIn(window);
        if (!cur.isEmpty()) {
          // apply transform and union result
          ret = ret.add(transform.transformWindows(plan, cur, simulationResults));
          totalFiltered += cur.size();
        }
        //short circuit
        if (totalFiltered >= windowsToTransform.size()) {
          break;
        }
      }
    }

    return ret;
  }
}
