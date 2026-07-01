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
 * Tests the full end-to-end flow of uploading simulation datasets into a plan.
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

    private static javax.json.JsonObject minimalSimResults(String start, String end) {
      return Json.createObjectBuilder()
          .add("simulationStartTime", start)
          .add("simulationEndTime", end)
          .add("profiles", Json.createObjectBuilder()
              .add("realProfiles", Json.createArrayBuilder())
              .add("discreteProfiles", Json.createArrayBuilder()))
          .add("spans", Json.createObjectBuilder()
              .add("simulatedActivities", Json.createArrayBuilder())
              .add("unfinishedActivities", Json.createArrayBuilder()))
          .build();
    }

    private static String buildRequest(int pid, javax.json.JsonObject simResults, javax.json.JsonObject session) {
      return Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add("input", Json.createObjectBuilder()
              .add("planId", pid)
              .add("simulationResults", simResults))
          .add("request_query", "mutation { uploadSimulationDataset }")
          .add("session_variables", session)
          .build()
          .toString();
    }

    @Test
    void invalidPlanId() {
      final String data = buildRequest(-1, minimalSimResults("2024-001T00:00:00.000", "2024-002T00:00:00.000"), admin.getSession());
      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("no such plan", getBody(response).getString("message"));
    }

    @Test
    void validWithEmptyProfiles() {
      final String data = buildRequest(planId, minimalSimResults("2024-001T00:00:00.000", "2024-001T12:00:00.000"), admin.getSession());
      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));
    }

    @Test
    void validWithDiscreteProfile() {
      final var simResults = Json.createObjectBuilder()
          .add("simulationStartTime", "2024-001T00:00:00.000")
          .add("simulationEndTime", "2024-001T12:00:00.000")
          .add("profiles", Json.createObjectBuilder()
              .add("realProfiles", Json.createArrayBuilder())
              .add("discreteProfiles", Json.createArrayBuilder()
                  .add(Json.createObjectBuilder()
                      .add("name", "/my_boolean")
                      .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                      .add("segments", Json.createArrayBuilder()
                          .add(Json.createObjectBuilder()
                              .add("extent", "01:00:00.000000")
                              .add("dynamics", true))
                          .add(Json.createObjectBuilder()
                              .add("extent", "01:00:00.000000")
                              .add("dynamics", false))))))
          .add("spans", Json.createObjectBuilder()
              .add("simulatedActivities", Json.createArrayBuilder())
              .add("unfinishedActivities", Json.createArrayBuilder()))
          .build();

      final String data = buildRequest(planId, simResults, admin.getSession());
      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));

      final int simulationDatasetId = getBody(response).getInt("simulationDatasetId");
      assertTrue(simulationDatasetId > 0, "Simulation dataset ID should be positive");
    }

    @Test
    void validWithRealProfile() {
      final var simResults = Json.createObjectBuilder()
          .add("simulationStartTime", "2024-001T00:00:00.000")
          .add("simulationEndTime", "2024-002T00:00:00.000")
          .add("profiles", Json.createObjectBuilder()
              .add("realProfiles", Json.createArrayBuilder()
                  .add(Json.createObjectBuilder()
                      .add("name", "/battery")
                      .add("schema", Json.createObjectBuilder().add("type", "real"))
                      .add("segments", Json.createArrayBuilder()
                          .add(Json.createObjectBuilder()
                              .add("extent", "01:00:00.000000")
                              .add("dynamics", Json.createObjectBuilder()
                                  .add("initial", 100.0)
                                  .add("rate", -0.5)))
                          .add(Json.createObjectBuilder()
                              .add("extent", "01:00:00.000000")
                              .add("dynamics", Json.createObjectBuilder()
                                  .add("initial", 98.2)
                                  .add("rate", -0.3))))))
              .add("discreteProfiles", Json.createArrayBuilder()))
          .add("spans", Json.createObjectBuilder()
              .add("simulatedActivities", Json.createArrayBuilder())
              .add("unfinishedActivities", Json.createArrayBuilder()))
          .build();

      final String data = buildRequest(planId, simResults, admin.getSession());
      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));
    }

    @Test
    void validWithMultipleProfiles() {
      final var simResults = Json.createObjectBuilder()
          .add("simulationStartTime", "2024-001T00:00:00.000")
          .add("simulationEndTime", "2024-001T12:00:00.000")
          .add("profiles", Json.createObjectBuilder()
              .add("realProfiles", Json.createArrayBuilder()
                  .add(Json.createObjectBuilder()
                      .add("name", "/battery")
                      .add("schema", Json.createObjectBuilder().add("type", "real"))
                      .add("segments", Json.createArrayBuilder()
                          .add(Json.createObjectBuilder()
                              .add("extent", "02:00:00.000000")
                              .add("dynamics", Json.createObjectBuilder()
                                  .add("initial", 100.0)
                                  .add("rate", -1.0))))))
              .add("discreteProfiles", Json.createArrayBuilder()
                  .add(Json.createObjectBuilder()
                      .add("name", "/mode")
                      .add("schema", Json.createObjectBuilder().add("type", "string"))
                      .add("segments", Json.createArrayBuilder()
                          .add(Json.createObjectBuilder()
                              .add("extent", "01:00:00.000000")
                              .add("dynamics", "IDLE"))
                          .add(Json.createObjectBuilder()
                              .add("extent", "01:00:00.000000")
                              .add("dynamics", "ACTIVE"))))))
          .add("spans", Json.createObjectBuilder()
              .add("simulatedActivities", Json.createArrayBuilder())
              .add("unfinishedActivities", Json.createArrayBuilder()))
          .build();

      final String data = buildRequest(planId, simResults, admin.getSession());
      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("simulationDatasetId"));
      assertFalse(getBody(response).isNull("simulationDatasetId"));
    }

    @Test
    void missingRequiredField() {
      // Returns a 400 when simulationResults is missing entirely
      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add("input", Json.createObjectBuilder()
              .add("planId", planId))
          .add("request_query", "mutation { uploadSimulationDataset }")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(400, response.status());
    }

    @Test
    void unauthorizedUser() {
      final String data = buildRequest(planId, minimalSimResults("2024-001T00:00:00.000", "2024-001T12:00:00.000"), nonOwner.getSession());
      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(403, response.status());
    }
  }
}
