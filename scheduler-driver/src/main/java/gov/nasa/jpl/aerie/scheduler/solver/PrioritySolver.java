package gov.nasa.jpl.aerie.scheduler.solver;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.driver.ActivityDirectiveId;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator;
import gov.nasa.jpl.aerie.scheduler.EquationSolvingAlgorithms;
import gov.nasa.jpl.aerie.scheduler.NotNull;
import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingActivityConflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingActivityInstanceConflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingActivityTemplateConflict;
import gov.nasa.jpl.aerie.scheduler.conflicts.MissingAssociationConflict;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.scheduling.GlobalConstraintWithIntrospection;
import gov.nasa.jpl.aerie.scheduler.goals.ActivityTemplateGoal;
import gov.nasa.jpl.aerie.scheduler.goals.CompositeAndGoal;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.goals.OptionGoal;
import gov.nasa.jpl.aerie.scheduler.goals.Procedure;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulePlanGrounder;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationData;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.scheduler.solver.stn.TaskNetworkAdapter;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * prototype scheduling algorithm that schedules activities for a plan
 *
 * this prototype is a single-shot priority-ordered greedy scheduler
 *
 * (note that there are many other possible scheduling algorithms!)
 */
public class PrioritySolver implements Solver {

  private static final Logger logger = LoggerFactory.getLogger(PrioritySolver.class);

  private boolean checkSimBeforeInsertingActivities;

  private boolean checkSimBeforeEvaluatingGoal;

  private boolean atLeastOneSimulateAfter;

  private SimulationData cachedSimulationResultsBeforeGoalEvaluation;

  /**
   * boolean stating whether only conflict analysis should be performed or not
   */
  private final boolean analysisOnly;

  /**
   * description of the planning problem to solve
   *
   * remains constant throughout solver lifetime
   */
  private final Problem problem;

  /**
   * the single-shot priority-ordered greedy solution devised by the solver
   *
   * this object is null until first call to getNextSolution()
   */
  private Plan plan;

  private final SimulationFacade simulationFacade;

  private final DirectiveIdGenerator idGenerator;

  public record ActivityMetadata(SchedulingActivity activityDirective){}

  public static class HistoryWithActivity implements EquationSolvingAlgorithms.History<Duration, ActivityMetadata> {
    List<Pair<EquationSolvingAlgorithms.FunctionCoordinate<Duration>, Optional<ActivityMetadata>>> events;

    public HistoryWithActivity(){
      events = new ArrayList<>();
    }
    public void add(EquationSolvingAlgorithms.FunctionCoordinate<Duration> functionCoordinate, ActivityMetadata activityMetadata){
      this.events.add(Pair.of(functionCoordinate, Optional.ofNullable(activityMetadata)));
    }

    @Override
    public List<Pair<EquationSolvingAlgorithms.FunctionCoordinate<Duration>, Optional<ActivityMetadata>>> getHistory() {
      return events;
    }

    public Optional<Pair<EquationSolvingAlgorithms.FunctionCoordinate<Duration>, Optional<ActivityMetadata>>> getLastEvent(){
      if(events.isEmpty()) return Optional.empty();
      return Optional.of(events.get(events.size() - 1));
    }

    @Override
    public boolean alreadyVisited(final Duration x) {
      for(final var event:events){
        if(event.getLeft().x().isEqualTo(x)) return true;
      }
      return false;
    }

    public void logHistory(){
      logger.info("Rootfinding history");
      for(final var event: events){
        logger.info("Start:" + event.getLeft().x() + " end:" + (event.getLeft().fx()==null ? "error" : event.getLeft().fx()));
      }
    }
  }

  /**
   * create a new greedy solver for the specified input planning problem
   *
   * the solver is configured to operate on a given planning problem, which
   * must not change out from under the solver during its lifetime
   *
   * @param problem IN, STORED description of the planning problem to be
   *     solved, which must not change
   */
  public PrioritySolver(final Problem problem, final boolean analysisOnly) {
    checkNotNull(problem, "creating solver with null input problem descriptor");
    this.checkSimBeforeInsertingActivities = true;
    this.checkSimBeforeEvaluatingGoal = true;
    this.atLeastOneSimulateAfter = false;
    this.problem = problem;
    this.simulationFacade = problem.getSimulationFacade();
    this.analysisOnly = analysisOnly;

    this.idGenerator = new DirectiveIdGenerator(
        problem
            .getInitialPlan()
            .getActivitiesById()
            .keySet()
            .stream()
            .map(ActivityDirectiveId::id)
            .max(Long::compareTo)
            .orElse(-1L)
        + 1
        );
  }

  public PrioritySolver(final Problem problem) {
    this(problem, false);
  }

  /**
   * {@inheritDoc}
   *
   * calculates the single-shot greedy solution to the input problem
   *
   * this solver is expended after one solution request; all subsequent
   * requests will return no solution
   */
  public Optional<Plan> getNextSolution() throws SchedulingInterruptedException {
    if (plan == null) {
      //on first call to solver; setup fresh solution workspace for problem
      if(simulationFacade.getCanceledListener().get()) throw new SchedulingInterruptedException("initializing plan");
      try {
        initializePlan();
        if(problem.getInitialSimulationResults().isPresent()) {
          logger.debug("Loading initial simulation results from the DB");
          simulationFacade.setInitialSimResults(problem.getInitialSimulationResults().get());
        }
      } catch (SimulationFacade.SimulationException e) {
        logger.error("Tried to initializePlan but at least one activity could not be instantiated", e);
        return Optional.empty();
      }

      //attempt to satisfy the goals in the problem
      solve();

      return Optional.of(plan);

    } else { //plan!=null

      //subsequent call after initial solution, so return null
      //(this simple solver only produces a single solution)
      return Optional.empty();
    }
  }

  public record InsertActivityResult(boolean success, List<SchedulingActivity> activitiesInserted){}

