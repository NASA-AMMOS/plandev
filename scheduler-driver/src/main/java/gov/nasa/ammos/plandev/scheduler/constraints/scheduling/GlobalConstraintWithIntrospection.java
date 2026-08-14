package gov.nasa.ammos.plandev.scheduler.constraints.scheduling;

import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.scheduler.model.Plan;
import gov.nasa.ammos.plandev.scheduler.conflicts.Conflict;

import java.util.Set;

/**
 * Interface defining methods that must be implemented by global constraints such as mutex or cardinality
 * Also provides a directory for creating these constraints
 */
public interface GlobalConstraintWithIntrospection {
  //specific to introspectable constraint : find the windows in which we can insert activities without violating
  //the constraint
  Windows findWindows(Plan plan, Windows windows, Conflict conflict, SimulationResults simulationResults, EvaluationEnvironment evaluationEnvironment);
  void extractResources(Set<String> names);
}
