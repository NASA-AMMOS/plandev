package gov.nasa.ammos.plandev.e2e.routes;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.ammos.plandev.e2e.utils.BaseURL;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import gov.nasa.ammos.plandev.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;

import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_admin;
import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_nonOwner;
import static gov.nasa.ammos.plandev.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("routes")
@Tag("scheduling")
public class SchedulerRoutesTests {
  // Requests
  private static Playwright playwright;
  private static APIRequestContext request;
  private static HasuraRequests hasura;

  // Cross-Test Data
  private int modelId;
  private int planId;
  private int schedulingSpecId;

  @BeforeAll
  static void beforeAll() {
    playwright = Playwright.create();
    // Set all rqs to go to the Scheduler Server
    request = playwright.request().newContext(
        new APIRequest.NewContextOptions()
            .setBaseURL(BaseURL.SCHEDULER_SERVER.url));
    hasura = new HasuraRequests(playwright);
  }

  @AfterAll
  static void afterAll() {
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
          "plandev_e2e_tests",
          "Scheduler Bindings");
    }

    // Insert the Plan
    final String plan_start_timestamp = "2023-01-01T00:00:00+00:00";
    final String duration = "24:00:00";

    planId = hasura.createPlan(
        modelId,
        "Test Plan - Scheduler Bindings",
        duration,
        plan_start_timestamp,
        routes_admin.session());
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
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "scheduler"))
                              .add("input", Json.createObjectBuilder().add("specificationId", -1))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/schedule", RequestOptions.create().setData(data));
      assertEquals(404, response.status());

      // Check the response
      final var body = getBody(response);
      final var extensions = body.getJsonObject("extensions");

      // Check the message field
      final var expectedMessage = "Could not check permissions on scheduling specification -1: specification does not exist.";
      assertEquals(expectedMessage, body.getString("message"));

      // Check the extensions object
      assertEquals("NO_SUCH_SCHEDULING_SPECIFICATION", extensions.getString("type"));
      assertEquals(expectedMessage, extensions.getString("message"));
      assertEquals("permissions_service", extensions.getString("service"));
    }
    @Test
    void forbidden(){
      // Returns a 403 if the user isn't allowed to run scheduling on the plan
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "scheduler"))
                              .add("input", Json.createObjectBuilder().add("specificationId", schedulingSpecId))
                              .add("request_query", "")
                              .add("session_variables", routes_nonOwner.getSession())
                              .build()
                              .toString();
      final var response = request.post("/schedule", RequestOptions.create().setData(data));
      assertEquals(403, response.status());
      assertEquals("User '" + routes_nonOwner.name() + "' with role 'user' cannot perform 'schedule' because they are not "
                   + "a 'PLAN_OWNER_COLLABORATOR' for plan with id '" + planId + "'",
                   getBody(response).getString("message"));
    }
    @Test
    void valid() throws InterruptedException{
      // Returns a 200 if the ID is valid
      final String data = Json.createObjectBuilder()
                              .add("action", Json.createObjectBuilder().add("name", "scheduler"))
                              .add("input", Json.createObjectBuilder().add("specificationId", schedulingSpecId))
                              .add("request_query", "")
                              .add("session_variables", routes_admin.getSession())
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
                              .add("session_variables", routes_admin.getSession())
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
                              .add("session_variables", routes_admin.getSession())
                              .build()
                              .toString();
      final var response = request.post("/schedulingDslTypescript", RequestOptions.create().setData(data));
      assertEquals(200, response.status());
      final var expectedBody = Json.createObjectBuilder()
                                   .add("status", "failure")
                                   .add("reason", "No plan exists with id `-1`")
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
                              .add("session_variables", routes_admin.getSession())
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
                              .add("session_variables", routes_admin.getSession())
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
