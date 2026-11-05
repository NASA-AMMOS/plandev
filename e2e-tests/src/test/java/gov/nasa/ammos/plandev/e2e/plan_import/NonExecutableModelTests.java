package gov.nasa.ammos.plandev.e2e.plan_import;

import com.microsoft.playwright.Playwright;
import gov.nasa.ammos.plandev.e2e.types.workspaces.HasuraRequestFailure;
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

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parameterized test class that tests Non-Executable Model behavior
 * independent of whether that model is defined by a JAR or JSON file.
 */
@ParameterizedClass
@ValueSource(booleans = {false, true})
@Tag("plan_import")
public record NonExecutableModelTests(boolean jarModel) {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;

  // Per plan data
  private static int planId;
  private static int modelId;

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
  static void modelSetup(boolean nonExecutableModel) throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      final var fileId = gateway.uploadJarFile();
      if(nonExecutableModel) {
        // import a plan.json, then mark the plan as not readonly
        final var rqInfo = hasura.importPlan(gateway, "Non-Executable Editable Imported Plan");
        planId = rqInfo.planId();
        modelId = rqInfo.modelId();
        hasura.setPlanReadOnly(planId, false);
      } else {
        // upload a model, create a plan, then mark the model as non-executable
        modelId = hasura.createMissionModel(fileId, "Non-Executable JAR Model", "Non-Executable Model Tests", "E2ETest");
        planId = hasura.createPlan(
            modelId,
            "Non-Executable Editable Plan",
            "24:00:00",
            "2026-01-01T00:00:00Z");
        hasura.setModelExecutability(modelId, false);
      }
    }
  }

  @AfterParameterizedClassInvocation
  static void modelTeardown() throws IOException {
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  /**
   * Models marked as non-executable may not be simulated.
   */
  @Test
  @Tag("simulation")
  void cannotSimulate() {
    final var failure = assertThrows(HasuraRequestFailure.class, () -> hasura.awaitSimulation(planId));
    assertEquals(
        "Mission model `%d` is non-executable, so it has no code to load.".formatted(modelId),
        failure.getMessage());
  }

  /**
   * Models marked as non-executable may not be scheduled.
   */
  @Test
  @Tag("scheduling")
  void cannotSchedule() throws IOException {
    final var specId = hasura.getSchedulingSpecId(planId);
    final var failure = assertThrows(HasuraRequestFailure.class, () -> hasura.awaitScheduling(specId));
    assertEquals(
        "Mission Model for Plan is marked as non-executable and cannot be used in scheduling.".formatted(modelId),
        failure.getMessage());
  }
}

