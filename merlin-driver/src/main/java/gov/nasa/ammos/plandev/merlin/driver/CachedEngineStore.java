package gov.nasa.ammos.plandev.merlin.driver;

import java.util.List;

public interface CachedEngineStore {
  void save(final CachedSimulationEngine cachedSimulationEngine,
            final SimulationEngineConfiguration configuration);
  List<CachedSimulationEngine> getCachedEngines(
      final SimulationEngineConfiguration configuration);

  int capacity();
}
