package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.banananation.Configuration;
import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelBuilder;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.types.MissionModelId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public final class SimulationUtility {

  private static MissionModel<?> makeMissionModel(final MissionModelBuilder builder, final Configuration config) {
    final var factory = new gov.nasa.jpl.aerie.banananation.generated.GeneratedModelType();
    final var registry = DirectiveTypeRegistry.extract(factory);
    final var model = factory.instantiate(Instant.EPOCH, config, builder);
    return builder.build(model, registry);
  }

  public static MissionModel<Mission>
  getFooMissionModel() {
    final var config = new gov.nasa.jpl.aerie.foomissionmodel.Configuration();
    final var factory = new gov.nasa.jpl.aerie.foomissionmodel.generated.GeneratedModelType();
    final var registry = DirectiveTypeRegistry.extract(factory);
    final var builder = new MissionModelBuilder();
    final var model = factory.instantiate(Instant.EPOCH, config, builder);
    return builder.build(model, registry);
  }

  public static Problem buildProblemFromFoo(final PlanningHorizon planningHorizon) {
    return buildProblemFromFoo(planningHorizon, 1);
  }

  public static Problem buildProblemFromFoo(final PlanningHorizon planningHorizon, final int simulationCacheSize){
    final var fooMissionModel = SimulationUtility.getFooMissionModel();
    final var fooSchedulerModel = SimulationUtility.getFooSchedulerModel();
    return new Problem(
        fooMissionModel,
        planningHorizon,
        new CheckpointSimulationFacade(
            fooMissionModel,
            fooSchedulerModel,
            new InMemoryCachedEngineStore(simulationCacheSize),
            planningHorizon,
            new SimulationEngineConfiguration(
                Map.of(),
                Instant.EPOCH,
                new MissionModelId(1)),
            () -> false),
        fooSchedulerModel);
  }

  public static Problem buildProblemFromBanana(final PlanningHorizon planningHorizon){
    final var bananaMissionModel = SimulationUtility.getBananaMissionModel();
    final var bananaSchedulerModel = SimulationUtility.getBananaSchedulerModel();
    return new Problem(
        bananaMissionModel,
        planningHorizon,
        new CheckpointSimulationFacade(
            bananaMissionModel,
            bananaSchedulerModel,
            new InMemoryCachedEngineStore(15),
            planningHorizon,
            new SimulationEngineConfiguration(
                Map.of(),
                Instant.EPOCH,
                new MissionModelId(1)),
            ()->false),
        bananaSchedulerModel);
  }

  public static SchedulerModel getFooSchedulerModel(){
    return new gov.nasa.jpl.aerie.foomissionmodel.generated.GeneratedSchedulerModel();
  }

  public static MissionModel<?> getBananaMissionModel(){
    final var config = new Configuration(Configuration.DEFAULT_PLANT_COUNT, Configuration.DEFAULT_PRODUCER, Path.of("/etc/hosts"), Configuration.DEFAULT_INITIAL_CONDITIONS);
    return makeMissionModel(new MissionModelBuilder(), config);
  }

  public static SchedulerModel getBananaSchedulerModel(){
    return new gov.nasa.jpl.aerie.banananation.generated.GeneratedSchedulerModel();
  }
}
