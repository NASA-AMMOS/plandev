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
import gov.nasa.jpl.aerie.e2e.types.workspaces.BulkPutItem;
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
import java.nio.file.Path;
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

    private final User owner = new User(
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
     * Tests for the /ws/{workspaceId}/<path> routes.
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
     * Tests for the /ws/bulk/{workspaceId}/ routes.
     */
    @Nested
    class BulkWSRoutes {
      @Nested
      class BulkPut {
        private int workspaceId;

        @BeforeEach
        void beforeEach() throws IOException {
          workspaceId = wsServer.createWorkspace("bulkPutWS", parcelId);
        }

        @AfterEach
        void afterEach() throws IOException {
          wsServer.deleteWorkspace(workspaceId);
        }

        /**
         * Basic successful cases.
         * All of these should return a top level status of 207,
         *    and an array of JSON objects with the same length as the input list.
         * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
         *    and a 'result' field that either says "directory created" or "file uploaded",
         *    as appropriate based on the created item's type.
         * Additionally, a GET request for the item should succeed after the PUT.
         */
        @ParameterizedTest
        @MethodSource("bulkPutBasicCasesArgs")
        void bulkPutBasicCases(List<BulkPutItem> inputs) {
          final var resp = wsServer.bulkPut(ownerToken, workspaceId, inputs);

          // Check status code
          assertEquals(207, resp.status());

          // Check details of response
          final var respBody = getArrayBody(resp);
          assertEquals(inputs.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = inputs.get(i);
            final var actual = respBody.get(i).asJsonObject();

            // Check the PUT response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.getPath().toString(), actual.getString("item"));
            if (expected instanceof BulkPutItem.FileBulkPutItem file) {
              assertEquals(
                  "File " + expected.getPath().getFileName() + " uploaded to " + expected.getPath(),
                  actual.getString("response"));
              // Check that file was uploaded with the correct contents
              final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
              assertEquals(200, getResp.status());
              assertEquals(file.fileContents(), getResp.text());
            } else {
              assertEquals("Directory created.", actual.getString("response"));
              // Simple check that the item was actually uploaded -- does not check directory contents
              final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
              assertEquals(200, getResp.status());
            }
          }
        }

        /**
         * Generate arguments to test basic upload cases.
         */
        private static Stream<Arguments> bulkPutBasicCasesArgs() {
          final var myFileInput = new BulkPutItem.FileBulkPutItem(
              Path.of("myFile.txt"),
              "this is my file contents");
          final var myDirInput = new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir"));
          final var folderDirInput = new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir/subDir"), true);
          final var secondFileInput = new BulkPutItem.FileBulkPutItem(
              Path.of("myDir/otherFile.txt"),
              "this is another file",
              "anotherFile.txt");

          return Stream.of(
              Arguments.arguments(named("Single File Bulk PUT", List.of(myFileInput))),
              Arguments.arguments(named("Single Directory Bulk PUT", List.of(myDirInput))),
              Arguments.arguments(named("Multiple Files Bulk PUT", List.of(myFileInput, secondFileInput))),
              Arguments.arguments(named("Multiple Directories Bulk PUT", List.of(myDirInput, folderDirInput))),
              Arguments.arguments(named(
                  "Mixed Files and Directories Bulk PUT",
                  List.of(myDirInput, folderDirInput, myFileInput, secondFileInput)))
          );
        }

        /**
         * When only one item upload fails, the overall status is 207, the successful items have a status of 200,
         * and the unsuccessful items have an appropriate error status.
         */
        @Test
        void mixedResults() {
          // Setup: upload a conflicting file using the non-bulk endpoint
          final var putResp = wsServer.putFile(ownerToken, workspaceId, Path.of("file.txt"), "original file contents");
          assertEquals(200, putResp.status());

          // Upload a list of items, including one conflict
          final List<BulkPutItem> toUpload = List.of(
              new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "conflicting file"),
              new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir")),
              new BulkPutItem.FileBulkPutItem(
                  Path.of("myDir/file.txt"),
                  "file with same name in another folder",
                  "otherFile.txt"));

          final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

          // Check Response
          assertEquals(207, resp.status());
          final var respBody = getArrayBody(resp);
          assertEquals(3, respBody.size());

          // First item should be the conflicted file with a 409 Conflicted
          final var conflictFile = respBody.getFirst().asJsonObject();
          assertEquals("file.txt", conflictFile.getString("item"));
          assertEquals(409, conflictFile.getInt("status"));
          assertEquals("original file contents", wsServer.get(ownerToken, workspaceId, Path.of("file.txt")).text());

          // Second item should be the unconflicted directory
          final var dir = respBody.get(1).asJsonObject();
          assertEquals("myDir", dir.getString("item"));
          assertEquals(200, dir.getInt("status"));
          assertEquals(
              "[{\"name\":\"file.txt\",\"type\":\"TEXT\"}]",
              wsServer.get(ownerToken, workspaceId, Path.of("myDir")).text());

          // Third item should be the unconflicted file
          final var otherFile = respBody.getLast().asJsonObject();
          assertEquals("myDir/file.txt", otherFile.getString("item"));
          assertEquals(200, otherFile.getInt("status"));
          assertEquals(
              "file with same name in another folder",
              wsServer.get(ownerToken, workspaceId, Path.of("myDir/file.txt")).text());
        }

        /**
         * File contents can be attached under a name other than the file's uploaded name using the input_file_name field
         */
        @Test
        void customInputName() {
          // Upload two items with the same name to different folders.
          final List<BulkPutItem> toUpload = List.of(
              new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file in one folder"),
              new BulkPutItem.FileBulkPutItem(
                  Path.of("myDir/file.txt"),
                  "file with same name in another folder",
                  "otherFile.txt"));

          final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

          // Check Response
          assertEquals(207, resp.status());
          final var respBody = getArrayBody(resp);
          assertEquals(toUpload.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = toUpload.get(i);
            final var actual = respBody.get(i).asJsonObject();

            // Check the PUT response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.getPath().toString(), actual.getString("item"));
            if (expected instanceof BulkPutItem.FileBulkPutItem file) {
              assertEquals(
                  "File " + expected.getPath().getFileName() + " uploaded to " + expected.getPath(),
                  actual.getString("response"));
              // Check that file was uploaded with the correct contents
              final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
              assertEquals(200, getResp.status());
              assertEquals(file.fileContents(), getResp.text());
            } else {
              fail();
            }
          }
        }

        /**
         * File contents can be attached under a name other than the file's uploaded name using the input_file_name field
         */
        @Test
        void bulkUploadCreate() {
          // Upload a list of items, including one conflict
          final List<BulkPutItem> toUpload = List.of(
              new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file in one folder"),
              new BulkPutItem.FileBulkPutItem(
                  Path.of("myDir/file.txt"),
                  "file with same name in another folder",
                  "otherFile.txt"));

          final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

          // Check Response
          assertEquals(207, resp.status());
          final var respBody = getArrayBody(resp);
          assertEquals(toUpload.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = toUpload.get(i);
            final var actual = respBody.get(i).asJsonObject();

            // Check the PUT response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.getPath().toString(), actual.getString("item"));
            if (expected instanceof BulkPutItem.FileBulkPutItem file) {
              assertEquals(
                  "File " + expected.getPath().getFileName() + " uploaded to " + expected.getPath(),
                  actual.getString("response"));
              // Check that file was uploaded with the correct contents
              final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
              assertEquals(200, getResp.status());
              assertEquals(file.fileContents(), getResp.text());
            } else {
              fail();
            }
          }
        }

        @Nested
        class MalformedRequest {
          private final static String endpoint = "/ws/bulk/%d";

          /**
           * A PUT request with no "body" component fails with a 400
           */
          @Test
          void noBodyRejected() {
            final var formData = FormData.create();
            final var fileContents = new FilePayload(
                "myFile.txt",
                "text/plain",
                "example file contents".getBytes(StandardCharsets.UTF_8));

            // Generate the request
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setMultipart(formData.set("files", fileContents));

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
            assertEquals(400, resp.status());
            final var respBody = getBody(resp);
            assertEquals("MALFORMED_REQUEST", respBody.getString("type"));
            assertEquals("Invalid body format.", respBody.getString("message"));
          }

          /**
           * A PUT request attempting to upload files must include a "files" component
           */
          @Test
          void noFilesFileUploadRejected() {
            final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file contents");
            final var formData = FormData.create();

            // Generate the request
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setMultipart(formData.set("body", Json.createArrayBuilder().add(fileUpload.toJson()).build().toString()));

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
            assertEquals(207, resp.status());
            final var fileResp = getArrayBody(resp).getFirst().asJsonObject();
            assertEquals("file.txt", fileResp.getString("item"));
            assertEquals(400, fileResp.getInt("status"));

            final var fileError = fileResp.getJsonObject("response");
            assertEquals("MALFORMED_REQUEST", fileError.getString("type"));
            assertEquals("No file provided with the name file.txt", fileError.getString("message"));
            assertEquals("Attach file contents under the 'files' part of the request.", fileError.getString("cause"));

            assertEquals(404, wsServer.get(ownerToken, workspaceId, fileUpload.getPath()).status());
          }

          /**
           * If multiple files are attached under the same name, the request is rejected.
           */
          @Test
          void attachedFileNameConflictRejected() {
            final List<BulkPutItem> toUpload = List.of(
                new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file in one folder"),
                new BulkPutItem.FileBulkPutItem(Path.of("myDir/file.txt"), "file with same name in another folder"));

            final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

            // Check Response
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Cannot process request: multiple files are attached under the same name.", body.getString("message"));

            // Check that no files were actually uploaded
            for(final var item : toUpload) {
              assertEquals(404, wsServer.get(ownerToken, workspaceId, item.getPath()).status());
            }
          }

          /**
           * Reject the request if multiple files are trying to be uploaded to the same location.
           */
          @ParameterizedTest
          @MethodSource("multipleItemsSameLocationArgs")
          void twoItemsToSameLocationRejected(List<BulkPutItem> toUpload) {
            final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

            // Check Response
            assertEquals(409, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Multiple items are attempting to be uploaded to the same location. Please give all items unique names.", body.getString("message"));

            // Check that no items were actually created
            for(final var item : toUpload) {
              assertEquals(404, wsServer.get(ownerToken, workspaceId, item.getPath()).status());
            }
          }

          private static Stream<Arguments> multipleItemsSameLocationArgs() {
            final var fileName = Path.of("file.txt");
            final var dirName = Path.of("myDir");
            final List<BulkPutItem> fileExample = List.of(
                new BulkPutItem.FileBulkPutItem(fileName, "file in one folder"),
                new BulkPutItem.FileBulkPutItem(fileName, "file with same name", "otherFile.txt"));

            final List<BulkPutItem> dirExample = List.of(
                new BulkPutItem.DirectoryBulkPutItem(dirName, true),
                new BulkPutItem.DirectoryBulkPutItem(dirName));

            final List<BulkPutItem> mixedExample = List.of(
                new BulkPutItem.DirectoryBulkPutItem(dirName),
                new BulkPutItem.FileBulkPutItem(dirName, "file with directory name"));

            final List<BulkPutItem> nestedExample = List.of(
                new BulkPutItem.FileBulkPutItem(dirName.resolve(fileName), "file in one folder"),
                new BulkPutItem.FileBulkPutItem(dirName.resolve(fileName), "file with same name", "otherFile.txt"));

            return Stream.of(
                Arguments.arguments(named("Two Files", fileExample)),
                Arguments.arguments(named("Two Directories", dirExample)),
                Arguments.arguments(named("File and Directory", mixedExample)),
                Arguments.arguments(named("Two Files in a Directory", nestedExample)));
          }

          /**
           * The input must be a multipart/form-data, even when just creating multiple directories
           */
          @Test
          void nonMultipartFails() {
            final BulkPutItem directoryUpload = new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir"));
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("content-type", "application/json")
                .setData(Json.createArrayBuilder().add(directoryUpload.toJson()).build().toString());

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
            assertEquals(400, resp.status());
            final var respBody = getBody(resp);
            assertEquals("MALFORMED_REQUEST", respBody.getString("type"));
            assertEquals("Invalid body format.", respBody.getString("message"));

            assertEquals(404, wsServer.get(ownerToken, workspaceId, directoryUpload.getPath()).status());
          }

          /**
           * The "body" part of the request must be a JSON
           */
          @Test
          void nonJSONBodyRejected() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setMultipart(FormData.create().set("body", "make a new folder please"));

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
            assertEquals(400, resp.status());
            final var respBody = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", respBody.getString("type"));
            assertTrue(respBody.getString("message").startsWith("Invalid body format. Expected body format is an array of JSON objects with the form:"));
          }

          /**
           * Directories can't have custom input names.
           */
          @Test
          void customInputNameDisallowedDirectory() {
            final var dirInput = Json.createObjectBuilder()
                                     .add("path", "myDir")
                                     .add("type", "directory")
                                     .add("input_file_name", "otherDir")
                                     .build();

            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer " + ownerToken)
                .setMultipart(FormData.create().set("body", dirInput.toString()));

            final var resp = wsServer.makeRequest(
                endpoint.formatted(workspaceId),
                options,
                WorkspaceRequests.RequestType.PUT);
            assertEquals(400, resp.status());
            final var respBody = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", respBody.getString("type"));
            assertTrue(respBody
                           .getString("message")
                           .startsWith(
                               "Invalid body format. Expected body format is an array of JSON objects with the form:"));
          }

          /**
           * The "files" part of the request, if provided, must contain files.
           */
          @Test
          void bodyInFilesRejected() {
            final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file contents");
            final var body = Json.createArrayBuilder().add(fileUpload.toJson()).build().toString();
            final var formData = FormData.create();

            // Generate the request
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setMultipart(formData.set("body", body).set("files", body));

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
            assertEquals(207, resp.status());
            final var fileResp = getArrayBody(resp).getFirst().asJsonObject();
            assertEquals("file.txt", fileResp.getString("item"));
            assertEquals(400, fileResp.getInt("status"));

            final var fileError = fileResp.getJsonObject("response");
            assertEquals("MALFORMED_REQUEST", fileError.getString("type"));
            assertEquals("No file provided with the name file.txt", fileError.getString("message"));
            assertEquals("Attach file contents under the 'files' part of the request.", fileError.getString("cause"));

            assertEquals(404, wsServer.get(ownerToken, workspaceId, fileUpload.getPath()).status());
          }

          /**
           * The PUT request must specify items to upload
           */
          @Test
          void emptyBodyRejected() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setMultipart(FormData.create().set("body", "[]"));

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
            assertEquals(400, resp.status());
            final var respBody = getBody(resp);
            assertEquals("MALFORMED_REQUEST", respBody.getString("type"));
            assertEquals("Cannot process request: at least one item must be specified.", respBody.getString("message"));
          }
        }

        @Nested
        class Overwrite {
          @BeforeEach
          void beforeEach() {
            wsServer.putFile(ownerToken, workspaceId, Path.of("myFile.txt"), "original file contents");
          }

          /**
           * Directories can't use the overwrite flag.
           */
          @Test
          void overwriteDisallowedDirectory() {
            final var dirInput = Json.createObjectBuilder()
                                     .add("path", "myDir")
                                     .add("type", "directory")
                                     .add("overwrite", true)
                                     .build();

            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer " + ownerToken)
                .setMultipart(FormData.create().set("body", dirInput.toString()));

            final var resp = wsServer.makeRequest(
                "/ws/bulk/%d".formatted(workspaceId),
                options,
                WorkspaceRequests.RequestType.PUT);
            assertEquals(400, resp.status());
            final var respBody = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", respBody.getString("type"));
            assertTrue(respBody
                           .getString("message")
                           .startsWith(
                               "Invalid body format. Expected body format is an array of JSON objects with the form:"));
          }

          /**
           * When the "overwrite" flag is not set, it defaults to false
           */
          @Test
          void overwriteUnset() {
            final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(
                Path.of("myFile.txt"),
                "new file contents"
            );

            // The overall response was a 207 Multipart
            final var resp = wsServer.bulkPut(ownerToken, workspaceId, List.of(fileUpload));
            assertEquals(207, resp.status());
            final var body = getArrayBody(resp);
            assertEquals(1, body.size());

            // The item's specific response was a 409
            final var item = body.getFirst().asJsonObject();
            final var itemResp = item.getJsonObject("response");
            assertEquals("myFile.txt", item.getString("item"));
            assertEquals(409, item.getInt("status"));
            assertEquals("INTERNAL_ERROR", itemResp.getString("type"));
            assertEquals("myFile.txt already exists.", itemResp.getString("message"));

            // The file's contents were NOT overwritten
            assertEquals("original file contents", wsServer.get(ownerToken, workspaceId, Path.of("myFile.txt")).text());
          }

          /**
           * When the "overwrite" flag is set to false, the server will not upload a file if it already exists
           */
          @Test
          void overwriteFalse() {
            final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(
                Path.of("myFile.txt"),
                "new file contents",
                false
            );

            // The overall response was a 207 Multipart
            final var resp = wsServer.bulkPut(ownerToken, workspaceId, List.of(fileUpload));
            assertEquals(207, resp.status());
            final var body = getArrayBody(resp);
            assertEquals(1, body.size());

            // The item's specific response was a 409
            final var item = body.getFirst().asJsonObject();
            final var itemResp = item.getJsonObject("response");
            assertEquals("myFile.txt", item.getString("item"));
            assertEquals(409, item.getInt("status"));
            assertEquals("INTERNAL_ERROR", itemResp.getString("type"));
            assertEquals("myFile.txt already exists.", itemResp.getString("message"));

            // The file's contents were NOT overwritten
            assertEquals("original file contents", wsServer.get(ownerToken, workspaceId, Path.of("myFile.txt")).text());
          }

          /**
           * When the "overwrite" flag is set to true, the server will overwrite a file if it is present
           */
          @Test
          void overwriteTrue() {
            final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(
                Path.of("myFile.txt"),
                "new file contents",
                true
            );

            // The overall response was a 207 Multipart
            final var resp = wsServer.bulkPut(ownerToken, workspaceId, List.of(fileUpload));
            assertEquals(207, resp.status());
            final var body = getArrayBody(resp);
            assertEquals(1, body.size());

            // The item's specific response was a 200
            final var item = body.getFirst().asJsonObject();
            assertEquals("myFile.txt", item.getString("item"));
            assertEquals(200, item.getInt("status"));
            assertEquals("File myFile.txt uploaded to myFile.txt", item.getString("response"));

            // The file's contents were overwritten
            assertEquals("new file contents", wsServer.get(ownerToken, workspaceId, Path.of("myFile.txt")).text());
          }
        }
      }

      @Nested
      class BulkPost {
        private int workspaceId;
        private int otherWorkspaceId;
        private final Path destinationPath = Path.of("./destination_dir");

        @BeforeEach
        void beforeEach() throws IOException {
          workspaceId = wsServer.createWorkspace("bulkPostWS", parcelId);
          otherWorkspaceId = wsServer.createWorkspace("otherBulkPostWs", parcelId);

          // Prepopulate ws with contents
          final List<BulkPutItem> wsContents = List.of(
              new BulkPutItem.DirectoryBulkPutItem("top_dir"),
              new BulkPutItem.DirectoryBulkPutItem("top_dir/nested_dir"),
              new BulkPutItem.DirectoryBulkPutItem("top_dir/other_nested_dir"),
              new BulkPutItem.DirectoryBulkPutItem("other_dir"),
              new BulkPutItem.DirectoryBulkPutItem("other_dir/nested_dir"),
              new BulkPutItem.FileBulkPutItem("top_file.txt", "top level file"),
              new BulkPutItem.FileBulkPutItem("top_dir/sub_file.txt", "file within a directory"),
              new BulkPutItem.FileBulkPutItem("other_dir/other_file.txt", "another file within a directory"),
              new BulkPutItem.FileBulkPutItem("top_dir/nested_dir/nested_file.txt", "file within a nested directory"),
              new BulkPutItem.DirectoryBulkPutItem("destination_dir"),
              new BulkPutItem.DirectoryBulkPutItem("destination")
          );
          wsServer.bulkPut(ownerToken, workspaceId, wsContents);
          wsServer.bulkPut(ownerToken, otherWorkspaceId, List.of(new BulkPutItem.DirectoryBulkPutItem("destination_dir")));
        }

        @AfterEach
        void afterEach() throws IOException {
          wsServer.deleteWorkspace(workspaceId);
          wsServer.deleteWorkspace(otherWorkspaceId);
        }

        /**
         * Basic successful cases of moving files within a workspace.
         * All of these should return a top level status of 207,
         *    and an array of JSON objects with the same length as the input list.
         * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
         *    and a 'response' field that says the item was moved.
         * Additionally, the item should only be findable at its new location
         */
        @ParameterizedTest
        @MethodSource("bulkPostBasicCasesArgs")
        void bulkMoveSameWorkspaceBasicCases(List<Path> inputs) {
          final var resp = wsServer.bulkMove(
              ownerToken,
              workspaceId,
              inputs,
              destinationPath,
              Optional.empty(),
              Optional.empty());


          // Check status code
          assertEquals(207, resp.status());

          // Check details of response
          final var respBody = getArrayBody(resp);
          assertEquals(inputs.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = inputs.get(i);
            final var expectedDestination = destinationPath.resolve(expected.getFileName());
            final var actual = respBody.get(i).asJsonObject();

            // Check the POST response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.toString(), actual.getString("item"));
            assertEquals("'%s' in Workspace %d moved to '%s' in Workspace %d"
                             .formatted(expected, workspaceId, expectedDestination, workspaceId),
                         actual.getString("response"));

            // Simple check that the item was actually moved:
            //  trying to get it at its old location should return a 404 Resource Not Found
            //  while trying to get it at its new location should return a 200
            final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
            assertEquals(404, getOldResp.status());

            final var getNewResp = wsServer.get(ownerToken, workspaceId, expectedDestination);
            assertEquals(200, getNewResp.status());
          }
        }

        /**
         * Basic successful cases of moving files from one workspace to another.
         * All of these should return a top level status of 207,
         *    and an array of JSON objects with the same length as the input list.
         * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
         *    and a 'response' field that says the item was moved.
         * Additionally, the item should only be findable at its new location
         */
        @ParameterizedTest
        @MethodSource("bulkPostBasicCasesArgs")
        void bulkMoveBtwnWorkspaceBasicCases(List<Path> inputs) {
          final var resp = wsServer.bulkMove(
              ownerToken,
              workspaceId,
              inputs,
              destinationPath,
              Optional.of(otherWorkspaceId),
              Optional.empty());


          // Check status code
          assertEquals(207, resp.status());

          // Check details of response
          final var respBody = getArrayBody(resp);
          assertEquals(inputs.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = inputs.get(i);
            final var expectedDestination = destinationPath.resolve(expected.getFileName());
            final var actual = respBody.get(i).asJsonObject();

            // Check the POST response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.toString(), actual.getString("item"));
            assertEquals("'%s' in Workspace %d moved to '%s' in Workspace %d"
                             .formatted(expected, workspaceId, expectedDestination, otherWorkspaceId),
                         actual.getString("response"));

            // Simple check that the item was actually moved:
            //  trying to get it at both its old and new location should return a 200
            //  while trying to get it at its new location should return a 200
            final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
            assertEquals(404, getOldResp.status());

            final var getNewResp = wsServer.get(ownerToken, otherWorkspaceId, expectedDestination);
            assertEquals(200, getNewResp.status());
          }
        }

        /**
         * Basic successful cases of copying files within a workspace.
         * All of these should return a top level status of 207,
         *    and an array of JSON objects with the same length as the input list.
         * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
         *    and a 'response' field that says the item was moved.
         * Additionally, the item should be findable at both its old and new location
         */
        @ParameterizedTest
        @MethodSource("bulkPostBasicCasesArgs")
        void bulkCopySameWorkspaceBasicCases(List<Path> inputs) {
          final var resp = wsServer.bulkCopy(
              ownerToken,
              workspaceId,
              inputs,
              destinationPath,
              Optional.empty(),
              Optional.empty());


          // Check status code
          assertEquals(207, resp.status());

          // Check details of response
          final var respBody = getArrayBody(resp);
          assertEquals(inputs.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = inputs.get(i);
            final var expectedDestination = destinationPath.resolve(expected.getFileName());
            final var actual = respBody.get(i).asJsonObject();

            // Check the POST response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.toString(), actual.getString("item"));
            assertEquals("'%s' in Workspace %d copied to '%s' in Workspace %d"
                             .formatted(expected, workspaceId, expectedDestination, workspaceId),
                         actual.getString("response"));

            // Simple check that the item was actually copied:
            //  trying to get it at both its old and new location should return a 200
            final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
            assertEquals(200, getOldResp.status());

            final var getNewResp = wsServer.get(ownerToken, workspaceId, expectedDestination);
            assertEquals(200, getNewResp.status());

            // The copied file has the same contents
            assertEquals(getOldResp.text(), getNewResp.text());
          }
        }

        /**
         * Basic successful cases of copying files from one workspace to another.
         * All of these should return a top level status of 207,
         *    and an array of JSON objects with the same length as the input list.
         * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
         *    and a 'response' field that says the item was moved.
         * Additionally, the item should be findable at both its old and new location
         */
        @ParameterizedTest
        @MethodSource("bulkPostBasicCasesArgs")
        void bulkCopyBtwnWorkspaceBasicCases(List<Path> inputs) {
          final var resp = wsServer.bulkCopy(
              ownerToken,
              workspaceId,
              inputs,
              destinationPath,
              Optional.of(otherWorkspaceId),
              Optional.empty());


          // Check status code
          assertEquals(207, resp.status());

          // Check details of response
          final var respBody = getArrayBody(resp);
          assertEquals(inputs.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = inputs.get(i);
            final var expectedDestination = destinationPath.resolve(expected.getFileName());
            final var actual = respBody.get(i).asJsonObject();

            // Check the POST response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.toString(), actual.getString("item"));
            assertEquals("'%s' in Workspace %d copied to '%s' in Workspace %d"
                             .formatted(expected, workspaceId, expectedDestination, otherWorkspaceId),
                         actual.getString("response"));

            // Simple check that the item was actually copied:
            //  trying to get it at both its old and new location should return a 200
            final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
            assertEquals(200, getOldResp.status());

            final var getNewResp = wsServer.get(ownerToken, otherWorkspaceId, expectedDestination);
            assertEquals(200, getNewResp.status());

            // The copied file has the same contents
            assertEquals(getOldResp.text(), getNewResp.text());
          }
        }

        /**
         * Generate arguments to test basic upload cases.
         */
        private static Stream<Arguments> bulkPostBasicCasesArgs() {
          final var topFileInput = Path.of("top_file.txt");
          final var nestedFileInput = Path.of("other_dir/other_file.txt");
          final var topDirInput = Path.of("top_dir");
          final var nestedDirInput = Path.of("other_dir/nested_dir");
          final var siblingNameInput = Path.of("destination");

          return Stream.of(
              Arguments.arguments(named("Top Level File Single Bulk POST", List.of(topFileInput))),
              Arguments.arguments(named("Top Level Directory Single Bulk POST", List.of(topDirInput))),
              Arguments.arguments(named("Nested File Single Bulk POST", List.of(nestedFileInput))),
              Arguments.arguments(named("Nested Directory Single Bulk POST", List.of(nestedDirInput))),
              Arguments.arguments(named("Multiple Files Bulk POST", List.of(topFileInput, nestedFileInput))),
              Arguments.arguments(named("Multiple Directories Bulk POST", List.of(topDirInput, nestedDirInput))),
              Arguments.arguments(named(
                  "Mixed Files and Directories Bulk POST",
                  List.of(topFileInput, nestedFileInput, nestedDirInput, topDirInput))),
              Arguments.arguments(named("Sibling Directory with Subset Name", List.of(siblingNameInput)))
          );
        }

        /**
         * When only one item move fails, the overall status is 207, the successful items have a status of 200,
         * and the unsuccessful items have an appropriate error status.
         */
        @Test
        void mixedResultsMove() {
          final var resp = wsServer.bulkMove(
              ownerToken,
              workspaceId,
              List.of(Path.of("fake_file.seq"), Path.of("top_file.txt"), Path.of("other_dir")),
              Path.of("top_dir/other_nested_dir"),
              Optional.empty(),
              Optional.empty());

          // Check Response
          assertEquals(207, resp.status());
          final var respBody = getArrayBody(resp);
          assertEquals(3, respBody.size());

          // First item should be the nonexistant file with a 404 File Not Found
          final var fakeFile = respBody.getFirst().asJsonObject();
          assertEquals("fake_file.seq", fakeFile.getString("item"));
          assertEquals(404, fakeFile.getInt("status"));

          // Second item should be the file that exists
          final var realFile = respBody.get(1).asJsonObject();
          assertEquals("top_file.txt", realFile.getString("item"));
          assertEquals(200, realFile.getInt("status"));
          // Check the item was moved
          assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("top_file.txt")).status());
          assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/top_file.txt")).status());

          // Third item should be the directory that exists
          final var otherFile = respBody.getLast().asJsonObject();
          assertEquals("other_dir", otherFile.getString("item"));
          assertEquals(200, otherFile.getInt("status"));
          // Check the item was moved
          assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("other_dir")).status());
          assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/other_dir")).status());
        }

        /**
         * When only one item copy fails, the overall status is 207, the successful items have a status of 200,
         * and the unsuccessful items have an appropriate error status.
         */
        @Test
        void mixedResultsCopy() {
          final var resp = wsServer.bulkCopy(
              ownerToken,
              workspaceId,
              List.of(Path.of("fake_file.seq"), Path.of("top_file.txt"), Path.of("other_dir")),
              Path.of("top_dir/other_nested_dir"),
              Optional.empty(),
              Optional.empty());

          // Check Response
          assertEquals(207, resp.status());
          final var respBody = getArrayBody(resp);
          assertEquals(3, respBody.size());

          // First item should be the nonexistant file with a 404 File Not Found
          final var fakeFile = respBody.getFirst().asJsonObject();
          assertEquals("fake_file.seq", fakeFile.getString("item"));
          assertEquals(404, fakeFile.getInt("status"));

          // Second item should be the file that exists
          final var realFile = respBody.get(1).asJsonObject();
          assertEquals("top_file.txt", realFile.getString("item"));
          assertEquals(200, realFile.getInt("status"));
          // Check the item was copied
          assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_file.txt")).status());
          assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/top_file.txt")).status());

          // Third item should be the directory that exists
          final var otherFile = respBody.getLast().asJsonObject();
          assertEquals("other_dir", otherFile.getString("item"));
          assertEquals(200, otherFile.getInt("status"));
          // Check the item was copied
          assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("other_dir")).status());
          assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/other_dir")).status());
        }

        @Nested
        class Overwrite {
          /**
           * Prep for the Overwrite Tests by putting conflict files in the destination directory
           */
          @BeforeEach
          void prepOverwriteTests() {
            final List<BulkPutItem> conflictContents = List.of(
                new BulkPutItem.FileBulkPutItem("destination_dir/top_file.txt", "conflicting top level file"),
                new BulkPutItem.DirectoryBulkPutItem("destination_dir/top_dir"),
                new BulkPutItem.DirectoryBulkPutItem("destination_dir/nested_dir"),
                new BulkPutItem.FileBulkPutItem("destination_dir/other_file.txt", "conflicting file within a directory")
            );

            wsServer.bulkPut(ownerToken, workspaceId, conflictContents);
            wsServer.bulkPut(ownerToken, otherWorkspaceId, conflictContents);
          }

          /**
           * An item that exists in both the original and destination locations
           *
           * @param originalPath the path of the item to be moved or copied
           * @param originalContents the contents of the item at its original location
           * @param conflictContents the contents of the conflicting
           */
          private record ConflictItem(Path originalPath, String originalContents, String conflictContents) {}

          /**
           * Generate arguments to test basic upload cases.
           */
          private static Stream<Arguments> overwriteCasesArgs() {
            final var topFile = new ConflictItem(
                Path.of("top_file.txt"),
                "top level file",
                "conflicting top level file");
            final var nestedFile = new ConflictItem(
                Path.of("other_dir/other_file.txt"),
                "another file within a directory",
                "conflicting file within a directory");
            final var topDir = new ConflictItem(
                Path.of("top_dir"),
                "["
                + "{\"name\":\"nested_dir\",\"type\":\"DIRECTORY\",\"contents\":["
                + "{\"name\":\"nested_file.txt\",\"type\":\"TEXT\"}]},"
                + "{\"name\":\"other_nested_dir\",\"type\":\"DIRECTORY\",\"contents\":[]},"
                + "{\"name\":\"sub_file.txt\",\"type\":\"TEXT\"}]",
                JsonArray.EMPTY_JSON_ARRAY.toString());
            final var nestedDir = new ConflictItem(
                Path.of("other_dir/nested_dir"),
                JsonArray.EMPTY_JSON_ARRAY.toString(),
                JsonArray.EMPTY_JSON_ARRAY.toString());

            return Stream.of(
                Arguments.arguments(named("Top Level File", List.of(topFile))),
                Arguments.arguments(named("Top Level Directory", List.of(topDir))),
                Arguments.arguments(named("Nested File", List.of(nestedFile))),
                Arguments.arguments(named("Nested Directory", List.of(nestedDir))),
                Arguments.arguments(named("Multiple Files", List.of(topFile, nestedFile))),
                Arguments.arguments(named("Multiple Directories", List.of(topDir, nestedDir))),
                Arguments.arguments(named("Mixed Files and Directories", List.of(topFile, nestedFile, nestedDir, topDir)))
            );
          }

          /**
           * With overwrite unset, a conflict is returned. This tests for both within and between workspaces
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkMoveOverwriteUnset(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var withinResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.empty(),
                Optional.empty());

            final var betweenResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.of(otherWorkspaceId),
                Optional.empty());

            // Check Status Code
            assertEquals(207, withinResp.status());
            assertEquals(207, betweenResp.status());

            // Check Details of Responses
            final var withinRespBody = getArrayBody(withinResp);
            final var betweenRespBody = getArrayBody(betweenResp);

            assertEquals(withinRespBody.size(), betweenRespBody.size());

            for (int i = 0; i < withinRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualWithin = withinRespBody.get(i).asJsonObject();
              final var actualBetween = betweenRespBody.get(i).asJsonObject();

              assertEquals(409, actualWithin.getInt("status"));
              assertEquals(409, actualBetween.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

              // Check that the original file was not moved due to the conflict,
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
            }
          }

          /**
           * With overwrite set to false, a conflict is returned. This tests for both within and between workspaces
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkMoveOverwriteFalse(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var withinResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.empty(),
                Optional.of(false));

            final var betweenResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.of(otherWorkspaceId),
                Optional.of(false));

            // Check Status Code
            assertEquals(207, withinResp.status());
            assertEquals(207, betweenResp.status());

            // Check Details of Responses
            final var withinRespBody = getArrayBody(withinResp);
            final var betweenRespBody = getArrayBody(betweenResp);

            assertEquals(withinRespBody.size(), betweenRespBody.size());

            for (int i = 0; i < withinRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualWithin = withinRespBody.get(i).asJsonObject();
              final var actualBetween = betweenRespBody.get(i).asJsonObject();

              assertEquals(409, actualWithin.getInt("status"));
              assertEquals(409, actualBetween.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

              // Check that the original file was not moved due to the conflict,
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
            }
          }

          /**
           * With overwrite set to true, no conflict occurs. This tests for moving within a workspace
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkMoveOverwriteTrueWithinWS(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var withinResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.empty(),
                Optional.of(true));

            // Check Status Code
            assertEquals(207, withinResp.status());

            // Check Details of Responses
            final var withinRespBody = getArrayBody(withinResp);


            for (int i = 0; i < withinRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualWithin = withinRespBody.get(i).asJsonObject();

              assertEquals(200, actualWithin.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());

              // Check that the original file was moved
              assertEquals(404, wsServer.get(ownerToken, workspaceId, expected.originalPath).status());
            }
          }

          /**
           * With overwrite set to true, no conflict occurs. This tests for moving between workspaces
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkMoveOverwriteTrueBetweenWS(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var betweenResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.of(otherWorkspaceId),
                Optional.of(true));

            // Check Status Code
            assertEquals(207, betweenResp.status());

            // Check Details of Responses
            final var betweenRespBody = getArrayBody(betweenResp);

            for (int i = 0; i < betweenRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualBetween = betweenRespBody.get(i).asJsonObject();

              assertEquals(200, actualBetween.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.originalContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

              // Check that the original file was moved
              assertEquals(404, wsServer.get(ownerToken, workspaceId, expected.originalPath).status());
            }
          }

          /**
           * With overwrite unset, a conflict is returned. This tests for both within and between workspaces
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkCopyOverwriteUnset(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var withinResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.empty(),
                Optional.empty());

            final var betweenResp = wsServer.bulkMove(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.of(otherWorkspaceId),
                Optional.empty());

            // Check Status Code
            assertEquals(207, withinResp.status());
            assertEquals(207, betweenResp.status());

            // Check Details of Responses
            final var withinRespBody = getArrayBody(withinResp);
            final var betweenRespBody = getArrayBody(betweenResp);

            assertEquals(withinRespBody.size(), betweenRespBody.size());

            for (int i = 0; i < withinRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualWithin = withinRespBody.get(i).asJsonObject();
              final var actualBetween = betweenRespBody.get(i).asJsonObject();

              assertEquals(409, actualWithin.getInt("status"));
              assertEquals(409, actualBetween.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

              // Check that the original file was untouched.
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
            }
          }

          /**
           * With overwrite set to false, a conflict is returned. This tests for both within and between workspaces
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkCopyOverwriteFalse(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var withinResp = wsServer.bulkCopy(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.empty(),
                Optional.of(false));

            final var betweenResp = wsServer.bulkCopy(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.of(otherWorkspaceId),
                Optional.of(false));

            // Check Status Code
            assertEquals(207, withinResp.status());
            assertEquals(207, betweenResp.status());

            // Check Details of Responses
            final var withinRespBody = getArrayBody(withinResp);
            final var betweenRespBody = getArrayBody(betweenResp);

            assertEquals(withinRespBody.size(), betweenRespBody.size());

            for (int i = 0; i < withinRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualWithin = withinRespBody.get(i).asJsonObject();
              final var actualBetween = betweenRespBody.get(i).asJsonObject();

              assertEquals(409, actualWithin.getInt("status"));
              assertEquals(409, actualBetween.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
              assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

              // Check that the original file was not touched
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
            }
          }

          /**
           * With overwrite set to true, no conflict occurs. This tests for moving within a workspace
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkCopyOverwriteTrueWithinWS(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var withinResp = wsServer.bulkCopy(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.empty(),
                Optional.of(true));

            // Check Status Code
            assertEquals(207, withinResp.status());

            // Check Details of Responses
            final var withinRespBody = getArrayBody(withinResp);


            for (int i = 0; i < withinRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualWithin = withinRespBody.get(i).asJsonObject();

              assertEquals(200, actualWithin.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());

              // Check that the original file is untouched
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
            }
          }

          /**
           * With overwrite set to true, no conflict occurs. This tests for moving between workspaces
           */
          @ParameterizedTest
          @MethodSource("overwriteCasesArgs")
          void bulkCopyOverwriteTrueBetweenWS(List<ConflictItem> inputs) {
            final var paths = inputs.stream().map(i -> i.originalPath).toList();
            final var destination = Path.of("./destination_dir");

            final var betweenResp = wsServer.bulkCopy(
                ownerToken,
                workspaceId,
                paths,
                destination,
                Optional.of(otherWorkspaceId),
                Optional.of(true));

            // Check Status Code
            assertEquals(207, betweenResp.status());

            // Check Details of Responses
            final var betweenRespBody = getArrayBody(betweenResp);

            for (int i = 0; i < betweenRespBody.size(); ++i) {
              final var expected = inputs.get(i);
              final var actualBetween = betweenRespBody.get(i).asJsonObject();

              assertEquals(200, actualBetween.getInt("status"));

              // Check file contents
              final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
              assertEquals(expected.originalContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

              // Check that the original file is untouched
              assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
            }
          }
        }

        @Nested
        class MalformedRequest {
          private static final String endpoint = "/ws/bulk/%d";

          @Test
          void noBody() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
            assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
          }

          @Test
          void nonJsonBody() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "text/plain")
                .setData("Delete some file please");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Body must be type application/json", body.getString("message"));
          }

          @Test
          void incorrectContentTypeHeader() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/octet-stream")
                .setData("{}");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Body must be type application/json", body.getString("message"));
          }

          @Test
          void emptyBody() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json")
                .setData("{}");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
            assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
          }

          @Test
          void emptyItemsArray() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json")
                .setData("{\"items\": [], \"moveTo\": \".\"}");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Cannot process request: at least one item must be specified.", body.getString("message"));
          }

          @Test
          void bothMoveAndCopy() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json")
                .setData("{\"items\": [{\"path\": \"top_file.txt\"}], \"moveTo\": \".\", \"copyTo\": \".\"}");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
            assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
          }

          /**
           * One of "copyTo" or "moveTo" must be specified
           */
          @Test
          void noPostTypeSpecified() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json")
                .setData("{\"items\": [{\"path\": \"top_file.txt\"}]");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
            assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
          }

          @Test
          void invalidPostType() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json")
                .setData("{\"items\": [{\"path\": \"top_file.txt\"}], \"move\": \".\"}");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
            assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
          }
        }
      }

      @Nested
      class BulkDelete {
        private int workspaceId;

        @BeforeEach
        void beforeEach() throws IOException {
          workspaceId = wsServer.createWorkspace("bulkDeleteWS", parcelId);

          // Prepopulate ws with contents
          final List<BulkPutItem> wsContents = List.of(
              new BulkPutItem.DirectoryBulkPutItem("top_dir"),
              new BulkPutItem.DirectoryBulkPutItem("top_dir/nested_dir"),
              new BulkPutItem.DirectoryBulkPutItem("top_dir/other_nested_dir"),
              new BulkPutItem.DirectoryBulkPutItem("other_dir"),
              new BulkPutItem.DirectoryBulkPutItem("other_dir/nested_dir"),
              new BulkPutItem.FileBulkPutItem("top_file.txt", "top level file"),
              new BulkPutItem.FileBulkPutItem("top_dir/sub_file.txt", "file within a directory"),
              new BulkPutItem.FileBulkPutItem("other_dir/other_file.txt", "another file within a directory"),
              new BulkPutItem.FileBulkPutItem("top_dir/nested_dir/nested_file.txt", "file within a nested directory")
          );

          wsServer.bulkPut(ownerToken, workspaceId, wsContents);
        }

        @AfterEach
        void afterEach() throws IOException {
          wsServer.deleteWorkspace(workspaceId);
        }

        /**
         * Basic successful cases.
         * All of these should return a top level status of 207,
         *    and an array of JSON objects with the same length as the input list.
         * Each object in the array should have a status of 200, an 'item' field with the deleted item's name,
         *    and a 'result' field that either says "directory created" or "file uploaded",
         *    as appropriate based on the created item's type.
         * Additionally, a GET request for the item should succeed after the PUT.
         */
        @ParameterizedTest
        @MethodSource("bulkDeleteBasicCasesArgs")
        void bulkDeleteBasicCases(List<Path> inputs) {
          final var resp = wsServer.bulkDelete(ownerToken, workspaceId, inputs);

          // Check status code
          assertEquals(207, resp.status());

          // Check details of response
          final var respBody = getArrayBody(resp);
          assertEquals(inputs.size(), respBody.size());

          for (int i = 0; i < respBody.size(); ++i) {
            final var expected = inputs.get(i);
            final var actual = respBody.get(i).asJsonObject();

            // Check the DELETE response
            assertEquals(200, actual.getInt("status"));
            assertEquals(expected.toString(), actual.getString("item"));


            // Simple check that the item was actually deleted -- trying to get it should return a 404 Resource Not Found
            final var getResp = wsServer.get(ownerToken, workspaceId, expected);
            assertEquals(404, getResp.status());
          }
        }

        /**
         * Generate arguments to test basic upload cases.
         */
        private static Stream<Arguments> bulkDeleteBasicCasesArgs() {
          final var topFileInput = Path.of("top_file.txt");
          final var nestedFileInput = Path.of("other_dir/other_file.txt");
          final var topDirInput = Path.of("top_dir");
          final var nestedDirInput = Path.of("other_dir/nested_dir");

          return Stream.of(
              Arguments.arguments(named("Top Level File Single Bulk DELETE", List.of(topFileInput))),
              Arguments.arguments(named("Top Level Directory Single Bulk DELETE", List.of(topDirInput))),
              Arguments.arguments(named("Nested File Single Bulk DELETE", List.of(nestedFileInput))),
              Arguments.arguments(named("Nested Directory Single Bulk DELETE", List.of(nestedDirInput))),
              Arguments.arguments(named("Multiple Files Bulk DELETE", List.of(topFileInput, nestedFileInput))),
              Arguments.arguments(named("Multiple Directories Bulk DELETE", List.of(topDirInput, nestedDirInput))),
              Arguments.arguments(named(
                  "Mixed Files and Directories Bulk DELETE",
                  List.of(topFileInput, nestedFileInput, nestedDirInput, topDirInput)))
          );
        }

        /**
         * When only one item delete fails, the overall status is 207, the successful items have a status of 200,
         * and the unsuccessful items have an appropriate error status.
         */
        @Test
        void mixedResults() {
          final var resp = wsServer.bulkDelete(
              ownerToken,
              workspaceId,
              List.of(Path.of("fake_file.seq"), Path.of("top_file.txt"), Path.of("other_dir")));

          // Check Response
          assertEquals(207, resp.status());
          final var respBody = getArrayBody(resp);
          assertEquals(3, respBody.size());

          // First item should be the nonexistant file with a 404 File Not Found
          final var fakeFile = respBody.getFirst().asJsonObject();
          assertEquals("fake_file.seq", fakeFile.getString("item"));
          assertEquals(404, fakeFile.getInt("status"));

          // Second item should be the file that exists
          final var realFile = respBody.get(1).asJsonObject();
          assertEquals("top_file.txt", realFile.getString("item"));
          assertEquals(200, realFile.getInt("status"));
          assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("top_file.txt")).status());

          // Third item should be the directory that exists
          final var otherFile = respBody.getLast().asJsonObject();
          assertEquals("other_dir", otherFile.getString("item"));
          assertEquals(200, otherFile.getInt("status"));
          assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("other_dir")).status());
        }

        @Nested
        class MalformedRequest {
          private static final String endpoint = "/ws/bulk/%d";

          @Test
          void noBody() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
            assertEquals("Invalid body format. Expected body format is an array of paths.", body.getString("message"));
          }

          @Test
          void nonJsonBody() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "text/plain")
                .setData("Delete some file please");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Body must be type application/json", body.getString("message"));
          }

          @Test
          void incorrectContentTypeHeader() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/octet-stream")
                .setData("[\"top-file.txt\"]");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Body must be type application/json", body.getString("message"));
          }

          @Test
          void emptyBodyArray() {
            final var options = RequestOptions
                .create()
                .setHeader("Authorization", "Bearer "+ownerToken)
                .setHeader("Content-type", "application/json")
                .setData("[]");

            final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
            assertEquals(400, resp.status());
            final var body = getBody(resp);
            assertEquals("MALFORMED_REQUEST", body.getString("type"));
            assertEquals("Cannot process request: at least one item must be specified.", body.getString("message"));
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
