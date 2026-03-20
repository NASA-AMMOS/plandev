package gov.nasa.jpl.aerie.serializationbenchmark;

import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelBuilder;
import gov.nasa.jpl.aerie.merlin.driver.SimulationDriver;
import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.framework.ThreadedTask;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.serializationbenchmark.generated.GeneratedModelType;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Stress test for simulation performance with a massive model.
 *
 * Model: 306 resources (6 complex discrete + 200 simple discrete + 100 real)
 * Plan: 500 StressActivity instances, each updating 206 discrete resources
 *
 * Run with: ./gradlew :examples:serialization-benchmark:test --tests SimulationStressTest -i
 */
public class SimulationStressTest {

  private static final int NUM_ACTIVITIES = 500;
  private static final Duration PLAN_DURATION = Duration.of(24, Duration.HOUR);

  @BeforeAll
  static void setup() {
    ThreadedTask.CACHE_READS = true;
  }

  private MissionModel<Mission> buildModel(Instant planStart) {
    final var builder = new MissionModelBuilder();
    final var factory = new GeneratedModelType();
    final var registry = DirectiveTypeRegistry.extract(factory);
    final var config = new Configuration();
    final var model = factory.instantiate(planStart, config, builder);
    return builder.build(model, registry);
  }

  private Map<ActivityDirectiveId, ActivityDirective> buildPlan() {
    final var plan = new HashMap<ActivityDirectiveId, ActivityDirective>();

    final var stepMicros = PLAN_DURATION.in(Duration.MICROSECOND) / (NUM_ACTIVITIES + 1);

    for (int i = 0; i < NUM_ACTIVITIES; i++) {
      final var offset = Duration.of(stepMicros * (i + 1), Duration.MICROSECOND);
      final var args = Map.<String, SerializedValue>of(
          "index", SerializedValue.of(i),
          "magnitude", SerializedValue.of(10.0 + i * 0.5)
      );

      plan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(offset, "StressActivity", args, null, true)
      );
    }

    return plan;
  }

  @Test
  public void simulateAndMeasure() {
    final var planStart = Instant.parse("2025-01-01T00:00:00Z");

    System.out.printf("""

        === Simulation Stress Test ===
        Resources: %d discrete + %d real = %d total
        Activities: %d StressActivity instances
        Plan duration: %s
        Each activity updates: 6 complex discrete + %d simple discrete resources

        """,
        6 + Mission.NUM_INDEXED_RESOURCES,
        Mission.NUM_REAL_RESOURCES,
        6 + Mission.NUM_INDEXED_RESOURCES + Mission.NUM_REAL_RESOURCES,
        NUM_ACTIVITIES,
        PLAN_DURATION,
        Mission.NUM_INDEXED_RESOURCES
    );

    // Build model
    long t0 = System.nanoTime();
    final var model = buildModel(planStart);
    long t1 = System.nanoTime();
    System.out.printf("Model build:      %7.1f ms%n", (t1 - t0) / 1e6);

    // Build plan
    final var plan = buildPlan();
    long t2 = System.nanoTime();
    System.out.printf("Plan build:       %7.1f ms%n", (t2 - t1) / 1e6);

    // Simulate
    final var resourceManager = new InMemorySimulationResourceManager();

    long simStart = System.nanoTime();
    final var results = SimulationDriver.simulate(
        model,
        plan,
        planStart,
        PLAN_DURATION,
        planStart,
        PLAN_DURATION,
        () -> false,
        $ -> {},
        resourceManager
    );
    long simEnd = System.nanoTime();
    double simTimeMs = (simEnd - simStart) / 1e6;

    // Compute profiles
    long profileStart = System.nanoTime();
    final var profiles = resourceManager.computeProfiles(PLAN_DURATION);
    long profileEnd = System.nanoTime();
    double profileTimeMs = (profileEnd - profileStart) / 1e6;

    // Count segments
    int realSegments = profiles.realProfiles().values().stream()
        .mapToInt(p -> p.segments().size()).sum();
    int discreteSegments = profiles.discreteProfiles().values().stream()
        .mapToInt(p -> p.segments().size()).sum();

    System.out.printf("""

        === Results ===
        Simulation time:  %7.1f ms
        Profile compute:  %7.1f ms
        Total:            %7.1f ms

        Real profiles:     %d resources, %d total segments
        Discrete profiles: %d resources, %d total segments
        Simulated activities: %d
        Unfinished activities: %d
        """,
        simTimeMs, profileTimeMs, simTimeMs + profileTimeMs,
        profiles.realProfiles().size(), realSegments,
        profiles.discreteProfiles().size(), discreteSegments,
        results.simulatedActivities.size(),
        results.unfinishedActivities.size()
    );

    // Assertions
    assertNotNull(results);
    assertFalse(results.simulatedActivities.isEmpty(), "Should have simulated activities");
    assertFalse(profiles.discreteProfiles().isEmpty(), "Should have discrete profiles");
    assertFalse(profiles.realProfiles().isEmpty(), "Should have real profiles");
  }
}
