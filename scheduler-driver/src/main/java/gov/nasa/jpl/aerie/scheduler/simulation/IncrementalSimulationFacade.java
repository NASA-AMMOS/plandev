package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationDriver;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResultsComputerInputs;
import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.Nullable;
import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacadeUtils.scheduleFromPlan;


/**
 * interface layer to the sim engine used by the scheduler to manage restarts, hypothesis testing, etc
 * <p>
 * this implementation utilizes the incremental simulation engine capabilities to avoid redoing most
 * of the simulation work at the expense of more memory to store causal data from prior simulations
 * <p>
 * if simulation results are available for the plan already (eg from the database), those may be provided
 * to an initial call to {@link #setInitialSimResults(SimulationData)}, and those results will be used to
 * serve any preliminary constraint etc queries up until a resimulation is triggered by any change to the
 * plan
 * <p>
 * the details of any activity directives encountered by the scheduler must be provided in advance of
 * the scheduler reasoning about them by prior call to {@link #addActivityTypes(Collection)}
 *
 * @param <Model> the type of mission model that this facade can simulate plans for
 */
public class IncrementalSimulationFacade<Model> implements SimulationFacade {

  /**
   * the simulation results for the unmodified initial plan if available, eg as loaded from the db
   * <p>
   * see {@link #setInitialSimResults(SimulationData)}
   **/
  private SimulationData latestSimulationData = null;

  /**
   * any necessary details about needed activity types, indexed by the activity type name
   * <p>
   * see {@link #addActivityTypes(Collection)}
   */
  private final Map<String, ActivityType> activityTypes = new HashMap<>();

  /**
   * notifier that flags to true if the current scheduling request has been cancelled
   * <p>
   * see {@link #getCanceledListener()}
   */
  private final Supplier<Boolean> canceledListener;

  /**
   * the simulation results for the unmodified initial plan if available, eg as loaded from the db
   * <p>
   * used to serve requests until a modification requires resimulation
   **/
  private SimulationData initialSimulationResults = null;

  /**
   * behavior model of the system, including activities and resources
   */
  private final MissionModel<Model> missionModel;

  /**
   * model details relevant to scheduling, eg activity duration types
   */
  private final SchedulerModel schedulerModel;

  /**
   * time range under consideration for planning
   */
  private final PlanningHorizon planningHorizon;


  /**
   * creates new facade using the provided plan details and cache of simulation engines
   *
   * @param missionModel behavior model of the system, including activities and resources
   * @param schedulerModel model details relevant to scheduling, eg activity duration types
   * @param planningHorizon time range under consideration for planning
   * @param canceledListener notifier that flags when scheduling request has been cancelled
   *     and the scheduler may abandon its current work, including possibly in progress simulation
   */
  public IncrementalSimulationFacade(
      final MissionModel<Model> missionModel,
      final SchedulerModel schedulerModel,
      final PlanningHorizon planningHorizon,
      final Supplier<Boolean> canceledListener)
  {
    checkNotNull(missionModel);
    checkNotNull(schedulerModel);
    checkNotNull(planningHorizon);
    checkNotNull(canceledListener);
    this.missionModel = missionModel;
    this.schedulerModel = schedulerModel;
    this.planningHorizon = planningHorizon;
    this.canceledListener = canceledListener;
  }

  /**
   * sets the initial simulation data (eg as loaded from the db) to use until a resimulation
   * <p>
   * called at most once before any simulation requests; if not provided before a request then
   * a fresh simulation is forced at the first request
   *
   * @param simulationData the initial simulation data to use until a resimulation is triggered
   */
  @Override
  public void setInitialSimResults(final SimulationData simulationData) {
    checkNotNull(simulationData);
    checkState(this.initialSimulationResults == null, "cannot reset initial sim results");
    checkState(this.latestSimulationData == null, "cannot set initial sim results after first request");
    this.initialSimulationResults = simulationData;
  }

  /**
   * inserts all provided activityTypes to the known mappings
   * <p>
   * must be called with at least the activity types in the plan before the scheduler encounters them
   * <p>
   * may be called multiple times to add activity type details or update them (based on name key)
   *
   * @param activityTypes any necessary details about activities needed in the plan
   */
  @Override
  public void addActivityTypes(final Collection<ActivityType> activityTypes) {
    checkNotNull(activityTypes);
    activityTypes.forEach(at -> this.activityTypes.put(at.getName(), at));
  }

