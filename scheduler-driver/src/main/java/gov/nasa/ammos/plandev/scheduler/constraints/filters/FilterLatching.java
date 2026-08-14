package gov.nasa.jpl.aerie.scheduler.constraints.filters;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.scheduler.model.Plan;

import java.util.ArrayList;
import java.util.List;

/**
 * A latching filter is a filter applying a different filter on the first element of a sequence and another filter on
 * the
 * remaining element of the sequence
 */
public class FilterLatching implements TimeWindowsFilter {
  private final FilterFunctional firstFilter;
  private final FilterFunctional otherFilter;

  public FilterLatching(final FilterFunctional filter1, final FilterFunctional filter2) {
    firstFilter = filter1;
    otherFilter = filter2;
  }

  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windows) {
    Windows ret = windows;
    boolean first = true;
    for (final var interval: windows.iterateEqualTo(true)) {
      if (first) {
        if (firstFilter.shouldKeep(simulationResults, plan, interval)) {
          first = false;
        } else {
          ret = ret.set(interval, false);
        }
      } else if (!otherFilter.shouldKeep(simulationResults, plan, interval)) {
        ret = ret.set(interval, false);
      }
    }
    return ret;
  }
}
