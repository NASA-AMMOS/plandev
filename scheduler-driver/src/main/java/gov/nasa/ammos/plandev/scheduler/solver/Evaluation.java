package gov.nasa.jpl.aerie.scheduler.solver;

import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;
import gov.nasa.jpl.aerie.scheduler.goals.ActivityExistentialGoal;
import gov.nasa.jpl.aerie.scheduler.goals.ChildCustody;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * description of how well a plan satisfies its goals
 *
 * different schedulers may evaluate the same plan in different ways
 *
 * the evaluation includes any scoring metrics that the scheduler calculated
 * as well as useful metadata, eg which goals were satisfied by which activity instances
 */
public class Evaluation {
  /**
   * the set of all per-goal evaluations
   */
  protected final java.util.HashMap<Goal, GoalEvaluation> goalEvals = new java.util.HashMap<>();

  /**
   * description of the satisfaction of a single goal in isolation
   */
  public static class GoalEvaluation {
    /**
     * a map associating each activity that contributed to the goal to a boolean stating whether the goal created it or not
     */
    protected final java.util.Map<SchedulingActivity, Boolean> acts = new java.util.HashMap<>();
    protected final java.util.Map<Conflict, ConflictSolverResult> conflicts = new java.util.HashMap<>();

    /**
     * fetches the numeric evaluation score for the goal
     *
     * @return the numeric evaluation score for the goal
     */
    public double getScore() { return -conflicts.values().stream().filter(result -> result.satisfaction() != ConflictSatisfaction.SAT).count(); }

    public ConflictSatisfaction getSatisfaction(){
      final var partiallySatCount = conflicts.values().stream().filter(result -> result.satisfaction() == ConflictSatisfaction.PARTIALLY_SAT).count();
      if(partiallySatCount > 0) return ConflictSatisfaction.PARTIALLY_SAT;
      final var notSatCount = conflicts.values().stream().filter(result -> result.satisfaction() == ConflictSatisfaction.NOT_SAT).count();
      if(notSatCount > 0) return ConflictSatisfaction.NOT_SAT;
      return ConflictSatisfaction.SAT;
    }

    /**
     * flags given activity as contributing to the goal's (dis)satisfaction
     *
     * @param act IN the activity instance that contributed to the goal's
     *     evaluation
     * @param createdByThisGoal IN a boolean stating whether the instance has been created by this goal or not
     */
    public void associate(
        final SchedulingActivity act,
        final boolean createdByThisGoal,
        final Conflict conflict) {
      acts.put(act, createdByThisGoal);
      final var conflictStatus = this.conflicts.computeIfAbsent(conflict, c -> new ConflictSolverResult());
      conflictStatus.activitiesCreated().add(act);
    }

    public void setConflictSatisfaction(final Conflict conflict, final ConflictSatisfaction conflictSatisfaction){
      this.conflicts.computeIfAbsent(conflict, c -> new ConflictSolverResult()).setSatisfaction(conflictSatisfaction);
    }

    /**
     * Replaces an activity in the goal evaluation by another activity
     * @param toBeReplaced the activity to be replaced
     * @param replacement the replacement activity
     */
    public void replace(final SchedulingActivity toBeReplaced, final SchedulingActivity replacement){
      final var found = acts.get(toBeReplaced);
      if(found != null){
        acts.remove(toBeReplaced);
        acts.put(replacement, found);
        for(final var activities: conflicts.entrySet()){
          final var wasThere = activities.getValue().activitiesCreated().remove(toBeReplaced);
          if(wasThere) activities.getValue().activitiesCreated().add(replacement);
        }
      }
    }

    /**
     * Duplicates the GoalEvaluation
     * @return the duplicate
     */
    public GoalEvaluation duplicate(){
      final var duplicate = new GoalEvaluation();
      duplicate.acts.putAll(this.acts);
      duplicate.conflicts.putAll(this.conflicts);
      return duplicate;
    }

    public void addConflicts(final Collection<Conflict> conflict) {
      conflict.forEach(c -> this.conflicts.put(c, new ConflictSolverResult()));
    }

    /**
     * flags all given activities as contributing to the goal's (dis)satisfaction
     *
     * @param acts IN container of activities that contributed to the goal's
     *     evaluation
     * @param createdByThisGoal IN a boolean stating whether the instance has been created by this goal or not
     * @param conflict IN a conflict if the activity has been associated to satisfy a specific conflict, can be null
     */
    public void associate(
        final java.util.Collection<SchedulingActivity> acts,
        final boolean createdByThisGoal,
        final Conflict conflict) {
      acts.forEach(act -> this.associate(act, createdByThisGoal, conflict));
    }

