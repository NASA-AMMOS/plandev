package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.scheduler.model.Plan;

public record SimulationData(
    Plan plan,
    SimulationResults driverResults,
    gov.nasa.jpl.aerie.constraints.model.SimulationResults constraintsResults
){
  public SimulationData(Plan plan, SimulationResults driverResults) {
    this(plan, driverResults, SimulationResultsConverter.convertToConstraintModelResults(driverResults));
  }
}