  /**
   * Tries to insert a collection of activity instances in plan. Simulates each of the activity and checks whether the expected
   * duration is equal to the simulated duration.
   * @param acts the activities to insert in the plan
   * @return false if at least one activity has a simulated duration not equal to the expected duration, true otherwise
   */
  private InsertActivityResult checkAndInsertActs(Collection<SchedulingActivity> acts) throws SchedulingInterruptedException{
    // TODO: When anchors are allowed to be added by Scheduling goals, inserting the new activities one at a time should be reconsidered
    boolean allGood = true;
    logger.info("Inserting new activities in the plan to check plan validity");
    for(var act: acts){
      //if some parameters are left uninstantiated, this is the last moment to do it
      var duration = act.duration();
      if(duration != null && act.startOffset().plus(duration).longerThan(this.problem.getPlanningHorizon().getEndAerie())) {
        logger.warn("Not simulating activity " + act
                           + " because it is planned to finish after the end of the planning horizon.");
        return new InsertActivityResult(allGood, List.of());
      }
    }
    final var planWithAddedActivities = plan.duplicate();
    planWithAddedActivities.add(acts);
    try {
      if(checkSimBeforeInsertingActivities) simulationFacade.simulateNoResultsAllActivities(planWithAddedActivities);
      plan = planWithAddedActivities;
    } catch (SimulationFacade.SimulationException e) {
      allGood = false;
      logger.error("Tried to simulate the plan {} but a simulation exception happened", planWithAddedActivities, e);
    }
    return new InsertActivityResult(allGood, acts.stream().map(act -> plan.getActivitiesById().get(act.id())).toList());
  }


  /**
   * creates internal storage space to build up partial solutions in
   **/
  public void initializePlan() throws SimulationFacade.SimulationException, SchedulingInterruptedException {
    plan = new PlanInMemory();

    //turn off simulation checking for initial plan contents (must accept user input regardless)
    final var prevCheckFlag = this.checkSimBeforeInsertingActivities;
    this.checkSimBeforeInsertingActivities = false;
    checkAndInsertActs(problem.getInitialPlan().getActivitiesByTime());
    this.checkSimBeforeInsertingActivities = prevCheckFlag;

    plan.addEvaluation(new Evaluation());
  }


  /**
   * iteratively fills in output plan to satisfy input problem description
   *
   * calculates a single-shot priority-ordered greedy solution to the problem;
   * ie it proceeds from highest to lowest priority goal, scheduling
   * activities for each in turn to the best still-available windows. it does
   * not attempt any search between different goals or even within the same
   * goal. priority ties are broken by alphabetic ordering of goal id.
   *
   * the solution may not be optimal or even dominating: eg, it can be fooled
   * into scheduling lenient high priority goals into times that conflict with
   * much more constrained lower priority goals
   *
   * the configuration, problem, and plan members must exist and be valid
   *
   * the output plan member is updated directly with the devised solution
   */
  private void solve() throws SchedulingInterruptedException{
    //construct a priority sorted goal container
    final var goalQ = getGoalQueue();
    assert goalQ != null;

    //process each goal independently in that order
    while (!goalQ.isEmpty()) {
      var goal = goalQ.remove();
      assert goal != null;

      //update the output solution plan directly to satisfy goal
      satisfyGoal(goal);
    }

  }

  /**
   * construct a priority sorted queue of goals to process
   *
   * the goals are ordered in descending priority (highest priority first)
   * with ties broken by the natural ordering of the goal identifiers
   *
   * the returned queue becomes owned by the caller, and may be modified
   * by removing or adding further goals
   *
   * the configuration and problem members must exist and be valid
   *
   * @return a descending-priority ordered queue of goals from the input
   *     problem, ready for processing
   */
  private LinkedList<Goal> getGoalQueue() {
    assert problem != null;
    final var rawGoals = problem.getGoals();
    assert rawGoals != null;

    this.atLeastOneSimulateAfter = rawGoals.stream().filter(g -> g.simulateAfter).findFirst().isPresent();

    //create queue container using comparator and pre-sized for all goals
    final var capacity = rawGoals.size();
    assert capacity >= 0;

    //fill the comparator-imbued container with goals to get sorted queue
    final var goalQ = new LinkedList<>(rawGoals);
    assert goalQ.size() == rawGoals.size();

    return goalQ;
  }

  private void satisfyGoal(Goal goal) throws SchedulingInterruptedException{
    if(simulationFacade.getCanceledListener().get()) throw new SchedulingInterruptedException("satisfying goal");
    final boolean checkSimConfig = this.checkSimBeforeInsertingActivities;
    this.checkSimBeforeInsertingActivities = goal.simulateAfter;
    if (goal instanceof CompositeAndGoal) {
      satisfyCompositeGoal((CompositeAndGoal) goal);
    } else if (goal instanceof OptionGoal) {
      satisfyOptionGoal((OptionGoal) goal);
    } else if (goal instanceof Procedure procedure) {
      if (!analysisOnly) {
        procedure.run(plan.getEvaluation(), plan, problem.getMissionModel(), this.problem::getActivityType, this.simulationFacade, this.idGenerator);
      }
    } else {
      satisfyGoalGeneral(goal);
    }
    this.checkSimBeforeEvaluatingGoal = goal.simulateAfter;
    this.checkSimBeforeInsertingActivities = checkSimConfig;
  }

