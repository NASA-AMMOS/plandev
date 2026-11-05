package gov.nasa.ammos.plandev.e2e.plan_import;

import com.microsoft.playwright.Playwright;
import gov.nasa.ammos.plandev.e2e.types.workspaces.HasuraRequestFailure;
import gov.nasa.ammos.plandev.e2e.utils.ExternalEventUtils;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import gov.nasa.ammos.plandev.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.Json;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameterized class that tests Read Only Plan behavior independent of whether the model is Executable.
 */
@ParameterizedClass
@ValueSource(booleans = {false, true})
@Tag("plan_import")
public record ReadOnlyPlanTests(boolean nonExecutableModel) {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;

  // Per plan data
  private static int planId;
  private static int modelId;

  // Class Level Data
  private static int constraintJarId;
  private static int goalJarId;

  @BeforeAll
  static void rqSetup() {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
  }

  @AfterAll
  static void rqTeardown() {
    hasura.close();
    playwright.close();
  }

  @BeforeParameterizedClassInvocation
  static void planSetup(boolean nonExecutableModel) throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      goalJarId = gateway.uploadJarFile("build/libs/DumbRecurrenceGoal.jar");
      constraintJarId = gateway.uploadJarFile("build/libs/FruitThresholdConstraint.jar");

      final var fileId = gateway.uploadJarFile();
      if(nonExecutableModel) {
        // import a plan.json
        final var rqInfo = hasura.importPlan(gateway, "NonExecutable ReadOnly Plan");
        planId = rqInfo.planId();
        modelId = rqInfo.modelId();
      } else {
        // create a normal plan, simulate it, and then mark it read only
        modelId = hasura.createMissionModel(fileId, "Executable Readonly Model", "Readonly Plan Tests", "E2ETest");
        planId = hasura.createPlan(
            modelId,
            "Executable ReadOnly Plan",
            "24:00:00",
            "2026-01-01T00:00:00Z");
        hasura.setPlanReadOnly(planId, true);
        hasura.awaitSimulation(planId);
      }
    }
  }

  @AfterParameterizedClassInvocation
  static void planTeardown() throws IOException {
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  /**
   * Read Only Plans can be simulated IFF their model is executable
   */
  @Test
  @Tag("simulation")
  void testSimulate() {
    if(nonExecutableModel) {
      final var failure = assertThrows(HasuraRequestFailure.class, () -> hasura.awaitSimulation(planId));
      assertEquals("Mission model `%d` is non-executable, so it has no code to load.".formatted(modelId), failure.getMessage());
    } else {
      assertDoesNotThrow(() -> hasura.awaitSimulation(planId));
    }
  }

  /**
   * Read Only plans cannot have scheduling goals associated with them
   */
  @Test
  @Tag("scheduling")
  void cannotAddSchedulingGoals() throws IOException {
    final var specId = hasura.getSchedulingSpecId(planId);
    final var ex = assertThrows(
        HasuraRequestFailure.class,
        () -> hasura.createSchedulingSpecProcedure("Scheduling Goal", goalJarId, specId, 0));
    assertEquals("Plan %d is marked as Read Only and cannot be edited.".formatted(planId), ex.getDatabaseError().getString("message"));
  }

  /**
   * Read Only Plans cannot be scheduled.
   */
  @Test
  @Tag("scheduling")
  void cannotSchedule() throws IOException {
    final var specId = hasura.getSchedulingSpecId(planId);
    final var failure = assertThrows(HasuraRequestFailure.class, () -> hasura.awaitScheduling(specId));
    assertEquals("Plan is marked as read only and cannot be scheduled.", failure.getMessage());
  }

  /**
   * Read Only plans can have constraints added and checked.
   */
  @Test
  @Tag("constraints")
  void canRunConstraint() throws IOException {
    // Add a constraint
    final var constraintId = hasura.createConstraintSpecProcedure("Constraint", constraintJarId, planId);
    hasura.updateConstraintArguments(
        constraintId.id(),
        Json.createObjectBuilder()
            .add("lowerBound", 0)
            .add("upperBound", 4)
            .build());
    try {
      // check constraints
      final var results = hasura.checkConstraintsJustResults(planId);
      assertFalse(results.isEmpty());
      assertEquals(1, results.size());

      final var res = results.getFirst();
      assertTrue(res.success());
      assertEquals(constraintId.id(), res.constraintId());
      assertEquals(constraintId.invocationId(), res.constraintInvocationId());
      assertTrue(res.errors().isEmpty());
    } finally {
      hasura.deleteConstraint(constraintId.id());
    }
  }

  /**
   * Read Only Plans can have their External Events associations updated.
   */
  @Test
  @Tag("external_events")
  void canHaveExternalEvents() throws IOException {
    final String eventType = "readOnlyPlanEvent";
    final String sourceType = "readOnlyPlanSourceType";
    final String sourceKey = "readOnlyPlanSourceKey";
    final String derivationGroup = "readOnlyPlanDerivationGroup";

    // Creating an instance of `ExternalEventUtils` uploads the necessary external event test data to the DB.
    // Closing this instance will automatically clean up that data
    try (final var util = new ExternalEventUtils(
        playwright,
        hasura,
        sourceType,
        sourceKey,
        eventType,
        derivationGroup)
    ) {
      // Associate External Events
      assertDoesNotThrow(() -> hasura.insertPlanDerivationGroupAssociation(planId, derivationGroup));
      // Remove associated External Events
      assertDoesNotThrow(() -> hasura.deletePlanDerivationGroupAssociation(planId, derivationGroup));
    }
  }
}
