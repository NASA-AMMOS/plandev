package gov.nasa.jpl.aerie.e2e.bindings;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.utils.BaseURL;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;

import static gov.nasa.jpl.aerie.e2e.types.User.admin;
import static gov.nasa.jpl.aerie.e2e.types.User.nonOwner;
import static gov.nasa.jpl.aerie.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the uploadSimulationDataset endpoint.
 * Tests the full end-to-end flow of uploading externally-generated simulation datasets.
 */
public class UploadSimulationDatasetTests {
  // Requests
  private static Playwright playwright;
  private static APIRequestContext request;
  private static HasuraRequests hasura;

  // Per-Test Data
  private int modelId;
  private int planId;

  @BeforeAll
  static void beforeAll() {
    // Setup Requests
    playwright = Playwright.create();
    // Set all requests to go to the Merlin Server
    request = playwright.request().newContext(
        new APIRequest.NewContextOptions()
            .setBaseURL(BaseURL.MERLIN_SERVER.url));
    hasura = new HasuraRequests(playwright);
  }

  @AfterAll
  static void afterAll() {
    // Cleanup Requests
    hasura.close();
    request.dispose();
    playwright.close();
  }

  @BeforeEach
  void beforeEach() throws IOException, InterruptedException {
    // Insert the Mission Model
    try (final var gateway = new GatewayRequests(playwright)) {
      modelId = hasura.createMissionModel(
          gateway.uploadJarFile(),
          "Banananation (uploadSimulationDataset tests)",
          "aerie_e2e_tests",
          "Upload Simulation Dataset Tests");
    }

    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - Upload Simulation Dataset",
        "24:00:00",
        "2024-01-01T00:00:00+00:00",
        admin.session());
  }

  @AfterEach
  void afterEach() throws IOException {
    // Remove Model and Plan
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  @Nested
  class UploadSimulationDataset {

    @Test
    void invalidPlanId() {
      // Returns a 404 if the PlanId is invalid
      // message is "no such plan"
      final var profileSetBuilder = Json.createObjectBuilder()
          .add(
              "/my_boolean",
              Json.createObjectBuilder()
                  .add("type", "discrete")
                  .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                  .add("segments", Json.createArrayBuilder()
                      .add(Json.createObjectBuilder()
                          .add("duration", 3600000000L)
                          .add("dynamics", true))));

      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add(
              "input", Json.createObjectBuilder()
                  .add("planId", -1)
                  .add("simulationStart", "2024-001T00:00:00.000")
                  .add("simulationEnd", "2024-002T00:00:00.000")
                  .add("arguments", Json.createObjectBuilder().build())
                  .add("profileSet", profileSetBuilder))
          .add("request_query", "")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("no such plan", getBody(response).getString("message"));
    }

    @Test
    void validWithEmptyProfiles() {
      // Returns a 201 with empty profiles
      final var profileSetBuilder = Json.createObjectBuilder();

      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add(
              "input", Json.createObjectBuilder()
                  .add("planId", planId)
                  .add("simulationStart", "2024-001T00:00:00.000")
                  .add("simulationEnd", "2024-001T12:00:00.000")
                  .add("arguments", Json.createObjectBuilder().build())
                  .add("profileSet", profileSetBuilder))
          .add("request_query", "")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));
    }

    @Test
    void validWithDiscreteProfile() {
      // Returns a 201 with a discrete profile
      final var profileSetBuilder = Json.createObjectBuilder()
          .add(
              "/my_boolean",
              Json.createObjectBuilder()
                  .add("type", "discrete")
                  .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                  .add("segments", Json.createArrayBuilder()
                      .add(Json.createObjectBuilder()
                          .add("duration", 3600000000L)
                          .add("dynamics", true))
                      .add(Json.createObjectBuilder()
                          .add("duration", 3600000000L)
                          .add("dynamics", false))));

      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add(
              "input", Json.createObjectBuilder()
                  .add("planId", planId)
                  .add("simulationStart", "2024-001T00:00:00.000")
                  .add("simulationEnd", "2024-001T12:00:00.000")
                  .add("arguments", Json.createObjectBuilder()
                      .add("param1", "value1")
                      .add("param2", 42))
                  .add("profileSet", profileSetBuilder))
          .add("request_query", "")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));

      // Verify the returned ID is a positive integer
      final int simulationDatasetId = getBody(response).getInt("simulationDatasetId");
      assertTrue(simulationDatasetId > 0, "Simulation dataset ID should be positive");
    }

    @Test
    void validWithRealProfile() {
      // Returns a 201 with a real profile
      final var profileSetBuilder = Json.createObjectBuilder()
          .add(
              "/battery",
              Json.createObjectBuilder()
                  .add("type", "real")
                  .add("schema", Json.createObjectBuilder().add("type", "real"))
                  .add("segments", Json.createArrayBuilder()
                      .add(Json.createObjectBuilder()
                          .add("duration", 3600000000L)
                          .add("dynamics", Json.createObjectBuilder()
                              .add("initial", 100.0)
                              .add("rate", -0.5)))
                      .add(Json.createObjectBuilder()
                          .add("duration", 3600000000L)
                          .add("dynamics", Json.createObjectBuilder()
                              .add("initial", 98.2)
                              .add("rate", -0.3)))));

      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add(
              "input", Json.createObjectBuilder()
                  .add("planId", planId)
                  .add("simulationStart", "2024-001T00:00:00.000")
                  .add("simulationEnd", "2024-002T00:00:00.000")
                  .add("arguments", Json.createObjectBuilder().build())
                  .add("profileSet", profileSetBuilder))
          .add("request_query", "")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));
    }

    @Test
    void validWithMultipleProfiles() {
      // Returns a 201 with multiple profiles (both real and discrete)
      final var profileSetBuilder = Json.createObjectBuilder()
          .add(
              "/battery",
              Json.createObjectBuilder()
                  .add("type", "real")
                  .add("schema", Json.createObjectBuilder().add("type", "real"))
                  .add("segments", Json.createArrayBuilder()
                      .add(Json.createObjectBuilder()
                          .add("duration", 7200000000L)
                          .add("dynamics", Json.createObjectBuilder()
                              .add("initial", 100.0)
                              .add("rate", -1.0)))))
          .add(
              "/mode",
              Json.createObjectBuilder()
                  .add("type", "discrete")
                  .add("schema", Json.createObjectBuilder().add("type", "string"))
                  .add("segments", Json.createArrayBuilder()
                      .add(Json.createObjectBuilder()
                          .add("duration", 3600000000L)
                          .add("dynamics", "IDLE"))
                      .add(Json.createObjectBuilder()
                          .add("duration", 3600000000L)
                          .add("dynamics", "ACTIVE"))));

      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add(
              "input", Json.createObjectBuilder()
                  .add("planId", planId)
                  .add("simulationStart", "2024-001T00:00:00.000")
                  .add("simulationEnd", "2024-001T12:00:00.000")
                  .add("arguments", Json.createObjectBuilder()
                      .add("simulationConfig", "external"))
                  .add("profileSet", profileSetBuilder))
          .add("request_query", "")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));
    }

    @Test
    void missingRequiredField() {
      // Returns a 400 if required field is missing
      final var profileSetBuilder = Json.createObjectBuilder();

      // Missing simulationEnd
      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add(
              "input", Json.createObjectBuilder()
                  .add("planId", planId)
                  .add("simulationStart", "2024-001T00:00:00.000")
                  // Missing simulationEnd
                  .add("arguments", Json.createObjectBuilder().build())
                  .add("profileSet", profileSetBuilder))
          .add("request_query", "")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(400, response.status());
    }

    @Test
    void unauthorizedUser() {
      // Returns a 403 if user doesn't have permission
      final var profileSetBuilder = Json.createObjectBuilder();

      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add(
              "input", Json.createObjectBuilder()
                  .add("planId", planId)
                  .add("simulationStart", "2024-001T00:00:00.000")
                  .add("simulationEnd", "2024-001T12:00:00.000")
                  .add("arguments", Json.createObjectBuilder().build())
                  .add("profileSet", profileSetBuilder))
          .add("request_query", "")
          .add("session_variables", nonOwner.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(403, response.status());
    }
  }
}
