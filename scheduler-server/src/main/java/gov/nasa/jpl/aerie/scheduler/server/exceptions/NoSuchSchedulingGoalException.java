package gov.nasa.jpl.aerie.scheduler.server.exceptions;

import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;

public final class NoSuchSchedulingGoalException extends Exception {
  public final GoalId goalId;

  public NoSuchSchedulingGoalException(final GoalId goalId) {
    super("No scheduling goal exists with id `" + goalId.id() + "` and revision `" + goalId.revision() + "`");
    this.goalId = goalId;
  }
}
