package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingActivityTemplateConflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingAssociationConflict;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeAnchor;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeExpressionRelative;
import gov.nasa.jpl.aerie.scheduler.model.PersistentTimeAnchor;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.scheduler.solver.ScheduleAt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * describes the desired coexistence of an activity with another
 */
public class CoexistenceGoal extends ActivityTemplateGoal {

  private TimeExpressionRelative startExpr;
  private TimeExpressionRelative endExpr;
  private String alias;
  private PersistentTimeAnchor persistentAnchor;
  /**
   * the pattern used to locate anchor activity instances in the plan
   */
  protected Expression<Spans> expr;

  /**
   * the builder can construct goals piecemeal via a series of method calls
   */
  public static class Builder extends ActivityTemplateGoal.Builder<Builder> {

    public Builder forEach(Expression<Spans> expression) {
      forEach = expression;
      return getThis();
    }

    protected Expression<Spans> forEach;

    public Builder startsAt(TimeExpressionRelative TimeExpressionRelative) {
      startExpr = TimeExpressionRelative;
      return getThis();
    }

    protected TimeExpressionRelative startExpr;

    public Builder endsAt(TimeExpressionRelative TimeExpressionRelative) {
      endExpr = TimeExpressionRelative;
      return getThis();
    }

    protected TimeExpressionRelative endExpr;


    public Builder startsAt(TimeAnchor anchor) {
      startExpr = TimeExpressionRelative.fromAnchor(anchor);
      return getThis();
    }

    public Builder endsAt(TimeAnchor anchor) {
      endExpr = TimeExpressionRelative.fromAnchor(anchor);
      return getThis();
    }

    public Builder endsBefore(TimeExpressionRelative expr) {
      endExpr = TimeExpressionRelative.endsBefore(expr);
      return getThis();
    }

    public Builder startsAfterEnd() {
      startExpr = TimeExpressionRelative.afterEnd();
      return getThis();
    }

    public Builder startsAfterStart() {
      startExpr = TimeExpressionRelative.afterStart();
      return getThis();
    }

    public Builder endsBeforeEnd() {
      endExpr = TimeExpressionRelative.beforeEnd();
      return getThis();
    }

    public Builder endsAfterEnd() {
      endExpr = TimeExpressionRelative.afterEnd();
      return getThis();
    }

    String alias;
    public Builder aliasForAnchors(String alias){
      this.alias = alias;
      return getThis();
    }

