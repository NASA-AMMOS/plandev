package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.driver.CachedSimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.SimulationUtility;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InMemoryCachedEngineStoreTest {
  private static final MissionModel<Mission> model = SimulationUtility.buildFooMissionModel();
  SimulationEngineConfiguration simulationEngineConfiguration;
  MissionModelId missionModelId;
  InMemoryCachedEngineStore store;

  @BeforeEach
  void beforeEach(){
    this.missionModelId = new MissionModelId(1);
    this.simulationEngineConfiguration = new SimulationEngineConfiguration(Map.of(), Instant.EPOCH, this.missionModelId);
    this.store = new InMemoryCachedEngineStore(2);
  }

  @AfterEach
  void afterEach() {
    this.store.close();
  }

  public static CachedSimulationEngine getCachedEngine1(){
    return new CachedSimulationEngine(
        Duration.SECOND,
        Map.of(
            new ActivityDirectiveId(1), new ActivityDirective(Duration.HOUR, "ActivityType1", Map.of(), null, true),
            new ActivityDirectiveId(2), new ActivityDirective(Duration.HOUR, "ActivityType2", Map.of(), null, true)
        ),
        new SimulationEngine(Instant.EPOCH,model,null),
        null,
        model,
        new InMemorySimulationResourceManager()
    );
  }

  public static CachedSimulationEngine getCachedEngine2(){
    return new CachedSimulationEngine(
        Duration.SECOND,
        Map.of(
            new ActivityDirectiveId(3), new ActivityDirective(Duration.HOUR, "ActivityType3", Map.of(), null, true),
            new ActivityDirectiveId(4), new ActivityDirective(Duration.HOUR, "ActivityType4", Map.of(), null, true)
        ),
        new SimulationEngine(Instant.EPOCH,model,null),
        null,
        model,
        new InMemorySimulationResourceManager()
    );
  }

  public static CachedSimulationEngine getCachedEngine3(){
    return new CachedSimulationEngine(
        Duration.SECOND,
        Map.of(
            new ActivityDirectiveId(5), new ActivityDirective(Duration.HOUR, "ActivityType5", Map.of(), null, true),
            new ActivityDirectiveId(6), new ActivityDirective(Duration.HOUR, "ActivityType6", Map.of(), null, true)
        ),
        new SimulationEngine(Instant.EPOCH,model,null),
        null,
        model,
        new InMemorySimulationResourceManager()
    );
  }

  @Test
  public void duplicateTest(){
    final var store = new InMemoryCachedEngineStore(2);
    store.save(CachedSimulationEngine.empty(model, this.simulationEngineConfiguration.simStartTime()), this.simulationEngineConfiguration);
    store.save(CachedSimulationEngine.empty(model, this.simulationEngineConfiguration.simStartTime()), this.simulationEngineConfiguration);
    store.save(CachedSimulationEngine.empty(model, this.simulationEngineConfiguration.simStartTime()), this.simulationEngineConfiguration);
    assertEquals(1, store.getCachedEngines(this.simulationEngineConfiguration).size());
  }

  @Test
  public void order(){
    final var cachedEngine1 = getCachedEngine1();
    final var cachedEngine2 = getCachedEngine2();
    final var cachedEngine3 = getCachedEngine3();
    store.save(cachedEngine1, this.simulationEngineConfiguration);
    store.save(cachedEngine2, this.simulationEngineConfiguration);
    final var cachedBeforeRegister = store.getCachedEngines(this.simulationEngineConfiguration);
    // no engines have been used, so the cache is ordered in descending creation date
    assertEquals(cachedBeforeRegister.get(0).activityDirectives(), cachedEngine1.activityDirectives());
    assertEquals(cachedBeforeRegister.get(1).activityDirectives(), cachedEngine2.activityDirectives());
    //engine2 has been used so it goes first in the list
    store.registerUsed(cachedEngine2);
    final var cachedAfterRegister = store.getCachedEngines(this.simulationEngineConfiguration);
    assertEquals(cachedAfterRegister.get(0).activityDirectives(), cachedEngine2.activityDirectives());
    assertEquals(cachedAfterRegister.get(1).activityDirectives(), cachedEngine1.activityDirectives());
    store.save(cachedEngine3, this.simulationEngineConfiguration);
    //to store cachedEngine3, we had to remove the last element of the list, engine 1 and the order is still most recently used
    final var cachedAfterRemoveLast = store.getCachedEngines(this.simulationEngineConfiguration);
    assertEquals(cachedAfterRemoveLast.get(0).activityDirectives(), cachedEngine2.activityDirectives());
    assertEquals(cachedAfterRemoveLast.get(1).activityDirectives(), cachedEngine3.activityDirectives());
  }
}
