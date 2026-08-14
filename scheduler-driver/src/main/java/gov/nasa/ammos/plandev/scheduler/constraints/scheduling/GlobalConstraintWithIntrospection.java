package gov.nasa.jpl.aerie.scheduler.constraints.scheduling;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;

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
