package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.merlin.driver.*;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.apache.commons.collections4.map.ListOrderedMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryCachedEngineStore implements AutoCloseable, CachedEngineStore {
  private record CachedEngineMetadata(
      SimulationEngineConfiguration configuration,
      Instant creationDate){}

  private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryCachedEngineStore.class);
  private final ListOrderedMap<CachedSimulationEngine, CachedEngineMetadata> cachedEngines;
  private final int capacity;
  private Duration savedSimulationTime;

  /**
   *
   * @param capacity the maximum number of engines that can be stored in memory
   */
  public InMemoryCachedEngineStore(final int capacity) {
    if(capacity <= 0) throw new IllegalArgumentException("Capacity of the cached engine store must be greater than 0");
    this.cachedEngines = new ListOrderedMap<>();
    this.capacity = capacity;
    this.savedSimulationTime = Duration.ZERO;
  }

  public Duration getTotalSavedSimulationTime(){
    return savedSimulationTime;
  }

  @Override
  public void close() {
    cachedEngines.forEach((cachedEngine, metadata) -> cachedEngine.simulationEngine().close());
    cachedEngines.clear();
  }

  /**
   * Register a re-use for a saved cached simulation engine. Will decrease likelihood of this engine being deleted.
   * @param cachedSimulationEngine the simulation engine
   */
  public void registerUsed(final CachedSimulationEngine cachedSimulationEngine){
    final var engineMetadata = this.cachedEngines.remove(cachedSimulationEngine);
    if(engineMetadata != null){
      this.cachedEngines.put(0, cachedSimulationEngine, engineMetadata);
      this.savedSimulationTime = this.savedSimulationTime.plus(cachedSimulationEngine.endsAt());
    }
  }

  public void save(
      final CachedSimulationEngine engine,
      final SimulationEngineConfiguration configuration) {
    if (shouldWeSave(engine, configuration)) {
      if (cachedEngines.size() + 1 > capacity) {
        removeLast();
      }
      final var metadata = new CachedEngineMetadata(configuration, Instant.now());
      cachedEngines.put(cachedEngines.size(), engine, metadata);
      LOGGER.info("Added a cached simulation engine to the store. Current occupation ratio: " + cachedEngines.size() + "/" + this.capacity);
    }
  }

  @Override
  public int capacity(){
    return capacity;
  }

  public List<CachedSimulationEngine> getCachedEngines(
      final SimulationEngineConfiguration configuration){
    return cachedEngines
        .entrySet()
        .stream()
        .filter(ce -> configuration.equals(ce.getValue().configuration))
        .map(Map.Entry::getKey)
        .toList();
  }

  public Optional<MissionModel<?>> getMissionModel(
      final Map<String, SerializedValue> configuration,
      final Instant simulationStartTime){
    for(final var entry: cachedEngines.entrySet()){
      if(entry.getValue().configuration.simulationConfiguration().equals(configuration) &&
         entry.getValue().configuration.simStartTime().equals(simulationStartTime)){
        return Optional.of(entry.getKey().missionModel());
      }
    }
    return Optional.empty();
  }

  private boolean shouldWeSave(final CachedSimulationEngine engine,
                               final SimulationEngineConfiguration configuration){
    //avoid duplicates
    for(final var cached: cachedEngines.entrySet()){
      final var savedEngine = cached.getKey();
      final var metadata = cached.getValue();
      if(engine.endsAt().equals(savedEngine.endsAt()) &&
         engine.activityDirectives().equals(savedEngine.activityDirectives()) &&
         metadata.configuration.equals(configuration)){
        return false;
      }
    }
    return true;
  }

  /**
   * Least-recently-used removal policy
   */
  private void removeLast(){
    LOGGER.info("Cleaning cached simulation engine from the store");
    this.cachedEngines.remove(this.cachedEngines.size() - 1);
  }
}
