package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingRecurrenceConflict;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingAssociationConflict;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * describes the desired recurrence of an activity every time period
 */
public class RecurrenceGoal extends ActivityTemplateGoal {

  /**
   * ctor creates an empty goal without details
   *
   * client code should use builders to instance goals
   */
  protected RecurrenceGoal() { }

  /**
   * the allowable range of durations over which a target activity must repeat
   * Of type: MinMaxAllowableRecurrenceInterval
   *
   * REVIEW: need to work out semantics of min on recurrenceInterval
   */
  protected Interval recurrenceInterval;

  /**
   * the start time of the last (fictional) activity before the horizon start
   * Can be negative. By default, set to -recurrenceInterval.max
   */
  protected Duration lastActivityStartedAt;

  /**
   * the builder can construct goals piecemeal via a series of method calls
   */
  public static class Builder extends ActivityTemplateGoal.Builder<Builder> {
    Duration separatedByAtLeast;
    Duration separatedByAtMost;
    Duration lastActWasAt;

    /**
     * specifies the fixed interval over which the activity should repeat
     *
     * this interval is understood to be a minimum (ie more frequent repetition
     * does not impede the goal's satisfaction)
     *
     * this specifier is required. it replaces any previous specification.
     *
     * @param interval IN the duration over which a matching activity instance
     *     must exist in order to satisfy the goal
     * @return this builder, ready for additional specification
     */
    public Builder repeatingEvery(Duration interval) {
      separatedByAtLeast = Duration.ZERO;
      separatedByAtMost = interval;
      return getThis();
    }

    public Builder separatedByAtMost(final Duration duration){
      separatedByAtMost = duration;
      return getThis();
    }

    public Builder separatedByAtLeast(final Duration duration){
      separatedByAtLeast = duration;
      return getThis();
    }

    public Builder lastActivityHappenedAt(final Duration duration){
      this.lastActWasAt = duration;
      return getThis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RecurrenceGoal build() { return fill(new RecurrenceGoal()); }

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
    protected RecurrenceGoal fill(RecurrenceGoal goal) {
      //first fill in any general specifiers from parents
      super.fill(goal);

      if (separatedByAtMost == null) {
        throw new IllegalArgumentException("creating recurrence goal requires non-null \"separatedByNoMore\" duration interval");
      }
      if (separatedByAtMost.isNegative()) {
        throw new IllegalArgumentException("Duration passed to RecurrenceGoal as the goal's maximum recurrence interval cannot be negative!");
      }
      else if (separatedByAtLeast.isNegative()) {
        throw new IllegalArgumentException("Duration passed to RecurrenceGoal as the goal's minimum recurrence interval cannot be negative!");
      }
      goal.recurrenceInterval = Interval.between(separatedByAtLeast, Interval.Inclusivity.Inclusive, separatedByAtMost, Interval.Inclusivity.Inclusive);
      if(goal.recurrenceInterval.isEmpty()){
        throw new IllegalArgumentException("Bad parametrization of RecurrenceGoal: separatedByAtLeast is greater than separatedByAtMost");
      }
      if(name==null){
        goal.name = "RecurrenceGoal_one_"+this.thereExists.type().getName()+"_every_["+goal.recurrenceInterval.start+","+goal.recurrenceInterval.end+"]";
      }

      if(lastActWasAt == null){
        goal.lastActivityStartedAt = Duration.negate(separatedByAtMost);
      } else {
        goal.lastActivityStartedAt = lastActWasAt;
      }

      return goal;
    }

  }//Builder


