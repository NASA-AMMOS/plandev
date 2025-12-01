package gov.nasa.jpl.plandev.scheduler.constraints.filters;

import gov.nasa.jpl.plandev.constraints.model.SimulationResults;
import gov.nasa.jpl.plandev.constraints.time.Interval;
import gov.nasa.jpl.plandev.constraints.time.Windows;
import gov.nasa.jpl.plandev.scheduler.model.Plan;

import java.util.Optional;

public abstract class FilterFunctional implements TimeWindowsFilter {


  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windows) {
    Windows ret = windows;
    for (final var interval: windows.iterateEqualTo(true)) {
      if (!shouldKeep(simulationResults, plan, interval)) {
        ret = ret.set(interval, false);
      }
    }
    return ret;
  }


  public abstract boolean shouldKeep(final SimulationResults simulationResults, final Plan plan, final Interval range);
}
