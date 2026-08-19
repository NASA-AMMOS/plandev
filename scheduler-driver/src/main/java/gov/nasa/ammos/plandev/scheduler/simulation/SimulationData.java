package gov.nasa.ammos.plandev.scheduler.simulation;

import gov.nasa.ammos.plandev.merlin.driver.SimulationResults;
import gov.nasa.ammos.plandev.scheduler.model.Plan;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;

import java.util.Map;

public record SimulationData(
    Plan plan,
    SimulationResults driverResults,
    gov.nasa.ammos.plandev.constraints.model.SimulationResults constraintsResults
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
