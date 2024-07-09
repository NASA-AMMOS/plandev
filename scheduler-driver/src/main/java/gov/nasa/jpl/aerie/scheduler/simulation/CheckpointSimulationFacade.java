package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.merlin.driver.ActivityDirective;
import gov.nasa.jpl.aerie.merlin.driver.ActivityDirectiveId;
import gov.nasa.jpl.aerie.merlin.driver.CachedSimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.CheckpointSimulationDriver;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelId;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResultsComputerInputs;
import gov.nasa.jpl.aerie.merlin.framework.ThreadedTask;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static gov.nasa.jpl.aerie.merlin.driver.CheckpointSimulationDriver.onceAllActivitiesAreFinished;
import static gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacadeUtils.scheduleFromPlan;
import static gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacadeUtils.updatePlanWithChildActivities;

public class CheckpointSimulationFacade implements SimulationFacade {
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckpointSimulationFacade.class);
  private final MissionModel<?> missionModel;
  private final InMemoryCachedEngineStore cachedEngines;
  private final PlanningHorizon planningHorizon;
  private final Map<String, ActivityType> activityTypes;
  private final SimulationEngineConfiguration configuration;
  private SimulationData initialSimulationResults;
  private final Supplier<Boolean> canceledListener;
  private final SchedulerModel schedulerModel;
  private Duration totalSimulationTime = Duration.ZERO;
  private SimulationData latestSimulationData;

  /**
   * Loads initial simulation results into the simulation. They will be served until initialSimulationResultsAreStale()
   * is called.
   * @param simulationData the initial simulation results
   */
  @Override
  public void setInitialSimResults(final SimulationData simulationData) {
    this.initialSimulationResults = simulationData;
  }


  public CheckpointSimulationFacade(
      final MissionModel<?> missionModel,
      final SchedulerModel schedulerModel,
      final InMemoryCachedEngineStore cachedEngines,
      final PlanningHorizon planningHorizon,
      final SimulationEngineConfiguration simulationEngineConfiguration,
      final Supplier<Boolean> canceledListener)
  {
    if (cachedEngines.capacity() > 1) ThreadedTask.CACHE_READS = true;
    this.missionModel = missionModel;
    this.schedulerModel = schedulerModel;
    this.cachedEngines = cachedEngines;
    this.planningHorizon = planningHorizon;
    this.activityTypes = new HashMap<>();
    this.configuration = simulationEngineConfiguration;
    this.canceledListener = canceledListener;
    this.latestSimulationData = null;
  }

  public CheckpointSimulationFacade(
      final PlanningHorizon planningHorizon,
      final MissionModel<?> missionModel,
      final SchedulerModel schedulerModel
  ) {
    this(
        missionModel,
        schedulerModel,
        new InMemoryCachedEngineStore(1),
        planningHorizon,
        new SimulationEngineConfiguration(Map.of(), Instant.now(), new MissionModelId(1)),
        () -> false
    );
  }

  /**
   * Returns the total simulated time
   * @return
   */
  @Override
  public Duration totalSimulationTime(){
    return totalSimulationTime;
  }

  @Override
  public Supplier<Boolean> getCanceledListener() {
    return this.canceledListener;
  }

  @Override
  public void addActivityTypes(final Collection<ActivityType> activityTypes) {
    activityTypes.forEach(at -> this.activityTypes.put(at.getName(), at));
  }

  private void replaceIds(
      final PlanSimCorrespondence planSimCorrespondence,
      final Map<ActivityDirectiveId, ActivityDirectiveId> updates){
    for(final var replacements : updates.entrySet()){
      if(planSimCorrespondence.directiveIdActivityDirectiveMap().containsKey(replacements.getKey())){
        final var value = planSimCorrespondence.directiveIdActivityDirectiveMap().remove(replacements.getKey());
        planSimCorrespondence.directiveIdActivityDirectiveMap().put(replacements.getValue(), value);
      }
      //replace the anchor ids in the plan
      final var replacementMap = new HashMap<ActivityDirectiveId, ActivityDirective>();
      for (final var act : planSimCorrespondence.directiveIdActivityDirectiveMap().entrySet()) {
        if (act.getValue().anchorId() != null && act.getValue().anchorId().equals(replacements.getKey())) {
          final var replacementActivity = new ActivityDirective(
              act.getValue().startOffset(),
              act.getValue().serializedActivity(),
              replacements.getValue(),
              act.getValue().anchoredToStart());
          replacementMap.put(act.getKey(), replacementActivity);
        }
      }
      for (final var replacement : replacementMap.entrySet()) {
        planSimCorrespondence.directiveIdActivityDirectiveMap().remove(replacement.getKey());
        planSimCorrespondence.directiveIdActivityDirectiveMap().put(replacement.getKey(), replacement.getValue());
      }
    }
  }

  /**
   * Simulates until the end of the last activity of a plan. Updates the input plan with child activities and activity durations.
   * @param plan the plan to simulate
   * @return the inputs needed to compute simulation results
   * @throws SimulationException if an exception happens during simulation
   */
  @Override
  public SimulationResultsComputerInputs simulateNoResultsAllActivities(final Plan plan)
  throws SimulationException, SchedulingInterruptedException
  {
    return simulateNoResults(plan, null, null).simulationResultsComputerInputs();
  }

  /**
   * Simulates a plan until the end of one of its activities
   * Do not use to update the plan as decomposing activities may not finish
   * @param plan
   * @param activity
   * @return
   * @throws SimulationException
   */
  @Override
  public SimulationResultsComputerInputs simulateNoResultsUntilEndAct(
      final Plan plan,
      final SchedulingActivity activity)
  throws SimulationException, SchedulingInterruptedException {
    return simulateNoResults(plan, null, activity).simulationResultsComputerInputs();
  }

  public AugmentedSimulationResultsComputerInputs simulateNoResults(final Plan plan, final Duration until)
  throws SimulationException, SchedulingInterruptedException {
    return simulateNoResults(plan, until, null);
  }


  /**
   * Simulates and updates plan
   * @param plan
   * @param until can be null
   * @param activity can be null
   */
  private AugmentedSimulationResultsComputerInputs simulateNoResults(
      final Plan plan,
      final Duration until,
      final SchedulingActivity activity)
  throws SimulationException, SchedulingInterruptedException {
    final var planSimCorrespondence = scheduleFromPlan(plan, this.schedulerModel);

    final var best = CheckpointSimulationDriver.bestCachedEngine(
        planSimCorrespondence.directiveIdActivityDirectiveMap(),
        cachedEngines.getCachedEngines(configuration),
        planningHorizon.getEndAerie());
    CachedSimulationEngine engine = null;
    Duration from = Duration.ZERO;
    if (best.isPresent()) {
      engine = best.get().getKey();
      replaceIds(planSimCorrespondence, best.get().getRight());
      from = engine.endsAt();
    }

    //Configuration
    //Three modes : (1) until a specific end time (2) until end of one specific activity (3) until end of last activity in plan
    Duration simulationDuration;
    Function<CheckpointSimulationDriver.SimulationState, Boolean>
        stoppingCondition;
    //(1)
    if (until != null && activity == null) {
      simulationDuration = until;
      stoppingCondition = CheckpointSimulationDriver.noCondition();
      LOGGER.info("Simulation mode: until specific time " + simulationDuration);
    }
    //(2)
    else if (activity != null && until == null) {
      simulationDuration = planningHorizon.getEndAerie();
      stoppingCondition = CheckpointSimulationDriver.stopOnceActivityHasFinished(
          activity.id());
      LOGGER.info("Simulation mode: until activity ends " + activity);
    }
    //(3)
    else if (activity == null && until == null) {
      simulationDuration = planningHorizon.getEndAerie();
      stoppingCondition = CheckpointSimulationDriver.onceAllActivitiesAreFinished();
      LOGGER.info("Simulation mode: until all activities end ");
    } else {
      throw new SimulationException("Bad configuration", null);
    }

    if (engine == null) engine = CachedSimulationEngine.empty(missionModel, planningHorizon.getStartInstant());

    Function<CheckpointSimulationDriver.SimulationState, Boolean> checkpointPolicy =
        new ResourceAwareSpreadCheckpointPolicy(
            cachedEngines.capacity(),
            Duration.ZERO,
            planningHorizon.getEndAerie(),
            Duration.max(engine.endsAt(), Duration.ZERO),
            simulationDuration,
            1,
            true);

    if (stoppingCondition.equals(CheckpointSimulationDriver.onceAllActivitiesAreFinished())) {
      checkpointPolicy = or(checkpointPolicy, onceAllActivitiesAreFinished());
    }

    if (best.isPresent()) cachedEngines.registerUsed(engine);
    try {
      final var simulation = CheckpointSimulationDriver.simulateWithCheckpoints(
          missionModel,
          planSimCorrespondence.directiveIdActivityDirectiveMap(),
          planningHorizon.getStartInstant(),
          simulationDuration,
          planningHorizon.getStartInstant(),
          planningHorizon.getEndAerie(),
          $ -> {},
          canceledListener,
          engine,
          checkpointPolicy,
          stoppingCondition,
          cachedEngines,
          configuration
      );
      this.totalSimulationTime = this.totalSimulationTime.plus(simulation.engine().getElapsedTime().minus(from));
      if (canceledListener.get()) throw new SchedulingInterruptedException("simulating");
      final var activityResults = simulation.computeActivitySimulationResults();

      updatePlanWithChildActivities(
          activityResults,
          activityTypes,
          plan,
          planningHorizon
      );

      SimulationFacadeUtils.pullActivityDurationsIfNecessary(
          plan,
          planSimCorrespondence,
          activityResults
      );
      //plan has been updated
      return new AugmentedSimulationResultsComputerInputs(simulation, planSimCorrespondence);
    } catch (SchedulingInterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new SimulationException("An exception happened during simulation", e);
    }
  }

  @SafeVarargs
  private static Function<CheckpointSimulationDriver.SimulationState, Boolean> or(
      final Function<CheckpointSimulationDriver.SimulationState, Boolean>... functions)
  {
    return (simulationState) -> {
      for (final var function : functions) {
        if (function.apply(simulationState)) {
          return true;
        }
      }
      return false;
    };
  }


  @Override
  public SimulationData simulateWithResults(final Plan plan, final Duration until)
  throws SimulationException, SchedulingInterruptedException
  {
    return simulateWithResults(plan, until, missionModel.getResources().keySet());
  }

  @Override
  public SimulationData simulateWithResults(
      final Plan plan,
      final Duration until,
      final Set<String> resourceNames
  ) throws SimulationException, SchedulingInterruptedException
  {
    if (this.initialSimulationResults != null) {
      final var inputPlan = scheduleFromPlan(plan, schedulerModel);
      final var initialPlanA = scheduleFromPlan(this.initialSimulationResults.plan(), schedulerModel);
      if (initialPlanA.equals(inputPlan)) {
        return initialSimulationResults;
      }
    }
    final var resultsInput = simulateNoResults(plan, until);
    final var driverResults = resultsInput.simulationResultsComputerInputs().computeResults(resourceNames);
    this.latestSimulationData = new SimulationData(
        plan,
        driverResults,
        SimulationResultsConverter.convertToConstraintModelResults(driverResults)
    );
    return this.latestSimulationData;
  }

  @Override
  public Optional<SimulationData> getLatestSimulationData() {
    if (this.latestSimulationData == null)
      return Optional.ofNullable(this.initialSimulationResults);
    else
      return Optional.ofNullable(this.latestSimulationData);
  }
}