  private void satisfyOptionGoal(OptionGoal goal) throws SchedulingInterruptedException{
      if (goal.hasOptimizer()) {
        //try to satisfy all and see what is best
        Goal currentSatisfiedGoal = null;
        Collection<SchedulingActivity> actsToInsert = null;
        Collection<SchedulingActivity> actsToAssociateWith = null;
        for (var subgoal : goal.getSubgoals()) {
          satisfyGoal(subgoal);
          if(plan.getEvaluation().forGoal(subgoal).getScore() == 0 || !subgoal.shouldRollbackIfUnsatisfied()) {
            var associatedActivities = plan.getEvaluation().forGoal(subgoal).getAssociatedActivities();
            var insertedActivities = plan.getEvaluation().forGoal(subgoal).getInsertedActivities();
            var aggregatedActivities = new ArrayList<SchedulingActivity>();
            aggregatedActivities.addAll(associatedActivities);
            aggregatedActivities.addAll(insertedActivities);
            if (!aggregatedActivities.isEmpty() &&
                (goal.getOptimizer().isBetterThanCurrent(aggregatedActivities) ||
                 currentSatisfiedGoal == null)) {
              actsToInsert = insertedActivities;
              actsToAssociateWith = associatedActivities;
              currentSatisfiedGoal = subgoal;
            }
          }
          rollback(subgoal);
        }
        //we should have the best solution
        if (currentSatisfiedGoal != null) {
          for(var act: actsToAssociateWith){
            //we do not care about ownership here as it is not really a piggyback but just the validation of the supergoal
            plan.getEvaluation().forGoal(goal).associate(act, false);
          }
          final var insertionResult = checkAndInsertActs(actsToInsert);
          if(insertionResult.success()) {
            for(var act: insertionResult.activitiesInserted()){
              plan.getEvaluation().forGoal(goal).associate(act, false);
            }
            plan.getEvaluation().forGoal(goal).setScore(0);
          } else{
            //this should not happen because we have already tried to insert the same set of activities in the plan and it
            //did not fail
            throw new IllegalStateException("Had satisfied subgoal but (1) simulation or (2) association with supergoal failed");
          }
        } else {
          plan.getEvaluation().forGoal(goal).setScore(-1);
        }
      } else {
        var atLeastOneSatisfied = false;
        //just satisfy any goal
        for (var subgoal : goal.getSubgoals()) {
          satisfyGoal(subgoal);
          final var evaluation = plan.getEvaluation();
          final var subgoalIsSatisfied = (evaluation.forGoal(subgoal).getScore() == 0);
          evaluation.forGoal(goal).associate(evaluation.forGoal(subgoal).getAssociatedActivities(), false);
          evaluation.forGoal(goal).associate(evaluation.forGoal(subgoal).getInsertedActivities(), true);
          if(subgoalIsSatisfied){
            logger.info("OR goal " + goal.getName() + ": subgoal " + subgoal.getName() + " has been satisfied, stopping");
            atLeastOneSatisfied = true;
            break;
          }
          logger.info("OR goal " + goal.getName() + ": subgoal " + subgoal.getName() + " could not be satisfied, moving on to next subgoal");
        }
        if(atLeastOneSatisfied){
          plan.getEvaluation().forGoal(goal).setScore(0);
        } else {
          plan.getEvaluation().forGoal(goal).setScore(-1);
          if(goal.shouldRollbackIfUnsatisfied()) {
            for (var subgoal : goal.getSubgoals()) {
              rollback(subgoal);
            }
          }
        }
      }
  }

  private void rollback(Goal goal){
    var evalForGoal = plan.getEvaluation().forGoal(goal);
    var associatedActivities = evalForGoal.getAssociatedActivities();
    var insertedActivities = evalForGoal.getInsertedActivities();
    plan.remove(insertedActivities);
    evalForGoal.removeAssociation(associatedActivities);
    evalForGoal.removeAssociation(insertedActivities);
    evalForGoal.setScore(-(evalForGoal.getNbConflictsDetected().orElse(1)));
  }

  private void satisfyCompositeGoal(CompositeAndGoal goal) throws SchedulingInterruptedException{
    assert goal != null;
    assert plan != null;

    var nbGoalSatisfied = 0;
    for (var subgoal : goal.getSubgoals()) {
      satisfyGoal(subgoal);
      if (plan.getEvaluation().forGoal(subgoal).getScore() == 0) {
        logger.info("AND goal " + goal.getName() + ": subgoal " + subgoal.getName() + " has been satisfied, moving on to next subgoal");
        nbGoalSatisfied++;
      } else {
        logger.info("AND goal " + goal.getName() + ": subgoal " + subgoal.getName() + " has NOT been satisfied");
        if(goal.shouldRollbackIfUnsatisfied()){
          logger.info("AND goal " + goal.getName() + ": stopping goal satisfaction after first failure, remove shouldRollbackIfUnsatisfied(true) on AND goal to maximize satisfaction instead of early termination");
          break;
        }
        logger.info("AND goal " + goal.getName() + ": moving on to next subgoal (trying to maximize satisfaction)");
      }
    }
    final var goalIsSatisfied = (nbGoalSatisfied == goal.getSubgoals().size());
    if (goalIsSatisfied) {
      plan.getEvaluation().forGoal(goal).setScore(0);
    } else {
      plan.getEvaluation().forGoal(goal).setScore(-1);
    }

    if(!goalIsSatisfied && goal.shouldRollbackIfUnsatisfied()){
      for (var subgoal : goal.getSubgoals()) {
        rollback(subgoal);
      }
    }
    if(goalIsSatisfied) {
      for (var subgoal : goal.getSubgoals()) {
        final var evaluation = plan.getEvaluation();
        evaluation.forGoal(goal).associate(evaluation.forGoal(subgoal).getAssociatedActivities(), false);
        evaluation.forGoal(goal).associate(evaluation.forGoal(subgoal).getInsertedActivities(), true);
      }
    }
  }


