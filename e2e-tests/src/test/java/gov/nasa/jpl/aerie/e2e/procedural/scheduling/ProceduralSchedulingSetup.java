package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ProceduralSchedulingSetup {

  // Requests
  protected Playwright playwright;
  protected HasuraRequests hasura;

  // Per-Test Data
  protected int modelId;
  protected int planId;
  protected int specId;

  // Cross-Test Constants
  protected final String planStartTimestamp = "2023-01-01T00:00:00+00:00";


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
    try (final var gateway = new GatewayRequests(playwright)) {
      modelId = hasura.createMissionModel(
          gateway.uploadJarFile(),
          "Banananation (e2e tests)",
          "aerie_e2e_tests",
          "Proc Scheduling Tests for subclass: %s".formatted(this.getClass().getSimpleName()));


    }
    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Proc Sched Plan - Proc Scheduling Tests for subclass: %s".formatted(this.getClass().getSimpleName()),
        "48:00:00",
        planStartTimestamp);

    specId = hasura.getSchedulingSpecId(planId);
  }

  @AfterEach
  void afterEach() throws IOException {
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }
}
