package gov.nasa.ammos.plandev.e2e.routes;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.ammos.plandev.e2e.types.ActionPermissionsSet;
import gov.nasa.ammos.plandev.e2e.types.ExternalDataset;
import gov.nasa.ammos.plandev.e2e.types.ValueSchema;
import gov.nasa.ammos.plandev.e2e.utils.BaseURL;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import gov.nasa.ammos.plandev.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_admin;
import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_nonOwner;
import static gov.nasa.ammos.plandev.e2e.utils.RequestBodyHelper.getArrayBody;
import static gov.nasa.ammos.plandev.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

@Tag("routes")
@Tag("merlin")
// "resourceTypes" and "getActivityEffectiveArguments" are not tested, as they are deprecated
public class MerlinRoutesTests {
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
    // Set all rqs to go to the Merlin Server
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
          "Banananation (e2e tests)",
          "plandev_e2e_tests",
          "Merlin Bindings");
    }

    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - Merlin Bindings",
        "24:00:00",
        "2023-01-01T00:00:00+00:00",
        routes_admin.session());
  }

  @AfterEach
  void afterEach() throws IOException {
    // Remove Model and Plan
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  @Nested
  class GetSimulationResults {
    @Test
    void invalidPlanId() {
      // Returns a 404 if the PlanId is invalid
      // message is "no such plan"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "simulate"))
                              .add("input", Json.createObjectBuilder().add("planId", -1))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getSimulationResults", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No plan exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void forbidden() {
      // Returns a 403 if Forbidden
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "simulate"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_nonOwner.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getSimulationResults", RequestOptions.create().setData(data));
      assertEquals(403, response.status());
      assertEquals(
          "User '" + routes_nonOwner.name() + "' with role 'user' cannot perform 'simulate' because they are not "
          + "a 'PLAN_OWNER_COLLABORATOR' for plan with id '" + planId + "'",
          getBody(response).getString("message"));
    }

    @Test
    void valid() throws InterruptedException {
      // Returns a 200 otherwise
      // "status" is not "failed"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "simulate"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getSimulationResults", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      assertNotEquals("failed", getBody(response).getString("status"));
      // Delay 1s to allow any workers to finish with the request
      Thread.sleep(1000);
    }

    static Stream<Arguments> forceArgs() {
      return Stream.of(
          Arguments.arguments(named("valid, force is NULL", JsonValue.NULL)),
          Arguments.arguments(named("valid, force is TRUE", JsonValue.TRUE)),
          Arguments.arguments(named("valid, force is FALSE", JsonValue.FALSE))
      );
    }

    @ParameterizedTest
    @MethodSource("forceArgs")
    void validWithForce(JsonValue force) throws InterruptedException {
      // Returns a 200 otherwise
      // "status" is not "failed"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "simulate"))
                              .add(
                                  "input",
                                  Json.createObjectBuilder().add("planId", planId).add("force", force))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getSimulationResults", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      assertNotEquals("failed", getBody(response).getString("status"));
      // Delay 1s to allow any workers to finish with the request
      Thread.sleep(1000);
    }
  }

  @Nested
  class ResourceSamples {
    @Test
    void invalidPlanId() {
      // Returns a 404 if the PlanId is invalid
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "resource_samples"))
                              .add("input", Json.createObjectBuilder().add("planId", -1))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/resourceSamples", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No plan exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void forbidden() throws IOException {
      // 403: Forbidden requires updating permissions
      final var ogPermissions = hasura.getActionPermissionsForRole("user");
      final var tempPermission = new ActionPermissionsSet(Map.of(
          ActionPermissionsSet.ActionKey.resource_samples,
          ActionPermissionsSet.Permission.PLAN_OWNER));
      hasura.updateActionPermissionsForRole("user", tempPermission);

      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "resource_samples"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_nonOwner.getSession())
                              .build()
                              .toString();
      final var response = request.post("/resourceSamples", RequestOptions.create().setData(data));
      assertEquals(403, response.status());
      assertEquals(
          "User '" + routes_nonOwner.name() + "' with role 'user' cannot perform 'resource_samples' because they "
          + "are not a 'PLAN_OWNER' for plan with id '" + planId + "'",
          getBody(response).getString("message"));

      // Fix Permissions
      hasura.updateActionPermissionsForRole("user", ogPermissions);
      assertEquals(ogPermissions, hasura.getActionPermissionsForRole("user"));
    }

    @Test
    void valid() {
      // Returns 200 otherwise
      // resourceSamples is empty since no sim has been run
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "resource_samples"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/resourceSamples", RequestOptions.create().setData(data));
      final var jsonBody = getBody(response);
      assertEquals(200, response.status());
      assertTrue(jsonBody.containsKey("resourceSamples"));
      assertEquals(JsonValue.EMPTY_JSON_OBJECT, jsonBody.getJsonObject("resourceSamples"));
    }
  }

  @Nested
  class ConstraintViolations {
    @Test
    void invalidPlanId() {
      // Returns a 404 if the PlanId is invalid
      // message is "no such plan"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                              .add("input", Json.createObjectBuilder().add("planId", -1))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No plan exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void invalidSimDatasetId() throws IOException {
      // Returns a 404 if the SimDatasetId is invalid
      hasura.awaitSimulation(planId);
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("planId", planId)
                                               .add("simulationDatasetId", -1))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
      assertEquals(404, response.status());

      final var body = getBody(response);
      final var extensions = body.getJsonObject("extensions");
      // Check the message field
      final var expectedMessage = "simulation dataset with id `-1` does not exist";
      assertEquals(expectedMessage, body.getString("message"));

      // Check the extensions
      assertEquals("INPUT_MISMATCH_EXCEPTION", extensions.getString("type"));
      assertEquals(expectedMessage, extensions.getString("message"));
      assertEquals("merlin_server", extensions.getString("service"));
    }

    @Test
    void incorrectSimDatasetId() throws IOException {
      // Setup: Create and simulate a temporary second plan
      final int secondPlanId = hasura.createPlan(
          modelId,
          "Temp Second Plan",
          "24:00:00",
          "2023-01-01T00:00:00+00:00");

      try {
        final int simDatasetId = hasura.awaitSimulation(secondPlanId).simDatasetId();

        // Returns a 404 because the simDataset belonged to a different plan
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                                .add(
                                    "input", Json.createObjectBuilder()
                                                 .add("planId", planId)
                                                 .add("simulationDatasetId", simDatasetId))
                                .add("request_query", "")
                                .add("session_variables", routes_admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
        assertEquals(404, response.status());

        // Check the response
        final var body = getBody(response);
        final var extensions = body.getJsonObject("extensions");

        // Check the message field
        final var expectedMessage = "Simulation Dataset with id `" + simDatasetId + "` does not belong to Plan with id `" + planId + "`";
        assertEquals(expectedMessage, body.getString("message"));

        // Check the extensions object
        assertEquals("SIM_DATASET_MISMATCH_EXCEPTION", extensions.getString("type"));
        assertEquals(expectedMessage, extensions.getString("message"));
        assertEquals("merlin_server", extensions.getString("service"));
      } finally {
        hasura.deletePlan(secondPlanId);
      }
    }

    @Test
    void forbidden() {
      // Returns a 403 if Forbidden
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_nonOwner.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
      assertEquals(403, response.status());
      assertEquals(
          "User '" + routes_nonOwner.name() + "' with role 'user' cannot perform 'check_constraints' because they"
          + " are not a 'PLAN_OWNER_COLLABORATOR' for plan with id '" + planId + "'",
          getBody(response).getString("message"));
    }

    @Test
    void noSimDatasets() {
      // Returns a 404 if no simulation datasets are found
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
      assertEquals(404, response.status());

      // Check the response
      final var body = getBody(response);
      final var extensions = body.getJsonObject("extensions");

      // Check the message field
      final var expectedMessage = "plan with id " + planId + " has not yet been simulated at its current revision";
      assertEquals(expectedMessage, body.getString("message"));

      // Check the extensions object
      assertEquals("INPUT_MISMATCH_EXCEPTION", extensions.getString("type"));
      assertEquals(expectedMessage, extensions.getString("message"));
      assertEquals("merlin_server", extensions.getString("service"));
    }

    @Test
    void valid() throws IOException {
      // Setup: Run a Simulation
      hasura.awaitSimulation(planId);

      // Returns a 200 because Sim Dataset exists
      // results are an empty array because there are no constraints that could've failed
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
      assertEquals(200, response.status());

      final var body = getBody(response);
      assertTrue(body.containsKey("requestId"));
      assertFalse(body.isNull("requestId"));
      assertTrue(body.containsKey("constraintsRun"));
      assertEquals(JsonValue.EMPTY_JSON_ARRAY, body.getJsonArray("constraintsRun"));
    }

    @Test
    void validWithSimDataset() throws IOException {
      // Setup: Run a Simulation
      final int simDatasetId = hasura.awaitSimulation(planId).simDatasetId();

      // Returns a 200 because Sim Dataset exists
      // results are an empty array because there are no constraints that could've failed
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("planId", planId)
                                               .add("simulationDatasetId", simDatasetId))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      final var body = getBody(response);
      assertTrue(body.containsKey("requestId"));
      assertFalse(body.isNull("requestId"));
      assertTrue(body.containsKey("constraintsRun"));
      assertEquals(JsonValue.EMPTY_JSON_ARRAY, body.getJsonArray("constraintsRun"));
    }
  }

  @Nested
  class RefreshModelParameters {
    @Test
    void invalidMissionModelId() {
      // Returns a 404 if the MissionModelId is invalid
      // message is "no such mission model"
      final String data = Json.createObjectBuilder()
                              .add(
                                  "event", Json.createObjectBuilder()
                                               .add(
                                                   "data", Json.createObjectBuilder()
                                                               .add("old", JsonValue.NULL)
                                                               .add("new", Json.createObjectBuilder().add("id", -1))))
                              .build()
                              .toString();
      final var response = request.post("/refreshModelParameters", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No mission model exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 200 if the ID is valid
      // There is no response body from this endpoint
      final String data = Json.createObjectBuilder()
                              .add(
                                  "event", Json.createObjectBuilder()
                                               .add(
                                                   "data", Json.createObjectBuilder()
                                                               .add("old", JsonValue.NULL)
                                                               .add(
                                                                   "new",
                                                                   Json.createObjectBuilder().add("id", modelId))))
                              .build()
                              .toString();
      final var response = request.post("/refreshModelParameters", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
    }
  }

  @Nested
  class RefreshActivityTypes {
    @Test
    void invalidMissionModelId() {
      // Returns a 404 if the MissionModelId is invalid
      // message is "no such mission model"
      final String data = Json.createObjectBuilder()
                              .add(
                                  "event", Json.createObjectBuilder()
                                               .add(
                                                   "data", Json.createObjectBuilder()
                                                               .add("old", JsonValue.NULL)
                                                               .add("new", Json.createObjectBuilder().add("id", -1))))
                              .build()
                              .toString();
      final var response = request.post("/refreshActivityTypes", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No mission model exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 200 if the ID is valid
      // There is no response body from this endpoint
      final String data = Json.createObjectBuilder()
                              .add(
                                  "event", Json.createObjectBuilder()
                                               .add(
                                                   "data", Json.createObjectBuilder()
                                                               .add("old", JsonValue.NULL)
                                                               .add(
                                                                   "new",
                                                                   Json.createObjectBuilder().add("id", modelId))))
                              .build()
                              .toString();
      final var response = request.post("/refreshActivityTypes", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
    }
  }

  @Nested
  class RefreshResourceTypes {
    @Test
    void invalidMissionModelId() {
      // Returns a 404 if the MissionModelId is invalid
      final String data = Json.createObjectBuilder()
                              .add(
                                  "event", Json.createObjectBuilder()
                                               .add(
                                                   "data", Json.createObjectBuilder()
                                                               .add("old", JsonValue.NULL)
                                                               .add("new", Json.createObjectBuilder().add("id", -1))))
                              .build()
                              .toString();
      final var response = request.post("/refreshResourceTypes", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No mission model exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 200 if the ID is valid
      // There is no response body from this endpoint
      final String data = Json.createObjectBuilder()
                              .add(
                                  "event", Json.createObjectBuilder()
                                               .add(
                                                   "data", Json.createObjectBuilder()
                                                               .add("old", JsonValue.NULL)
                                                               .add(
                                                                   "new",
                                                                   Json.createObjectBuilder().add("id", modelId))))
                              .build()
                              .toString();
      final var response = request.post("/refreshResourceTypes", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
    }
  }

  @Nested
  class ValidateActivityArguments {
    @Test
    void invalidMissionModelId() {
      // Returns a 404 if the MissionModelId is invalid
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "validateActivityArguments"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", -1)
                                               .add("activityTypeName", "BiteBanana")
                                               .add("activityArguments", JsonValue.EMPTY_JSON_OBJECT))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/validateActivityArguments", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      final var body = getBody(response);
      assertEquals("No mission model exists with id `-1`", body.getString("message"));
      assertTrue(body.containsKey("extensions"));
      assertEquals("NO_SUCH_MISSION_MODEL", body.getJsonObject("extensions").getString("type"));
    }

    @Test
    void valid() {
      // Returns a 200 otherwise
      // "success" is true
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "validateActivityArguments"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", modelId)
                                               .add("activityTypeName", "BiteBanana")
                                               .add("activityArguments", JsonValue.EMPTY_JSON_OBJECT))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/validateActivityArguments", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      assertTrue(getBody(response).getBoolean("success"));
    }
  }

  @Nested
  class ValidateModelArguments {
    @Test
    void invalidMissionModelId() {
      // Returns a 404 if the MissionModelId is invalid
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "validateModelArguments"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", -1)
                                               .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/validateModelArguments", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No mission model exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 200 if the ID is valid
      // "success" is true
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "validateModelArguments"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", modelId)
                                               .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/validateModelArguments", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      assertTrue(getBody(response).getBoolean("success"));
    }
  }

  @Nested
  class ValidatePlan {
    @Test
    void invalidPlanId() {
      // Returns a 404 if the PlanId is invalid
      // message is "no such plan"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "validatePlan"))
                              .add("input", Json.createObjectBuilder().add("planId", -1))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/validatePlan", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No plan exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 200 if the ID is valid
      // "success" is true
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "validatePlan"))
                              .add("input", Json.createObjectBuilder().add("planId", planId))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/validatePlan", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      assertTrue(getBody(response).getBoolean("success"));
    }
  }

  @Nested
  class GetModelEffectiveArguments {
    @Test
    void invalidMissionModelId() {
      // Returns a 404 if the MissionModelId is invalid
      // message is "no such mission model"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "getModelEffectiveArguments"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", -1)
                                               .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getModelEffectiveArguments", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No mission model exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 200 otherwise
      // Body contains the complete set of args for the mission model (all default in this case)
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "getModelEffectiveArguments"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", modelId)
                                               .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getModelEffectiveArguments", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      // Validate Body
      final var expectedBody = Json.createObjectBuilder()
                                   .add("success", true)
                                   .add(
                                       "arguments",
                                       Json.createObjectBuilder()
                                           .add("initialPlantCount", 200)
                                           .add("initialDataPath", "/etc/os-release")
                                           .add("initialProducer", "Chiquita")
                                           .add(
                                               "initialConditions",
                                               Json.createObjectBuilder()
                                                   .add("peel", 4.0)
                                                   .add("fruit", 4.0)
                                                   .add("flag", "A")))
                                   .build();
      assertEquals(expectedBody, getBody(response));
    }
  }

  @Nested
  class GetActivityEffectiveArgumentsBulk {
    @Test
    void invalidMissionModelId() {
      // Returns a 404 if the MissionModelId is invalid
      // message is "no such mission model"
      final String data = Json.createObjectBuilder()
                              .add(
                                  "action",
                                  Json.createObjectBuilder().add("name", "getActivityEffectiveArgumentsBulk"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", -1)
                                               .add("activities", JsonValue.EMPTY_JSON_ARRAY))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getActivityEffectiveArgumentsBulk", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No mission model exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 200 otherwise
      // Body contains the complete set of args for the given activities
      final var activitiesBuilder = Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder()
                                                 .add("activityTypeName", "GrowBanana")
                                                 .add("activityArguments", JsonValue.EMPTY_JSON_OBJECT))
                                        .add(Json.createObjectBuilder()
                                                 .add("activityTypeName", "GrowBanana")
                                                 .add(
                                                     "activityArguments",
                                                     Json.createObjectBuilder().add("quantity", 100)));

      final String data = Json.createObjectBuilder()
                              .add(
                                  "action",
                                  Json.createObjectBuilder().add("name", "getActivityEffectiveArgumentsBulk"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", modelId)
                                               .add("activities", activitiesBuilder))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/getActivityEffectiveArgumentsBulk", RequestOptions.create().setData(data));
      assertEquals(200, response.status());

      // Validate Body
      final var expectedBody = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("typeName", "GrowBanana")
                                            .add("success", true)
                                            .add(
                                                "arguments", Json.createObjectBuilder()
                                                                 .add("growingDuration", 3600000000L)
                                                                 .add("quantity", 1)))
                                   .add(Json.createObjectBuilder()
                                            .add("typeName", "GrowBanana")
                                            .add("success", true)
                                            .add(
                                                "arguments", Json.createObjectBuilder()
                                                                 .add("growingDuration", 3600000000L)
                                                                 .add("quantity", 100)))
                                   .build();
      assertEquals(expectedBody, getArrayBody(response));
    }
  }

  @Nested
  class AddExternalDataset {
    @Test
    void invalidPlanId() {
      // Returns a 404 if the PlanId is invalid
      // message is "no such plan"
      final var profileSetBuilder = Json.createObjectBuilder()
                                        .add(
                                            "/my_boolean",
                                            Json.createObjectBuilder()
                                                .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                                                .add(
                                                    "segments",
                                                    Json.createArrayBuilder()
                                                        .add(Json.createObjectBuilder()
                                                                 .add("duration", 3600000000L)
                                                                 .add("dynamics", true)))
                                                .add("type", "discrete"));
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "addExternalDataset"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("planId", -1)
                                               .add("datasetStart", "2021-001T06:00:00.000")
                                               .add("profileSet", profileSetBuilder)
                                               .add("simulationDatasetId", JsonValue.NULL))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/addExternalDataset", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No plan exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() {
      // Returns a 201 otherwise
      final var profileSetBuilder = Json.createObjectBuilder()
                                        .add(
                                            "/my_boolean",
                                            Json.createObjectBuilder()
                                                .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                                                .add(
                                                    "segments",
                                                    Json.createArrayBuilder()
                                                        .add(Json.createObjectBuilder()
                                                                 .add("duration", 3600000000L)
                                                                 .add("dynamics", true)))
                                                .add("type", "discrete"));
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "addExternalDataset"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("planId", planId)
                                               .add("datasetStart", "2021-001T06:00:00.000")
                                               .add("profileSet", profileSetBuilder)
                                               .add("simulationDatasetId", JsonValue.NULL))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/addExternalDataset", RequestOptions.create().setData(data));
      assertEquals(201, response.status());
      assertTrue(getBody(response).containsKey("datasetId"));
      assertFalse(getBody(response).isNull("datasetId"));
    }
  }

  @Nested
  class ExtendExternalDataset {
    @Test
    void invalidDatasetId() {
      // Returns a 404 if the DatasetId is invalid
      // message is "no such plan dataset"
      final var profileSetBuilder = Json.createObjectBuilder()
                                        .add(
                                            "/my_boolean",
                                            Json.createObjectBuilder()
                                                .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                                                .add(
                                                    "segments",
                                                    Json.createArrayBuilder()
                                                        .add(Json.createObjectBuilder()
                                                                 .add("duration", 3600000000L)
                                                                 .add("dynamics", true)))
                                                .add("type", "discrete"));
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "extendExternalDataset"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("datasetId", -1)
                                               .add("profileSet", profileSetBuilder))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/extendExternalDataset", RequestOptions.create().setData(data));
      assertEquals(404, response.status());
      assertEquals("No plan dataset exists with id `-1`", getBody(response).getString("message"));
    }

    @Test
    void valid() throws IOException {
      // Setup: Insert a dataset
      final var myBooleanProfile = new ExternalDataset.ProfileInput(
          "/my_boolean",
          "discrete",
          ValueSchema.VALUE_SCHEMA_BOOLEAN,
          List.of(new ExternalDataset.ProfileInput.ProfileSegmentInput(3600000000L, JsonValue.TRUE)));
      final var datasetId = hasura.insertExternalDataset(
          planId,
          "2021-001T06:00:00.000",
          List.of(myBooleanProfile));

      // Returns a 200 if the ID is valid
      // Performed inside a try-finally to ensure that cleanup is attempted, even if there is an exception during the test
      try {
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "extendExternalDataset"))
                                .add(
                                    "input", Json.createObjectBuilder()
                                                 .add("datasetId", datasetId)
                                                 .add(
                                                     "profileSet",
                                                     Json
                                                         .createObjectBuilder()
                                                         .add(myBooleanProfile.name(), myBooleanProfile.toJSON())))
                                .add("request_query", "")
                                .add("session_variables", routes_admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/extendExternalDataset", RequestOptions.create().setData(data));
        assertEquals(200, response.status());
        assertEquals(Json.createObjectBuilder().add("datasetId", datasetId).build(), getBody(response));
      } finally {
        // Cleanup: remove external dataset
        hasura.deleteExternalDataset(planId, datasetId);
      }
    }
  }

  @Nested
  class ConstraintsDslTypescript {
    @Test
    void invalidMissionModelId() {
      // Returns a 200 with a failure status if the MissionModelId is invalid
      // reason is "No mission model exists with id `-1`"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "constraintsDslTypescript"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", -1)
                                               .add("planId", JsonValue.NULL))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintsDslTypescript", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      final var expectedBody = Json.createObjectBuilder()
                                   .add("status", "failure")
                                   .add("reason", "No mission model exists with id `-1`")
                                   .build();
      assertEquals(expectedBody, getBody(response));
    }

    /**
     * TODO: Enable and update this test once this behavior has been fixed
     * Expectation: According to `GenerateConstraintsLibAction::run`, this request should fail
     *  with reason = 'No plan exists with id `-1`'.
     * However, PostgresPlanRepository's implementation of `getExternalResourceSchemas` doesn't throw NoSuchPlanException,
     *  it returns an empty list if planId doesn't exist
     */
    @Disabled
    @Test
    void invalidPlanId() {
      // Returns a 200 with a failure status if the PlanId is invalid
      // reason is "No plan exists with id `-1`"
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "constraintsDslTypescript"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", modelId)
                                               .add("planId", -1))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintsDslTypescript", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      final var expectedBody = Json.createObjectBuilder()
                                   .add("status", "failure")
                                   .add("reason", "No mission model exists with id `-1`")
                                   .build();
      assertEquals(expectedBody, getBody(response));
    }

    @Test
    void valid() {
      // Returns a 200 with a success status if the ID is valid
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "constraintsDslTypescript"))
                              .add(
                                  "input", Json.createObjectBuilder()
                                               .add("missionModelId", modelId)
                                               .add("planId", JsonValue.NULL))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/constraintsDslTypescript", RequestOptions.create().setData(data));
      assertEquals(200, response.status());

      // Validate response body
      final var jsonBody = getBody(response);
      assertEquals("success", jsonBody.getString("status"));
      assertTrue(jsonBody.containsKey("typescriptFiles"));
      assertFalse(jsonBody.getJsonArray("typescriptFiles").isEmpty());

      for (final var entry : jsonBody.getJsonArray("typescriptFiles")) {
        final var file = entry.asJsonObject();
        assertTrue(file.containsKey("filePath"));
        assertTrue(file.containsKey("content"));
        assertFalse(file.getString("content").isEmpty());
      }
    }
  }
}
