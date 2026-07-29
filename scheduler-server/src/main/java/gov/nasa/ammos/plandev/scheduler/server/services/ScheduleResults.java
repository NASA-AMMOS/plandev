package gov.nasa.ammos.plandev.scheduler.server.services;

import java.util.Collection;
import java.util.Map;

import gov.nasa.ammos.plandev.scheduler.model.GoalId;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;

/**
 * summary of results from running the scheduler, including goal satisfaction metrics and changes made
 * TODO: @param javadocs (Adrien)
 */
public record ScheduleResults(Map<GoalId, GoalResult> goalResults) {

  public record GoalResult(
      Collection<ActivityDirectiveId> createdActivities,
      Collection<ActivityDirectiveId> satisfyingActivities,
      boolean satisfied
  )
  { }
}
