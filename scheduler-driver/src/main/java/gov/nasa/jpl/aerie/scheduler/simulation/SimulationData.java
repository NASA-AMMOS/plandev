package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.Map;

public record SimulationData(
    Plan plan,
    SimulationResults driverResults,
    gov.nasa.jpl.aerie.constraints.model.SimulationResults constraintsResults
) {
  public SimulationData replaceIds(Map<ActivityDirectiveId, ActivityDirectiveId>  map) {
    if (map.isEmpty()) return this;
    return new SimulationData(
        plan.replaceIds(map),
        driverResults.replaceIds(map),
        constraintsResults.replaceIds(map)
    );
  }
}