  /**
   * attempts to satisfy the specified goal as much as possible
   *
   * updates the output plan member with newly scheduled activities in order
   * to meet the goal, but does so without perturbing any of the existing
   * scheduled activities (as required by the strict priority ordering of the
   * algorithm)
   *
   * the scheduled activities are placed in a myopic greedy fashion, utilizing
   * the best timing and parameters for each new activity and ignoring any
   * potential downstream impact on either this or subsequent goal
   * achievement. (eg it might even be fooled into blocking its own subsequent
   * activities.) in case of ties in timing selection, activities are
   * scheduled at the latest permissible slots
   *
   * the configuration, problem, and plan members must exist and be valid
   *
   * the goal must be a member of the problem specification
   *
   * @param goal IN the single goal to address with plan modifications
   */
  private void satisfyGoalGeneral(Goal goal) throws SchedulingInterruptedException{

    assert goal != null;
    assert plan != null;

    //continue creating activities as long as goal wants more and we can do so
    logger.info("Starting conflict detection before goal " + goal.getName());
    var missingConflicts = getConflicts(goal);
    logger.info("Found "+ missingConflicts.size() +" conflicts in conflict detection");
    //setting the number of conflicts detected at first evaluation, will be used at backtracking
    plan.getEvaluation().forGoal(goal).setNbConflictsDetected(missingConflicts.size());
    assert missingConflicts != null;

    final var itConflicts = missingConflicts.iterator();
    int i = 0;
    //create new activity instances for each missing conflict
    while (itConflicts.hasNext()) {
      final var missing = itConflicts.next();
      assert missing != null;
      logger.info("Processing conflict " + (++i));
      logger.info(missing.toString());
      //determine the best activities to satisfy the conflict
      if (!analysisOnly && (missing instanceof MissingActivityInstanceConflict missingActivityInstanceConflict)) {
        final var acts = getBestNewActivities(missingActivityInstanceConflict);
        //add the activities to the output plan
        if (!acts.isEmpty()) {
          logger.info("Found activity to satisfy missing activity instance conflict");
          final var insertionResult = checkAndInsertActs(acts);
          if(insertionResult.success){
            plan.getEvaluation().forGoal(goal).associate(insertionResult.activitiesInserted(), true);
            itConflicts.remove();
            //REVIEW: really association should be via the goal's own query...
          } else{
            logger.info("Conflict " + i + " could not be satisfied");
          }
        }
      }
      else if(!analysisOnly &&  (missing instanceof MissingActivityTemplateConflict missingActivityTemplateConflict)){
        var cardinalityLeft = missingActivityTemplateConflict.getCardinality();
        var durationToAccomplish = missingActivityTemplateConflict.getTotalDuration();
        var durationLeft = Duration.ZERO;
        if(durationToAccomplish.isPresent()) {
          durationLeft = durationToAccomplish.get();
        }
        var nbIterations = 0;
        while(cardinalityLeft > 0 || durationLeft.longerThan(Duration.ZERO)){
          logger.info("Trying to satisfy template conflict " + i + " (iteration: "+(++nbIterations)+"). Missing cardinality: " + cardinalityLeft + ", duration: " + (durationLeft.isEqualTo(Duration.ZERO) ? "N/A" : durationLeft));
          final var acts = getBestNewActivities(missingActivityTemplateConflict);
          assert acts != null;
          //add the activities to the output plan
          if (!acts.isEmpty()) {
            logger.info("Found activity to satisfy missing activity template conflict");
            final var insertionResult = checkAndInsertActs(acts);
            if(insertionResult.success()){

              plan.getEvaluation().forGoal(goal).associate(insertionResult.activitiesInserted(), true);
              //REVIEW: really association should be via the goal's own query...
              cardinalityLeft--;
              durationLeft = durationLeft.minus(insertionResult
                                                    .activitiesInserted()
                                                    .stream()
                                                    .map(SchedulingActivity::duration)
                                                    .reduce(Duration.ZERO, Duration::plus));
            }
          } else{
            logger.info("Conflict " + i + " could not be satisfied");
            break;
          }
        }
        if(cardinalityLeft <= 0 && durationLeft.noLongerThan(Duration.ZERO)){
          logger.info("Conflict " + i + " has been addressed");
          itConflicts.remove();
        }
      } else if(missing instanceof MissingAssociationConflict missingAssociationConflict){
        var actToChooseFrom = missingAssociationConflict.getActivityInstancesToChooseFrom();
        //no act type constraint to consider as the activities have been scheduled
        //no global constraint for the same reason above mentioned
        //only the target goal state constraints to consider
        for(var act : actToChooseFrom){
          var actWindow = new Windows(false).set(Interval.between(act.startOffset(), act.getEndTime()), true);
          var stateConstraints = goal.getResourceConstraints();
          var narrowed = actWindow;
          if(stateConstraints!= null) {
            narrowed = narrowByResourceConstraints(actWindow, List.of(stateConstraints));
          }
          if(narrowed.includes(actWindow)){
            // If existing activity is a match but is missing the anchor, then the appropriate anchorId has been included in MissingAssociationConflict.
            // In that case, a new activity must be created as a copy of act but including the anchorId. This activity is then added to all appropriate data structures and the association is created
            if (missingAssociationConflict.getAnchorIdTo().isPresent()){
              SchedulingActivity predecessor = plan.getActivitiesById().get(missingAssociationConflict.getAnchorIdTo().get());
              Duration startOffset = act.startOffset().minus(plan.calculateAbsoluteStartOffsetAnchoredActivity(predecessor));
              // In case the goal requires generation of anchors, then check that the anchor is to the Start. Otherwise (anchor to End), make sure that there is a positive offset
              if(missingAssociationConflict.getAnchorToStart().isEmpty() || missingAssociationConflict.getAnchorToStart().get() || startOffset.longerThan(Duration.ZERO)){
                var replacementAct = act.withNewAnchor(
                    missingAssociationConflict.getAnchorIdTo().get(),
                    missingAssociationConflict.getAnchorToStart().get(),
                    startOffset
                );
                plan.replaceActivity(act,replacementAct);
                //decision-making here, we choose the first satisfying activity
                plan.getEvaluation().forGoal(goal).associate(replacementAct, false);
                itConflicts.remove();
                logger.info("Activity " + replacementAct + " has been associated to goal " + goal.getName() +" to satisfy conflict " + i);
                break;
              }
               else{
                logger.info("Activity " + act + " could not be associated to goal " + goal.getName() + " because of goal constraints");
               }
            }
            else {
              //decision-making here, we choose the first satisfying activity
              plan.getEvaluation().forGoal(goal).associate(act, false);
              itConflicts.remove();
              logger.info("Activity "
                          + act
                          + " has been associated to goal "
                          + goal.getName()
                          + " to satisfy conflict "
                          + i);
              break;
            }
          } else{
            logger.info("Activity " + act + " could not be associated to goal " + goal.getName() + " because of goal constraints");
          }
        }
      }
    }//for(missing)


    if(!missingConflicts.isEmpty() && goal.shouldRollbackIfUnsatisfied()){
      logger.warn("Rolling back changes for "+goal.getName());
      rollback(goal);
    }
    logger.info("Finishing goal satisfaction for goal " + goal.getName() +":"+ (missingConflicts.isEmpty()
                                                                                    ? "SUCCESS" : "FAILURE. Number of conflicts that could not be addressed: " + missingConflicts.size()));
    plan.getEvaluation().forGoal(goal).setScore(-missingConflicts.size());
  }

