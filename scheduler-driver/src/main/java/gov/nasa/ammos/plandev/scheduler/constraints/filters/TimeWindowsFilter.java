package gov.nasa.ammos.plandev.scheduler.constraints.filters;

import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

/**
 * a filter selects a subset of windows
 */
public interface TimeWindowsFilter {

  Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windowsToFilter);


}
