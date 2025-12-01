package gov.nasa.jpl.plandev.scheduler.conflicts;

import gov.nasa.jpl.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.plandev.constraints.time.Interval;
import gov.nasa.jpl.plandev.constraints.time.Windows;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.plandev.scheduler.goals.Goal;

public class MissingRecurrenceConflict extends Conflict {

  public Duration lastStart;
  public Duration nextStart;
  public boolean afterBoundIsActivity;
  public Interval minMaxConstraints;
  public Interval validWindow;
  public ActivityExpression desiredActivityTemplate;

  /**
   * ctor creates a new conflict
   *
   * @param goal IN STORED the dissatisfied goal that issued the conflict
   * @param evaluationEnvironment
   */
  public MissingRecurrenceConflict(
      final Goal goal,
      final EvaluationEnvironment evaluationEnvironment,
      final Duration lastStart,
      final Duration nextStart,
      final Interval validWindow,
      final boolean afterBoundIsActivity,
      final Interval minMaxConstraints,
      final ActivityExpression desiredActivityTemplate) {
    super(goal, evaluationEnvironment);
    this.lastStart = lastStart;
    this.nextStart = nextStart;
    this.afterBoundIsActivity = afterBoundIsActivity;
    this.minMaxConstraints = minMaxConstraints;
    this.validWindow = validWindow;
    this.desiredActivityTemplate = desiredActivityTemplate;
  }

  @Override
  public Windows getTemporalContext() {
    return null;
  }
}
