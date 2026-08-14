package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.Range;
import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingActivityTemplateConflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingAssociationConflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.UnsatisfiableGoalConflict;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.scheduler.solver.ScheduleAt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * describes the desired coexistence of an activity with another
 */
public class CardinalityGoal extends ActivityTemplateGoal {

  private static final Logger logger = LoggerFactory.getLogger(CardinalityGoal.class);

  /**
   * minimum/maximum total duration of activities
   */
  private Interval durationRange;

  /*
   *minimum/maximum number of occurrence of activities
   *  */
  private Range<Integer> occurrenceRange;

  // Following members are related to diagnosis of the scheduler being stuck with inserting 0-duration activities
  /**
   * Activities inserted so far to satisfy this goal
   */
  private final Set<ActivityDirectiveId> insertedSoFar = new HashSet<>();
  /**
   * Current number of steps without inserting an activity with non-zero duration
   */
  private int stepsWithoutProgress = 0;
  /**
   * Maximum acceptable number of steps without progress
   */
  private static final int maxNoProgressSteps = 50;

  /**
   * the builder can construct goals piecemeal via a series of method calls
   */
  public static class Builder extends ActivityTemplateGoal.Builder<Builder> {

    Interval durationRange;
    Range<Integer> occurrenceRange;

    public Builder duration(Interval durationRange) {
      this.durationRange = durationRange;
      return getThis();
    }

    public Builder occurences(Range<Integer> occurrenceRange) {
      this.occurrenceRange = occurrenceRange;
      return getThis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CardinalityGoal build() { return fill(new CardinalityGoal()); }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Builder getThis() { return this; }

    /**
     * populates the provided goal with specifiers from this builder and above
     *
     * typically called by any derived builder classes to fill in the
     * specifiers managed at this builder level and above
     *
     * @param goal IN/OUT a goal object to be filled with specifiers from this
     *     level of builder and above
     * @return the provided object, with details filled in
     */
    protected CardinalityGoal fill(CardinalityGoal goal) {
      //first fill in any general specifiers from parents
      super.fill(goal);

      if (durationRange != null) {
        goal.durationRange = durationRange;

      }
      if (occurrenceRange != null) {
        goal.occurrenceRange = occurrenceRange;
      }
      if(name==null){
        goal.name = "CardinalityGoal_"+"thereExists_"+this.thereExists.type().getName()+"_(duration:"+((this.durationRange != null) ? this.durationRange: "N/A") +", occurrences:" + ((this.occurrenceRange != null) ? this.occurrenceRange: "N/A") +")";
      }
      return goal;
    }
  }//Builder


  /**
   * {@inheritDoc}
   *
   * collects conflicts wherein a matching anchor activity was found
   * but there was no corresponding target activity instance (and one
   * should probably be created!)
   */
  @Override
  public Collection<Conflict> getConflicts(
      final Plan plan,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final SchedulerModel schedulerModel) {

    //unwrap temporalContext
    final var windows = getTemporalContext().evaluate(simulationResults, evaluationEnvironment);

    //iterate through it and then within each iteration do exactly what you did before
    final var conflicts = new LinkedList<Conflict>();

    for(Interval subInterval : windows.iterateEqualTo(true)) {
      final var subIntervalWindows = new Windows(false).set(subInterval, true);
      final var acts = new LinkedList<SchedulingActivity>();
      for(final var window : subIntervalWindows.iterateEqualTo(true)){
        final var actTB =
            new ActivityExpression.Builder().basedOn(this.matchActTemplate).startsIn(window).build();
        acts.addAll(plan.find(actTB, simulationResults, evaluationEnvironment));
      }
      acts.sort(Comparator.comparing(SchedulingActivity::startOffset));
      int nbActs = 0;
      Duration total = Duration.ZERO;
      var planEvaluation = plan.getEvaluation();
      var associatedActivitiesToThisGoal = planEvaluation.forGoal(this).getAssociatedActivities();
      for (var act : acts) {
        if (planEvaluation.canAssociateMoreToCreatorOf(act) || associatedActivitiesToThisGoal.contains(act)) {
          total = total.plus(act.duration());
          nbActs++;
        }
      }

      Duration durToSchedule = Duration.ZERO;
      int nbToSchedule = 0;
      if (this.durationRange != null && !this.durationRange.contains(total)) {
        if (total.compareTo(this.durationRange.start) < 0) {
          durToSchedule = this.durationRange.start.minus(total);
        } else if (total.compareTo(this.durationRange.end) > 0) {
          logger.warn(
              "Need to decrease duration of activities from the plan, impossible because scheduler cannot remove activities");
          return List.of(new UnsatisfiableGoalConflict(
              this,
              "Need to decrease duration of activities from the plan, impossible because scheduler cannot remove activities"));
        }
      }
      if (this.occurrenceRange != null && !this.occurrenceRange.contains(nbActs)) {
        if (nbActs < this.occurrenceRange.getMinimum()) {
          nbToSchedule = this.occurrenceRange.getMinimum() - nbActs;
        } else if (nbActs > this.occurrenceRange.getMaximum()) {
          logger.warn("Need to remove activities from the plan to satify cardinality goal, impossible");
          return List.of(new UnsatisfiableGoalConflict(
              this,
              "Need to remove activities from the plan to satify cardinality goal, impossible"));
        }
      }

      if (stuckInsertingZeroDurationActivities(plan, this.occurrenceRange == null || nbToSchedule == 0)) return List.of(
          new UnsatisfiableGoalConflict(
              this,
              "During "
              + this.maxNoProgressSteps
              + " steps, solver has created only 0-duration activities to satisfy duration cardinality goal, exiting. "));

      //at this point, have thrown exception if not satisfiable
      //compute the missing association conflicts
      for (final var act : acts) {
        if (!associatedActivitiesToThisGoal.contains(act) && planEvaluation.canAssociateMoreToCreatorOf(act)) {
          //they ALL have to be associated
          conflicts.add(new MissingAssociationConflict(this, List.of(act), Optional.empty(), Optional.empty()));
        }
      }

      if(nbToSchedule>0 || durToSchedule.isPositive()) {
        conflicts.add(new MissingActivityTemplateConflict(
            this,
            subIntervalWindows,
            this.desiredActTemplate,
            evaluationEnvironment,
            nbToSchedule,
            Optional.empty(),
            Optional.empty(),
            durToSchedule.isPositive() ? Optional.of(durToSchedule) : Optional.empty(),
            ScheduleAt.EARLIEST));
      }
    }

    return conflicts;
  }

  private boolean stuckInsertingZeroDurationActivities(final Plan plan, final boolean occurrencePartIsSatisfied){
    if(this.durationRange != null && occurrencePartIsSatisfied){
      final var inserted = plan.getEvaluation().forGoal(this).getInsertedActivities();
      final var newlyInsertedActivities = inserted.stream().filter(a -> !insertedSoFar.contains(a.id())).toList();
      final var durationNewlyInserted = newlyInsertedActivities.stream().reduce(Duration.ZERO, (partialSum, activityInstance2) -> partialSum.plus(activityInstance2.duration()), Duration::plus);
      if(durationNewlyInserted.isZero()) {
        this.stepsWithoutProgress++;
        //otherwise, reset it, we have made some progress
      } else{
        this.stepsWithoutProgress = 0;
      }
      if(stepsWithoutProgress > maxNoProgressSteps){
        return true;
      }
      newlyInsertedActivities.forEach(a -> insertedSoFar.add(a.id()));
    }
    return false;
  }

  /**
   * /**
   * ctor creates an empty goal without details
   *
   * client code should use builders to instance goals
   */
  protected CardinalityGoal() { }


}
