package gov.nasa.jpl.aerie.scheduler.model;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.solver.Evaluation;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * a solution to a planning problem including a schedule of activities
 *
 * may only be a partial solution to the whole planning problem, ie some
 * goals may be left unsatisfied
 */
public interface Plan {

  /**
   * Duplicates a plan
   * @return the duplicate plan
   */
  Plan duplicate();

  /**
   * adds the given activity instances to the scheduled plan solution
   *
   * the provided instances must have start times specified
   *
   * @param acts IN the set of activity instances to schedule into the plan
   */
  void add(Collection<SchedulingActivity> acts);

  /**
   * adds the given activity instance to the scheduled plan solution
   *
   * the provided instance must have start time specified
   *
   * @param act IN activity instance to schedule into the plan
   */
  void add(SchedulingActivity act);
  /**
   * adds the given activity instances to the scheduled plan solution
   *
   * the provided instances must have start times specified
   *
   * @param acts IN the set of activity instances to remove from the plan
   */
  void remove(Collection<SchedulingActivity> acts);

  /**
   * removes the given activity instance to the scheduled plan solution
   *
   * the provided instance must have start time specified
   *
   * @param act IN activity instance to remove from the plan
   */
  void remove(SchedulingActivity act);

  /**
   * replace and old activity by a new one
   * @param oldAct Old Activity
   * @param newAct New Activity
   */
  void replaceActivity(SchedulingActivity oldAct, SchedulingActivity newAct);

  /**
   * fetches activities in the plan ordered by start time
   *
   * @return set of all activities in the plan ordered by start time
   */
  List<SchedulingActivity> getActivitiesByTime();

  /**
   * fetches activities in the plan by type
   *
   * @return map of all activities in the plan by type
   */
  Map<ActivityType, List<SchedulingActivity>> getActivitiesByType();

  /**
   * fetches activities in the plan by id
   *
   * @return map of all activities in the plan by id
   */
  Map<ActivityDirectiveId, SchedulingActivity> getActivitiesById();

  /**
   * fetches activities in the plan
   *
   * @return set of all activities in the plan
   */
  Set<SchedulingActivity> getActivities();

  /**
  * @return the set of anchors from all activities in the plan
   */
  Set<ActivityDirectiveId> getAnchorIds();

  /**
   * finds activity instances in the plan that meet the given criteria
   *
   * @param template IN the matching criteria to use on activity instances
   * @return collection of instances that match the given template
   */
  Collection<SchedulingActivity> find(
      ActivityExpression template, SimulationResults simulationResults, EvaluationEnvironment evaluationEnvironment);
  /**
   * adds a new evaluation to the plan
   *
   * note that different solvers or metrics will have different evaluations
   * for the same plan
   *
   * @param eval IN the new evaluation to add to the plan
   */
  void addEvaluation(Evaluation eval);

  /**
   * fetches evaluation posted to the plan
   *
   * @return evaluation posted to the plan
   */
  Evaluation getEvaluation();

  Duration calculateAbsoluteStartOffsetAnchoredActivity(SchedulingActivity actAnchorTo);

  Plan replaceIds(Map<ActivityDirectiveId, ActivityDirectiveId> map);
}
