package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.types.ActionPermissionsSet;
import gov.nasa.jpl.aerie.e2e.types.ActionPermissionsSet.*;
import gov.nasa.jpl.aerie.e2e.types.ExternalDataset;
import gov.nasa.jpl.aerie.e2e.types.User;
import gov.nasa.jpl.aerie.e2e.types.ValueSchema;
import gov.nasa.jpl.aerie.e2e.utils.BaseURL;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Named.named;

/**
 * Test the Action Bindings for the Merlin and Scheduler Servers
 * Health endpoints are already tested in HealthTests
 */
public class BindingsTests {
  // Users are shared between the subclasses
  private static final User admin = new User(
      "bindings_admin_user",
      "aerie_admin",
      new String[]{"aerie_admin", "viewer"},
      Map.of("x-hasura-role", "aerie_admin", "x-hasura-user-id", "bindings_admin_user"));
  private static final User nonOwner = new User(
      "bindings_not_owner",
      "user",
      new String[]{"user", "viewer"},
      Map.of("x-hasura-role", "user", "x-hasura-user-id", "bindings_not_owner"));
  private static final User viewer = new User(
      "bindings_viewer",
      "viewer",
      new String[]{"viewer"},
      Map.of("x-hasura-role", "viewer", "x-hasura-user-id", "bindings_viewer"));

  @BeforeAll
  static void beforeAll() throws IOException {
    try(final var playwright = Playwright.create();
        final var hasura = new HasuraRequests(playwright)){
      // Insert the Users
      hasura.createUser(admin);
      hasura.createUser(nonOwner);
      hasura.createUser(viewer);
    }
  }
  @AfterAll
  static void afterAll() throws IOException {
    try(final var playwright = Playwright.create();
        final var hasura = new HasuraRequests(playwright)){
      // Remove the Users
      hasura.deleteUser(admin);
      hasura.deleteUser(nonOwner);
      hasura.deleteUser(viewer);
    }
  }

