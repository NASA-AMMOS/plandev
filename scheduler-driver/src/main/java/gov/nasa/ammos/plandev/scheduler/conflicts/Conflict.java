package gov.nasa.jpl.aerie.scheduler.conflicts;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;

/**
 * describes an issue in a plan that can be improved
 *
 * conflicts arise from various planning goals being dissatisfied with the
 * current contents of the plan: eg not enough activities, resources out of
 * bounds, disallowed transitions, etc
 *
 * each derived conflict type contains the additional context that may be
 * useful in addressing the problem and improving the plan
 *
 * REVIEW: should not-fully-satisfied preferences be conflicts?
 */
public abstract class Conflict {

  /**
   * ctor creates a new conflict
   *
   * @param goal IN STORED the dissatisfied goal that issued the conflict
   */
  public Conflict(Goal goal, EvaluationEnvironment evaluationEnvironment) {
    if (goal == null) {
      throw new IllegalArgumentException(
          "creating conflict from null goal");
    }
    this.evaluationEnvironment = evaluationEnvironment;
    this.goal = goal;
  }

  /**
   * the goal whose dissatisfaction initially created this conflict
   *
   * @return reference to the dissatisfied goal that caused this conflict
   */
  public Goal getGoal() {
    return goal;
  }

  public EvaluationEnvironment getEvaluationEnvironment(){
    return evaluationEnvironment;
  }

  /**
   * the times over which the goal was dissatisfied and induced this conflict
   *
   * the relevant times may be composed of several discontinous spans
   *
   * @return the time windows when the goal was dissatisfied
   */
  public abstract Windows getTemporalContext();

  /**
   * reference to the goal that initially issued the conflict
   *
   * used to hint at how to best solve the conflict
   */
  protected final Goal goal;

  /**
   * the evaluation environment is used to pass information to the scheduler
   */
  private final EvaluationEnvironment evaluationEnvironment;

}
