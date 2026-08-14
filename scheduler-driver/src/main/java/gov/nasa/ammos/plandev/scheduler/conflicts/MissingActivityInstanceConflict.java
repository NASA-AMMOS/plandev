package gov.nasa.jpl.aerie.scheduler.conflicts;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.scheduler.goals.ActivityExistentialGoal;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.solver.ScheduleAt;

/**
 * describes an issue in a plan caused by a specific activity instance missing
 *
 * such conflicts are typically addressed by scheduling the specific instance
 */
public class MissingActivityInstanceConflict extends MissingActivityConflict {

  /**
   * ctor creates a new conflict regarding a missing activity instance
   *
   * REVIEW: do we want an ActivityInstanceGoal type?
   *
   * @param goal IN STORED the dissatisfied goal that issued the conflict
   * @param instance IN STORED the specific activity instance that is
   *     desired in the plan
   */
  public MissingActivityInstanceConflict(
      ActivityExistentialGoal goal,
      SchedulingActivity instance,
      EvaluationEnvironment evaluationEnvironment)
  {
    super(goal, evaluationEnvironment);

    if (instance == null) {
      throw new IllegalArgumentException(
          "creating specific missing instance conflict required non-null instance");
    }
    this.instance = instance;
  }

  @Override
  public String toString() {
    if (this.instance != null) {
      return "Conflict : missing activity instance " + this.instance + ". Produced by goal " + getGoal().getName();
    }
    return "Empty conflict";
  }

  @Override
  public ScheduleAt scheduleAt() {
    return ScheduleAt.EARLIEST;
  }

  /**
   * {@inheritDoc}
   *
   * FINISH: fix up documentation
   * the time at which the desired activity would start
   *
   * does not consider other constraints on the possible activity, eg timing
   * with respect to other events or allowable state transitions or even
   * the activity's own duration limits
   *
   * the times encompass just the desired start times of the missing activity
   * @return
   */
  @Override
  public Windows getTemporalContext() {
    return new Windows(false).set(Interval.between(instance.startOffset(), instance.getEndTime()), true);
  }

  /**
   * the goal whose dissatisfaction initially created this conflict
   *
   * @return reference to the dissatisfied goal that caused this conflict
   */
  @Override
  public ActivityExistentialGoal getGoal() {
    return (ActivityExistentialGoal) super.getGoal();
  }

  /**
   * fetches the specifically requested instance that is desired
   *
   * @return the specifically requested instance that is desired
   */
  public SchedulingActivity getInstance() {
    return instance;
  }

  /**
   * the specific activity instance that is desired in the plan
   */
  protected SchedulingActivity instance;

}
