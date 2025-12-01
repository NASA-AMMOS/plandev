package gov.nasa.jpl.plandev.scheduler.conflicts;

import gov.nasa.jpl.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.plandev.constraints.time.Windows;
import gov.nasa.jpl.plandev.scheduler.goals.Goal;

public class UnsatisfiableGoalConflict extends Conflict {

  final private String reason;

  /**
   * ctor creates a new conflict
   *
   * @param goal IN STORED the dissatisfied goal that issued the conflict
   * @param  reason IN the reason why the goal issued the conflict
   */
  public UnsatisfiableGoalConflict(final Goal goal, final String reason) {
    super(goal, new EvaluationEnvironment());
    this.reason = reason;
  }

  @Override
  public Windows getTemporalContext() {
    return null;
  }
}
