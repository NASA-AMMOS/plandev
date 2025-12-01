package gov.nasa.jpl.plandev.scheduler.constraints.transformers;

import gov.nasa.jpl.plandev.constraints.model.SimulationResults;
import gov.nasa.jpl.plandev.constraints.time.Windows;
import gov.nasa.jpl.plandev.scheduler.model.Plan;

public interface TimeWindowsTransformer {

  Windows transformWindows(final Plan plan, final Windows windows, final SimulationResults simulationResults);

}