  /**
   * fetch the cancellation notifier that the scheduler should check occasionally
   * <p>
   * a true return indicates that the current scheduling request (and internal simulations) is no longer
   * relevant work to complete it may be stopped.
   *
   * @return a cancellation notifier that the scheduler should check occasionally
   */
  @Override
  public Supplier<Boolean> getCanceledListener()
  {
    return this.canceledListener;
  }


  /**
   * simulates until the end of the last activity of a plan, updating it with children and durations
   * <p>
   * does not actually generate the results at this time, instead returning a record with enough
   * data for the caller to calculate the results later
   *
   * @param plan plan to simulate, which will be updated in place with children and duration data
   * @return input set needed to compute simulation results later
   *
   * @throws SimulationException on simulation error, eg invalid activity args or model exception
   * @throws SchedulingInterruptedException on early halt triggered by the cancellation notifier
   */
  @Override
  public SimulationResultsComputerInputs simulateNoResultsAllActivities(final Plan plan)
    throws SimulationException, SchedulingInterruptedException
  {
    checkNotNull(plan);
    return simulateNoResults(plan, null, null).simulationResultsComputerInputs();
  }

  /**
   * simulates until the end of the target activity of a plan, partially updating child/duration data
   * <p>
   * the simulation early at the end of the given activity and thus may not fully update all
   * the children/durations for other still ongoing or later activities
   * <p>
   * does not actually generate the results at this time, instead returning a record with enough
   * data for the caller to calculate the results later
   *
   * @param plan plan to simulate, which will be updated in place with children and duration data
   * @param activity target activity that the simulation should stop after completing
   * @return input set needed to compute simulation results later
   *
   * @throws SimulationException on simulation error, eg invalid activity args or model exception
   * @throws SchedulingInterruptedException on early halt triggered by the cancellation notifier
   */
  @Override
  public SimulationResultsComputerInputs simulateNoResultsUntilEndAct(
      final Plan plan, final SchedulingActivity activity)
    throws SimulationException, SchedulingInterruptedException
  {
    checkNotNull(plan);
    checkNotNull(activity);
    return simulateNoResults(plan, null, activity).simulationResultsComputerInputs();
  }

  /**
   * simulates until the specified stop time in a plan, partially updating child/duration data
   * <p>
   * the simulation halts at the target time and thus may not fully update all the children/durations
   * for other still ongoing or later activities
   * <p>
   * does not actually generate the results at this time, instead returning a record with enough
   * data for the caller to calculate the results later
   *
   * @param plan plan to simulate, which will be updated in place with children and duration data
   * @param until target time point after which the simulation should stop
   * @return input set needed to compute simulation results later
   *
   * @throws SimulationException on simulation error, eg invalid activity args or model exception
   * @throws SchedulingInterruptedException on early halt triggered by the cancellation notifier
   */
  @Override
  public AugmentedSimulationResultsComputerInputs simulateNoResults(
      final Plan plan, final Duration until)
    throws SimulationException, SchedulingInterruptedException
  {
    checkNotNull(plan);
    checkNotNull(until);
    return simulateNoResults(plan, until, null);
  }


  /**
   * simulates until the specified stop time in a plan, partially updating child/duration data
   * <p>
   * collects results for all resources in the model immediately for return, possibly from the
   * cached initial simulation results provided to {@link #setInitialSimResults(SimulationData)}
   *
   * @param plan plan to simulate, which will be updated in place with children and duration data
   * @param until target time point after which the simulation should stop
   * @return simulation results for all model resources up to the limit time point
   *
   * @throws SimulationException on simulation error, eg invalid activity args or model exception
   * @throws SchedulingInterruptedException on early halt triggered by the cancellation notifier
   */
  @Override
  public SimulationData simulateWithResults(
      final Plan plan, final Duration until)
    throws SimulationException, SchedulingInterruptedException
  {
    checkNotNull(plan);
    checkNotNull(until);
    return simulateWithResults(plan, until, this.missionModel.getResources().keySet());
  }

