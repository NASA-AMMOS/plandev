package gov.nasa.jpl.plandev.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.plandev.e2e.types.SimulationDataset;
import gov.nasa.jpl.plandev.e2e.types.SimulationDataset.SimulatedActivity;
import gov.nasa.jpl.plandev.e2e.utils.GatewayRequests;
import gov.nasa.jpl.plandev.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SimulationTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  // Per-Test Data
  private int modelId;
  private int planId;

  // Cross-Test Constants
  private final String planStartTimestamp = "2023-01-01T00:00:00+00:00";
  private final String  midwayPlanTimestamp = "2023-01-01T12:00:00+00:00";
  private final String  planEndTimestamp = "2023-01-02T00:00:00+00:00";

  @BeforeAll
  void beforeAll() {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
  }

  @AfterAll
  void afterAll() {
    // Cleanup Requests
    hasura.close();
    playwright.close();
  }

  @BeforeEach
  void beforeEach() throws IOException, InterruptedException {
    // Insert the Mission Model
    try (final var gateway = new GatewayRequests(playwright)) {
      modelId = hasura.createMissionModel(
          gateway.uploadJarFile(),
          "Banananation (e2e tests)",
          "aerie_e2e_tests",
          "Simulation Tests");
    }
    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - Simulation Tests",
        "24:00:00",
        planStartTimestamp);
  }

  @AfterEach
  void afterEach() throws IOException {
    // Remove Model and Plan
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  /**
   * GrowBanana with duration of 1 microsecond should finish with a successful simulation
   */
  @Test
  void simulationMicrosecondResolution() throws IOException {
    final var activityArgs = Json.createObjectBuilder().add("duration", 1).build();
    hasura.insertActivityDirective(planId, "ControllableDurationActivity", "1h", activityArgs);
    assertDoesNotThrow(() -> hasura.awaitSimulation(planId));
  }

  /**
   * ParentIds of child spans should be posted regardless of if the span is finished.
   */
  @Test
  void incompleteParentIdsPosted() throws IOException {
    hasura.insertActivityDirective(planId, "parent", "0h", JsonValue.EMPTY_JSON_OBJECT);
    hasura.insertActivityDirective(planId, "DecomposingSpawnParent", "0h", JsonValue.EMPTY_JSON_OBJECT);
    final var simulatedActivities = hasura.getSimulationDataset(hasura.awaitSimulation(planId).simDatasetId())
                                          .activities();

    assertEquals(6, simulatedActivities.size());

    final var typeMap = new HashMap<String, ArrayList<SimulatedActivity>>();
    simulatedActivities.forEach(act -> typeMap.computeIfAbsent(act.type(), k -> new ArrayList<>()).add(act));

    // Check parentIds are present on unfinished activities
    assertEquals(1, typeMap.get("parent").size());
    final var parent = typeMap.get("parent").get(0);
    assertNull(parent.parentId());
    assertNull(parent.duration());

    assertEquals(1, typeMap.get("child").size());
    final var child = typeMap.get("child").get(0);
    assertEquals(parent.spanId(), child.parentId());
    assertNull(child.duration());

    assertEquals(1, typeMap.get("grandchild").size());
    final var grandchild = typeMap.get("grandchild").get(0);
    assertEquals(child.spanId(), grandchild.parentId());
    assertNull(grandchild.duration());

    // Check parentIds are present on finished activities
    assertEquals(1, typeMap.get("DecomposingSpawnParent").size());
    final var decompParent = typeMap.get("DecomposingSpawnParent").get(0);
    assertNull(decompParent.parentId());
    assertNotNull(decompParent.duration());

    assertEquals(2, typeMap.get("DecomposingSpawnChild").size());
    typeMap.get("DecomposingSpawnChild")
           .forEach(dsc -> {
             assertEquals(decompParent.spanId(), dsc.parentId());
             assertNotNull(dsc.duration());
           });
  }

  @Nested
  class TemporalSubsetSimulation {
    private int firstHalfActivityId;
    private int midpointActivityId;
    private int secondHalfActivityId;

    private final String firstHalfActivityStartTime = "2023-01-01T01:00:00+00:00";
    private final String midpointActivityStartTime = midwayPlanTimestamp;
    private final String secondHalfActivityStartTime = "2023-01-01T14:00:00+00:00";

    @BeforeEach
    void beforeEach() throws IOException {
      // Insert Activities
      firstHalfActivityId = hasura.insertActivityDirective(
          planId,
          "ControllableDurationActivity",
          "1hr",
          Json.createObjectBuilder().add("duration", 3600000000L).build()); //1hr duration
      midpointActivityId = hasura.insertActivityDirective(
          planId,
          "ControllableDurationActivity",
          "12hr",
          Json.createObjectBuilder().add("duration", 7200000000L).build()); //2hr duration
      secondHalfActivityId = hasura.insertActivityDirective(
          planId,
          "ControllableDurationActivity",
          "14hr",
          Json.createObjectBuilder().add("duration", 10800000000L).build()); //3hr duration
    }

    // No AfterEach in the nested class, as SimulationTests' AfterEach will perform the necessary cleanup

    /**
     * Expect the first half of the plan to be simulated
     */
    @Test
    void simulateFirstHalf() throws IOException {
      hasura.updateSimBounds(planId, planStartTimestamp, midwayPlanTimestamp);

      // Assert SimDataset Properties
      final var simDataset = hasura.getSimulationDataset(hasura.awaitSimulation(planId).simDatasetId());
      assertFalse(simDataset.canceled());
      assertEquals(planStartTimestamp, simDataset.simStartTime());
      assertEquals(midwayPlanTimestamp, simDataset.simEndTime());
      assertEquals(2, simDataset.activities().size());

      // Sort to be certain of the order
      simDataset.activities().sort(Comparator.comparingInt(SimulatedActivity::directiveId));

      // Assert Activities
      final var firstActivity = simDataset.activities().get(0);
      assertEquals(firstHalfActivityId, firstActivity.directiveId());
      assertEquals("01:00:00", firstActivity.duration());
      assertEquals("01:00:00", firstActivity.startOffset());
      assertEquals(firstHalfActivityStartTime, firstActivity.startTime());

      final var unfinishedActivity = simDataset.activities().get(1);
      assertEquals(midpointActivityId, unfinishedActivity.directiveId());
      assertNull(unfinishedActivity.duration());
      assertEquals("12:00:00", unfinishedActivity.startOffset());
      assertEquals(midpointActivityStartTime, unfinishedActivity.startTime());
    }

    /**
     * Expect the second half of the plan to be simulated
     */
    @Test
    void simulateSecondHalf() throws IOException {
      hasura.updateSimBounds(planId, midwayPlanTimestamp, planEndTimestamp);

      // Assert SimDataset Properties
      final var simDataset = hasura.getSimulationDataset(hasura.awaitSimulation(planId).simDatasetId());
      assertFalse(simDataset.canceled());
      assertEquals(midwayPlanTimestamp, simDataset.simStartTime());
      assertEquals(planEndTimestamp, simDataset.simEndTime());
      assertEquals(2, simDataset.activities().size());

      // Sort to be certain of the order
      simDataset.activities().sort(Comparator.comparingInt(SimulatedActivity::directiveId));

      // Assert Activities
      final var firstActivity = simDataset.activities().get(0);
      assertEquals(midpointActivityId, firstActivity.directiveId());
      assertEquals("02:00:00", firstActivity.duration());
      assertEquals("00:00:00", firstActivity.startOffset());
      assertEquals(midpointActivityStartTime, firstActivity.startTime());

      final var secondActivity = simDataset.activities().get(1);
      assertEquals(secondHalfActivityId, secondActivity.directiveId());
      assertEquals("03:00:00", secondActivity.duration());
      assertEquals("02:00:00", secondActivity.startOffset());
      assertEquals(secondHalfActivityStartTime, secondActivity.startTime());
    }

    /**
     * In this test:
     *  - Simulation's simulation_start_time: (January 1, 2023, at 02:00:00 Z)
     *  - Simulation's simulation_end_time: (January 1, 2023, at 13:00:00 Z)
     * Expect the simulation to be 11 Hours long (02:00:00 Z to 13:00:00 Z)
    */
    @Test
    void simulateMiddle() throws IOException {
      hasura.updateSimBounds(planId, "2023-01-01T02:00:00+00:00", "2023-01-01T13:00:00+00:00");

      // Assert SimDataset Properties
      final var simDataset = hasura.getSimulationDataset(hasura.awaitSimulation(planId).simDatasetId());
      assertFalse(simDataset.canceled());
      assertEquals("2023-01-01T02:00:00+00:00", simDataset.simStartTime());
      assertEquals("2023-01-01T13:00:00+00:00", simDataset.simEndTime());
      assertEquals(1, simDataset.activities().size());

      // Assert Activities
      final var unfinishedActivity = simDataset.activities().get(0);
      assertEquals(midpointActivityId, unfinishedActivity.directiveId());
      assertNull(unfinishedActivity.duration());
      assertEquals("10:00:00", unfinishedActivity.startOffset());
      assertEquals(midpointActivityStartTime, unfinishedActivity.startTime());
    }

    /**
     * Expect the entire plan to be simulated
     */
    @Test
    void simulateCompletePlan() throws IOException {
      hasura.updateSimBounds(planId, planStartTimestamp, planEndTimestamp);

      // Assert SimDataset Properties
      final var simDataset = hasura.getSimulationDataset(hasura.awaitSimulation(planId).simDatasetId());
      assertFalse(simDataset.canceled());
      assertEquals(planStartTimestamp, simDataset.simStartTime());
      assertEquals(planEndTimestamp, simDataset.simEndTime());
      assertEquals(3, simDataset.activities().size());

      // Sort to be certain of the order
      simDataset.activities().sort(Comparator.comparingInt(SimulatedActivity::directiveId));

      // Assert Activities
      final var firstActivity = simDataset.activities().get(0);
      assertEquals(firstHalfActivityId, firstActivity.directiveId());
      assertEquals("01:00:00", firstActivity.duration());
      assertEquals("01:00:00", firstActivity.startOffset());
      assertEquals(firstHalfActivityStartTime, firstActivity.startTime());

      final var secondActivity = simDataset.activities().get(1);
      assertEquals(midpointActivityId, secondActivity.directiveId());
      assertEquals("02:00:00", secondActivity.duration());
      assertEquals("12:00:00", secondActivity.startOffset());
      assertEquals(midpointActivityStartTime, secondActivity.startTime());

      final var thirdActivity = simDataset.activities().get(2);
      assertEquals(secondHalfActivityId, thirdActivity.directiveId());
      assertEquals("03:00:00", thirdActivity.duration());
      assertEquals("14:00:00", thirdActivity.startOffset());
      assertEquals(secondHalfActivityStartTime, thirdActivity.startTime());
    }
  }

  @Nested
  class CancelingSimulation {
    private int fooId;
    private int fooPlan;

    @BeforeEach
    void beforeEach() throws IOException, InterruptedException {
      // Insert the Mission Model
      // Long Foo plans take long enough to be canceled without risking a race condition like with Banananation
      try (final var gateway = new GatewayRequests(playwright)) {
        fooId = hasura.createMissionModel(
            gateway.uploadFooJar(),
            "Foo (e2e tests)",
            "aerie_e2e_tests",
            "Simulation Tests");
      }
      // Insert the Plan
      fooPlan = hasura.createPlan(
          fooId,
          "Foo Plan - Simulation Tests",
          "1760:00:00",
          planStartTimestamp);

      // Add a ControllableDurationActivity every hour
      for(int i = 0; i < 1760; ++i) {
        final var startOffset = (i<10 ? "0"+i : i) + ":00:00";
        final var growArgs = Json.createObjectBuilder()
                                 .add("duration", Json.createObjectBuilder()
                                                      .add("amountInMicroseconds", 60000000*60L))  // 1 hour
                                 .build();
        hasura.insertActivityDirective(fooPlan, "ControllableDurationActivity", startOffset, growArgs);
      }
    }

    @AfterEach
    void afterEach() throws IOException {
      // Remove Model and Plan
      hasura.deletePlan(fooPlan);
      hasura.deleteMissionModel(fooId);
    }

    /**
     * Cancelling a simulation returns results up to that point
     */
    @Test
    void cancelingSimReturnsPartialResults() throws IOException {
      final var results = hasura.cancelingSimulation(fooPlan);
      // Assert that the simulation was incomplete
      assertEquals(SimulationDataset.SimulationStatus.incomplete, results.status());

      // Assert reason
      assertTrue(results.reason().isPresent());
      final var reason = results.reason().get();
      assertEquals("SIMULATION_CANCELED", reason.type());
      assertEquals("Simulation run was canceled", reason.message());
      assertEquals("", reason.trace());
      assertNotNull(reason.timestamp());

      // Assert the data in the reason
      final var reasonData = reason.data();
      assertEquals(2, reasonData.size());
      assertTrue(reasonData.containsKey("utcTimeDoy"));
      assertTrue(reasonData.containsKey("elapsedTime"));

      assertNotEquals(planEndTimestamp, reasonData.getString("utcTimeDoy"));

      final var elapsedHours = reasonData.getString("elapsedTime").split(":")[0];
      final int startedActivities = Integer.parseInt(elapsedHours) + 1; // Hours + 1 (Accounts for 0 index)

      // There should be as many activities that began simulation as hours into time elapsed
      assertEquals(startedActivities, results.activities().size());
    }
  }

  @Nested
  class ForceResimulation {
    private int simulationDatasetId;
    @BeforeEach
    void beforeEach() throws IOException {
      simulationDatasetId = hasura.awaitSimulation(planId).simDatasetId();
    }
    @Test
    void noResimWhenNull() throws IOException {
      assertEquals(simulationDatasetId, hasura.awaitSimulation(planId, null).simDatasetId());
    }

    @Test
    void noResimWhenFalse() throws IOException {
      assertEquals(simulationDatasetId, hasura.awaitSimulation(planId, false).simDatasetId());
    }

    @Test
    void noResimWhenAbsent() throws IOException {
      assertEquals(simulationDatasetId, hasura.awaitSimulation(planId).simDatasetId());
    }

    @Test
    void resimOnlyUpdatesConfigRevision() throws IOException {
      final int planRevision = hasura.getPlanRevision(planId);
      final var simConfig = hasura.getSimConfig(planId);

      // Assert forcibly resimming returned a new simulation dataset
      final var newSimDatasetId = hasura.awaitSimulation(planId, true).simDatasetId();
      assertNotEquals(simulationDatasetId, newSimDatasetId);

      // Assert that the plan revision is unchanged
      assertEquals(planRevision, hasura.getPlanRevision(planId));

      // Assert that the simulation configuration has only had its revision updated
      final var newSimConfig = hasura.getSimConfig(planId);
      assertNotEquals(simConfig.revision(), newSimConfig.revision());
      assertEquals(simConfig.id(), newSimConfig.id());
      assertEquals(simConfig.planId(), newSimConfig.planId());
      assertEquals(simConfig.simulationTemplateId(), newSimConfig.simulationTemplateId());
      assertEquals(simConfig.arguments(), newSimConfig.arguments());
      assertEquals(simConfig.simulationStartTime(), newSimConfig.simulationStartTime());
      assertEquals(simConfig.simulationEndTime(), newSimConfig.simulationEndTime());
    }
  }

  @Nested
  class SimulationExceptionResponse {
    private int fooId;
    private int fooPlan;

    @BeforeEach
    void beforeEach() throws IOException, InterruptedException {
      // Insert the Mission Model
      // Foo contains ways to trigger exceptions on-demand
      try (final var gateway = new GatewayRequests(playwright)) {
        fooId = hasura.createMissionModel(
            gateway.uploadFooJar(),
            "Foo (e2e tests)",
            "aerie_e2e_tests",
            "Simulation Tests");
      }
      // Insert the Plan
      fooPlan = hasura.createPlan(
          fooId,
          "Foo Plan - Error Simulation Tests",
          "02:00:00",
          planStartTimestamp);
    }

    @AfterEach
    void afterEach() throws IOException {
      // Remove Model and Plan
      hasura.deletePlan(fooPlan);
      hasura.deleteMissionModel(fooId);
    }

    /**
     * If a directive throws an exception, the error response will include the executing directive id.
     */
    @Test
    void directiveIdIncluded() throws IOException {
      // Setup: Insert a directive that will throw
      int dirId = hasura.insertActivityDirective(
          fooPlan,
          "DaemonCheckerActivity",
          "01:00:00",
          Json.createObjectBuilder().add("minutesElapsed", 1).build());

      final var simDatasetId = hasura.awaitFailingSimulation(fooPlan).simDatasetId();
      final var simDataset = hasura.getSimulationDataset(simDatasetId);
      assertEquals(SimulationDataset.SimulationStatus.failed, simDataset.status());

      // The correct type of error was returned
      assertTrue(simDataset.reason().isPresent());
      final var reason = simDataset.reason().get();
      assertEquals("SIMULATION_EXCEPTION", reason.type());

      final var exception = reason.data();

      // The error includes the directive id that was executing
      // and the executing activity type and stack trace
      assertTrue(exception.containsKey("executingDirectiveId"));
      assertTrue(exception.containsKey("executingActivityType"));
      assertEquals(dirId, exception.getInt("executingDirectiveId"));
      assertEquals("DaemonCheckerActivity", exception.getString("executingActivityType"));
      assertTrue(exception.containsKey("activityStackTrace"));
      assertEquals("DaemonCheckerActivity", exception.getString("activityStackTrace"));

      // The error message is correct
      assertEquals("Minutes elapsed is incorrect. TimeTrackerDaemon may have stopped.\n\tExpected: 1 Actual: 59",
                   reason.message());

      // The error was thrown at the correct time
      assertTrue(exception.containsKey("utcTimeDoy"));
      assertEquals("2023-001T01:00:00", exception.getString("utcTimeDoy"));

      // The trace starts at the original exception and doesn't include the intermediary SpanException and SimulationException
      final var expectedStart = """
          java.lang.RuntimeException: Minutes elapsed is incorrect. TimeTrackerDaemon may have stopped.
          \tExpected: 1 Actual: 59
          \tat gov.nasa.jpl.plandev.foomissionmodel.activities.DaemonCheckerActivity.run(DaemonCheckerActivity.java""";
      assertTrue(reason.trace().startsWith(expectedStart));
    }

    /**
     * If a child span throws an exception, the error response will include the parent directive's id.
     */
    @Test
    void directiveIdOnDescendant() throws IOException {
      // Setup: Insert a directive that will throw
      int dirId = hasura.insertActivityDirective(
          fooPlan,
          "DaemonTaskActivity",
          "01:00:00",
          Json.createObjectBuilder().add("minutesElapsed", 1).add("spawnDelay", 1).build());

      final var simDatasetId = hasura.awaitFailingSimulation(fooPlan).simDatasetId();
      final var simDataset = hasura.getSimulationDataset(simDatasetId);
      assertEquals(SimulationDataset.SimulationStatus.failed, simDataset.status());

      // The correct type of error was returned
      assertTrue(simDataset.reason().isPresent());
      final var reason = simDataset.reason().get();
      assertEquals("SIMULATION_EXCEPTION", reason.type());

      final var exception = reason.data();

      // The error includes the directive id that was executing
      // and the executing activity type and stack trace
      assertTrue(exception.containsKey("executingDirectiveId"));
      assertEquals(dirId, exception.getInt("executingDirectiveId"));
      assertTrue(exception.containsKey("executingActivityType"));
      assertEquals(dirId, exception.getInt("executingDirectiveId"));
      assertEquals("DaemonTaskActivity", exception.getString("executingActivityType"));
      assertTrue(exception.containsKey("activityStackTrace"));
      assertEquals("DaemonTaskActivity\n"
                   + "|-DaemonCheckerSpawner\n"
                   + "|--DaemonCheckerActivity", exception.getString("activityStackTrace"));

      // The error message is correct
      assertEquals("Minutes elapsed is incorrect. TimeTrackerDaemon may have stopped.\n\tExpected: 1 Actual: 61",
                   reason.message());

      // The error was thrown at the correct time
      assertTrue(exception.containsKey("utcTimeDoy"));
      assertEquals("2023-001T01:02:00", exception.getString("utcTimeDoy"));

      // The trace starts at the original exception and doesn't include the intermediary SpanException and SimulationException
      final var expectedStart = """
          java.lang.RuntimeException: Minutes elapsed is incorrect. TimeTrackerDaemon may have stopped.
          \tExpected: 1 Actual: 61
          \tat gov.nasa.jpl.plandev.foomissionmodel.activities.DaemonCheckerActivity.run(DaemonCheckerActivity.java""";
      assertTrue(reason.trace().startsWith(expectedStart));
    }

    /**
     * If a daemon task throws an exception, the error response will not include any id
     * but will include the exception message.
     */
    @Test
    void noDirectiveIdOnDaemon() throws IOException {
      // Set up: Update the config to force an exception 1hr into the plan
      hasura.updateSimArguments(fooPlan, Json.createObjectBuilder().add("raiseException", JsonValue.TRUE).build());

      final var simDatasetId = hasura.awaitFailingSimulation(fooPlan).simDatasetId();
      final var simDataset = hasura.getSimulationDataset(simDatasetId);
      assertEquals(SimulationDataset.SimulationStatus.failed, simDataset.status());

      // The correct type of error was returned
      assertTrue(simDataset.reason().isPresent());
      final var reason = simDataset.reason().get();
      assertEquals("SIMULATION_EXCEPTION", reason.type());

      final var exception = reason.data();

      // The error does not include any directive ids,
      // executingActivityType, and activityStackTrace
      assertFalse(exception.containsKey("executingDirectiveId"));
      assertFalse(exception.containsKey("executingActivityType"));
      assertFalse(exception.containsKey("activityStackTrace"));

      // The error message is correct
      assertEquals("Daemon task exception raised.", reason.message());

      // The error was thrown at the correct time
      assertTrue(exception.containsKey("utcTimeDoy"));
      assertEquals("2023-001T01:00:00", exception.getString("utcTimeDoy"));

      // The trace starts at the original exception and doesn't include the intermediary SimulationException
      final var expectedStart = """
          java.lang.RuntimeException: Daemon task exception raised.
          \tat gov.nasa.jpl.plandev.foomissionmodel.Mission.lambda$new$0(Mission.java""";
      assertTrue(reason.trace().startsWith(expectedStart));
    }

    /**
     * If a daemon task throws an exception while an activity directive is executing,
     * the error response won't include the directive's id
     */
    @Test
    void noDirectiveIdDaemonMidDirective() throws IOException {
      // Set up: Update the config to force an exception 1hr into the plan
      // and add an activity that will be executing at that time
      hasura.updateSimArguments(fooPlan, Json.createObjectBuilder().add("raiseException", JsonValue.TRUE).build());
      hasura.insertActivityDirective(
          fooPlan,
          "DaemonCheckerSpawner",
          "00:59:00",
          Json.createObjectBuilder().add("minutesElapsed", 1).add("spawnDelay", 5).build());

      final var simDatasetId = hasura.awaitFailingSimulation(fooPlan).simDatasetId();
      final var simDataset = hasura.getSimulationDataset(simDatasetId);
      assertEquals(SimulationDataset.SimulationStatus.failed, simDataset.status());

      // The correct type of error was returned
      assertTrue(simDataset.reason().isPresent());
      final var reason = simDataset.reason().get();
      assertEquals("SIMULATION_EXCEPTION", reason.type());

      final var exception = reason.data();

      // The error does not include any directive ids,
      // executingActivityType, and activityStackTrace
      assertFalse(exception.containsKey("executingDirectiveId"));
      assertFalse(exception.containsKey("executingActivityType"));
      assertFalse(exception.containsKey("activityStackTrace"));

      // The error message is correct
      assertEquals("Daemon task exception raised.", reason.message());

      // The error was thrown at the correct time
      assertTrue(exception.containsKey("utcTimeDoy"));
      assertEquals("2023-001T01:00:00", exception.getString("utcTimeDoy"));
    }
  }
}
