package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingActivityInstanceConflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingAssociationConflict;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/**
 * describes the desired existence of a set of externally generated activities
 *
 * procedural goals use some outside code to determine what activity instances
 * should exist in the plan
 */
public class ProceduralCreationGoal extends ActivityExistentialGoal {

  /**
   * the builder can construct goals piecemeal via a series of method calls
   */
  public static class Builder extends ActivityExistentialGoal.Builder<Builder> {

    /**
     * specifies the procedure used to generate desired activities
     *
     * the procedure takes as input the "current" partial plan as of the execution of
     * this goal's satisfaction and produces a list of activity
     * instances that must exist to satisfy this goal. the activities are not
     * immediately inserted in the plan. partial satisfaction is possible,
     * using the heuristic that more matching activities is always preferable
     * to fewer. individual instances must match exactly (all arguments)
     *
     * the procedure may recommend creation of a list of activities all at
     * once, or may make repeated incremental recommendations for single
     * additional activities at a time
     *
     * the procedure should be a pure function and not have any internal state
     * that could produce variant results on re-invocation with different
     * hypothetical inputs
     *
     * this specifier is required. it replaces any previous specification.
     *
     * TODO: generator function should take arg for temporal context
     *
     * @param generator IN/OUT the function invoked to generate the desired
     *     activity instances, which takes the plan as input and outputs a
     *     list of activity instances. should be an idempotent pure
     *     function. may be called out of order from different contexts.
     * @return this builder, ready for additional specification
     */
    public Builder generateWith(Function<Plan, Collection<SchedulingActivity>> generator) {
      this.generateWith = generator;
      return this;
    }

    protected Function<Plan, Collection<SchedulingActivity>> generateWith;

    /**
     * {@inheritDoc}
     */
    @Override
    public ProceduralCreationGoal build() { return fill(new ProceduralCreationGoal()); }

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
    protected ProceduralCreationGoal fill(ProceduralCreationGoal goal) {
      //first fill in any general specifiers from parents
      super.fill(goal);

      if (generateWith == null) {
        throw new IllegalArgumentException(
            "creating procedural goal requires non-null \"generateWith\" generator function");
      }
      goal.generator = generateWith;

      return goal;
    }

  }//Builder


  /**
   * {@inheritDoc}
   *
   * collects conflicts where the external procedural generator would like to
   * create an activity instance but an exactly matching one does not exist in
   * the plan (and should probably be created). The matching is strict: all
   * arguments must be identical.
   */
  @Override
  public Collection<Conflict> getConflicts(
      final Plan plan,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final SchedulerModel schedulerModel) {
    final var conflicts = new java.util.LinkedList<Conflict>();

    //run the generator to see what acts are still desired
    //REVIEW: maybe some caching on plan hash here?
    final var requestedActs = getRelevantGeneratedActivities(plan, simulationResults, evaluationEnvironment);

    //walk each requested act and try to find an exact match in the plan
    for (final var requestedAct : requestedActs) {

      //use a strict matching based on all arguments of the instance
      //(including exact start time, but not name)
      //REVIEW: should strict name also match? but what if uuid names?
      final var satisfyingActSearch = new ActivityExpression.Builder()
          .basedOn(requestedAct)
          .build();
      final var matchingActs = plan.find(satisfyingActSearch, simulationResults, new EvaluationEnvironment());

      var missingActAssociations = new ArrayList<SchedulingActivity>();
      var planEvaluation = plan.getEvaluation();
      var associatedActivitiesToThisGoal = planEvaluation.forGoal(this).getAssociatedActivities();
      var alreadyOneActivityAssociated = false;
      for(var act : matchingActs){
        //has already been associated to this goal
        if(associatedActivitiesToThisGoal.contains(act)){
          alreadyOneActivityAssociated = true;
          break;
        }
      }
      if(!alreadyOneActivityAssociated){
        //fetch all activities that can be associated, scheduler will make a choice
        for(var act : matchingActs){
          if(planEvaluation.canAssociateMoreToCreatorOf(act)){
            missingActAssociations.add(act);
          }
        }
      }
      //adding appropriate conflicts
      if(!alreadyOneActivityAssociated) {
        //generate a conflict if no matching acts found
        if (matchingActs.isEmpty()) {
          conflicts.add(new MissingActivityInstanceConflict(
              this, requestedAct, new EvaluationEnvironment()));
          //REVIEW: pass the requested instance to conflict or otherwise cache it
          //        for the imminent request to create it in the plan
        } else {
          conflicts.add(new MissingAssociationConflict(
              this,
              missingActAssociations,
              Optional.empty(),
              Optional.empty()));
        }
      }
    }//for(requestedAct)

    return conflicts;
  }

  /**
   * ctor creates an empty goal without details
   *
   * client code should use builders to instance goals
   */
  protected ProceduralCreationGoal() { }

  /**
   * specifies the procedure used to generate desired activities
   *
   * the procedure takes as input the "current" partial plan as of the execution of
   * this goal's satisfaction and produces a list of activity
   * instances that must exist to satisfy this goal. the activities are not
   * immediately inserted in the plan. partial satisfaction is possible,
   * using the heuristic that more matching activities is always preferable
   * to fewer. individual instances must match exactly (all arguments)
   *
   * the procedure may recommend creation of a list of activities all at
   * once, or may make repeated incremental recommendations for single
   * additional activities at a time
   *
   * for now, the procedure should be a pure function and not have any
   * internal state that could produce variant results on re-invocation
   * with different hypothetical inputs
   */
  protected Function<Plan, Collection<SchedulingActivity>> generator;

  /**
   * use the generator to determine the set of relevant activity requests
   *
   * may or may not actually re-invoke the generator depending on optmizations
   *
   * the returned set of activity instances is filtered based on this goal's
   * temporal context, and only activities with grounded start times
   *
   * @param plan IN the plan context that the generator should be invoked with
   * @return the set of activity instances that the generator requested that
   *     are deemed relevant to this goal (eg within the temporal context
   *     of this goal)
   */
  private Collection<SchedulingActivity> getRelevantGeneratedActivities(Plan plan, SimulationResults simulationResults, EvaluationEnvironment evaluationEnvironment) {

    //run the generator in the plan context
    final var allActs = generator.apply(plan);

    //filter out acts that don't have a start time within the goal purview
    final var evaluatedGoalContext = getTemporalContext().evaluate(simulationResults, evaluationEnvironment);
    final var filteredActs = allActs.stream().filter(
        act -> ((act.startOffset() != null)
                && evaluatedGoalContext.includes(Interval.at(0, act.startOffset())))
    ).collect(java.util.stream.Collectors.toList());

    return filteredActs;
  }


}
