package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelBuilder;
import gov.nasa.jpl.aerie.merlin.driver.timeline.TemporalEventSource;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.IncrementalSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.SchedulerSimulationReuseStrategy;
import gov.nasa.jpl.aerie.types.MissionModelId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * utility factory methods used to set up fixtures for testing the scheduler
 */
public final class SimulationUtility {

  /**
   * choose which kind of simulation to use in the scheduler tests
   * <p>
   * just one at a time for now; could upgrade to vary and run tests with each
   */
  public static final SchedulerSimulationReuseStrategy SIM_REUSE_STRATEGY = SchedulerSimulationReuseStrategy.Incremental;

  /**
   * creates a new problem description for testing using the default foo model
   *
   * @param planningHorizon horizon the scheduler will plan within
   * @return a new problem description for testing using the default foo model
   */
  public static Problem buildFooProblem(final PlanningHorizon planningHorizon) {
    return buildFooProblemWithCacheSize(planningHorizon, 1);
  }

  /**
   * creates a new problem description for testing using the default foo model
   *
   * @param planningHorizon horizon the scheduler will plan within
   * @param simulationCacheSize maximum number of cached engines the facade may store; 1 means no cache
   * @return a new problem description for testing using the default foo model
   */
  public static Problem buildFooProblemWithCacheSize(
      final PlanningHorizon planningHorizon,
      final int simulationCacheSize){
    TemporalEventSource.freezable  = !TemporalEventSource.neverfreezable;
    final var fooMissionModel = SimulationUtility.buildFooMissionModel();
    final var fooSchedulerModel = SimulationUtility.buildFooSchedulerModel();
    TemporalEventSource.freezable  = TemporalEventSource.alwaysfreezable;
    return new Problem(
        fooMissionModel,
        planningHorizon,
        buildFacadeWithCacheSize(
            planningHorizon,
            fooMissionModel,fooSchedulerModel, //use same model objs
            simulationCacheSize),
        fooSchedulerModel);
  }

  /**
   * creates a new problem description for testing using the default banana nation model
   *
   * @param planningHorizon horizon the scheduler will plan within
   * @return a new problem description for testing using the default banana nation model
   */
  public static Problem buildBananaProblem(final PlanningHorizon planningHorizon){
    final var bananaMissionModel = SimulationUtility.buildBananaMissionModel();
    final var bananaSchedulerModel = SimulationUtility.buildBananaSchedulerModel();
    return new Problem(
        bananaMissionModel,
        planningHorizon,
        buildFacade(planningHorizon,bananaMissionModel,bananaSchedulerModel), //use same model objs
        bananaSchedulerModel);
  }

  /**
   * creates a new simulation facade for testing using the provided models
   *
   * @param planningHorizon horizon the scheduler will plan within
   * @param missionModel the mission simulation model the scheduler will use
   * @param schedulerModel extra information for the scheduler eg duration types
   * @return a new simulation facade for testing using the provided models
   * @param <Model> the mission model the facade can simulate
   */
  public static <Model> SimulationFacade buildFacade(
      final PlanningHorizon planningHorizon,
      final MissionModel<Model> missionModel,
      final SchedulerModel schedulerModel) {
    return buildFacadeWithCacheSize(planningHorizon,missionModel,schedulerModel,1);
  }

  /**
   * creates a new simulation facade for testing using the provided models and max cache size
   * <p>
   * some facade types may not support caching at all, in which case the cache size argument is ignored
   *
   * @param planningHorizon horizon the scheduler will plan within
   * @param missionModel the mission simulation model the scheduler will use
   * @param schedulerModel extra information for the scheduler eg duration types
   * @param simulationCacheSize maximum number of cached engines the facade may store; 1 means no cache
   * @return a new simulation facade for testing using the provided models
   * @param <Model> the mission model the facade can simulate
   */
  public static <Model> SimulationFacade buildFacadeWithCacheSize(
      final PlanningHorizon planningHorizon,
      final MissionModel<Model> missionModel,
      final SchedulerModel schedulerModel,
      final int simulationCacheSize) {
    TemporalEventSource.freezable  = !TemporalEventSource.neverfreezable;
    var facade = switch (SIM_REUSE_STRATEGY) {
      case Incremental -> new IncrementalSimulationFacade<>(
          missionModel, schedulerModel, planningHorizon, ()->false);
      case Checkpoint -> new CheckpointSimulationFacade(
          missionModel,
          schedulerModel,
          new InMemoryCachedEngineStore(simulationCacheSize),
          planningHorizon,
          new SimulationEngineConfiguration(
              Map.of(),
              Instant.EPOCH,
              new MissionModelId(1)),
          () -> false);
      };
    TemporalEventSource.freezable  = TemporalEventSource.alwaysfreezable;
    return facade;
  }

  /**
   * creates a new instance of the foo scheduler model
   * @return a new instance of the foo scheduler model
   */
  public static SchedulerModel buildFooSchedulerModel(){
    return new gov.nasa.jpl.aerie.foomissionmodel.generated.GeneratedSchedulerModel();
  }

  /**
   * creates a new instance of the banana scheduler model
   * @return a new instance of the banana scheduler model
   */
  public static SchedulerModel buildBananaSchedulerModel(){
    return new gov.nasa.jpl.aerie.banananation.generated.GeneratedSchedulerModel();
  }

  /**
   * creates a new instance of the foo mission model with default configuration
   * @return a new instance of the foo mission model with default configuration
   */
  public static MissionModel<gov.nasa.jpl.aerie.foomissionmodel.Mission> buildFooMissionModel() {
    final var config = new gov.nasa.jpl.aerie.foomissionmodel.Configuration();
    final var factory = new gov.nasa.jpl.aerie.foomissionmodel.generated.GeneratedModelType();
    final var registry = DirectiveTypeRegistry.extract(factory);
    final var builder = new MissionModelBuilder();
    final var model = factory.instantiate(Instant.EPOCH, config, builder);
    return builder.build(model, registry);
  }

  /**
   * creates a new instance of the banana mission model with mostly default configuration
   * <p>
   * for unknown reason the path config was specifically set to "/etc/hosts" instead of the default
   *
   * @return a new instance of the banana mission model with mostly default configuration
   */
  public static MissionModel<gov.nasa.jpl.aerie.banananation.Mission> buildBananaMissionModel() {
    final var config = new gov.nasa.jpl.aerie.banananation.Configuration(
        gov.nasa.jpl.aerie.banananation.Configuration.DEFAULT_PLANT_COUNT,
        gov.nasa.jpl.aerie.banananation.Configuration.DEFAULT_PRODUCER,
        Path.of("/etc/hosts"),
        gov.nasa.jpl.aerie.banananation.Configuration.DEFAULT_INITIAL_CONDITIONS,
        false);
    final var factory = new gov.nasa.jpl.aerie.banananation.generated.GeneratedModelType();
    final var registry = DirectiveTypeRegistry.extract(factory);
    final var builder = new MissionModelBuilder();
    final var model = factory.instantiate(Instant.EPOCH, config, builder);
    return builder.build(model, registry);
  }

}
