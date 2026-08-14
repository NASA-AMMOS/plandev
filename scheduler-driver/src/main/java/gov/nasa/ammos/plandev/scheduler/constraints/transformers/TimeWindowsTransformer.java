package gov.nasa.ammos.plandev.scheduler.constraints.transformers;

import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

public interface TimeWindowsTransformer {

  Windows transformWindows(final Plan plan, final Windows windows, final SimulationResults simulationResults);

}