  /**
   * finds plan conflicts due to missing activities induced by the goal
   *
   * the solution plan must exist and be valid
   *
   * @param goal IN the goal to find missing activities for
   * @return the set of missing activity conflicts in the current solution
   *     plan due to the specified goal
   */
  private Collection<Conflict> getConflicts(Goal goal) throws SchedulingInterruptedException
  {
    assert goal != null;
    assert plan != null;
    //REVIEW: maybe should have way to request only certain kinds of conflicts
    logger.debug("Computing simulation results until "+ this.problem.getPlanningHorizon().getEndAerie() + " (planning horizon end) in order to compute conflicts");
    final var resources = new HashSet<String>();
    goal.extractResources(resources);
    final var simulationResults = this.getLatestSimResultsUpTo(this.problem.getPlanningHorizon().getEndAerie(), resources);
    final var evaluationEnvironment = new EvaluationEnvironment(this.problem.getRealExternalProfiles(), this.problem.getDiscreteExternalProfiles());
    final var rawConflicts = goal.getConflicts(
        plan,
        simulationResults.constraintsResults(),
        evaluationEnvironment,
        this.problem.getSchedulerModel());
    assert rawConflicts != null;
    return rawConflicts;
  }

  /**
   * determines the best activity instances to add to improve the plan
   *
   * calculates the scheduling for a set of activity instances that will best
   * satisfy the given conflict in the fixed context of the current plan (but
   * does not actually put them in the solution yet)
   *
   * the suggested activities might only reduce the degree of conflict present
   * without eliminating it completely
   *
   * multiple activities may be returned, eg to allow for scheduling
   * interelated activities (eg co-dependent observations, ancillary
   * setup/cleanups, etc)
   *
   * //REVIEW: should multiple acts be handled by a decomposition instead?
   *
   * the activities are chosen in a myopic greedy fashion: they ignore any
   * opportunity cost of choices on subsequent goal satisfaction (either for
   * their own or others goals). the algorithm also does not consider other
   * joint modifications to the plan (eg moves to allow scheduling)
   *
   * this method does at least choose additions that avoid introducing any new
   * conflicts with anything that is already in the plan, including registered
   * state constraints
   *
   * returns an empty container if there are no activities that can be added
   * to satisfy the conflict without introducing other conflicts
   *
   * the output plan member must exist and be valid
   *
   * @param missing IN the conflict describing an acute lack of an activity
   *     that is causing goal dissatisfaction in the current plan
   * @return an ensemble of new activity instances that are suggested to be
   *     added to the plan to best satisfy the conflict without disrupting
   *     the rest of the plan, or null if there are no such suggestions
   */
  private Collection<SchedulingActivity> getBestNewActivities(MissingActivityConflict missing)
  throws SchedulingInterruptedException {
    assert missing != null;
    var newActs = new LinkedList<SchedulingActivity>();

    //REVIEW: maybe push into polymorphic method of conflict/goal? (picking best act
    //may depend on the source goal)
    final var goal = missing.getGoal();

    //start from the time interval where the missing activity causes a problem
    //NB: these are start windows
    var possibleWindows = missing.getTemporalContext();
    //prune based on constraints on goal and activity type (mutex, state,
    //event, etc)
    //TODO: move this into polymorphic method. don't want to be demuxing types
    Collection<Expression<Windows>> resourceConstraints = new LinkedList<>();

    //add all goal constraints
    final var goalConstraints = goal.getResourceConstraints();

    if (goalConstraints != null) {
      resourceConstraints.add(goalConstraints);
    }
    if (missing instanceof final MissingActivityInstanceConflict missingInstance) {
      final var act = missingInstance.getInstance();
      final var c = act.getType().getStateConstraints();
      if (c != null) resourceConstraints.add(c);
    } else if (goal instanceof ActivityTemplateGoal activityTemplateGoal) {
      final var c = activityTemplateGoal.getActivityStateConstraints();
      if (c != null) resourceConstraints.add(c);
    } else {
      //TODO: placeholder for now to avoid mutex fall through
      throw new IllegalArgumentException("request to create activities for conflict of unrecognized type");
    }
    logger.debug("Initial windows from conflict temporal context :" + possibleWindows.trueSegmentsToString());
    possibleWindows = narrowByResourceConstraints(possibleWindows, resourceConstraints);
    logger.debug("Windows after narrowing by resource constraints :" + possibleWindows.trueSegmentsToString());
    possibleWindows = narrowGlobalConstraints(plan, missing, possibleWindows, this.problem.getGlobalConstraints(), missing.getEvaluationEnvironment());
    logger.debug("Windows after narrowing by global scheduling conditions :" + possibleWindows.trueSegmentsToString());
    //narrow to windows where activity duration will fit
    var startWindows = possibleWindows;
    //for now handling just start-time windows, so no need to prune duration
    //    //REVIEW: how to handle dynamic durations? for now pessimistic!
    //    final var durationMax = goal.getActivityDurationRange().getMaximum();
    //    possibleWindows = null;
    //    startWindows.contractBy( Duration.ofZero(), durationMax );

    //create new act if there is any valid time (otherwise conflict is
    //unsatisfiable in current plan)
    if (startWindows.stream().anyMatch(Segment::value)) {
      //TODO: move this into a polymorphic method? definitely don't want to be
      //demuxing on all the conflict types here
      if (missing instanceof final MissingActivityInstanceConflict missingInstance) {
        //FINISH: clean this up code dupl re windows etc
        final var act = missingInstance.getInstance();
        newActs.add(act);

      } else if (missing instanceof final MissingActivityTemplateConflict missingTemplate) {
        //select the "best" time among the possibilities, and latest among ties
        //REVIEW: currently not handling preferences / ranked windows

        startWindows = startWindows.and(missing.getTemporalContext());
        //create the new activity instance (but don't place in schedule)
        //REVIEW: not yet handling multiple activities at a time
        logger.info("Instantiating activity in windows " + startWindows.trueSegmentsToString());
        final var act = createOneActivity(
            missingTemplate,
            goal.getName() + "_" + java.util.UUID.randomUUID(),
            startWindows,
            missing.getEvaluationEnvironment());
        if(act.isPresent()){
          if (missingTemplate.getAnchorId().isPresent()) {
            final SchedulingActivity predecessor = plan.getActivitiesById().get(missingTemplate.getAnchorId().get());
            int includePredDuration = 0;
            if (missingTemplate.getAnchorToStart().isPresent()) {
              includePredDuration = missingTemplate.getAnchorToStart().get() ? 0 : 1;
            }
            final Duration dur = predecessor.duration().times(includePredDuration);
            final Duration startOffset = act.get().startOffset().minus(plan.calculateAbsoluteStartOffsetAnchoredActivity(predecessor).plus(dur));
            // In case the goal requires generation of anchors and anchor is startsAt End, then check that predecessor completes before act starts. If that's not the case, don't add act as the anchor cannot be verified
            if(((MissingActivityTemplateConflict) missing).getAnchorToStart().isEmpty() || ((MissingActivityTemplateConflict) missing).getAnchorToStart().get() || startOffset.noShorterThan(Duration.ZERO)){
              final var actWithAnchor = Optional.of(act.get().withNewAnchor(missingTemplate.getAnchorId().get(), missingTemplate.getAnchorToStart().get(), startOffset));
              newActs.add(actWithAnchor.get());
            }
            else{
              logger.info("Activity " + act + " could not be associated to goal " + goal.getName() + " because of goal constraints");
            }
          }
          else{
            newActs.add(act.get());
          }
        }
        //is an exception that act is empty?
      }

    }//if(startWindows)

    return newActs;
  }

