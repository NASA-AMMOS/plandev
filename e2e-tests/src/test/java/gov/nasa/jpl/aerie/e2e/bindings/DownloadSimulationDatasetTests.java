package gov.nasa.jpl.aerie.e2e.bindings;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.types.ActionPermissionsSet;
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
import javax.json.JsonObject;
import java.io.IOException;
import java.util.Map;

import static gov.nasa.jpl.aerie.e2e.types.User.admin;
import static gov.nasa.jpl.aerie.e2e.types.User.nonOwner;
import static gov.nasa.jpl.aerie.e2e.types.User.viewer;
import static gov.nasa.jpl.aerie.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the downloadSimulationDataset endpoint.
 * Datasets are seeded through uploadSimulationDataset, so these tests also cover
 * the round trip of a simulation dataset through Merlin.
 */
public class DownloadSimulationDatasetTests {
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
          "Banananation (downloadSimulationDataset tests)",
          "aerie_e2e_tests",
          "Download Simulation Dataset Tests");
    }

    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - Download Simulation Dataset",
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
  class DownloadSimulationDataset {

    private static JsonObject minimalSimResults(String start, String end) {
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

    /** Simulation results with one of everything the download format can carry. */
    private static JsonObject populatedSimResults() {
      return Json.createObjectBuilder()
          .add("simulationStartTime", "2024-001T00:00:00.000")
          .add("simulationEndTime", "2024-001T02:00:00.000")
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
                                  .add("initial", 99.5)
                                  .add("rate", 0.0))))))
              .add("discreteProfiles", Json.createArrayBuilder()
                  .add(Json.createObjectBuilder()
                      .add("name", "/mode")
                      .add("schema", Json.createObjectBuilder().add("type", "string"))
                      .add("segments", Json.createArrayBuilder()
                          .add(Json.createObjectBuilder()
                              .add("extent", "02:00:00.000000")
                              .add("dynamics", "IDLE"))))))
          .add("spans", Json.createObjectBuilder()
              .add("simulatedActivities", Json.createArrayBuilder()
                  .add(Json.createObjectBuilder()
                      .add("id", 1)
                      .addNull("directiveId")
                      .addNull("parentId")
                      .add("childIds", Json.createArrayBuilder())
                      .add("type", "BiteBanana")
                      .add("duration", "00:30:00.000000")
                      .add("attributes", Json.createObjectBuilder()
                          .add("type", "string")
                          .add("value", "done"))
                      .add("arguments", Json.createObjectBuilder()
                          .add("biteSize", Json.createObjectBuilder()
                              .add("type", "real")
                              .add("value", 1.0)))
                      .add("startTime", "2024-001T01:00:00.000")))
              .add("unfinishedActivities", Json.createArrayBuilder()))
          .add("simulationArguments", Json.createObjectBuilder()
              .add("initialPlantCount", Json.createObjectBuilder()
                  .add("type", "int")
                  .add("value", 200)))
          .build();
    }

    private static String buildRequest(int pid, long simulationDatasetId, JsonObject session) {
      return Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "downloadSimulationDataset"))
          .add("input", Json.createObjectBuilder()
              .add("planId", pid)
              .add("simulationDatasetId", simulationDatasetId))
          .add("request_query", "query { downloadSimulationDataset }")
          .add("session_variables", session)
          .build()
          .toString();
    }

    /** Seed a simulation dataset on the test plan and return its id. */
    private int uploadDataset(JsonObject simResults) {
      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
          .add("input", Json.createObjectBuilder()
              .add("planId", planId)
              .add("simulationResults", simResults))
          .add("request_query", "mutation { uploadSimulationDataset }")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/uploadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status(), "Failed to seed the simulation dataset to download");
      return getBody(response).getInt("simulationDatasetId");
    }

    @Test
    void invalidPlanId() {
      final String data = buildRequest(-1, 1, admin.getSession());
      final var response = request.post("/downloadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No plan exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void missingSimulationDatasetId() {
      // Returns a 400 when simulationDatasetId is missing entirely
      final String data = Json.createObjectBuilder()
          .add("action", Json.createObjectBuilder().add("name", "downloadSimulationDataset"))
          .add("input", Json.createObjectBuilder().add("planId", planId))
          .add("request_query", "query { downloadSimulationDataset }")
          .add("session_variables", admin.getSession())
          .build()
          .toString();

      final var response = request.post("/downloadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(400, response.status());
    }

    @Test
    void nonexistentSimulationDatasetId() {
      // A dataset id that does not exist falls through to the catch-all error handler
      final String data = buildRequest(planId, 9999999, admin.getSession());
      final var response = request.post("/downloadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(500, response.status());
      assertEquals(
          "No simulation dataset with id `9999999` exists",
          getBody(response).getString("message"));
    }

    @Test
    void forbidden() throws IOException {
      // 403: Forbidden requires updating permissions, as 'user' may download by default
      final var ogPermissions = hasura.getActionPermissionsForRole("user");
      final var tempPermission = new ActionPermissionsSet(Map.of(
          ActionPermissionsSet.ActionKey.resource_samples,
          ActionPermissionsSet.Permission.PLAN_OWNER));
      hasura.updateActionPermissionsForRole("user", tempPermission);

      try {
        final String data = buildRequest(planId, 1, nonOwner.getSession());
        final var response = request.post("/downloadSimulationDataset", RequestOptions.create().setData(data));
        assertEquals(403, response.status());
        assertEquals(
            "User '" + nonOwner.name() + "' with role 'user' cannot perform 'resource_samples' because they "
            + "are not a 'PLAN_OWNER' for plan with id '" + planId + "'",
            getBody(response).getString("message"));
      } finally {
        // Fix Permissions
        hasura.updateActionPermissionsForRole("user", ogPermissions);
        assertEquals(ogPermissions, hasura.getActionPermissionsForRole("user"));
      }
    }

    @Test
    void validWithEmptyProfiles() {
      final int simulationDatasetId = uploadDataset(
          minimalSimResults("2024-001T00:00:00.000", "2024-001T12:00:00.000"));

      final String data = buildRequest(planId, simulationDatasetId, admin.getSession());
      final var response = request.post("/downloadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(200, response.status());

      final var simResults = getBody(response).getJsonObject("simulationResults");
      assertEquals("2024-001T00:00:00", simResults.getString("simulationStartTime"));
      assertEquals("2024-001T12:00:00", simResults.getString("simulationEndTime"));

      final var profiles = simResults.getJsonObject("profiles");
      assertTrue(profiles.getJsonArray("realProfiles").isEmpty());
      assertTrue(profiles.getJsonArray("discreteProfiles").isEmpty());

      final var spans = simResults.getJsonObject("spans");
      assertTrue(spans.getJsonArray("simulatedActivities").isEmpty());
      assertTrue(spans.getJsonArray("unfinishedActivities").isEmpty());

      // Empty sections are omitted from the download payload
      assertFalse(simResults.containsKey("topics"));
      assertFalse(simResults.containsKey("events"));
    }

    @Test
    void validRoundTrip() {
      final int simulationDatasetId = uploadDataset(populatedSimResults());

      final String data = buildRequest(planId, simulationDatasetId, admin.getSession());
      final var response = request.post("/downloadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(200, response.status());

      final var simResults = getBody(response).getJsonObject("simulationResults");
      assertEquals("2024-001T00:00:00", simResults.getString("simulationStartTime"));
      assertEquals("2024-001T02:00:00", simResults.getString("simulationEndTime"));

      // Real profile
      final var realProfiles = simResults.getJsonObject("profiles").getJsonArray("realProfiles");
      assertEquals(1, realProfiles.size());
      final var battery = realProfiles.getJsonObject(0);
      assertEquals("/battery", battery.getString("name"));
      assertEquals("real", battery.getJsonObject("schema").getString("type"));
      final var batterySegments = battery.getJsonArray("segments");
      assertEquals(2, batterySegments.size());
      assertEquals(
          100.0,
          batterySegments.getJsonObject(0).getJsonObject("dynamics").getJsonNumber("initial").doubleValue());
      assertEquals(
          -0.5,
          batterySegments.getJsonObject(0).getJsonObject("dynamics").getJsonNumber("rate").doubleValue());

      // Discrete profile
      final var discreteProfiles = simResults.getJsonObject("profiles").getJsonArray("discreteProfiles");
      assertEquals(1, discreteProfiles.size());
      final var mode = discreteProfiles.getJsonObject(0);
      assertEquals("/mode", mode.getString("name"));
      assertEquals("string", mode.getJsonObject("schema").getString("type"));
      assertEquals(1, mode.getJsonArray("segments").size());

      // Spans
      final var simulatedActivities = simResults.getJsonObject("spans").getJsonArray("simulatedActivities");
      assertEquals(1, simulatedActivities.size());
      final var activity = simulatedActivities.getJsonObject(0);
      assertEquals("BiteBanana", activity.getString("type"));
      assertEquals("2024-001T01:00:00", activity.getString("startTime"));
      assertTrue(activity.getJsonObject("arguments").containsKey("biteSize"));
      assertTrue(simResults.getJsonObject("spans").getJsonArray("unfinishedActivities").isEmpty());

      // Simulation arguments
      assertEquals(
          200,
          simResults.getJsonObject("simulationArguments").getJsonObject("initialPlantCount").getInt("value"));
    }

    @Test
    void viewerMayDownload() {
      // Downloading is gated on 'resource_samples', which the 'viewer' role holds as NO_CHECK,
      // so a viewer may export results from a plan they do not own
      final int simulationDatasetId = uploadDataset(
          minimalSimResults("2024-001T00:00:00.000", "2024-001T12:00:00.000"));

      final String data = buildRequest(planId, simulationDatasetId, viewer.getSession());
      final var response = request.post("/downloadSimulationDataset", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      assertTrue(getBody(response).containsKey("simulationResults"));
    }
  }
}
