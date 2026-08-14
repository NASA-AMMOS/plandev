package gov.nasa.jpl.aerie.scheduler.constraints.filters;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.scheduler.constraints.TimeRangeExpression;
import gov.nasa.jpl.aerie.scheduler.model.Plan;


/**
 * this filter turns any filter into a filter with resets
 */
public class FilterWithReset implements TimeWindowsFilter {


  public FilterWithReset(final TimeRangeExpression reset, final TimeWindowsFilter filter) {
    this.filter = filter;
    this.resetExpr = reset;
  }

  final TimeWindowsFilter filter;
  final TimeRangeExpression resetExpr;

  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windowsToFilter) {
    Windows ret = new Windows();
    int totalFiltered = 0;

    if (!windowsToFilter.stream().noneMatch(Segment::value)) {

      var resetPeriods = resetExpr.computeRange(simulationResults, plan, new Windows(Interval.FOREVER, true));

      for (var window : resetPeriods.iterateEqualTo(true)) {
        // get windows to filter that are completely contained in reset period
        Windows cur = windowsToFilter.trueSubsetContainedIn(window);
        if (!cur.stream().noneMatch(Segment::value)) {
          //apply filter and union result
          ret = ret.set(filter.filter(simulationResults, plan, cur));
          totalFiltered += cur.size();
        }
        //short circuit
        if (totalFiltered >= windowsToFilter.size()) {
          break;
        }
      }
    }

    return ret;
  }
}