  /**
   * Get the JSON Object from the Body of an APIResponse
   * @param response APIResponse from a Playwright Request
   * @return the JSON Object representation of the response body
   */
  private static JsonObject getBody(final APIResponse response){
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      return reader.readObject();
    }
  }

  /**
   * Get the JSON Array from the Body of an APIResponse
   * @param response APIResponse from a Playwright Request
   * @return the JSON Array representation of the response body
   */
  private static JsonArray getArrayBody(final APIResponse response){
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      return reader.readArray();
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  // "resourceTypes" and "getActivityEffectiveArguments" are not tested, as they are deprecated
  class MerlinBindings {
    // Requests
    private Playwright playwright;
    private APIRequestContext request;
    private HasuraRequests hasura;

    // Per-Test Data
    private int modelId;
    private int planId;

    @BeforeAll
    void beforeAll() {
      // Setup Requests
      playwright = Playwright.create();
      // Set all rqs to go to the Merlin Server
      request = playwright.request().newContext(
          new APIRequest.NewContextOptions()
              .setBaseURL(BaseURL.MERLIN_SERVER.url));
      hasura = new HasuraRequests(playwright);
    }

    @AfterAll
    void afterAll() {
      // Cleanup Requests
      hasura.close();
      request.dispose();
      playwright.close();
    }

    @BeforeEach
    void beforeEach() throws IOException, InterruptedException {
      // Insert the Mission Model
      try(final var gateway = new GatewayRequests(playwright)){
        modelId = hasura.createMissionModel(
            gateway.uploadJarFile(),
            "Banananation (e2e tests)",
            "aerie_e2e_tests",
            "Merlin Bindings");
      }

      // Insert the Plan
      planId = hasura.createPlan(
          modelId,
          "Test Plan - Merlin Bindings",
          "24:00:00",
          "2023-01-01T00:00:00+00:00",
          admin.session());
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
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/getSimulationResults", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such plan", getBody(response).getString("message"));
      }

      @Test
      void forbidden() {
        // Returns a 403 if Forbidden
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "simulate"))
                                .add("input", Json.createObjectBuilder().add("planId", planId))
                                .add("request_query", "")
                                .add("session_variables", nonOwner.getSession())
                                .build()
                                .toString();
        final var response = request.post("/getSimulationResults", RequestOptions.create().setData(data));
        assertEquals(403, response.status());
        assertEquals(
            "User '" + nonOwner.name() + "' with role 'user' cannot perform 'simulate' because they are not "
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
                                .add("session_variables", admin.getSession())
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
                                .add("session_variables", admin.getSession())
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
        // message is "no such plan"
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "resource_samples"))
                                .add("input", Json.createObjectBuilder().add("planId", -1))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/resourceSamples", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such plan", getBody(response).getString("message"));
      }
      @Test
      void forbidden() throws IOException {
        // 403: Forbidden requires updating permissions
        final var ogPermissions = hasura.getActionPermissionsForRole("user");
        final var tempPermission = new ActionPermissionsSet(Map.of(ActionKey.resource_samples, Permission.PLAN_OWNER));
        hasura.updateActionPermissionsForRole("user", tempPermission);

        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "resource_samples"))
                                .add("input", Json.createObjectBuilder().add("planId", planId))
                                .add("request_query", "")
                                .add("session_variables", nonOwner.getSession())
                                .build()
                                .toString();
        final var response = request.post("/resourceSamples", RequestOptions.create().setData(data));
        assertEquals(403, response.status());
        assertEquals("User '"+nonOwner.name()+"' with role 'user' cannot perform 'resource_samples' because they "
                     + "are not a 'PLAN_OWNER' for plan with id '"+planId+"'",
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
                                .add("session_variables", admin.getSession())
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
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such plan", getBody(response).getString("message"));
      }

      @Test
      void invalidSimDatasetId() throws IOException {
        // Returns a 404 if the SimDatasetId is invalid
        // Message is an "input mismatch exception"
        hasura.awaitSimulation(planId);
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("planId", planId)
                                                  .add("simulationDatasetId", -1))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        final var expectedResponse = Json.createObjectBuilder()
                                         .add("message", "input mismatch exception")
                                         .add("extensions", Json.createObjectBuilder()
                                                               .add("cause", "simulation dataset with id `-1` does not exist"))
                                         .build();
        assertEquals(expectedResponse, getBody(response));
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
          // Message is 'simulation dataset mismatch exception'
          final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("planId", planId)
                                                  .add("simulationDatasetId", simDatasetId))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        final var expectedCause = "Simulation Dataset with id `"+simDatasetId+"` does not belong to Plan with id `"+planId+"`";
        final var expectedResponse = Json.createObjectBuilder()
                                         .add("message", "simulation dataset mismatch exception")
                                         .add("extensions", Json.createObjectBuilder().add("cause", expectedCause))
                                         .build();
        assertEquals(expectedResponse, getBody(response));
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
                                .add("session_variables", nonOwner.getSession())
                                .build()
                                .toString();
        final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
        assertEquals(403, response.status());
        assertEquals( "User '"+nonOwner.name()+"' with role 'user' cannot perform 'check_constraints' because they"
                      + " are not a 'PLAN_OWNER_COLLABORATOR' for plan with id '"+planId+"'",
                      getBody(response).getString("message"));
      }

      @Test
      void noSimDatasets() {
        // Returns a 404 if no simulation datasets are found
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "check_constraints"))
                                .add("input", Json.createObjectBuilder().add("planId", planId))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/constraintViolations", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        final var expectedCause = "plan with id " + planId + " has not yet been simulated at its current revision";
        final var expectedBody = Json.createObjectBuilder()
                                         .add("message", "input mismatch exception")
                                         .add("extensions", Json.createObjectBuilder().add("cause", expectedCause))
                                         .build();
        assertEquals(expectedBody, getBody(response));
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
                                .add("session_variables", admin.getSession())
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
                                .add("input", Json.createObjectBuilder()
                                                  .add("planId", planId)
                                                  .add("simulationDatasetId", simDatasetId))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
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
                                .add("event", Json.createObjectBuilder()
                                         .add("data", Json.createObjectBuilder()
                                                  .add("old", JsonValue.NULL)
                                                  .add("new", Json.createObjectBuilder().add("id", -1))))
                                .build()
                                .toString();
        final var response = request.post("/refreshModelParameters", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such mission model", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 200 if the ID is valid
        // There is no response body from this endpoint
        final String data = Json.createObjectBuilder()
                                .add("event", Json.createObjectBuilder()
                                         .add("data", Json.createObjectBuilder()
                                                  .add("old", JsonValue.NULL)
                                                  .add("new", Json.createObjectBuilder().add("id", modelId))))
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
                                .add("event", Json.createObjectBuilder()
                                         .add("data", Json.createObjectBuilder()
                                                  .add("old", JsonValue.NULL)
                                                  .add("new", Json.createObjectBuilder().add("id", -1))))
                                .build()
                                .toString();
        final var response = request.post("/refreshActivityTypes", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such mission model", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 200 if the ID is valid
        // There is no response body from this endpoint
        final String data = Json.createObjectBuilder()
                                .add("event", Json.createObjectBuilder()
                                         .add("data", Json.createObjectBuilder()
                                                  .add("old", JsonValue.NULL)
                                                  .add("new", Json.createObjectBuilder().add("id", modelId))))
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
        // message is "no such mission model"
        final String data = Json.createObjectBuilder()
                                .add("event", Json.createObjectBuilder()
                                         .add("data", Json.createObjectBuilder()
                                                  .add("old", JsonValue.NULL)
                                                  .add("new", Json.createObjectBuilder().add("id", -1))))
                                .build()
                                .toString();
        final var response = request.post("/refreshResourceTypes", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such mission model", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 200 if the ID is valid
        // There is no response body from this endpoint
        final String data = Json.createObjectBuilder()
                                .add("event", Json.createObjectBuilder()
                                         .add("data", Json.createObjectBuilder()
                                                  .add("old", JsonValue.NULL)
                                                  .add("new", Json.createObjectBuilder().add("id", modelId))))
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
        // message is "no such mission model"
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "validateActivityArguments"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", -1)
                                                  .add("activityTypeName", "BiteBanana")
                                                  .add("activityArguments", JsonValue.EMPTY_JSON_OBJECT))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/validateActivityArguments", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such mission model", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 200 otherwise
        // "success" is true
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "validateActivityArguments"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("activityTypeName", "BiteBanana")
                                                  .add("activityArguments", JsonValue.EMPTY_JSON_OBJECT))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
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
        // message is "no such mission model"
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "validateModelArguments"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", -1)
                                                  .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/validateModelArguments", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such mission model", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 200 if the ID is valid
        // "success" is true
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "validateModelArguments"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
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
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/validatePlan", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such plan", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 200 if the ID is valid
        // "success" is true
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "validatePlan"))
                                .add("input", Json.createObjectBuilder().add("planId", planId))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
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
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", -1)
                                                  .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/getModelEffectiveArguments", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such mission model", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 200 otherwise
        // Body contains the complete set of args for the mission model (all default in this case)
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "getModelEffectiveArguments"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("modelArguments", JsonValue.EMPTY_JSON_OBJECT))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/getModelEffectiveArguments", RequestOptions.create().setData(data));
        assertEquals(200, response.status());
        // Validate Body
        final var expectedBody = Json.createObjectBuilder()
                                     .add("success", true)
                                     .add("arguments",
                                          Json.createObjectBuilder()
                                              .add("initialPlantCount", 200)
                                              .add("initialDataPath", "/etc/os-release")
                                              .add("initialProducer", "Chiquita")
                                              .add("initialConditions",
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
                                .add("action", Json.createObjectBuilder().add("name", "getActivityEffectiveArgumentsBulk"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", -1)
                                                  .add("activities", JsonValue.EMPTY_JSON_ARRAY))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/getActivityEffectiveArgumentsBulk", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such mission model", getBody(response).getString("message"));
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
                                            .add("activityArguments", Json.createObjectBuilder().add("quantity", 100)));

        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "getActivityEffectiveArgumentsBulk"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("activities", activitiesBuilder))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/getActivityEffectiveArgumentsBulk", RequestOptions.create().setData(data));
        assertEquals(200, response.status());

        // Validate Body
        final var expectedBody = Json.createArrayBuilder()
                                     .add(Json.createObjectBuilder()
                                              .add("typeName", "GrowBanana")
                                              .add("success", true)
                                              .add("arguments", Json.createObjectBuilder()
                                                                    .add("growingDuration", 3600000000L)
                                                                    .add("quantity", 1)))
                                     .add(Json.createObjectBuilder()
                                              .add("typeName", "GrowBanana")
                                              .add("success", true)
                                              .add("arguments", Json.createObjectBuilder()
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
                                          .add("/my_boolean",
                                               Json.createObjectBuilder()
                                                   .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                                                   .add("segments",
                                                        Json.createArrayBuilder()
                                                            .add(Json.createObjectBuilder()
                                                                     .add("duration", 3600000000L)
                                                                     .add("dynamics", true)))
                                                   .add("type", "discrete"));
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "addExternalDataset"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("planId", -1)
                                                  .add("datasetStart", "2021-001T06:00:00.000")
                                                  .add("profileSet", profileSetBuilder)
                                                  .add("simulationDatasetId", JsonValue.NULL))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/addExternalDataset", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such plan", getBody(response).getString("message"));
      }
      @Test
      void valid() {
        // Returns a 201 otherwise
        final var profileSetBuilder = Json.createObjectBuilder()
                                          .add("/my_boolean",
                                               Json.createObjectBuilder()
                                                   .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                                                   .add("segments",
                                                        Json.createArrayBuilder()
                                                            .add(Json.createObjectBuilder()
                                                                     .add("duration", 3600000000L)
                                                                     .add("dynamics", true)))
                                                   .add("type", "discrete"));
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "addExternalDataset"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("planId", planId)
                                                  .add("datasetStart", "2021-001T06:00:00.000")
                                                  .add("profileSet", profileSetBuilder)
                                                  .add("simulationDatasetId", JsonValue.NULL))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
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
                                          .add("/my_boolean",
                                               Json.createObjectBuilder()
                                                   .add("schema", Json.createObjectBuilder().add("type", "boolean"))
                                                   .add("segments",
                                                        Json.createArrayBuilder()
                                                            .add(Json.createObjectBuilder()
                                                                     .add("duration", 3600000000L)
                                                                     .add("dynamics", true)))
                                                   .add("type", "discrete"));
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "extendExternalDataset"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("datasetId", -1)
                                                  .add("profileSet", profileSetBuilder))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/extendExternalDataset", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such plan dataset", getBody(response).getString("message"));
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
                                  .add("input", Json.createObjectBuilder()
                                                    .add("datasetId", datasetId)
                                                    .add("profileSet", Json.createObjectBuilder().add(myBooleanProfile.name(), myBooleanProfile.toJSON())))
                                  .add("request_query", "")
                                  .add("session_variables", admin.getSession())
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
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", -1)
                                                  .add("planId", JsonValue.NULL))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
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
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("planId", -1))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
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
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("planId", JsonValue.NULL))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/constraintsDslTypescript", RequestOptions.create().setData(data));
        assertEquals(200, response.status());

        // Validate response body
        final var jsonBody = getBody(response);
        assertEquals("success", jsonBody.getString("status"));
        assertTrue(jsonBody.containsKey("typescriptFiles"));
        assertFalse(jsonBody.getJsonArray("typescriptFiles").isEmpty());

        for(final var entry : jsonBody.getJsonArray("typescriptFiles")){
          final var file = entry.asJsonObject();
          assertTrue(file.containsKey("filePath"));
          assertTrue(file.containsKey("content"));
          assertFalse(file.getString("content").isEmpty());
        }
      }
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class SchedulerBindings{
    // Requests
    private Playwright playwright;
    private APIRequestContext request;
    private HasuraRequests hasura;

    // Cross-Test Data
    private int modelId;
    private int planId;
    private int schedulingSpecId;

    @BeforeAll
    void beforeAll() {
      playwright = Playwright.create();
      // Set all rqs to go to the Scheduler Server
      request = playwright.request().newContext(
          new APIRequest.NewContextOptions()
              .setBaseURL(BaseURL.SCHEDULER_SERVER.url));
      hasura = new HasuraRequests(playwright);
    }

    @AfterAll
    void afterAll() {
      // Cleanup RQs
      hasura.close();
      request.dispose();
      playwright.close();
    }

    @BeforeEach
    void beforeEach() throws IOException, InterruptedException {
      // Insert the Mission Model
      try(final var gateway = new GatewayRequests(playwright)){
        modelId = hasura.createMissionModel(
            gateway.uploadJarFile(),
            "Banananation (e2e tests)",
            "aerie_e2e_tests",
            "Scheduler Bindings");
      }

      // Insert the Plan
      final String plan_start_timestamp = "2023-01-01T00:00:00+00:00";
      final String plan_end_timestamp = "2023-01-02T00:00:00+00:00";
      final String duration = "24:00:00";

      planId = hasura.createPlan(
          modelId,
          "Test Plan - Scheduler Bindings",
          duration,
          plan_start_timestamp,
          admin.session());
      schedulingSpecId = hasura.getSchedulingSpecId(planId);
    }

    @AfterEach
    void afterEach() throws IOException {
      // Remove Model and Plan/Scheduling Spec
      hasura.deletePlan(planId);
      hasura.deleteMissionModel(modelId);
    }

    @Nested
    class Schedule{
      @Test
      void invalidSpecId(){
        // Returns a 404 if the SpecId is invalid
        // message is "no such scheduling specification"
        final String data = Json.createObjectBuilder()
                                   .add("action", Json.createObjectBuilder().add("name", "scheduler"))
                                   .add("input", Json.createObjectBuilder().add("specificationId", -1))
                                   .add("request_query", "")
                                   .add("session_variables", admin.getSession())
                                   .build()
                                   .toString();
        final var response = request.post("/schedule", RequestOptions.create().setData(data));
        assertEquals(404, response.status());
        assertEquals("no such scheduling specification", getBody(response).getString("message"));
      }
      @Test
      void forbidden(){
        // Returns a 403 if the user isn't allowed to run scheduling on the plan
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "scheduler"))
                                .add("input", Json.createObjectBuilder().add("specificationId", schedulingSpecId))
                                .add("request_query", "")
                                .add("session_variables", nonOwner.getSession())
                                .build()
                                .toString();
        final var response = request.post("/schedule", RequestOptions.create().setData(data));
        assertEquals(403, response.status());
        assertEquals("User '"+nonOwner.name()+"' with role 'user' cannot perform 'schedule' because they are not "
                     + "a 'PLAN_OWNER_COLLABORATOR' for plan with id '"+planId+"'",
                     getBody(response).getString("message"));
      }
      @Test
      void valid() throws InterruptedException{
        // Returns a 200 if the ID is valid
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "scheduler"))
                                .add("input", Json.createObjectBuilder().add("specificationId", schedulingSpecId))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/schedule", RequestOptions.create().setData(data));
        assertEquals(200, response.status());
        // Delay 1s to allow any workers to finish with the request
        Thread.sleep(1000);
      }
    }

    @Nested
    class SchedulingDSLTypescript{
      @Test
      void invalidModelId(){
        // Returns a 200 with a failure status if the MissionModelId is invalid
        // reason is "No mission model exists with id `MissionModelId[id=-1]`"
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "schedulingDslTypescript"))
                                .add("input", Json.createObjectBuilder().add("missionModelId", -1))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/schedulingDslTypescript", RequestOptions.create().setData(data));
        assertEquals(200, response.status());
        final var expectedBody = Json.createObjectBuilder()
                                         .add("status", "failure")
                                         .add("reason", "No mission model exists with id `-1`")
                                         .build();
        assertEquals(expectedBody, getBody(response));
      }
      @Test
      void invalidPlanId() {
        // Returns a 200 with a failure status if an invalid plan id is passed
        // message is "No plan exists with id `PlanId[id=-1]`"
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "schedulingDslTypescript"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("planId", -1))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/schedulingDslTypescript", RequestOptions.create().setData(data));
        assertEquals(200, response.status());
        final var expectedBody = Json.createObjectBuilder()
                                         .add("status", "failure")
                                         .add("reason", "No plan exists with id `PlanId[id=-1]`")
                                         .build();
        assertEquals(expectedBody, getBody(response));
      }
      @Test
      void validModelId() {
        // Returns a 200 with a success status if the ID is valid
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "schedulingDslTypescript"))
                                .add("input", Json.createObjectBuilder().add("missionModelId", modelId))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/schedulingDslTypescript", RequestOptions.create().setData(data));
        assertEquals(200, response.status());
        final var jsonBody = getBody(response);
        // Validate response body
        assertEquals("success", jsonBody.getString("status"));
        assertTrue(jsonBody.containsKey("typescriptFiles"));
        assertFalse(jsonBody.getJsonArray("typescriptFiles").isEmpty());

        for(final var entry : jsonBody.getJsonArray("typescriptFiles")){
          final var file = entry.asJsonObject();
          assertTrue(file.containsKey("filePath"));
          assertTrue(file.containsKey("content"));
          assertFalse(file.getString("content").isEmpty());
        }
      }
      @Test
      void bothValid() {
        // Returns a 200 with a success status if both IDs are valid
        final String data = Json.createObjectBuilder()
                                .add("action", Json.createObjectBuilder().add("name", "schedulingDslTypescript"))
                                .add("input", Json.createObjectBuilder()
                                                  .add("missionModelId", modelId)
                                                  .add("planId", planId))
                                .add("request_query", "")
                                .add("session_variables", admin.getSession())
                                .build()
                                .toString();
        final var response = request.post("/schedulingDslTypescript", RequestOptions.create().setData(data));
        assertEquals(200, response.status());
        final var jsonBody = getBody(response);
        // Validate response body
        assertEquals("success", jsonBody.getString("status"));
        assertTrue(jsonBody.containsKey("typescriptFiles"));
        assertFalse(jsonBody.getJsonArray("typescriptFiles").isEmpty());

        for(final var entry : jsonBody.getJsonArray("typescriptFiles")){
          final var file = entry.asJsonObject();
          assertTrue(file.containsKey("filePath"));
          assertTrue(file.containsKey("content"));
          assertFalse(file.getString("content").isEmpty());
        }
      }
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class WorkspaceBindings {
    // Requests
    private Playwright playwright;
    private HasuraRequests hasura;
    private WorkspaceRequests wsServer;

    // Class-Wide Data
    private int cdictId;
    private int parcelId;

    private User owner = new User(
        "ws_bindings_owner",
        "user",
        new String[] {"user"},
        Map.of("x-hasura-role", "user", "x-hasura-user-id", "ws_bindings_owner"));

    private String adminToken;
    private String ownerToken;
    private String nonOwnerToken;
    private String viewerToken;

    @BeforeAll
    void beforeAll() throws IOException {
      // Setup Requests
      playwright = Playwright.create();
      hasura = new HasuraRequests(playwright);
      wsServer = new WorkspaceRequests(playwright);

      // Get valid JWT tokens for the users
      try (final var gateway = new GatewayRequests(playwright)) {
        adminToken = gateway.login(admin);
        ownerToken = gateway.login(owner);
        nonOwnerToken = gateway.login(nonOwner);
        viewerToken = gateway.login(viewer);
      }

      // Set up parcel and dictionary to use across the tests
      cdictId = hasura.createMockCommandDictionary("WorkspaceBindingsTest", "Workspace E2E Test");
      parcelId = hasura.createMockParcel("Workspace Bindings Parcel", cdictId);
    }

    @AfterAll
    void afterAll() throws IOException {
      // Cleanup parcel and dictionary
      hasura.deleteMockCommandDictionary(cdictId);
      hasura.deleteMockParcel(parcelId);

      // Cleanup Requests
      wsServer.close();
      hasura.close();
      playwright.close();
    }

    /**
     * Tests for the /ws/create and /ws/{workspace_id} routes
     *
     * Tests that have yet to be implemented are disabled
     */
    @Nested
    class WorkspaceManagementRoutes {
      @TestInstance(TestInstance.Lifecycle.PER_CLASS)
      @Nested
      class CreateWorkspace {
        /**
         * The workspace server returns a 403 Forbidden response when a user with insufficient role privileges
         * attempts to create a workspace
         */
        @Test
        void forbiddenInsufficientPrivileges() {
          final var response = wsServer.createWorkspace(viewerToken, "Should Fail", Optional.empty(), parcelId);
          assertEquals(403, response.status());
          final var body = getBody(response);
          assertEquals("FORBIDDEN", body.getString("type"));
          assertEquals("Role 'viewer' is not allowed to perform action 'create_workspace'", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        @ParameterizedTest
        @MethodSource("improperlyFormattedBodyArgs")
        @Disabled
        void improperlyFormattedBody(JsonObject jsonBodyString) {
          // TODO: make this a parametrized test that includes:
          //  no body,
          //  empty body,
          //  missing workspace location,
          //  missing parcel id,
          //  missing both
          //  (extra params is excluded bc we don't check from them currently)
          //  In all cases, request should be rejected
        }

        Stream<Arguments> improperlyFormattedBodyArgs() {
          return Stream.of(
              Arguments.arguments(named("no body", null)),
              Arguments.arguments(named("empty body", JsonValue.EMPTY_JSON_OBJECT)),
              Arguments.arguments(named("array", JsonValue.EMPTY_JSON_ARRAY)),
              Arguments.arguments(named("no workspace location", Json.createObjectBuilder()
                                                                     .add("parcelId", parcelId)
                                                                     .build())),
              Arguments.arguments(named("no parcel id", Json.createObjectBuilder()
                                                            .add("workspaceLocation", "improperBodyArgsWs")
                                                            .build())),
              Arguments.arguments(named("no workspace location or parcel id", Json.createObjectBuilder()
                                                                                  .add("workspaceName", "Improper Body WS")
                                                                                  .build()))
          );
        }


        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "/", "~", ".", "~/.", "~/usr/src/worspaces.myworkspace.txt"})
        @Disabled
        void invalidCharactersWorkspaceString(String workspaceLocation) {
          // TODO: make this a parametrized test w/ the 'NullAndEmptySource' annotation that includes:
          //  empty string,
          //  null,
          //  "/",
          //  ".",
          //  "~",
          //  all three bad symbols
          //  In all cases, the request should be rejected.
        }

        @Test
        @Disabled
        void validInputsNoWorkspaceName() {
          // TODO: expected success with the resulting workspace's name equalling its path (use a GQL query to check this)
        }

        @ParameterizedTest
        @NullAndEmptySource
        @Disabled
        void invalidWorkspaceName(String workspaceName) {
          /*
          TODO: WS server should reject both a null and empty workspace name
           */
        }

        @ParameterizedTest
        @ValueSource(strings = {"nameTestWS", "Workspace Names Test WS", "/", "~", ".", "~/.", "~/fakepath.ext"})
        @Disabled
        void validWorkspaceName(String workspaceName) {
          /*
           TODO: Make this a parametrized tests that includes a custom workspace name:
            - That matches the workspace name
            - "/"
            - "."
            - "~"
            - all three bad symbols
            In all cases, the custom name should be accepted and set as the workspace's name (use a GQL query to check this)
           */
        }
      }

      @Nested
      class DeleteWorkspace {
        // Per-Test Data
        private int workspaceId;

        @BeforeEach
        void beforeEach() throws IOException {
          workspaceId = wsServer.createWorkspace("deleteWSTests", parcelId);
        }

        @AfterEach
        void afterEach() throws IOException {
          wsServer.deleteWorkspace(workspaceId);
        }

        /**
         * The workspace server returns a 403 Forbidden response when a user with insufficient role privileges
         * attempts to delete a workspace
         */
        @Test
        void forbiddenInsufficientPrivileges() {
          final var response = wsServer.deleteWorkspace(nonOwnerToken, workspaceId);
          assertEquals(403, response.status());
          final var body = getBody(response);
          assertEquals("FORBIDDEN", body.getString("type"));
          assertEquals(("User 'bindings_not_owner' with role 'user' cannot perform 'delete_workspace' "
                        + "because they are not a 'OWNER' for workspace with id '%d'").formatted(workspaceId),
                       body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        /**
         * The workspace server returns a 403 Forbidden response when a user with the `user` role who isn't the
         * OWNER attempts to delete a workspace
         */
        @Test
        void forbiddenNotOwner() {
          final var response = wsServer.deleteWorkspace(nonOwnerToken, workspaceId);
          assertEquals(403, response.status());
          final var body = getBody(response);
          assertEquals("FORBIDDEN", body.getString("type"));
          assertEquals(("User 'bindings_not_owner' with role 'user' cannot perform 'delete_workspace' "
                        + "because they are not a 'OWNER' for workspace with id '%d'").formatted(workspaceId),
                       body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        /**
         * The workspace server returns a 404 Resource Not Found response when an invalid id is provided.
         *
         * Disabled because test is not implemented
         */
        @Test
        @Disabled
        void invalidId() {
          // TODO: test:
          //  - not a number (may return a 500 instead b/c of a parsing error),
          //  - negative number
        }

        @Test
        void ownerCanDelete() throws IOException {
          final var wsId = wsServer.createWorkspace("OwnerCanDelete", parcelId);
          hasura.changeOwner(wsId, nonOwner);
          final var deleteResp = wsServer.deleteWorkspace(nonOwnerToken, wsId);
          assertEquals(200, deleteResp.status());
          assertEquals("Workspace deleted.", deleteResp.text());
        }
      }

      // Suite disabled until tests are written
      @Nested
      @Disabled
      class ListWorkspaceContents {
        /*
         * No auth tests because
         * 1. This endpoint is used in "WSAuthorizeTests", meaning it auth is pretty thoroughly tested
         * 2. There are no default roles that cannot access this method
         *
         * No input validation because this endpoint defers its behavior to `listContents`,
         * which is tested in WSRoutes.GET
         */
        @Test
        void emptyWorkspace() {
          // TODO: Expected results: success with an empty JSON
        }

        @Test
        void nonEmptyWorkspaceNoDepth() {
          // TODO: Place some non-empty nested folders in the workspace
          //  Expected results: success with the workspace contents
        }

        @Test
        void nonEmptyWorkspaceWithDepth() {
          // TODO: Place some non-empty nested folders in the workspace and provide the depth query variable
          //  Expected results: success with the workspace contents up to the specified depth
        }
      }
    }

    /**
     * Tests for the /ws/{workspaceId}/<filePath> routes.
     *
     * Disabled because the suite is skeletoned but not implemented.
     */
    @Nested
    @Disabled
    class WSRoutes {
      @Nested
      class Get {
        @Test
        void forbidden() {
          // TODO: Returns a 403 if Forbidden. Will need to temporarily update permissions to actually get this effect
        }

        @Test
        void noSuchFile() {
          // TODO: Returns a 404 if the file specified does not exist
        }

        @Test
        void noSuchWorkspace() {
          // TODO: returns a 404 if the workspace does not exist
        }

        @Test
        void getFile() {
          // TODO: Returns the file's contents
        }

        @Test
        void getDirectoryEmpty() {
          // TODO: Returns a list of the directory's contents
          //  The directory in this test is empty
        }

        @Test
        void getDirectoryAllFileTypes() {
          // TODO: Returns a list of the directory's contents
          //  The directory in this test has one file of each supported content type (including a nested directory)
          //  Once metadata is added, this method should be updated to include the fetched metadata info
        }

        @Test
        void getDirectoryValidDepth() {
          // TODO: pass the depth flag and set it to 1. run it against a folder with a nested folder.
          //  expected result: only the first level is returned
        }

        @Test
        void getDirectoryInvalidDepth() {
          // TODO: Pass invalid values to the depth flag. Should return 400 errors.
        }
      }

      @Nested
      class Put {
        @Test
        void forbidden() {
          // TODO: Returns a 403 if Forbidden. Use viewer role for this
        }

        @Test
        void forbiddenNotOwner() {
          // TODO: Someone who is not the owner cannot put a file in
        }

        @Test
        void noSuchWorkspace() {
          // TODO: Expected 404
        }

        @Test
        void ownerCanPutFile() {
          // TODO: Owner can put a file in the workspace
        }

        @Test
        void collaboratorCanPutFile() {
          // TODO: Collaborator can put a file in the workspace
        }

        @Test
        void ownerCanPutDirectory() {
          // TODO: Owner can put a directory in the workspace
        }

        @Test
        void collaboratorCanPutDirectory() {
          // TODO: Collaborator can put a directory in the workspace
        }

        @Test
        void putCreatesParentDirs() {
          // TODO: When an file's parent directory does not exist,
          //  the ws server automatically creates the parent directory.
        }

        @Test
        void noTypeProvided() {
          // TODO: mandatory query param 'type' is skipped. Expected 400
        }

        @Test
        void invalidTypeProvided() {
          // TODO: mandatory query param 'type' is set to an invalid value. Expected 400
        }

        @Test
        void nameConflictOverwriteFalse() {
          // TODO: If there already exists a file with the same name as the file trying to be set,
          //  and the query param 'overwrite' is set to 'false', the ws server will return 409 Conflicted
          //  and not post the new file (check the file contents)
        }

        @Test
        void overwriteDefaultsToFalse() {
          // TODO: If there already exists a file with the same name as the file trying to be set,
          //  and the query param 'overwrite' is set to 'true', the ws server will return 200 and update the file
          //  (check the file contents)
        }

        @Test
        void nameConflictOverwriteTrue() {
          // TODO: If there already exists a file with the same name as the file trying to be set,
          //  and the query param 'overwrite' is not included, the ws server will return 409 Conflicted
          //  and not post the new file (check the file contents)
        }

        @Test
        void overwriteForbiddenOnDirectory() {
          // TODO: The query param 'overwrite' is forbidden when trying to create a directory. Expected 400
        }

        @Test
        void nameConflictDirectory() {
          // TODO: When the user tries to create a directory that does not exist,
          //  the ws server does nothing and returns 200.
          //  (Put sth in the directory and check its contents before and after)
        }

        @Test
        void cannotPutOutsideOfWorkspace() {
          // TODO: The user cannot put a file outside of the workspace's directory (ie, using ../ or ~/)
        }

        @Test
        void fileNotIncluded() {
          // TODO: No file is attached to the body. Expected 400
        }

        @Test
        void fileAttachedWrongName() {
          // TODO: File is attached, but under the wrong name. Expected 400
        }
      }

      @Nested
      class Delete {
        @Test
        void forbidden() {
          // TODO: Returns a 403 if Forbidden. Use viewer role for this
        }

        @Test
        void forbiddenNotOwner() {
          // TODO: Someone who is not the owner cannot delete an item
        }

        @Test
        void noSuchWorkspace() {
          // TODO: Expected 404
        }

        @Test
        void ownerCanDeleteFile() {
          // TODO: Owner can delete a file in the workspace
        }

        @Test
        void collaboratorCanDeleteFile() {
          // TODO: Collaborator can delete a file in the workspace
        }

        @Test
        void ownerCanDeleteDirectory() {
          // TODO: Owner can delete a directory in the workspace
        }

        @Test
        void collaboratorCanDeleteDirectory() {
          // TODO: Collaborator can delete a directory in the workspace
        }

        @Test
        void deleteRecursive() {
          // TODO: Deleting a directory is recursive (removes all content below)
          //  (Notable as this behavior diverges from the default behavior of rm or rmdir, and for the most part
          //    our endpoints follow standard OS behaviors, ie mv and cp)
        }

        @Test
        void deleteIncludesMetadata() {
          // TODO: When a file with a metadata file is deleted, its metadata file is deleted as well.
        }

        @Test
        void cannotDeleteOutsideOfWorkspace() {
          // TODO: A file outside of the workspace cannot be targeted for deletion (ie, using ../ or ~/)
        }
      }

      @Nested
      class Post {
        @Test
        void noMoveOrCopyKey() {
          // TODO: Expected 400 error. Error message should include the endpoint's helptext.
        }

        @Nested
        class Move {
          @Test
          void forbiddenCannotReadSource() {
            // TODO: The role requires the "read_file_directory" permission
            //  (additionally, the user must pass the permission's check for the source ws)
            //    Testing this requires permissions modifications (remember to revert at the end of test)
            //    (to remove the permission from a role and then set the permission to OWNER
            //      (and have the user NOT be the owner of the source ws))
          }

          @Test
          void forbiddenCannotDeleteSource() {
            // TODO: The role requires the "delete_file_directory" permission
            //  (additionally, the user must pass the permission's check for the source ws)
            //  The "viewer" role and the "user" role where the user is not owner/collaborator of the source ws
            //    will test both cases
          }

          @Test
          void forbiddenCannotWriteTarget() {
            // TODO: The role requires the "write_file_directory" permission
            //  (additionally, the user must pass the permission's check for the source ws)
            //  The "viewer" role and the "user" role where the user is not owner/collaborator of the target ws
            //    will test both cases
          }

          @Test
          void withinWSMove() {
            /*
                TODO: Test cases:
                  - Owner
                  - Collaborator
                  - Both (as in the owner is also listed as a collaborator)
                 Expected Results: All cases succeed
             */
          }

          @Test
          void crossWSMove() {
            /*
              TODO: Test cases:
                - owner source, collaborator target
                - owner source,  owner target
                - collaborator source, owner target
                - collaborator source, collaborator target
               Expected Results: All cases succeed
             */
          }

          @Test
          void noSuchSourceWS() {
            // TODO: Expected 404
          }

          @Test
          void noSuchTargetWS() {
            // TODO: Expected 404
          }

          @Test
          void noSuchFile() {
            // TODO: The file trying to be moved does not exist. Expected 404
          }

          @Test
          void noSuchDirectory() {
            // TODO: The directory trying to be moved does not exist. Expected 404
          }

          @Test
          void cannotMoveFileNotInSource() {
            // TODO: Cannot move a file that exists, but is not in the source workspace
          }

          @Test
          void cannotMoveOutsideWSBounds() {
            // TODO: Show that the file cannot be moved to somewhere "outside" of the target workspace's path
          }

          @Test
          void cannotMoveRecursive() {
            // TODO: A directory cannot be moved within itself
          }

          @Test
          void moveIncludesContents() {
            // TODO: All contents of a directory, including subdirectories, are moved as well
          }

          @Test
          void moveIncludesMetadata() {
            // TODO: When a file with a metadata file is moved, its metadata file is moved as well
          }

          @Test
          void conflictedIfDestinationExists() {
            // TODO: When the destination file exists, the move returns a 409 Conflicted and will not move the file
          }
        }

        @Nested
        class Copy {
          @Test
          void forbiddenCannotReadSource() {
            // TODO: The role requires the "read_file_directory" permission
            //  (additionally, the user must pass the permission's check for the source ws)
            //    Testing this requires permissions modifications (remember to revert at the end of test)
            //    (to remove the permission from a role and then set the permission to OWNER
            //      (and have the user NOT be the owner of the source ws))
          }

          @Test
          void forbiddenCannotWriteTarget() {
            // TODO: The role requires the "write_file_directory" permission
            //  (additionally, the user must pass the permission's check for the source ws)
            //  The "viewer" role and the "user" role where the user is not owner/collaborator of the target ws
            //    will test both cases
          }

          @Test
          void withinWSCopy() {
            /*
                TODO: Test cases:
                  - Owner
                  - Collaborator
                  - Both (as in the owner is also listed as a collaborator)
                 Expected Results: All cases succeed
             */
          }

          @Test
          void crossWSCopy() {
            /*
              TODO: Test cases:
                - owner source, collaborator target
                - owner source,  owner target
                - collaborator source, owner target
                - collaborator source, collaborator target
               Expected Results: All cases succeed
             */
          }

          @Test
          void noSuchSourceWS() {
            // TODO: Expected 404
          }

          @Test
          void noSuchTargetWS() {
            // TODO: Expected 404
          }

          @Test
          void noSuchFile() {
            // TODO: The file trying to be copied does not exist. Expected 404
          }

          @Test
          void noSuchDirectory() {
            // TODO: The directory trying to be copied does not exist. Expected 404
          }

          @Test
          void cannotCopyFileNotInSource() {
            // TODO: Cannot copy a file that exists, but is not in the source workspace
          }

          @Test
          void cannotCopyOutsideWSBounds() {
            // TODO: Show that the file cannot be copied to somewhere "outside" of the target workspace's path
          }

          @Test
          void cannotCopyRecursive() {
            // TODO: A directory cannot be copied within itself
          }

          @Test
          void moveIncludesContents() {
            // TODO: All contents of a directory, including subdirectories, are copied as well
          }

          @Test
          void moveIncludesMetadata() {
            // TODO: When a file with a metadata file is copied, its metadata file is copied as well
          }

          @Test
          void conflictedIfDestinationExists() {
            // TODO: When the destination file exists, the endpoint returns a 409 Conflicted and will not copy the file
          }
        }
      }
    }

    /**
     * Tests for the response of the `authorize` before on `/ws/*` routes.
     * Uses GET /ws/{workspaceId} for testing
     *
     * Does not test the case where the Workspace Server doesn't have the HasuraAdminSecret provided,
     * as our test environment has that set.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class WSAuthorizeTests {
      private int workspaceId;

      @BeforeAll
      void beforeAll() throws IOException {
        workspaceId = wsServer.createWorkspace("wsAuthorizeTests", parcelId);
      }

      @AfterAll
      void afterAll() throws IOException {
        wsServer.deleteWorkspace(workspaceId);
      }

      /**
       * The workspace server returns a 401 Unauthorized response when no authorization headers are provided.
       */
      @Test
      void noAuthHeader() {
        final var response = wsServer.listWorkspaceContents(Map.of(), workspaceId);
        assertEquals(401, response.status());
        final var body = getBody(response);
        assertEquals("UNAUTHORIZED", body.getString("type"));
        assertEquals("Invalid Authorization header provided.", body.getString("message"));
        assertEquals("aerie_workspace", body.getString("service"));
      }

      @Nested
      class AdminSecret {
        private static final String adminSecret = System.getenv("HASURA_GRAPHQL_ADMIN_SECRET");

        /**
         * The workspace server returns a 401 Unauthorized response when a request tries to use an invalid admin secret.
         */
        @Test
        void invalidSecret() {
          final String fakeSecret = adminSecret.substring(0, 1);
          final var headers = Map.of("x-hasura-role", "user",
                                     "x-hasura-user-id", "bindings_not_owner",
                                     "x-hasura-admin-secret", fakeSecret);
          final var response = wsServer.listWorkspaceContents(headers, workspaceId);
          assertEquals(401, response.status());
          final var body = getBody(response);
          assertEquals("UNAUTHORIZED", body.getString("type"));
          assertEquals("Invalid Hasura admin secret", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        /**
         * The workspace server returns a 401 Unauthorized response when the admin secret is provided
         * but a user id is not.
         */
        @Test
        void noUserIdProvided() {
          final var headers = Map.of("x-hasura-role", "user",
                                     "x-hasura-admin-secret", adminSecret);
          final var response = wsServer.listWorkspaceContents(headers, workspaceId);
          assertEquals(401, response.status());
          final var body = getBody(response);
          assertEquals("UNAUTHORIZED", body.getString("type"));
          assertEquals("x-hasura-user-id header is required when x-hasura-admin-secret is set", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        /**
         * If the workspace server is provided a valid admin secret and user id, but no active role,
         * the server will default to the "aerie_admin" role.
         *
         * This test proves this using the "put file" endpoint with a user who is neither the owner nor collaborator.
         * Only the 'aerie_admin' role is allowed to put files on a workspace they don't own.
         * (see WSRoutes.Put#forbidden for more information)
         */
        @Test
        void noActiveRoleProvided() {
          final var fileName = "sampleFile";
          final var filePayload = new FilePayload(fileName,
                                                  "text/plain",
                                                  "this is an example file".getBytes(StandardCharsets.UTF_8));
          final var options = RequestOptions.create()
                                            .setQueryParam("type", "file")
                                            .setHeader("x-hasura-admin-secret", adminSecret)
                                            .setHeader("x-hasura-user-id", "not a user")
                                            .setMultipart(FormData.create().set("file", filePayload));
          final var response = wsServer.makeRequest("/ws/%d/%s".formatted(workspaceId, fileName),
                                                    options,
                                                    WorkspaceRequests.RequestType.PUT);
          assertEquals(200, response.status());
          assertEquals("File %s uploaded to %s".formatted(fileName, fileName), response.text());
        }

        /**
         * The workspace server accepts an admin secret, userid, and active role
         *
         * This test proves this using the "put file" endpoint with a user trying to use the "viewer" role.
         * This role is not allowed to use this endpoint, while "aerie_admin" is
         */
        @Test
        void validSecretUserIdActiveRole() {
          final var fileName = "sampleFile";
          final var filePayload = new FilePayload(fileName,
                                                  "text/plain",
                                                  "this is an example file".getBytes(StandardCharsets.UTF_8));
          final var options = RequestOptions.create()
                                            .setQueryParam("type", "file")
                                            .setHeader("x-hasura-admin-secret", adminSecret)
                                            .setHeader("x-hasura-user-id", "not a user")
                                            .setHeader("x-hasura-role", "viewer")
                                            .setMultipart(FormData.create().set("file", filePayload));
          final var response = wsServer.makeRequest("/ws/%d/%s".formatted(workspaceId, fileName),
                                                    options,
                                                    WorkspaceRequests.RequestType.PUT);
          assertEquals(403, response.status());
          final var body = getBody(response);
          assertEquals("FORBIDDEN", body.getString("type"));
          assertEquals("Role 'viewer' is not allowed to perform action 'write_file_directory'", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }
      }

      @Nested
      @TestInstance(TestInstance.Lifecycle.PER_CLASS)
      class JWTAuth {
        /**
         * The workspace server returns a 401 Unauthorized error when the JWT header has an invalid format.
         */
        @ParameterizedTest
        @MethodSource("misformattedJWTHeaderArgs")
        void misformattedJWTHeader(String token) {
          final var headers = Map.of("Authorization", token);
          final var response = wsServer.listWorkspaceContents(headers, workspaceId);
          assertEquals(401, response.status());
          final var body = getBody(response);
          assertEquals("UNAUTHORIZED", body.getString("type"));
          assertEquals("Invalid Authorization header provided.", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        private Stream<Arguments> misformattedJWTHeaderArgs() {
          return Stream.of(
              Arguments.arguments(named("empty header", "")),
              Arguments.arguments(named("valid token without Bearer", adminToken)),
              Arguments.arguments(named("Bearer but no token", "Bearer ")),
              Arguments.arguments(named("invalid token", "not_a_valid_token"))
          );
        }

        /**
         * The workspace server returns a 401 Unauthorized error when the JWT passed is invalid.
         */
        @Test
        void invalidJWT() {
          final var headers = Map.of("Authorization", "Bearer not_a_valid_token");
          final var response = wsServer.listWorkspaceContents(headers, workspaceId);
          assertEquals(401, response.status());
          final var body = getBody(response);
          assertEquals("UNAUTHORIZED", body.getString("type"));
          assertEquals("The token was expected to have 3 parts, but got 0.", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        /**
         * The workspace server accepts a valid JWT.
         */
        @Test
        void validJWT() {
          final var headers = Map.of("Authorization", "Bearer " +viewerToken);
          final var response = wsServer.listWorkspaceContents(headers, workspaceId);
          assertEquals(200, response.status());
        }

        /**
         * The workspace server returns a 401 Unauthorized response when a user passes a valid JWT
         * but attempts to use the x-hasura-role flag for a role they don't have permission to use.
         */
        @Test
        void validJWTInvalidRole() {
          final var headers = Map.of("Authorization", "Bearer "+viewerToken,
                                     "x-hasura-role", "aerie_admin");
          final var response = wsServer.listWorkspaceContents(headers, workspaceId);
          assertEquals(401, response.status());
          final var body = getBody(response);
          assertEquals("UNAUTHORIZED", body.getString("type"));
          assertEquals("Provided active role is not in the set of permitted roles.", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        /**
         * The workspace server allows the user to use the x-hasura-role header to swap to one of their permitted roles.
         *
         * This test proves this using the admin token to the "put file" endpoint while setting the current role to "viewer".
         * While "aerie_admin" (the token's default role) is allowed to access this endpoint,
         * the "viewer" role will receive a 403 Forbidden error.
         */
        @Test
        void validJWTValidRole() {
          final var fileName = "sampleFile";
          final var filePayload = new FilePayload(fileName,
                                                  "text/plain",
                                                  "this is an example file".getBytes(StandardCharsets.UTF_8));
          final var options = RequestOptions.create()
                                            .setQueryParam("type", "file")
                                            .setHeader("Authorization", "Bearer "+adminToken)
                                            .setHeader("x-hasura-role", "viewer")
                                            .setMultipart(FormData.create().set("file", filePayload));
          final var response = wsServer.makeRequest("/ws/%d/%s".formatted(workspaceId, fileName),
                                                    options,
                                                    WorkspaceRequests.RequestType.PUT);
          assertEquals(403, response.status());
          final var body = getBody(response);
          assertEquals("FORBIDDEN", body.getString("type"));
          assertEquals("Role 'viewer' is not allowed to perform action 'write_file_directory'", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }

        /**
         * The presence of the `x-hasura-admin-secret` header overrides the presence of the `Authorization` header
         *
         * Demonstrated by providing a valid JWT and an invalid admin secret and having the request be rejected
         * because of the invalid admin secret
         */
        @Test
        void secretOverridesJWT() {
          final String fakeSecret = System.getenv("HASURA_GRAPHQL_ADMIN_SECRET").substring(0, 1);
          final var headers = Map.of("Authorization", "Bearer " +viewerToken,
                                     "x-hasura-admin-secret", fakeSecret,
                                     "x-hasura-user-id", "bindings_viewer");
          final var response = wsServer.listWorkspaceContents(headers, workspaceId);
          assertEquals(401, response.status());
          final var body = getBody(response);
          assertEquals("UNAUTHORIZED", body.getString("type"));
          assertEquals("Invalid Hasura admin secret", body.getString("message"));
          assertEquals("aerie_workspace", body.getString("service"));
        }
      }
    }
  }
}
