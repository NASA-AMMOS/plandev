package gov.nasa.jpl.aerie.foomissionmodel;

import gov.nasa.jpl.aerie.foomissionmodel.generated.GeneratedModelType;
import gov.nasa.jpl.aerie.merlin.driver.CachedEngineStore;
import gov.nasa.jpl.aerie.merlin.driver.CachedSimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.CheckpointSimulationDriver;
import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelBuilder;
import gov.nasa.jpl.aerie.merlin.driver.SimulationDriver;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.framework.ThreadedTask;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTE;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FooSimulationDuplicationTest {
  CachedEngineStore store;
  final private class InfiniteCapacityEngineStore implements CachedEngineStore{
    private final Map<SimulationEngineConfiguration, List<CachedSimulationEngine>> store = new HashMap<>();
    @Override
    public void save(
        final CachedSimulationEngine cachedSimulationEngine,
        final SimulationEngineConfiguration configuration) {
      store.computeIfAbsent(configuration, conf -> new ArrayList<>());
      store.get(configuration).add(cachedSimulationEngine);
    }

    @Override
    public List<CachedSimulationEngine> getCachedEngines(final SimulationEngineConfiguration configuration) {
      return store.get(configuration);
    }

    @Override
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

  @BeforeAll
  static void beforeAll() {
    ThreadedTask.CACHE_READS = true;
  }

  private static MissionModel<Mission> makeMissionModel(final MissionModelBuilder builder, final Instant planStart, final Configuration config) {
    final var factory = new GeneratedModelType();
    final var registry = DirectiveTypeRegistry.extract(factory);
    final var model = factory.instantiate(planStart, config, builder);
    return builder.build(model, registry);
  }

  @Test
  void testCompareCheckpointOnEmptyPlan() {
    final MissionModel<Mission> missionModel = makeMissionModel(
        new MissionModelBuilder(),
        Instant.EPOCH,
        new Configuration());
    final var results = simulateWithCheckpoints(
        missionModel,
        List.of(Duration.of(5, MINUTES)),
        Map.of(),
        store,
        mockConfiguration()
    );
    final SimulationResults expected = SimulationDriver.simulate(
        missionModel,
        Map.of(),
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        () -> false);
    assertResultsEqual(expected, results);
  }

  @Test
  void testFooNonEmptyPlan() {
    final MissionModel<Mission> missionModel = makeMissionModel(
        new MissionModelBuilder(),
        Instant.EPOCH,
        new Configuration());
    final Map<ActivityDirectiveId, ActivityDirective> schedule = Map.ofEntries(
        activityFrom(1, MINUTE, "foo", Map.of("z", SerializedValue.of(123))),
        activityFrom(7, MINUTES, "foo", Map.of("z", SerializedValue.of(999)))
    );
    final var results = simulateWithCheckpoints(
        missionModel,
        List.of(Duration.of(5, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );
    final SimulationResults expected = SimulationDriver.simulate(
        missionModel,
        schedule,
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        () -> false);
    assertResultsEqual(expected, results);

    assertEquals(Duration.of(5, MINUTES), store.getCachedEngines(mockConfiguration()).getFirst().endsAt());

    final var results2 = simulateWithCheckpoints(
        missionModel,
        store.getCachedEngines(mockConfiguration()).get(0),
        List.of(Duration.of(5, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );

    assertResultsEqual(expected, results2);
  }

  @Test
  void testFooNonEmptyPlanMultipleResumes() {
    final MissionModel<Mission> missionModel = makeMissionModel(
        new MissionModelBuilder(),
        Instant.EPOCH,
        new Configuration());
    final Map<ActivityDirectiveId, ActivityDirective> schedule = Map.ofEntries(
        activityFrom(1, MINUTE, "foo", Map.of("z", SerializedValue.of(123))),
        activityFrom(7, MINUTES, "foo", Map.of("z", SerializedValue.of(999)))
    );
    final var results = simulateWithCheckpoints(
        missionModel,
        List.of(Duration.of(5, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );
    final SimulationResults expected = SimulationDriver.simulate(
        missionModel,
        schedule,
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        () -> false);
    assertResultsEqual(expected, results);

    assertEquals(Duration.of(5, MINUTES), store.getCachedEngines(mockConfiguration()).getFirst().endsAt());

    final var results2 = simulateWithCheckpoints(
        missionModel,
        store.getCachedEngines(mockConfiguration()).getFirst(),
        List.of(Duration.of(5, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );

    assertResultsEqual(expected, results2);

    final var results3 = simulateWithCheckpoints(
        missionModel,
        store.getCachedEngines(mockConfiguration()).getFirst(),
        List.of(Duration.of(5, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );

    assertResultsEqual(expected, results3);
  }

  @Test
  void testFooNonEmptyPlanMultipleCheckpointsMultipleResumes() {
    final MissionModel<Mission> missionModel = makeMissionModel(
        new MissionModelBuilder(),
        Instant.EPOCH,
        new Configuration());
    final Map<ActivityDirectiveId, ActivityDirective> schedule = Map.ofEntries(
        activityFrom(1, MINUTE, "foo", Map.of("z", SerializedValue.of(123))),
        activityFrom(7, MINUTES, "foo", Map.of("z", SerializedValue.of(999)))
    );
    final var results = simulateWithCheckpoints(
        missionModel,
        List.of(Duration.of(5, MINUTES), Duration.of(6, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );
    final SimulationResults expected = SimulationDriver.simulate(
        missionModel,
        schedule,
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        () -> false);
    assertResultsEqual(expected, results);

    assertEquals(Duration.of(5, MINUTES), store.getCachedEngines(mockConfiguration()).getFirst().endsAt());

    final var results2 = simulateWithCheckpoints(
        missionModel,
        store.getCachedEngines(mockConfiguration()).getFirst(),
        List.of(Duration.of(5, MINUTES), Duration.of(6, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );

    assertResultsEqual(expected, results2);

    final var results3 = simulateWithCheckpoints(
        missionModel,
        store.getCachedEngines(mockConfiguration()).get(1),
        List.of(Duration.of(5, MINUTES), Duration.of(6, MINUTES)),
        schedule,
        store,
        mockConfiguration()
    );

    assertResultsEqual(expected, results3);
  }

  @Test
  void testFooNonEmptyPlanMultipleCheckpointsMultipleResumesWithEdits() {
    final MissionModel<Mission> missionModel = makeMissionModel(
        new MissionModelBuilder(),
        Instant.EPOCH,
        new Configuration());
    final Pair<ActivityDirectiveId, ActivityDirective> activity1 = activityFrom(
        1,
        MINUTE,
        "foo",
        Map.of("z", SerializedValue.of(123)));
    final Map<ActivityDirectiveId, ActivityDirective> schedule1 = Map.ofEntries(
        activity1,
        activityFrom(7, MINUTES, "foo", Map.of("z", SerializedValue.of(999)))
    );
    final Map<ActivityDirectiveId, ActivityDirective> schedule2 = Map.ofEntries(
        activity1,
        activityFrom(390, SECONDS, "foo", Map.of("z", SerializedValue.of(999)))
    );
    final var results = simulateWithCheckpoints(
        missionModel,
        List.of(Duration.of(5, MINUTES), Duration.of(6, MINUTES)),
        schedule1,
        store,
        mockConfiguration()
    );
    final SimulationResults expected1 = SimulationDriver.simulate(
        missionModel,
        schedule1,
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        () -> false);

    final SimulationResults expected2 = SimulationDriver.simulate(
        missionModel,
        schedule2,
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        () -> false);

    assertResultsEqual(expected1, results);

    assertEquals(Duration.of(5, MINUTES), store.getCachedEngines(mockConfiguration()).getFirst().endsAt());

    final var results2 = simulateWithCheckpoints(
        missionModel,
        store.getCachedEngines(mockConfiguration()).getFirst(),
        List.of(Duration.of(5, MINUTES), Duration.of(6, MINUTES)),
        schedule2,
        store,
        mockConfiguration()
    );
    assertResultsEqual(expected2, results2);

    final SimulationResults results3 = simulateWithCheckpoints(
        missionModel,
        store.getCachedEngines(mockConfiguration()).get(1),
        List.of(Duration.of(5, MINUTES), Duration.of(6, MINUTES)),
        schedule2,
        store,
        mockConfiguration()
    );

    assertResultsEqual(expected2, results3);
  }

  private static long nextActivityDirectiveId = 0L;

  private static Pair<ActivityDirectiveId, ActivityDirective> activityFrom(final long quantity, final Duration unit, final String type, final Map<String, SerializedValue> args) {
    return activityFrom(Duration.of(quantity, unit), type, args);
  }

  private static Pair<ActivityDirectiveId, ActivityDirective> activityFrom(final Duration startOffset, final String type, final Map<String, SerializedValue> args) {
    return Pair.of(new ActivityDirectiveId(nextActivityDirectiveId++), new ActivityDirective(startOffset, type, args, null, true));
  }


  static void assertResultsEqual(SimulationResults expected, SimulationResults actual) {
    if (expected.equals(actual)) return;
    final var differences = new ArrayList<String>();
    if (!expected.duration.equals(actual.duration)) {
      differences.add("duration");
    }
    if (!expected.realProfiles.equals(actual.realProfiles)) {
      differences.add("realProfiles");
    }
    if (!expected.discreteProfiles.equals(actual.discreteProfiles)) {
      differences.add("discreteProfiles");
    }
    if (!expected.simulatedActivities.equals(actual.simulatedActivities)) {
      differences.add("simulatedActivities");
    }
    if (!expected.unfinishedActivities.equals(actual.unfinishedActivities)) {
      differences.add("unfinishedActivities");
    }
    if (!expected.startTime.equals(actual.startTime)) {
      differences.add("startTime");
    }
    if (!expected.duration.equals(actual.duration)) {
      differences.add("duration");
    }
    if (!expected.topics.equals(actual.topics)) {
      differences.add("topics");
    }
    if (!expected.events.equals(actual.events)) {
      differences.add("events");
    }
    if (!differences.isEmpty()) {
      System.out.println();
    }
    System.out.println(differences);
    assertEquals(expected, actual);
  }

  static SimulationResults simulateWithCheckpoints(
      final MissionModel<?> missionModel,
      final CachedSimulationEngine cachedSimulationEngine,
      final List<Duration> desiredCheckpoints,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final CachedEngineStore cachedEngineStore,
      final SimulationEngineConfiguration simulationEngineConfiguration
  ) {
    return CheckpointSimulationDriver.simulateWithCheckpoints(
        missionModel,
        schedule,
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        $ -> {},
        () -> false,
        cachedSimulationEngine,
        CheckpointSimulationDriver.desiredCheckpoints(desiredCheckpoints),
        CheckpointSimulationDriver.noCondition(),
        cachedEngineStore,
        simulationEngineConfiguration
    ).computeResults();
  }

  static SimulationResults simulateWithCheckpoints(
      final MissionModel<?> missionModel,
      final List<Duration> desiredCheckpoints,
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final CachedEngineStore cachedEngineStore,
      final SimulationEngineConfiguration simulationEngineConfiguration
  ) {
    return CheckpointSimulationDriver.simulateWithCheckpoints(
        missionModel,
        schedule,
        Instant.EPOCH,
        Duration.HOUR,
        Instant.EPOCH,
        Duration.HOUR,
        $ -> {},
        () -> false,
        CachedSimulationEngine.empty(missionModel, Instant.EPOCH),
        CheckpointSimulationDriver.desiredCheckpoints(desiredCheckpoints),
        CheckpointSimulationDriver.noCondition(),
        cachedEngineStore,
        simulationEngineConfiguration
    ).computeResults();
  }
}
