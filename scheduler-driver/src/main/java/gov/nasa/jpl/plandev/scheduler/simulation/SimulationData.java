package gov.nasa.jpl.plandev.scheduler.simulation;

import gov.nasa.jpl.plandev.merlin.driver.SimulationResults;
import gov.nasa.jpl.plandev.scheduler.model.Plan;
import gov.nasa.jpl.plandev.types.ActivityDirectiveId;

import java.util.Map;

public record SimulationData(
    Plan plan,
    SimulationResults driverResults,
    gov.nasa.jpl.plandev.constraints.model.SimulationResults constraintsResults
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