    PersistentTimeAnchor persistentAnchor;
    public Builder createPersistentAnchor(PersistentTimeAnchor persistentAnchor){
      this.persistentAnchor = persistentAnchor;
      return getThis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CoexistenceGoal build() { return fill(new CoexistenceGoal()); }

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
    protected CoexistenceGoal fill(CoexistenceGoal goal) {
      //first fill in any general specifiers from parents
      super.fill(goal);

      if (forEach == null) {
        throw new IllegalArgumentException(
            "creating coexistence goal requires non-null \"forEach\" anchor template");
      }
      if (alias == null) {
        throw new IllegalArgumentException(
            "creating coexistence goal requires non-null \"alias\" name");
      }
      goal.expr = forEach;

      goal.startExpr = startExpr;

      goal.endExpr = endExpr;

      goal.alias = alias;

      goal.persistentAnchor = Objects.requireNonNullElse(persistentAnchor, PersistentTimeAnchor.DISABLED);

      if(name==null){
        goal.name = "CoexistenceGoal_forEach_"+forEach.prettyPrint()+"_thereExists_"+this.thereExists.type().getName();
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
  @SuppressWarnings({"unchecked", "rawtypes"})
  @Override
  public java.util.Collection<Conflict> getConflicts(
      final Plan plan,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final SchedulerModel schedulerModel) { //TODO: check if interval gets split and if so, notify user?

    //unwrap temporalContext
    final var windows = getTemporalContext().evaluate(simulationResults, evaluationEnvironment);

    final var anchors = expr.evaluate(simulationResults, evaluationEnvironment).intersectWith(windows);

    // can only check if bisection has happened if you can extract the interval from expr like you do in computeRange but without the final windows parameter,
    //    then use that and compare it to local variable windows to check for bisection;
    //    I can add that, but it doesn't seem necessary for now.

    //the rest is the same if no such bisection has happened
    final var conflicts = new java.util.LinkedList<Conflict>();
    for (var window : anchors) {
      ActivityExpression.Builder activityFinder = null;
      ActivityExpression.Builder activityCreationTemplate = null;
      if (this.desiredActTemplate != null) {
        activityFinder = new ActivityExpression.Builder();
        activityCreationTemplate = new ActivityExpression.Builder();
      }
      assert activityFinder != null;
      activityFinder.basedOn(this.matchActTemplate);
      activityCreationTemplate.basedOn(this.desiredActTemplate);

      final var afterEndWindow = Interval.between(window.interval().end, Interval.Inclusivity.Inclusive, planHorizon.getEndAerie(), Interval.Inclusivity.Inclusive);
      if (this.startExpr != null) {
        Interval startTimeRange = this.startExpr.computeTime(simulationResults, plan, window.interval());
        // This condition further constraints the window in which we are looking for satisfying directives so that we don't create/look activities in the past of the directive's end
        if(this.persistentAnchor.equals(PersistentTimeAnchor.END)){
          startTimeRange = Interval.intersect(afterEndWindow,startTimeRange);
        }
        activityFinder.startsIn(startTimeRange);
        activityCreationTemplate.startsIn(startTimeRange);
      }
      if (this.endExpr != null) {
        Interval endTimeRange = this.endExpr.computeTime(simulationResults, plan, window.interval());
        // This condition further constraints the window in which we are looking for satisfying directives so that we don't create/look activities in the past of the directive's end
        if(this.persistentAnchor.equals(PersistentTimeAnchor.END)) {
          endTimeRange = Interval.intersect(afterEndWindow, endTimeRange);
        }
        activityFinder.endsIn(endTimeRange);
        activityCreationTemplate.endsIn(endTimeRange);
      }

      final var activitiesFound = plan.find(
          activityFinder.build(),
          simulationResults,
          createEvaluationEnvironmentFromAnchor(evaluationEnvironment, window));

      var missingActAssociations = new ArrayList<SchedulingActivity>();
      var planEvaluation = plan.getEvaluation();
      var associatedActivitiesToThisGoal = planEvaluation.forGoal(this).getAssociatedActivities();
      var alreadyOneActivityAssociated = false;

      // Assuming 'activitiesFound' is a Set or Collection
      for (var act : activitiesFound) {
        //has already been associated to this goal
        if (associatedActivitiesToThisGoal.contains(act)) {
          alreadyOneActivityAssociated = true;
          break;
        }
      }
      if (!alreadyOneActivityAssociated) {
        ActivityDirectiveId anchorIdTo = null;
        if (window.value().isPresent()) {
          anchorIdTo = window.value().get().activityInstance().directiveId().orElse(null);
        }
        final var missingActAssociationsWithAnchor = new ArrayList<SchedulingActivity>();
        final var missingActAssociationsWithoutAnchor = new ArrayList<SchedulingActivity>();
        /*
        If activities that can satisfy the goal have been found, then create two arraylist to distinguish between:
         1) those activities that also satisfy the anchoring  (e.g. anchorId value equals the ActivityDirectiveId of the "for each" activity directive in the goal
         2) activities without the anchorId set
         */
        for (var act : activitiesFound) {
          if (planEvaluation.canAssociateMoreToCreatorOf(act)) {
            if (anchorIdTo != null && act.anchorId() != null && act.anchorId().id() == anchorIdTo.id())
              missingActAssociationsWithAnchor.add(act);
            else
              missingActAssociationsWithoutAnchor.add(act);
          }
        }

/*      The truth table that determines the type of conflict is shown below. The variables considered in the table are:
        1. If PersistentTimeAnchor is disabled, then no anchors are created. There are two scenarios:
          1.1 Matching activity found: MissingAssociationConflict created.
          1.2 No matching activity found: MissingActivityTemplateConflict created
        2. If PersistentTimeAnchor is enabled (START or END) then an anchor will be created. There are three scenarios
          2.1 Matching activity with anchor found. MissingAssociationConflict created.
          2.2 Matching activity found, but only without anchor. MissingAssociationConflict created, passing the ID of the matching activity in order to create the anchor
          2.3 No matching activity found: MissingActivityTemplateConflict created

        PersistentTimeAnchor	missingActAssociationsWithAnchor	missingActAssociationsWithoutAnchor 	type conflict
        0	                      0	                                0	                                    MissingActivityTemplateConflict // Check no anchor created
        0	                      0	                                1	                                    MissingAssociationConflict(this, missingActAssociationsWithoutAnchor, Optional.empty(), false)
        0	                      1	                                0	                                    MissingAssociationConflict(this, missingActAssociationsWithAnchor,  Optional.empty(), false)
        0	                      1	                                1	                                    MissingAssociationConflict(this, missingActAssociationsWithAnchor,  Optional.empty(), false)
        1	                      0	                                0	                                    MissingActivityTemplateConflict(anchorId)
        1	                      0	                                1	                                    MissingAssociationConflict(this, missingActAssociationsWithAnchor,  Optional.of(anchorIdTo), false)
        1	                      1	                                0	                                    MissingAssociationConflict(this, missingActAssociationsWithAnchor,  Optional.empty(), false)
        1	                      1	                                1	                                    MissingAssociationConflict(this, missingActAssociationsWithAnchor,  Optional.empty(), false)
 */
        // If anchors are disabled or there are some activity directives that satisfy the goal and already have the anchor or the anchorID is null, then we pass an empty anchor. Otherwise, we pass the anchorID of the directive that can satisfy the goal
        final Optional<ActivityDirectiveId> anchorValue =
            (this.persistentAnchor.equals(PersistentTimeAnchor.DISABLED) || !missingActAssociationsWithAnchor.isEmpty()
             || anchorIdTo == null) ? Optional.empty() : Optional.of(anchorIdTo);
        //  Create MissingActivityTemplateConflict if no matching target activity found
        if (activitiesFound.isEmpty()) {
          var temporalContext = this.temporalContext.evaluate(simulationResults, evaluationEnvironment);
          final var activityCreationTemplateBuilt = activityCreationTemplate.build();
          final var newEvaluationEnvironment = createEvaluationEnvironmentFromAnchor(evaluationEnvironment, window);
          final var newTemporalContext = boundTemporalContextWithSchedulerModel(
              temporalContext,
              schedulerModel,
              activityCreationTemplateBuilt,
              newEvaluationEnvironment);
          conflicts.add(new MissingActivityTemplateConflict(
              this,
              newTemporalContext,
              activityCreationTemplateBuilt,
              newEvaluationEnvironment,
              1,
              anchorValue,
              Optional.of(this.persistentAnchor.equals(PersistentTimeAnchor.START)),
              Optional.empty(),
              ScheduleAt.EARLIEST
          ));
        } else {
            final var actsToAssociate = missingActAssociationsWithAnchor.isEmpty() ? missingActAssociationsWithoutAnchor : missingActAssociationsWithAnchor;
            conflicts.add(new MissingAssociationConflict(
                this,
                actsToAssociate,
                anchorValue,
                    Optional.of(this.persistentAnchor.equals(PersistentTimeAnchor.START))
            ));
        }
      }
    }
    return conflicts;
  }

  private Windows boundTemporalContextWithSchedulerModel(
      final Windows baseTemporalContext,
      final SchedulerModel schedulerModel,
      final ActivityExpression activityTemplate,
      final EvaluationEnvironment evaluationEnvironment){
    var boundedTemporalContext = new Windows().add(baseTemporalContext);
    var currentTemporalContextUpperBound = baseTemporalContext.maxTrueTimePoint().get().getKey();
    final var reduced = activityTemplate.reduceTemporalConstraints(planHorizon, schedulerModel, evaluationEnvironment, List.of());
    if(reduced.isPresent()) {
      currentTemporalContextUpperBound = Duration.min(currentTemporalContextUpperBound, reduced.get().end().end);
      //invalidate the end
      boundedTemporalContext = boundedTemporalContext.and(new Windows(true).set(Interval.between(currentTemporalContextUpperBound, Interval.Inclusivity.Exclusive, Duration.MAX_VALUE, Interval.Inclusivity.Exclusive), false));
    }
    return boundedTemporalContext;
  }

  private EvaluationEnvironment createEvaluationEnvironmentFromAnchor(EvaluationEnvironment existingEnvironment, Segment<Optional<Spans.Metadata>> span){
    if(span.value().isPresent()){
      final var metadata = span.value().get();
      final var activityInstances = new HashMap<>(existingEnvironment.activityInstances());
      activityInstances.put(this.alias, metadata.activityInstance());
      return new EvaluationEnvironment(
          activityInstances,
          existingEnvironment.spansInstances(),
          existingEnvironment.intervals(),
          existingEnvironment.eventsByDerivationGroup(),
          existingEnvironment.realExternalProfiles(),
          existingEnvironment.discreteExternalProfiles()
      );
    } else{
      assert this.alias != null;
      final var intervals = new HashMap<>(existingEnvironment.intervals());
      intervals.put(this.alias, span.interval());
      return new EvaluationEnvironment(
          existingEnvironment.activityInstances(),
          existingEnvironment.spansInstances(),
          intervals,
          existingEnvironment.eventsByDerivationGroup(),
          existingEnvironment.realExternalProfiles(),
          existingEnvironment.discreteExternalProfiles()
      );
    }
  }

  @Override
  public void extractResources(final Set<String> names) {
    super.extractResources(names);
    this.expr.extractResources(names);
    if(this.startExpr != null) this.startExpr.extractResources(names);
    if(this.endExpr != null) this.endExpr.extractResources(names);
  }

  /**
   * Ctor creates an empty goal without details.
   * Client code should use builders to instance goals.
   */
  protected CoexistenceGoal() { }
}