  /**
   * {@inheritDoc}
   *
   * collects conflicts wherein a matching target activity instance does not
   * exist over a timespan longer than the allowed range (and one should
   * probably be created!)
   */
  @Override
  public java.util.Collection<Conflict> getConflicts(
      @NotNull final Plan plan,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final SchedulerModel schedulerModel) {
    final var conflicts = new java.util.LinkedList<Conflict>();

    //unwrap temporalContext
    final var tempWindowPlanHorizon = new Windows(false).set(List.of(this.planHorizon.getHor()), true);
    final var windows = tempWindowPlanHorizon.and(this.getTemporalContext().evaluate(simulationResults, evaluationEnvironment));

    //check repeat is larger than activity duration
    if(this.getActTemplate().type().getDurationType() instanceof DurationType.Fixed act){
      boolean durActVSRec = act.duration( ).longerThan(this.recurrenceInterval.start);
      if(durActVSRec){
        throw new UnexpectedTemporalContextChangeException("The goal is unsatisfiable as its duration is longer than the repeat duration");
      }
    }

    //iterate through it and then within each iteration do exactly what you did before
    for (Interval subInterval : windows.iterateEqualTo(true)) {
      if(subInterval.end.shorterThan(lastActivityStartedAt)) continue;
      final var cutSubinterval = Interval.between(Duration.max(subInterval.start, lastActivityStartedAt), subInterval.end);
      final var localizedBeforeAct = subInterval.start.plus(lastActivityStartedAt);
      //collect all matching target acts ordered by start time
      //REVIEW: could collapse with prior template start time query too?
      final var satisfyingActSearch = new ActivityExpression.Builder()
          .basedOn(matchActTemplate)
          .startsIn(cutSubinterval)
          .build();
      final var acts = new java.util.LinkedList<>(plan.find(satisfyingActSearch, simulationResults, new EvaluationEnvironment()));
      //add fake activity to the all intervals
      acts.sort(java.util.Comparator.comparing(SchedulingActivity::startOffset));

      //walk through existing matching activities to find too-large gaps,
      //starting from the goal's own start time
      //REVIEW: some clever implementation with stream reduce / combine?
      final var actI = acts.iterator();
      final var lastStartT = cutSubinterval.end;
      var prevStartT = localizedBeforeAct;

      while (actI.hasNext() && prevStartT.compareTo(lastStartT) < 0) {
        final var act = actI.next();
        final var actStartT = act.startOffset();

        //check if the inter-activity gap is too large
        //REVIEW: should do any check based on min gap duration?
        final var strideDur = actStartT.minus(prevStartT).abs();
        if (strideDur.compareTo(this.recurrenceInterval.end) > 0) {
          //fill conflicts for all the missing activities in that long span
          conflicts.add(makeRecurrenceConflicts(prevStartT, actStartT, cutSubinterval, true, evaluationEnvironment));
        } else if(strideDur.noShorterThan(this.recurrenceInterval.start)) {
          var planEval = plan.getEvaluation();
          if (!planEval.forGoal(this).getAssociatedActivities().contains(act) && planEval.canAssociateMoreToCreatorOf(
              act)) {
            conflicts.add(new MissingAssociationConflict(this, List.of(act), Optional.empty(), Optional.empty()));
          }
        }
        prevStartT =
            actStartT; //if we dont do this sum it'll just restart where the last event started and keep adding an instance of the last event. it required you to go out of bounds on the last event which was a problem, so this accommodates.
      }

      //fill in conflicts for all missing activities in the last span up to the
      //goal's own end time (also handles case of no matching acts at all)
      if(lastStartT.minus(prevStartT).longerThan(this.recurrenceInterval.end))
        conflicts.add(makeRecurrenceConflicts(prevStartT, lastStartT, cutSubinterval, false, evaluationEnvironment));
    }

    return conflicts;
  }
  /**
   * creates conflicts for missing activities in the provided span
   *
   * there may be more than one conflict if the span is larger than
   * the maximum allowed recurrence interval. there may be no conflicts
   * if the span is less than the maximum interval.
   *
   * @param lastStart IN the start time of the span to fill with conflicts (inclusive)
   * @param nextStart IN the end time of the span to fill with conflicts (exclusive)
   */
  private MissingRecurrenceConflict makeRecurrenceConflicts(
      final Duration lastStart,
      final Duration nextStart,
      final Interval interval,
      final boolean afterBoundIsActivity,
      final EvaluationEnvironment evaluationEnvironment)
  {
    return new MissingRecurrenceConflict(
        this,
        evaluationEnvironment,
        lastStart,
        nextStart,
        interval,
        afterBoundIsActivity,
        this.recurrenceInterval,
        this.desiredActTemplate);
  }
}