  /**
   * simulates until the specified stop time in a plan, partially updating child/duration data
   * <p>
   * collects results for all resources in the model immediately for return, possibly from the
   * cached initial simulation results provided to {@link #setInitialSimResults(SimulationData)}
   *
   * @param plan plan to simulate, which will be updated in place with children and duration data
   * @param until target time point after which the simulation should stop
   * @param resourceNames set of resources that should be collected into the return results
   * @return simulation results for at least the requested resources up to the limit time point
   *
   * @throws SimulationException on simulation error, eg invalid activity args or model exception
   * @throws SchedulingInterruptedException on early halt triggered by the cancellation notifier
   */
  @Override
  public SimulationData simulateWithResults(
      final Plan plan, final Duration until, final Set<String> resourceNames)
    throws SimulationException, SchedulingInterruptedException
  {
    checkNotNull(plan);
    checkNotNull(until);
    checkNotNull(resourceNames);

    //check if cached results are still relevant
    if(this.latestSimulationData==null && initialSimulationResults != null ) {
      final var initialSchedule = scheduleFromPlan(this.initialSimulationResults.plan(),this.schedulerModel);
      final var currentSchedule = scheduleFromPlan(plan,this.schedulerModel);
      if(initialSchedule.equals(currentSchedule)) {
        //plan is unchanged since initial, so can return cached data directly
        return this.initialSimulationResults;
      }
    }

    //otherwise fall through and compute new results
    final var resultsInput = simulateNoResults(plan,until);
    final var driverResults = resultsInput.simulationResultsComputerInputs().computeResults(resourceNames);
    this.latestSimulationData = new SimulationData(
        plan, driverResults,
        new SimulationResults(driverResults));
    return this.latestSimulationData;
  }

  /**
   * simulates until either the specified time, the target activity completes, or the end of the plan
   * <p>
   * the provided plan is updated in place with child activity and duration data. the simulation halts
   * at the target time or activity end (if any), and thus may not fully update all the children and
   * durations for other still ongoing or later activities.
   * <p>
   * does not actually generate the results at this time, instead returning a record with enough
   * data for the caller to calculate the results later
   *
   * @param plan plan to simulate, which will be updated in place with children and duration data
   * @param activity target activity that the simulation should stop after completing; if null,
   *     the simulation continues until another limit or the end of the plan is reached
   * @param until target time point after which the simulation should stop; if null,
   *     the simulation continues until another limit or the end of the plan is reached
   * @return input set needed to compute simulation results later
   *
   * @throws SimulationException on simulation error, eg invalid activity args or model exception
   * @throws SchedulingInterruptedException on early halt triggered by the cancellation notifier
   * @throws SimulationException on simulation error, eg invalid activity args or model exception
   * @throws SchedulingInterruptedException on early halt triggered by the cancellation notifier
   */
  private AugmentedSimulationResultsComputerInputs simulateNoResults(
      final Plan plan,
      @Nullable final Duration until,
      @Nullable final SchedulingActivity activity)
    throws SimulationException, SchedulingInterruptedException
  {
    checkNotNull(plan);
    checkArgument(until==null || !until.isNegative(),
                  "target time limit specified but is negative");
    checkArgument(activity==null || plan.getActivities().contains(activity),
                  "target activity specified but not found in given plan");
    if(canceledListener.get()) throw new SchedulingInterruptedException("simulation setup");

    //should also try to use preloaded initial results if the plan is unchanged (instead of only
    //checking that higher up at the simulateWithResults() level)

    //use time limit if specified, otherwise just the end of the plan
    final var simulationStartTime = this.planningHorizon.getStartInstant();
    //TODO: turn back on to limit simulation span (testing a dumber version that does whole plan every time)
    //final var simulationDuration = until!=null ? until : this.planningHorizon.getAerieHorizonDuration();
    final var simulationDuration = this.planningHorizon.getAerieHorizonDuration();

    //locate the best starting point driver (and internal engine)
    //(don't try-with-res AutoClosable SimDriver/SimEng since may come back to it again and again)
    final var driver = findBestDriverToStartFrom(plan);

    //might have checked if plan exactly matched the best driver/engine's current plan, but incremental
    //simulation will do a diff anyway, and then see zero diffs and be fast

    //call incremental simulation, which will derive a new engine based on prior one
    final var planSimCorrespondence = scheduleFromPlan(plan, this.schedulerModel);
    final var schedule = planSimCorrespondence.directiveIdActivityDirectiveMap();
    final Consumer<Duration> noopSimExtentConsumer= $->{}; //no progress bar in scheduler since it would jump around
    final var resourceManager = new InMemorySimulationResourceManager();
    //eventually want to pass down stopping condition re specific activity vs all acts
    try {
      driver.initSimulation(simulationDuration);
      driver.diffAndSimulate(
          schedule,
          simulationStartTime, simulationDuration,
          //same plan vs sim start/dur ok for now, but should distinguish if scheduling in just a window
          simulationStartTime, simulationDuration,
          true, // TODO -- don't compute all results; will calculate act timing data only below;
                                //         presently having to pass in true because resource info is somehow lost, maybe for old engines
          this.canceledListener,
          noopSimExtentConsumer,
          resourceManager);
    } catch (Exception e) {
      //re-wrap exceptions from simulation itself to clarify to scheduler re eg invalid plan
      throw new SimulationException("exception during plan simulation", e);
    }
    if(canceledListener.get()) throw new SchedulingInterruptedException("simulation cleanup");
    //compute just the activity timing needed out of simulation (not full results)
    final var activityResults = driver.getEngine().computeCombinedActivitySimulationResults(simulationStartTime);

    //update the input plan object to contain child activities and durations
    SimulationFacadeUtils.updatePlanWithChildActivities(
        activityResults, this.activityTypes, plan, this.planningHorizon);
    SimulationFacadeUtils.pullActivityDurationsIfNecessary(
        plan, planSimCorrespondence, activityResults);

    //package up args needed to compute resource results later
    final var resultsComputer = new SimulationResultsComputerInputs(
        driver.getEngine(),
        simulationStartTime,
        simulationDuration, //for now sim always goes to time limit (not stopping at specific act)
        SimulationEngine.defaultActivityTopic, //always the same static topic, not per engine
        missionModel.getTopics(),
        driver.getEngine().spanInfo.directiveIdToSpanId(),
        resourceManager);
    return new AugmentedSimulationResultsComputerInputs(resultsComputer, planSimCorrespondence);
  }

