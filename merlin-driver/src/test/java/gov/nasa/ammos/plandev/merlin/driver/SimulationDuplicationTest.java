package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SimulationDuplicationTest {
  CachedEngineStore store;
  final private class InfiniteCapacityEngineStore implements CachedEngineStore{
    private Map<SimulationEngineConfiguration, List<CachedSimulationEngine>> store = new HashMap<>();
    @Override
    public void save(
        final CachedSimulationEngine cachedSimulationEngine,
        final SimulationEngineConfiguration configuration)
    {
      store.computeIfAbsent(configuration, conf -> new ArrayList<>());
      store.get(configuration).add(cachedSimulationEngine);
    }

    @Override
    public List<CachedSimulationEngine> getCachedEngines(final SimulationEngineConfiguration configuration) {
      return store.get(configuration);
    }

    public int capacity() {
      return Integer.MAX_VALUE;
    }
  }

  public static SimulationEngineConfiguration mockConfiguration(){
    return new SimulationEngineConfiguration(
        Map.of(),
        Instant.EPOCH,
        new MissionModelId(0)
    );
  }

  @BeforeEach
  void beforeEach(){
    this.store = new InfiniteCapacityEngineStore();
  }

  @Test
  void testDuplicate() {
    final var results = simulateWithCheckpoints(
        CachedSimulationEngine.empty(TestMissionModel.missionModel(), Instant.EPOCH),
        List.of(Duration.of(5, MINUTES)),
        store);
    final SimulationResults expected = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        Map.of(),
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        () -> false,
        $ -> {},
        new InMemorySimulationResourceManager());
    assertEquals(expected, results);
    final var newResults = simulateWithCheckpoints(store.getCachedEngines(mockConfiguration()).get(0), List.of(), store);
    assertEquals(expected, newResults);
  }

  static SimulationResults simulateWithCheckpoints(
      final CachedSimulationEngine cachedEngine,
      final List<Duration> desiredCheckpoints,
      final CachedEngineStore engineStore
  ) {
    return CheckpointSimulationDriver.simulateWithCheckpoints(
        TestMissionModel.missionModel(),
        Map.of(),
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        $ -> {},
        () -> false,
        cachedEngine,
        CheckpointSimulationDriver.desiredCheckpoints(desiredCheckpoints),
        CheckpointSimulationDriver.noCondition(),
        engineStore,
        mockConfiguration()
    ).computeResults();
  }
}