  /**
   * contracts the given windows according to the provided constraints
   *
   * the remaining windows after this call will be the interesection of
   * all the individual constraint windows with the initial input windows
   *
   * evaluates the constraints in the context of the current solution plan
   *
   * the remaining windows may be empty!
   *
   * @param windows IN/OUT the windows to be contracted by constraints.
   *     updated in place. may be empty (but not null)
   * @param constraints IN the constraints to use to narrow the windows,
   *     may be empty (but not null)
   */
  private Windows narrowByResourceConstraints(
      Windows windows,
      Collection<Expression<Windows>> constraints
  ) throws SchedulingInterruptedException {
    assert windows != null;
    assert constraints != null;
    Windows ret = windows;
    //short circuit on already empty windows or no constraints: no work to do!
    if (windows.stream().noneMatch(Segment::value) || constraints.isEmpty()) {
      return ret;
    }
    logger.info("Narrowing windows by resource constraints");
    final var totalDomain = Interval.between(windows.minTrueTimePoint().get().getKey(), windows.maxTrueTimePoint().get().getKey());
    //make sure the simulation results cover the domain
    logger.debug("Computing simulation results until "+ totalDomain.end + " in order to compute resource constraints");
    final var resourceNames = new HashSet<String>();
    constraints.forEach(c -> c.extractResources(resourceNames));
    final var latestSimulationResults = this.getLatestSimResultsUpTo(totalDomain.end, resourceNames);
    //iteratively narrow the windows from each constraint
    //REVIEW: could be some optimization in constraint ordering (smallest domain first to fail fast)
    final var evaluationEnvironment = new EvaluationEnvironment(this.problem.getRealExternalProfiles(), this.problem.getDiscreteExternalProfiles());
    for (final var constraint : constraints) {
      //REVIEW: loop through windows more efficient than enveloppe(windows) ?
      final var validity = constraint.evaluate(latestSimulationResults.constraintsResults(), totalDomain, evaluationEnvironment);
      ret = ret.and(validity);
      //short-circuit if no possible windows left
      if (ret.stream().noneMatch(Segment::value)) {
        break;
      }
    }
    return ret;
  }

  private SimulationData getLatestSimResultsUpTo(final Duration time, final Set<String> resourceNames) throws SchedulingInterruptedException {
    //if no resource is needed, build the results from the current plan
    if(resourceNames.isEmpty()) {
      final var groundedPlan = SchedulePlanGrounder.groundSchedule(
          this.plan.getActivities().stream().toList(),
          this.problem.getPlanningHorizon().getEndAerie());
      if (groundedPlan.isPresent()) {
        return new SimulationData(
            plan,
            null,
            new SimulationResults(
              problem.getPlanningHorizon().getStartInstant(),
              problem.getPlanningHorizon().getHor(),
              groundedPlan.get(),
              Map.of(),
              Map.of())
        );
      } else {
        logger.debug(
            "Tried mocking simulation results with a grounded plan but could not because of the activity cannot be grounded.");
      }
    }
    try {
      var resources = new HashSet<String>(resourceNames);
      var resourcesAreMissing = false;
      if(cachedSimulationResultsBeforeGoalEvaluation != null){
        final var allResources = new HashSet<>(cachedSimulationResultsBeforeGoalEvaluation.constraintsResults().realProfiles.keySet());
        allResources.addAll(cachedSimulationResultsBeforeGoalEvaluation.constraintsResults().discreteProfiles.keySet());
        resourcesAreMissing = !allResources.containsAll(resourceNames);
      }
      //if at least one goal needs the simulateAfter, we can't compute partial resources to avoid future recomputations
      if(atLeastOneSimulateAfter){
        resources.clear();
        resources.addAll(this.problem.getMissionModel().getResources().keySet());
      }
      if(checkSimBeforeEvaluatingGoal || cachedSimulationResultsBeforeGoalEvaluation == null || cachedSimulationResultsBeforeGoalEvaluation.constraintsResults().bounds.end.shorterThan(time) || resourcesAreMissing)
        cachedSimulationResultsBeforeGoalEvaluation = simulationFacade
            .simulateWithResults(plan, time, resources);
      return cachedSimulationResultsBeforeGoalEvaluation;
    } catch (SimulationFacade.SimulationException e) {
    throw new RuntimeException("Exception while running simulation before evaluating conflicts", e);
    }
  }

