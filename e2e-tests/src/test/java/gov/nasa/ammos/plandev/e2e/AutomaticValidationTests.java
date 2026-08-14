package gov.nasa.ammos.plandev.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.ammos.plandev.e2e.types.ActivityValidation;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import gov.nasa.ammos.plandev.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("merlin")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutomaticValidationTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  // Per-Test Data
  private int modelId;
  private int planId;

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
    // Insert the Mission Model
    try (final var gateway = new GatewayRequests(playwright)) {
      modelId = hasura.createMissionModel(
          gateway.uploadJarFile(),
          "Banananation (e2e tests)",
          "aerie_e2e_tests",
          "Automatic Validation Tests");
    }
    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - Automatic Validation Tests",
        "1212h",
        "2021-01-01T00:00:00Z");
  }

  @AfterEach
  void afterEach() throws IOException {
    // Remove Model, Plan, and Constraint
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  @Test
  void validationSuccess() throws IOException, InterruptedException {
    final var activityId = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        Json.createObjectBuilder().add("biteSize", 1).build());
    Thread.sleep(1000); // TODO consider a while loop here
    final var activityValidations = hasura.getActivityValidations(planId);
    final ActivityValidation activityValidation = activityValidations.get((long) activityId);
    assertEquals(new ActivityValidation.Success(), activityValidation);
  }

  @Test
  void noSuchActivityType() throws IOException, InterruptedException {
    final var activityId = hasura.insertActivityDirective(
        planId,
        "NopeBanana",
        "1h",
        Json.createObjectBuilder().build());
    Thread.sleep(1000); // TODO consider a while loop here
    final var activityValidations = hasura.getActivityValidations(planId);
    final ActivityValidation activityValidation = activityValidations.get((long) activityId);
    assertEquals(new ActivityValidation.NoSuchActivityTypeFailure("No such activity type NopeBanana", "NopeBanana"), activityValidation);
  }

  @Test
  void validationNotice() throws IOException, InterruptedException {
    final var activityId = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        Json.createObjectBuilder().add("biteSize", 0).build());
    Thread.sleep(1000); // TODO consider a while loop here
    final var activityValidations = hasura.getActivityValidations(planId);
    final ActivityValidation activityValidation = activityValidations.get((long) activityId);
    assertEquals(new ActivityValidation.ValidationFailure(List.of(
        new ActivityValidation.ValidationNotice(List.of("biteSize"), "bite size must be positive"))
    ), activityValidation);
  }

  @Test
  void instantiationError() throws IOException, InterruptedException {
    final var activityId = hasura.insertActivityDirective(
        planId,
        "BakeBananaBread",
        "1h",
        Json.createObjectBuilder()
            .add("dontNeed", 0)
            .add("temperature", "this is a string")
            .add("tbSugar", 1)
            .build());
    Thread.sleep(1000); // TODO consider a while loop here
    final var activityValidations = hasura.getActivityValidations(planId);
    final ActivityValidation activityValidation = activityValidations.get((long) activityId);
    assertEquals(new ActivityValidation.InstantiationFailure(
        List.of("dontNeed"),
        List.of("glutenFree"),
        List.of(new ActivityValidation.UnconstructableArgument(
            "temperature",
            "Expected real number, got StringValue[value=this is a string]"))
    ), activityValidation);
  }

  @Test
  void noSuchMissionModelError() throws IOException, InterruptedException {
    final var activityId = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT
    );
    Thread.sleep(1000); // TODO consider a while loop here

    hasura.deleteMissionModel(modelId);

    final var arguments = Json.createObjectBuilder().add("biteSize", 2).build();
    hasura.updateActivityDirectiveArguments(planId, activityId, arguments);
    Thread.sleep(1000); // TODO consider a while loop here

    final var activityValidations = hasura.getActivityValidations(planId);
    final ActivityValidation activityValidation = activityValidations.get((long) activityId);
    assertEquals(
        new ActivityValidation.NoSuchMissionModelFailure("No mission model exists with id `0`", 0),
        activityValidation
    );
  }

  @Test
  void exceptionDuringValidationHandled() throws IOException, InterruptedException {
    final var exceptionActivityId = hasura.insertActivityDirective(
        planId,
        "ExceptionActivity",
        "1h",
        Json.createObjectBuilder().add("throwException", true).build());

    // sleep to make sure exception activity is picked up
    Thread.sleep(1000); // TODO consider a while loop here

    final var biteActivityId = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        Json.createObjectBuilder().add("biteSize", 1).build());

    Thread.sleep(1000); // TODO consider a while loop here

    final var activityValidations = hasura.getActivityValidations(planId);

    final ActivityValidation exceptionValidations = activityValidations.get((long) exceptionActivityId);
    final ActivityValidation biteValidations = activityValidations.get((long) biteActivityId);

    // then make sure the exception was caught and serialized, and didn't crash the worker thread
    assertEquals(new ActivityValidation.ValidationFailure(List.of(
        new ActivityValidation.ValidationNotice(List.of("throwException"), "Throwing runtime exception during validation"))),
                 exceptionValidations);
    // if the above is true, bite banana will have its validation still
    assertEquals(new ActivityValidation.Success(), biteValidations);
  }
}
