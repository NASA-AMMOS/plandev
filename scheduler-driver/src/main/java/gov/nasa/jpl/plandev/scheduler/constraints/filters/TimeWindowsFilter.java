package gov.nasa.jpl.plandev.scheduler.constraints.filters;

import gov.nasa.jpl.plandev.constraints.model.SimulationResults;
import gov.nasa.jpl.plandev.constraints.time.Windows;
import gov.nasa.jpl.plandev.scheduler.model.Plan;

/**
 * a filter selects a subset of windows
 */
public interface TimeWindowsFilter {

  Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windowsToFilter);


}