  private Windows narrowGlobalConstraints(
      final Plan plan,
      final MissingActivityConflict mac,
      final Windows windows,
      final Collection<GlobalConstraintWithIntrospection> constraints,
      final EvaluationEnvironment evaluationEnvironment) throws SchedulingInterruptedException {
    Windows tmp = windows;
    if(tmp.stream().noneMatch(Segment::value)){
      return tmp;
    }
    //make sure the simulation results cover the domain
    logger.debug("Computing simulation results until "+ tmp.maxTrueTimePoint().get().getKey() + " in order to compute global scheduling conditions");
    final var resourceNames = new HashSet<String>();
    constraints.forEach(c -> c.extractResources(resourceNames));
    final var latestSimulationResults = this.getLatestSimResultsUpTo(tmp.maxTrueTimePoint().get().getKey(), resourceNames);
    for (final var gc : constraints) {
      tmp = gc.findWindows(
          plan,
          tmp,
          mac,
          latestSimulationResults.constraintsResults(),
          evaluationEnvironment);
    }
  return tmp;
  }

  /**
   * creates one activity if possible
   *
   * @param name the activity name
   * @param windows the windows in which the activity can be instantiated
   * @return the instance of the activity (if successful; else, an empty object) wrapped as an Optional.
   */
  public @NotNull Optional<SchedulingActivity> createOneActivity(
      final MissingActivityTemplateConflict missingConflict,
      final String name,
      final Windows windows,
      final EvaluationEnvironment evaluationEnvironment) throws SchedulingInterruptedException
  {
    //REVIEW: how to properly export any flexibility to instance?
    logger.info("Trying to create one activity, will loop through possible windows");
    for (var window : windows.iterateEqualTo(true)) {
      logger.info("Trying in window " + window);
      var activity = instantiateActivity(missingConflict.getActTemplate(), name, window, missingConflict.getEvaluationEnvironment());
      if (activity.isPresent()) {
          return activity;
      }
    }
    return Optional.empty();
  }