  /**
   * find the best driver (and engine) to start from in history of incremental engines
   * <p>
   * the goal of this search is to reduce the overall resimulation (plus search) time for a given plan.
   * at best, the current engine's already simulated plan will be an exact match to the requested plan.
   * intermediate, a similar prior plan may be a close match to start from.
   * at worst, a completely new engine will be allocated.
   * <p>
   * assumes that the simulation configuration has <b>not</b> changed since prior calls and thus is not
   * part of the cache lookup (valid if calls all made within same scheduling request and scheduler
   * is not playing with those configs during search, eg changing sampling periods)
   *
   * @param plan plan that we want to simulate, used to find a close match to an existing engine/driver
   *
   * @return a simulation driver (and engine) to use to incrementally simulate given plan, one which
   *         should reduce overall engine churn
   */
  private SimulationDriver<Model> findBestDriverToStartFrom(
      final Plan plan)
  {
    checkNotNull(plan);

    //typical use by current scheduler will just exact match the current plan or one prior, ie
    //doA+doB+doC or doA+undoA+doB patterns. more rarely it might jump way back after unwinding a series
    //of mods, eg doA+doB+undoA+undoB.
    //
    //in general plans along different hypothesis branches could converge to be similar enough that it
    //would be less simulation surgery work to start from a distant cousin engine in the tree, but
    //finding that cousin itself is a lot of work unless some clever distance metrics / prefix hashing
    //is used... overkill for now. not to mention the memory cost of keeping a tree of engines around
    //versus just a single chain
    //
    //with the current implementation of Driver/Engine it is hard to do much here since
    //1. the driver privately owns its engine, so we need to update ctors/init methods to allow passing it
    //   or accessors for all the data needed from the engine
    //2. the prior engine is closed during initSim, but we'd want to keep it live so that it can have
    //   future children along a different hypothesis branch (maybe closed is ok for this?)
    //3. the plan (ie directives) in the prior engine is deleted during its child diffAndSim() call,
    //   so we'd need to come up with a way to keep those or some good hash around to find a close match.
    //
    //so for now we just do incremental sims in a straight chain only using the single leaf tip, even if
    //the plan has arrived at a prior plan. hopefully the incremental speedups make this fast enough and
    //don't kill the memory use.
    if(this.driverEngineCache!=null) return this.driverEngineCache;

    //no suitable engine found so fallback to creating and caching a fresh one
    final var newDriver = new SimulationDriver<>(
        this.missionModel,
        this.planningHorizon.getStartInstant(),
        this.planningHorizon.getAerieHorizonDuration());
    this.driverEngineCache = newDriver;
    return newDriver;
  }

  @Override
  public Optional<SimulationData> getLatestSimulationData() {
    if (this.latestSimulationData == null)
      return Optional.ofNullable(this.initialSimulationResults);
    else
      return Optional.of(this.latestSimulationData);
  }

  /**
   * stores the drivers (and engines) that may be useful as starting points for simulation requests
   * <p>
   * it might be desirable to keep the engine cache around between separate scheduling requests too,
   * but in that case we would need to assure that other inputs also match up (eg sim config)
   * <p>
   * see notes in {@link #findBestDriverToStartFrom(Plan)}, but for now just one driver. in the future
   * this may be a container of several options with fast-access by plan similarity.
   */
  private SimulationDriver<Model> driverEngineCache;
}
