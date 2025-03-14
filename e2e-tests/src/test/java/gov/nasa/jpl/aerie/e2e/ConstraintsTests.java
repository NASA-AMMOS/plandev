package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.types.ConstraintActionResponse;
import gov.nasa.jpl.aerie.e2e.types.ConstraintError;
import gov.nasa.jpl.aerie.e2e.types.ExternalDataset.ProfileInput;
import gov.nasa.jpl.aerie.e2e.types.ExternalDataset.ProfileInput.ProfileSegmentInput;
import gov.nasa.jpl.aerie.e2e.types.ValueSchema;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.*;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConstraintsTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  // Per-Test Data
  private int modelId;
  private int planId;
  private int activityId;
  private int constraintId;
  private int invocationId;

  private final String constraintName = "fruit_equal_peel";
  private final String constraintDefinition =
      "export default (): Constraint => Real.Resource(\"/fruit\").equal(Real.Resource(\"/peel\"))";

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
          "Constraints Tests");
    }
    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - Constraints Tests",
        "1212h",
        "2021-01-01T00:00:00Z");
    //Insert the Activity
    activityId = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        Json.createObjectBuilder().add("biteSize", 1).build());
    // Insert the Constraint
    final var insertResp = hasura.insertPlanConstraint(
        constraintName,
        planId,
        constraintDefinition,
        "");
    constraintId = insertResp.id();
    invocationId = insertResp.invocationId();
  }

  @AfterEach
  void afterEach() throws IOException {
    // Remove Model, Plan, and Constraint
    hasura.deleteConstraint(constraintId);
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  @Test
  void constraintsFailNoSimData() {
    final var exception = assertThrows(RuntimeException.class, () -> hasura.checkConstraints(planId));
    final var message = exception.getMessage().split("\"message\":\"")[1].split("\"}]")[0];
    // Hasura strips the cause message ("Assumption falsified -- mission model for existing plan does not exist")
    // from the error it returns
    if (!message.equals("input mismatch exception")) {
      throw exception;
    }
  }

  @Test
  void constraintsSucceedOneViolation() throws IOException {
    hasura.awaitSimulation(planId);
    final var constraintsResponses = hasura.checkConstraintsJustResults(planId);
    assertEquals(1, constraintsResponses.size());

    // Check the Response
    final var constraintResponse = constraintsResponses.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(constraintId, constraintResponse.constraintId());
    assertEquals(constraintName, constraintResponse.constraintName());
    // Check the Result
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();
    // Resources
    final var resources = constraintResult.resourceIds();
    assertEquals(2, resources.size());
    assertTrue(resources.containsAll(List.of("/peel", "/fruit")));

    // Violation
    assertEquals(1, constraintResult.violations().size());
    final var violation = constraintResult.violations().getFirst();
    assertEquals(1, violation.windows().size());

    final long activityOffset = 60 * 60 * 1000000L; // 1h in micros
    final long planDuration = 1212 * 60 * 60 * 1000000L; // 1212h in micros

    assertEquals(activityOffset, violation.windows().getFirst().start());
    assertEquals(planDuration, violation.windows().getFirst().end());
    // Gaps
    assertTrue(constraintResult.gaps().isEmpty());
  }

  @Test
  void constraintsSucceedAgainstVariantWithUnits() throws IOException {
    hasura.deleteActivity(planId, activityId);
    hasura.deleteConstraint(constraintId);
    var fruitConstraintName = "fruit_equals_3";
    var fruitConstraintId = hasura.insertPlanConstraint(
        fruitConstraintName,
        planId,
        "export default (): Constraint => Real.Resource(\"/fruit\").equal(3)",
        "");
    hasura.insertActivityDirective(
        planId,
        "PeelBanana",
        "1h",
        Json.createObjectBuilder().add("peelDirection", "fromStem").build());

    hasura.awaitSimulation(planId);
    final var constraintsResponses = hasura.checkConstraintsJustResults(planId);
    assertEquals(1, constraintsResponses.size());

    // Check the Response
    final var constraintResponse = constraintsResponses.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(fruitConstraintId.id(), constraintResponse.constraintId());
    assertEquals(fruitConstraintName, constraintResponse.constraintName());
    // Check the Result
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();
    // Resources
    final var resources = constraintResult.resourceIds();
    assertEquals(1, resources.size());
    assertTrue(resources.contains("/fruit"));

    // Violation
    assertEquals(1, constraintResult.violations().size());
    final var violation = constraintResult.violations().getFirst();
    assertEquals(1, violation.windows().size());

    final long activityOffset = 60 * 60 * 1000000L; // 1h in micros

    assertEquals(0, violation.windows().getFirst().start());
    assertEquals(activityOffset, violation.windows().getFirst().end());
    // Gaps
    assertTrue(constraintResult.gaps().isEmpty());
  }

  @Test
  void constraintsSucceedNoViolations() throws IOException {
    // Delete activity to avoid violation
    hasura.deleteActivity(planId, activityId);
    hasura.awaitSimulation(planId);
    final var constraintResponses = hasura.checkConstraintsJustResults(planId);

    assertEquals(1, constraintResponses.size());
    assertTrue(constraintResponses.getFirst().success());
    assertEquals(constraintId, constraintResponses.getFirst().constraintId());
    assertEquals(constraintName, constraintResponses.getFirst().constraintName());
    assertTrue(constraintResponses.getFirst().result().isPresent());
    assertEquals(0, constraintResponses.getFirst().result().get().violations().size());
  }

  @Test
  void constraintCachedViolation() throws IOException {
    final var simDatasetId = hasura.awaitSimulation(planId).simDatasetId();
    final var constraintActionResponse = hasura.checkConstraints(planId);
    final var constraintResponses = constraintActionResponse.constraintsRun();

    final var cachedResponse = hasura.getConstraintRequest(constraintActionResponse.requestId());
    final var cachedRuns = cachedResponse.constraintsRun();

    // There's the correct number of results
    assertEquals(1, cachedRuns.size());
    assertEquals(1, constraintResponses.size());

    // Check properties
    final var cachedRun = cachedRuns.getFirst();
    assertEquals(constraintResponses.getFirst().constraintRevision(), cachedRun.results().constraintRevision());
    assertEquals(constraintResponses.getFirst().constraintInvocationId(), cachedRun.constraintInvocationId());
    assertEquals(invocationId, cachedRun.constraintInvocationId());
    assertEquals(simDatasetId, cachedResponse.simDatasetId());

    // Check results
    final var cachedResults = cachedRun.results();
    assertTrue(cachedResults.results().isPresent());
    assertTrue(cachedResults.arguments().isEmpty());

    final var constraintResponse = constraintResponses.getFirst();
    assertTrue(constraintResponse.success());
    assertTrue(constraintResponse.result().isPresent());
    assertEquals(constraintResponse.result().get(), cachedResults.results().get());
  }

  @Test
  void constraintCachedNoViolations() throws IOException {
    // Delete activity to avoid violation
    hasura.deleteActivity(planId, activityId);
    final var simDatasetId = hasura.awaitSimulation(planId).simDatasetId();
    final var constraintActionResponse = hasura.checkConstraints(planId);
    final var constraintResponses = constraintActionResponse.constraintsRun();

    final var cachedResponse = hasura.getConstraintRequest(constraintActionResponse.requestId());
    final var cachedRuns = cachedResponse.constraintsRun();

    // There's the correct number of results
    assertEquals(1, cachedRuns.size());
    assertEquals(1, constraintResponses.size());

    // Check properties
    final var cachedRun = cachedRuns.getFirst();
    assertEquals(constraintResponses.getFirst().constraintInvocationId(), cachedRun.constraintInvocationId());
    assertEquals(constraintResponses.getFirst().constraintRevision(), cachedRun.results().constraintRevision());
    assertEquals(invocationId, cachedRun.constraintInvocationId());
    assertEquals(simDatasetId, cachedResponse.simDatasetId());

    // Check results
    final var cachedResults = cachedRun.results();
    assertTrue(cachedResults.results().isPresent());

    final var results = cachedResults.results().get();
    assertEquals(2,results.resourceIds().size());
    assertEquals("/peel",results.resourceIds().get(0));
    assertEquals("/fruit",results.resourceIds().get(1));
    assertEquals(0,results.violations().size());
    assertEquals(0,results.gaps().size());
  }

  /**
   * Test that an activity with a duration longer than one month is written to and read back from the database
   * successfully
   * by Aerie's simulation and constraints checking components respectively. The driving concern here is that Aerie
   * needs
   * to interpret span durations as microseconds; if the simulation results were to be written using the postgres
   * interval's "months" field, constraints checking would fail to load these values back from the database.
   */
  @Test
  void constraintsWorkMonthLongActivity() throws IOException {
    // Setup
    hasura.updateConstraintDefinition(
        constraintId,
        "export default (): Constraint => Windows.During(ActivityType.ControllableDurationActivity).not()");
    hasura.deleteActivity(planId, activityId);
    final long thirtyFiveDays = 35 * 24 * 60 * 60 * 1000L * 1000; // 35 days in microseconds
    hasura.insertActivityDirective(
        planId,
        "ControllableDurationActivity",
        "0h",
        Json.createObjectBuilder().add("duration", thirtyFiveDays).build());
    hasura.awaitSimulation(planId);
    final var constraintsResponses = hasura.checkConstraints(planId).constraintsRun();
    assertEquals(1, constraintsResponses.size());

    // Check the Response
    final var constraintResponse = constraintsResponses.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(constraintId,constraintResponse.constraintId());
    assertEquals(constraintName, constraintResponse.constraintName());

    //Check Result
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();

    // Resources
    final var resources = constraintResult.resourceIds();
    assertEquals(0, resources.size());

    // Violation
    assertEquals(1, constraintResult.violations().size());
    final var violation = constraintResult.violations().getFirst();
    assertEquals(1, violation.windows().size());

    assertEquals(0, violation.windows().getFirst().start());
    assertEquals(thirtyFiveDays, violation.windows().getFirst().end());
    // Gaps
    assertTrue(constraintResult.gaps().isEmpty());
  }

  @Test
  void runConstraintsOnOldSimulation() throws IOException {
    final int oldSimDatasetId = hasura.awaitSimulation(planId).simDatasetId();

    // Delete Activity to make the simulation outdated, then resim
    hasura.deleteActivity(planId, activityId);
    final int newSimDatasetId = hasura.awaitSimulation(planId).simDatasetId();

    // Expect no violations on the new simulation
    final var newConstraintResponses = hasura.checkConstraintsJustResults(planId, newSimDatasetId);
    assertEquals(1, newConstraintResponses.size());
    assertEquals(constraintId, newConstraintResponses.getFirst().constraintId());
    assertEquals(constraintName, newConstraintResponses.getFirst().constraintName());
    assertTrue(newConstraintResponses.getFirst().result().isPresent());
    final var newConstraintResult = newConstraintResponses.getFirst().result().get();
    assertTrue(newConstraintResult.violations().isEmpty());


    // Expect one violation on the old simulation
    final var oldConstraintsResponses = hasura.checkConstraints(planId, oldSimDatasetId).constraintsRun();
    assertEquals(1, oldConstraintsResponses.size());

    // Check the Result
    final var oldConstraintResponse = oldConstraintsResponses.getFirst();
    assertTrue(oldConstraintResponse.success());
    assertEquals(constraintId, oldConstraintResponse.constraintId());
    assertEquals(constraintName, oldConstraintResponse.constraintName());
    assertTrue(oldConstraintResponse.result().isPresent());
    final var constraintResult = oldConstraintResponse.result().get();

    // Resources
    final var resources = constraintResult.resourceIds();
    assertEquals(2, resources.size());
    assertTrue(resources.containsAll(List.of("/peel", "/fruit")));

    // Violation
    assertEquals(1, constraintResult.violations().size());
    final var violation = constraintResult.violations().getFirst();
    assertEquals(1, violation.windows().size());

    final long activityOffset = 60 * 60 * 1000000L; // 1h in micros
    final long planDuration = 1212 * 60 * 60 * 1000000L; // 1212h in micros

    assertEquals(activityOffset, violation.windows().getFirst().start());
    assertEquals(planDuration, violation.windows().getFirst().end());
    // Gaps
    assertTrue(constraintResult.gaps().isEmpty());
  }

  /**
   *  If a plan specifies a version of a constraint to use, then that version will be used
   *  when checking constraints.
   *
   *  If the test fails with a compilation error, that means it used the latest version of the constraint
   */
  @Test
  void constraintVersionLocking() throws IOException {
    hasura.awaitSimulation(planId);

    // Update the plan's constraint specification to use a specific version
    hasura.updatePlanConstraintSpecVersion(constraintId, 0);

    // Update definition to have invalid syntax
    final int newRevision = hasura.updateConstraintDefinition(
        constraintId,
        " error :-(");

    // Check constraints -- should succeed
    final var initResults = hasura.checkConstraintsJustResults(planId);
    assertEquals(1, initResults.size());
    final var initConstraint = initResults.getFirst();
    assertEquals(constraintId, initConstraint.constraintId());
    assertEquals(0, initConstraint.constraintRevision());
    assertTrue(initConstraint.success());
    assertTrue(initConstraint.errors().isEmpty());
    assertTrue(initConstraint.result().isPresent());

    // Update constraint spec to use invalid definition
    hasura.updatePlanConstraintSpecVersion(invocationId, newRevision);

    // Check constraints -- should fail
    final var badDefinitionResults = hasura.checkConstraintsJustResults(planId);
    assertEquals(1, badDefinitionResults.size());
    final var badConstraint = badDefinitionResults.getFirst();
    assertEquals(constraintId, badConstraint.constraintId());
    assertFalse(badConstraint.success());
    assertEquals(constraintName, badConstraint.constraintName());
    assertEquals(2, badConstraint.errors().size());
    assertEquals("Constraint 'fruit_equal_peel' compilation failed:\n" +
            " TypeError: TS2306 No default export. Expected a default export function with the signature: " +
            "\"(...args: []) => Constraint | Promise<Constraint>\".",
          badConstraint.errors().get(0).message());
    assertEquals("Constraint 'fruit_equal_peel' compilation failed:\n TypeError: TS1109 Expression expected.",
          badConstraint.errors().get(1).message());

    // Update constraint spec to use initial definition
    hasura.updatePlanConstraintSpecVersion(invocationId, 0);

    // Check constraints -- should match
    assertEquals(initResults, hasura.checkConstraintsJustResults(planId));
  }

  @Test
  @DisplayName("Disabled Constraints are not checked")
  void constraintIgnoreDisabled() throws IOException {
    hasura.awaitSimulation(planId);
    // Add a problematic constraint to the spec, then disable it
    final String problemConstraintName = "bad constraint";
    final var problemConstraintId = hasura.insertPlanConstraint(
        problemConstraintName,
        planId,
        "error :-(",
        "constraint that shouldn't compile");

    final var invocationId = problemConstraintId.invocationId();

    try {
      hasura.updatePlanConstraintSpecEnabled(invocationId, false);

      // Check constraints -- Validate that only the enabled constraint is included
      final var initResults = hasura.checkConstraintsJustResults(planId);
      assertEquals(1, initResults.size());
      assertEquals(constraintId, initResults.getFirst().constraintId());
      assertTrue(initResults.getFirst().success());

      // Enable disabled constraint
      hasura.updatePlanConstraintSpecEnabled(invocationId, true);

      // Check constraints -- Validate that the other constraint is present and a failure
      final var results = hasura.checkConstraintsJustResults(planId);
      results.sort(Comparator.comparing(ConstraintActionResponse.ConstraintRecord::constraintId));
      assertEquals(2, results.size());
      assertEquals(constraintId, results.getFirst().constraintId());
      assertTrue(results.getFirst().success());

      final var problemResults = results.get(1);
      assertEquals(problemConstraintId.id(), problemResults.constraintId());
      assertFalse(problemResults.success());
      assertEquals(problemConstraintName, problemResults.constraintName());
      assertEquals(2, problemResults.errors().size());
      assertEquals("Constraint 'bad constraint' compilation failed:\n" +
              " TypeError: TS2306 No default export. Expected a default export function with the signature: " +
              "\"(...args: []) => Constraint | Promise<Constraint>\".",
          problemResults.errors().get(0).message());
      assertEquals("Constraint 'bad constraint' compilation failed:\n TypeError: TS1109 Expression expected.",
          problemResults.errors().get(1).message());
    } finally {
      hasura.deleteConstraint(problemConstraintId.id());
    }
  }

  @Nested
  class WithExternalDatasets {
    // Per-Test Data
    private int simDatasetId;
    private int externalDatasetId;
    // Cross-Test Constants
    private final ProfileInput myBooleanProfile = new ProfileInput(
        "/my_boolean",
        "discrete",
        ValueSchema.VALUE_SCHEMA_BOOLEAN,
        List.of(
            new ProfileSegmentInput(3600000000L, JsonValue.TRUE), // 1hr in micros
            new ProfileSegmentInput(3600000000L, JsonValue.FALSE)));

    public static final String constraintDefinition = "export default (): Constraint => Discrete.Resource(\"/my_boolean\").equal(true)";

    @BeforeEach
    void beforeEach() throws IOException {
      // Change Constraint to be about MyBoolean
      hasura.updateConstraintDefinition(
          constraintId,
          constraintDefinition);
      // Simulate Plan
      simDatasetId = hasura.awaitSimulation(planId).simDatasetId();
      // Add Simulation-Associated External Dataset
      final String externalDatasetStartTimestamp = "2021-001T06:00:00.000";
      externalDatasetId = hasura.insertExternalDataset(
          planId,
          simDatasetId,
          externalDatasetStartTimestamp,
          List.of(myBooleanProfile));
    }

    @AfterEach
    void afterEach() throws IOException {
      // Remove External Dataset
      hasura.deleteExternalDataset(planId, externalDatasetId);
    }

    @Test
    @DisplayName("Simulation-Associated External Datasets are loaded when Simulation is current")
    void oneViolationCurrentSimulation() throws IOException {
      // Constraint Results w/o SimDatasetId
      final var noDatasetResponses = hasura.checkConstraintsJustResults(planId);
      assertEquals(1, noDatasetResponses.size());
      assertTrue(noDatasetResponses.getFirst().success());
      assertEquals(constraintId, noDatasetResponses.getFirst().constraintId());
      assertEquals(constraintName, noDatasetResponses.getFirst().constraintName());
      assertTrue(noDatasetResponses.getFirst().result().isPresent());
      final var nRecordResults = noDatasetResponses.getFirst().result().get();

      // Constraint Results w/ SimDatasetId
      final var withDatasetResponses = hasura.checkConstraintsJustResults(planId, simDatasetId);
      assertEquals(1, withDatasetResponses.size());
      assertTrue(withDatasetResponses.getFirst().success());
      assertEquals(constraintId, withDatasetResponses.getFirst().constraintId());
      assertEquals(constraintName, withDatasetResponses.getFirst().constraintName());
      assertTrue(withDatasetResponses.getFirst().result().isPresent());
      final var wRecordResults = withDatasetResponses.getFirst().result().get();

      // The results should be the same
      assertEquals(nRecordResults, wRecordResults);


      // Resources
      assertEquals(1, nRecordResults.resourceIds().size());
      assertTrue(nRecordResults.resourceIds().contains("/my_boolean"));

      // Violation
      assertEquals(1, nRecordResults.violations().size());
      final var violation = nRecordResults.violations().getFirst();
      assertEquals(1, violation.windows().size());

      final long falseBeginMicros = 7 * 60 * 60 * 1000000L; // 7h in micros
      final long datasetEndMicros = 8 * 60 * 60 * 1000000L; // 8h in micros

      assertEquals(falseBeginMicros, violation.windows().getFirst().start());
      assertEquals(datasetEndMicros, violation.windows().getFirst().end());

      // Gaps
      // Two are expected: 1 from plan start to the external dataset start
      // and 1 from the external dataset end to plan end
      assertEquals(2, nRecordResults.gaps().size());
      final long datasetOffsetMicros = 6 * 60 * 60 * 1000000L; // 6h in micros
      final long planDuration = 1212 * 60 * 60 * 1000000L; // 1212h in micros

      final var firstGap = nRecordResults.gaps().getFirst();
      assertEquals(0, firstGap.start());
      assertEquals(datasetOffsetMicros, firstGap.end());

      final var secondGap = nRecordResults.gaps().get(1);
      assertEquals(datasetEndMicros, secondGap.start());
      assertEquals(planDuration, secondGap.end());
    }

    @Test
    @DisplayName("Outdated Simulation-Associated External Datasets are loaded when Outdated Simulation is passed")
    void oneViolationOutdatedSimIdPassed() throws IOException {
      // Delete Activity to make the simulation outdated, then resim
      hasura.deleteActivity(planId, activityId);
      hasura.awaitSimulation(planId);
      // Check constraints against the old simID (the one with the external dataset)
      final var constraintResponses = hasura.checkConstraintsJustResults(planId, simDatasetId);
      assertEquals(1, constraintResponses.size());

      final var constraintResponse = constraintResponses.getFirst();
      assertTrue(constraintResponse.success());
      assertEquals(constraintId, constraintResponse.constraintId());
      assertEquals(constraintName, constraintResponse.constraintName());
      assertTrue(constraintResponse.result().isPresent());
      final var record = constraintResponse.result().get();

      // Resources
      assertEquals(1, record.resourceIds().size());
      assertTrue(record.resourceIds().contains("/my_boolean"));

      // Violation
      assertEquals(1, record.violations().size());
      final var violation = record.violations().getFirst();
      assertEquals(1, violation.windows().size());

      final long falseBeginMicros = 7 * 60 * 60 * 1000000L; // 7h in micros
      final long datasetEndMicros = 8 * 60 * 60 * 1000000L; // 8h in micros

      assertEquals(falseBeginMicros, violation.windows().getFirst().start());
      assertEquals(datasetEndMicros, violation.windows().getFirst().end());

      // Gaps
      // Two are expected: 1 from plan start to the external dataset start
      // and 1 from the external dataset end to plan end
      assertEquals(2, record.gaps().size());
      final long datasetOffsetMicros = 6 * 60 * 60 * 1000000L; // 6h in micros
      final long planDuration = 1212 * 60 * 60 * 1000000L; // 1212h in micros

      final var firstGap = record.gaps().getFirst();
      assertEquals(0, firstGap.start());
      assertEquals(datasetOffsetMicros, firstGap.end());

      final var secondGap = record.gaps().get(1);
      assertEquals(datasetEndMicros, secondGap.start());
      assertEquals(planDuration, secondGap.end());
    }

    @Test
    @DisplayName("Simulation-Associated External Datasets aren't loaded when other SimIds are passed")
    void compilationFailsOutdatedSimulationSimDatasetId() throws IOException {
      // Delete Activity to make the simulation outdated, then resim
      hasura.deleteActivity(planId, activityId);
      final int newSimDatasetId = hasura.awaitSimulation(planId).simDatasetId();
      // This test causes the endpoint to throw an exception when it fails to compile the constraint,
      // as it cannot find the external dataset resource in the set of known resource types.
      final var constraintResponses = hasura.checkConstraintsJustResults(planId, newSimDatasetId);
      assertEquals(1,constraintResponses.size());
      final var constraintResponse = constraintResponses.getFirst();
      assertEquals(constraintId, constraintResponse.constraintId());
      assertEquals(constraintName, constraintResponse.constraintName());
      assertFalse(constraintResponse.success());
      assertTrue(constraintResponse.result().isEmpty());
      assertEquals(1,constraintResponse.errors().size());
      final var error = constraintResponse.errors().getFirst();
      assertEquals("Constraint 'fruit_equal_peel' compilation failed:\n"
                   + " TypeError: TS2345 Argument of type '\"/my_boolean\"' is not assignable to parameter of type 'ResourceName'.",error.message());
    }

    @Test
    @DisplayName("Outdated Simulation-Associated External Datasets aren't loaded")
    void compilationFailsOutdatedSimulationNoSimDataset() throws IOException {
      // Delete Activity to make the simulation outdated, then resim
      hasura.deleteActivity(planId, activityId);
      hasura.awaitSimulation(planId);

      // The constraint run is expected to fail with the following message because the constraint
      // DSL isn't being generated for datasets that are outdated.
      final var constraintResponses = hasura.checkConstraintsJustResults(planId);
      assertEquals(1,constraintResponses.size());
      final var constraintResponse = constraintResponses.getFirst();
      assertFalse(constraintResponse.success());
      assertEquals(constraintId, constraintResponse.constraintId());
      assertEquals(constraintName, constraintResponse.constraintName());
      assertTrue(constraintResponse.result().isEmpty());
      assertEquals(1,constraintResponse.errors().size());
      final ConstraintError error = constraintResponse.errors().getFirst();
      assertEquals("Constraint 'fruit_equal_peel' compilation failed:\n"
                   + " TypeError: TS2345 Argument of type '\"/my_boolean\"' is not assignable to parameter of type 'ResourceName'.",error.message());
    }
  }
}