  private Optional<SchedulingActivity> instantiateActivity(
      final ActivityExpression activityExpression,
      final String name,
      final Interval interval,
      final EvaluationEnvironment evaluationEnvironment
  ) throws SchedulingInterruptedException {
    final var planningHorizon = this.problem.getPlanningHorizon();
    final var envelopes = new ArrayList<Interval>();
    if(interval != null) envelopes.add(interval);
    final var reduced = activityExpression.reduceTemporalConstraints(
        planningHorizon,
        this.problem.getSchedulerModel(),
        evaluationEnvironment,
        envelopes);

    if(reduced.isEmpty()) return Optional.empty();
    final var solved = reduced.get();

    //Extract resource names to lighten the computation of simulation results
    final var resourceNames = new HashSet<String>();
    activityExpression.extractResources(resourceNames);

    //the domain of user/scheduling temporal constraints have been reduced with the STN,
    //now it is time to find an assignment compatible
    //CASE 1: activity has an uncontrollable duration
    if(activityExpression.type().getDurationType() instanceof DurationType.Uncontrollable){
      final var history = new HistoryWithActivity();
      final var f = new EquationSolvingAlgorithms.Function<Duration, ActivityMetadata>(){
        @Override
        public Duration valueAt(Duration start, final EquationSolvingAlgorithms.History<Duration, ActivityMetadata> history)
        throws EquationSolvingAlgorithms.DiscontinuityException, SchedulingInterruptedException
        {
          final var latestConstraintsSimulationResults = getLatestSimResultsUpTo(start, resourceNames);
          final var actToSim = new SchedulingActivity(
              idGenerator.next(),
              activityExpression.type(),
              start,
              null,
              SchedulingActivity.instantiateArguments(
                  activityExpression.arguments(),
                  start,
                  latestConstraintsSimulationResults.constraintsResults(),
                  evaluationEnvironment,
                  activityExpression.type()),
              null,
              null,
              true,
              true
          );
          Duration computedDuration = null;
          try {
            final var duplicatePlan = plan.duplicate();
            duplicatePlan.add(actToSim);
            simulationFacade.simulateNoResultsUntilEndAct(duplicatePlan, actToSim);
            computedDuration = duplicatePlan.getActivitiesById().get(actToSim.id()).duration();
            if(computedDuration != null) {
              history.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(start, start.plus(computedDuration)), new ActivityMetadata(actToSim.withNewDuration(computedDuration)));
            } else{
              logger.debug("No simulation error but activity duration could not be found in simulation, likely caused by unfinished activity or activity outside plan bounds.");
              history.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(start,  null), new ActivityMetadata(actToSim));
            }
          } catch (SimulationFacade.SimulationException e) {
            logger.debug("Simulation error while trying to simulate activities: " + e);
            history.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(start,  null), new ActivityMetadata(actToSim));
          }
          if(computedDuration == null) throw new EquationSolvingAlgorithms.DiscontinuityException();
          return start.plus(computedDuration);
        }

      };
      return rootFindingHelper(f, history, solved);
      //CASE 2: activity has a controllable duration
    } else if (activityExpression.type().getDurationType() instanceof DurationType.Controllable dt) {
      //select earliest start time, STN guarantees satisfiability
      final var earliestStart = solved.start().start;
      final var instantiatedArguments = SchedulingActivity.instantiateArguments(
          activityExpression.arguments(),
          earliestStart,
          getLatestSimResultsUpTo(earliestStart, resourceNames).constraintsResults(),
          evaluationEnvironment,
          activityExpression.type());

      final var durationParameterName = dt.parameterName();
      //handle variable duration parameter here
      final Duration setActivityDuration;
      if (instantiatedArguments.containsKey(durationParameterName)) {
        final var argumentDuration = problem.getSchedulerModel().deserializeDuration(instantiatedArguments.get(durationParameterName));
        if (solved.duration().contains(argumentDuration)) {
          setActivityDuration = argumentDuration;
        } else {
          logger.debug(
              "Controllable duration set by user is incompatible with temporal constraints associated to the activity template");
          return Optional.empty();
        }
      } else {
        //REVIEW: should take default duration of activity type maybe ?
        setActivityDuration = solved.end().start.minus(solved.start().start);
      }
      // TODO: When scheduling is allowed to create activities with anchors, this constructor should pull from an expanded creation template
      return Optional.of(SchedulingActivity.of(
          idGenerator.next(),
          activityExpression.type(),
          earliestStart,
          setActivityDuration,
          instantiatedArguments,
          null,
          null,
          true,
          true
      ));
    } else if (activityExpression.type().getDurationType() instanceof DurationType.Fixed dt) {
      if (!solved.duration().contains(dt.duration())) {
        logger.debug("Interval is too small");
        return Optional.empty();
      }

      final var earliestStart = solved.start().start;
      // TODO: When scheduling is allowed to create activities with anchors, this constructor should pull from an expanded creation template
      return Optional.of(SchedulingActivity.of(
          idGenerator.next(),
          activityExpression.type(),
          earliestStart,
          dt.duration(),
          SchedulingActivity.instantiateArguments(
              activityExpression.arguments(),
              earliestStart,
              getLatestSimResultsUpTo(earliestStart, resourceNames).constraintsResults(),
              evaluationEnvironment,
              activityExpression.type()),
          null,
          null,
          true,
          true
      ));
    } else if (activityExpression.type().getDurationType() instanceof DurationType.Parametric dt) {
      final var history = new HistoryWithActivity();
      final var f = new EquationSolvingAlgorithms.Function<Duration, ActivityMetadata>() {
        @Override
        public Duration valueAt(final Duration start, final EquationSolvingAlgorithms.History<Duration, ActivityMetadata> history)
        throws SchedulingInterruptedException {
          final var instantiatedArgs = SchedulingActivity.instantiateArguments(
              activityExpression.arguments(),
              start,
              getLatestSimResultsUpTo(start, resourceNames).constraintsResults(),
              evaluationEnvironment,
              activityExpression.type()
          );

          try {
            final var duration = dt.durationFunction().apply(instantiatedArgs);
            final var activity = SchedulingActivity.of(
                idGenerator.next(),
                activityExpression.type(),start,
                duration,
                instantiatedArgs,
                null,
                null,
                true,
                true
            );
            history.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(start, start.plus(duration)), new ActivityMetadata(activity));
            return duration.plus(start);
          } catch (InstantiationException e) {
            logger.error("Cannot instantiate parameterized duration activity type: " + activityExpression.type().getName());
            throw new RuntimeException(e);
          }
        }
      };

      return rootFindingHelper(f, history, solved);
    } else {
      throw new UnsupportedOperationException("Unsupported duration type found: " + activityExpression.type().getDurationType());
    }
  }

  private  Optional<SchedulingActivity> rootFindingHelper(
      final EquationSolvingAlgorithms.Function<Duration, ActivityMetadata> f,
      final HistoryWithActivity history,
      final TaskNetworkAdapter.TNActData solved
  ) throws SchedulingInterruptedException {
    try {
      var endInterval = solved.end();
      var startInterval = solved.start();

      final var durationHalfEndInterval = endInterval.duration().dividedBy(2);

      final var result = new EquationSolvingAlgorithms
          .SecantDurationAlgorithm<ActivityMetadata>()
          .findRoot(
              f,
              history,
              startInterval.start,
              endInterval.start.plus(durationHalfEndInterval),
              durationHalfEndInterval,
              durationHalfEndInterval,
              startInterval.start,
              startInterval.end,
              20);

      // TODO: When scheduling is allowed to create activities with anchors, this constructor should pull from an expanded creation template
      logger.info("Finished rootfinding: SUCCESS");
      history.logHistory();
      final var lastActivityTested = result.history().getHistory().get(history.getHistory().size() - 1);
      return Optional.of(lastActivityTested.getRight().get().activityDirective());
    } catch (EquationSolvingAlgorithms.ZeroDerivativeException zeroOrInfiniteDerivativeException) {
      logger.info("Rootfinding encountered a zero-derivative");
    } catch (EquationSolvingAlgorithms.InfiniteDerivativeException infiniteDerivativeException) {
      logger.info("Rootfinding encountered an infinite-derivative");
    } catch (EquationSolvingAlgorithms.DivergenceException e) {
      logger.info("Rootfinding diverged");
    } catch (EquationSolvingAlgorithms.ExceededMaxIterationException e) {
      logger.info("Too many iterations");
    } catch (EquationSolvingAlgorithms.NoSolutionException e) {
      logger.info("Rootfinding found no solution");
    }
    logger.info("Finished rootfinding: FAILURE");
    history.logHistory();
    return Optional.empty();
  }

  public void printEvaluation() {
    final var evaluation = plan.getEvaluation();
    logger.warn("Remaining conflicts for goals ");
    for (var goalEval : evaluation.getGoals()) {
      logger.warn(goalEval.getName() + " -> " + evaluation.forGoal(goalEval).score);
      logger.warn("Activities created by this goal:"+  evaluation.forGoal(goalEval).getInsertedActivities().stream().map(
          SchedulingActivity::toString).collect(
          Collectors.joining(" ")));
      logger.warn("Activities associated to this goal:"+  evaluation.forGoal(goalEval).getAssociatedActivities().stream().map(
          SchedulingActivity::toString).collect(
          Collectors.joining(" ")));
    }
  }

}