    public void removeAssociation(java.util.Collection<SchedulingActivity> acts){
      this.acts.entrySet().removeIf(act -> acts.contains(act.getKey()));
      this.conflicts.forEach((c, conflictSolverReturn) -> conflictSolverReturn.activitiesCreated().removeAll(acts));
    }

    /**
     * fetches the set of all activities that contributed to the evaluation
     *
     * @return the set of all activities that contributed to the evaluation
     */
    public java.util.Collection<SchedulingActivity> getAssociatedActivities() {
      return java.util.Collections.unmodifiableSet(acts.keySet());
    }
    /**
     * fetches the set of all activities that this goal inserted in the plan
     *
     * @return the set of all activities that this goal inserted in the plan
     */
    public java.util.Collection<SchedulingActivity> getInsertedActivities() {
      return java.util.Collections.unmodifiableSet(acts.entrySet().stream().filter((a)-> a.getValue().equals(true)).map(
          Map.Entry::getKey).collect(
          Collectors.toSet()));
    }
  }

  /**
   * returns the (possibly new) evaluation for a given goal
   *
   * if there is no current evaluation for the given goal, a new empty
   * one is created and returned
   *
   * @param goal the goal to evaluate
   * @return the evaluation of the specified goal
   */
  public GoalEvaluation forGoal(Goal goal) {
    return goalEvals.computeIfAbsent(goal, k -> new GoalEvaluation());
  }

  /**
   * fetches the set of all goals evaluated
   *
   * @return the goals that are evaluated
   */
  public java.util.Collection<Goal> getGoals() {
    return goalEvals.keySet();
  }

  /**
   * Duplicates the Evaluation
   * @return the duplicate evaluation
   */
  public Evaluation duplicate(){
    final var duplicate = new Evaluation();
    for(final var goalEvaluation : goalEvals.entrySet()){
      duplicate.goalEvals.put(goalEvaluation.getKey(), goalEvaluation.getValue().duplicate());
    }
    return duplicate;
  }

  /**
   * fetch all goals and their current individual evaluation
   *
   * @return mapping from goals to their current individual evaluation (non-modifiable)
   */
  public java.util.Map<Goal,GoalEvaluation> getGoalEvaluations() { return Collections.unmodifiableMap(goalEvals); }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Evaluation that = (Evaluation) o;
    return Objects.equals(goalEvals, that.goalEvals);
  }

  @Override
  public int hashCode() {
    return Objects.hash(goalEvals);
  }

  public boolean canAssociateMoreToCreatorOf(final SchedulingActivity instance){
    final var creator$ = getGoalCreator(instance);
    // for now: all existing activities in the plan are allowed to be associated with any goal
    if (creator$.isEmpty()) return true;
    final var creator = creator$.get();

    if(!(creator instanceof ActivityExistentialGoal activityExistentialCreator)) return true;
      return activityExistentialCreator.getChildCustody() == ChildCustody.Jointly;//we can piggyback
  }

  /**
   * If an activity instance was already in the plan prior to this run of the scheduler, this method will return Optional.empty()
   */
  private Optional<Goal> getGoalCreator(final SchedulingActivity instance){
    for(final var goalEval : goalEvals.entrySet()){
      if(goalEval.getValue().getInsertedActivities().contains(instance)){
        return Optional.of(goalEval.getKey());
      }
    }
    return Optional.empty();
  }

  /**
   * Replace an old activity by a new one in every goal
   * @param oldAct Old Activity
   * @param newAct New Activity
   */
  public void updateGoalEvals(final SchedulingActivity oldAct, final SchedulingActivity newAct) {
    for (GoalEvaluation goalEval : goalEvals.values()) {
      if (goalEval.acts.containsKey(oldAct)) {
        Boolean value = goalEval.acts.get(oldAct);
        goalEval.acts.remove(oldAct);
        goalEval.acts.put(newAct, value);
        for(final var conflictStatuses: goalEval.conflicts.entrySet()){
          final var wasThere = conflictStatuses.getValue().activitiesCreated().remove(oldAct);
          if(wasThere) conflictStatuses.getValue().activitiesCreated().add(newAct);
        }
      }
    }
  }

}

