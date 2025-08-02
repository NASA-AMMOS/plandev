package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.types.Plan;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExternalEventsSchedulingTests extends ProceduralSchedulingSetup {
  private GoalInvocationId procedureId;
  private final static String SOURCE_TYPE = "TestType";
  private final static String EVENT_TYPE = "TestType";
  private final static String ADDITIONAL_EVENT_TYPE = EVENT_TYPE + "_2";
  private final static String SOURCE_KEY = "Test.json";
  private final static String ADDITIONAL_SOURCE_KEY = "NewTest.json";

  private final static String DERIVATION_GROUP = "TestGroup";
  private final static String ADDITIONAL_DERIVATION_GROUP = DERIVATION_GROUP + "_2";

  void uploadExternalSourceEventTypes() throws IOException {
    final String event_types = """
        {
          "%s": {
            "type": "object",
            "properties": {
              "projectUser": {
                "type": "string"
              },
              "code": {
                "type": "string"
              },
              "optional": {
                "type": "string"
              }
            },
            "required": ["projectUser", "code"]
          },
          "%s": {
            "type": "object",
            "properties": {
              "projectUser": {
                  "type": "string"
              },
              "code": {
                  "type": "string"
              },
              "optional": {
                "type": "string"
              }
            },
            "required": ["projectUser", "code"]
          }
        }
        """.formatted(EVENT_TYPE, ADDITIONAL_EVENT_TYPE);

    final String source_types = """
        {
          "%s": {
            "type": "object",
            "properties": {
              "version": {
                  "type": "number"
              },
              "optional": {
                "type": "string"
              }
          },
          "required": ["version"]
          }
        }
        """.formatted(SOURCE_TYPE);

    final JsonObject schema = Json.createObjectBuilder()
                                  .add("event_types", event_types)
                                  .add("source_types", source_types)
                                  .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSourceEventTypes(schema);
    }
  }


  void uploadExternalSources() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource("scheduling_source_A.json", DERIVATION_GROUP);
      gateway.uploadExternalSource("scheduling_source_B.json", ADDITIONAL_DERIVATION_GROUP);
    }
  }

  @BeforeEach
  void localBeforeEach() throws IOException {
    // Upload some External Events
    uploadExternalSourceEventTypes();
    uploadExternalSources();
    hasura.insertPlanDerivationGroupAssociation(planId, DERIVATION_GROUP);
    hasura.insertPlanDerivationGroupAssociation(planId, ADDITIONAL_DERIVATION_GROUP);
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteSchedulingGoal(procedureId.goalId());

    // External Event Related
    hasura.deletePlanDerivationGroupAssociation(planId, DERIVATION_GROUP);
    hasura.deletePlanDerivationGroupAssociation(planId, ADDITIONAL_DERIVATION_GROUP);
    hasura.deleteExternalSource(SOURCE_KEY, DERIVATION_GROUP);
    hasura.deleteExternalSource(ADDITIONAL_SOURCE_KEY, ADDITIONAL_DERIVATION_GROUP);
    hasura.deleteDerivationGroup(DERIVATION_GROUP);
    hasura.deleteDerivationGroup(ADDITIONAL_DERIVATION_GROUP);
    hasura.deleteExternalSourceType(SOURCE_TYPE);
    hasura.deleteExternalEventType(EVENT_TYPE);
    hasura.deleteExternalEventType(ADDITIONAL_EVENT_TYPE);
  }

  @Test
  void testExternalEventSimple() throws IOException {
    // first, run the goal
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalEventsSimpleGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // ensure the order lines up with the events'
    activities.sort(Comparator.comparing(Plan.ActivityDirective::startOffset));

    // all events' start times from the first source
    List<String> expected = List.of(
      "2023-01-01T01:00:00Z",
      "2023-01-01T03:00:00Z",
      "2023-01-01T05:00:00Z"
    );

    // compare arrays
    assertEquals(expected.size(), activities.size());
    for (int i = 0; i < activities.size(); i++) {
      Instant activityStartTime = Duration.addToInstant(
          Instant.parse(planStartTimestamp),
          Duration.fromString(activities.get(i).startOffset())
      );
      assertEquals(expected.get(i), activityStartTime.toString());
    }
  }

  @Test
  void testExternalEventTypeQuery() throws IOException {
    // first, run the goal
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalEventsTypeQueryGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // ensure the orderings line up
    activities.sort(Comparator.comparing(Plan.ActivityDirective::startOffset));

    // get the set of start times we expect (anything in TestGroup or TestGroup_2, and of type TestType)
    List<String> expected = List.of(
      "2023-01-01T01:00:00Z",
      "2023-01-01T03:00:00Z",
      "2023-01-01T05:00:00Z",
      "2023-01-02T01:00:00Z"
    );

    // compare arrays
    assertEquals(expected.size(), activities.size());
    for (int i = 0; i < activities.size(); i++) {
      Instant activityStartTime = Duration.addToInstant(
          Instant.parse(planStartTimestamp),
          Duration.fromString(activities.get(i).startOffset())
      );
      assertEquals(activityStartTime.toString(), expected.get(i));
    }
  }

  @Test
  void testExternalEventSourceQuery() throws IOException {
    // first, run the goal
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalEventsSourceQueryGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // only 1 activity this time
    assertEquals(1, activities.size());
    Instant activityStartTime = Duration.addToInstant(
        Instant.parse(planStartTimestamp),
        Duration.fromString(activities.getFirst().startOffset())
    );
    assertEquals(activityStartTime.toString(), "2023-01-02T01:00:00Z");
  }

  @Test
  void testExternalSourceAttribute() throws IOException {
    // first, run the goal
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalEventsSourceAttributeQueryGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // ensure the orderings line up
    activities.sort(Comparator.comparing(Plan.ActivityDirective::startOffset));

    // get the set of start times we expect (anything in NewTest.json)
    List<String> expected = List.of(
      "2023-01-02T01:00:00Z",
      "2023-01-02T03:00:00Z",
      "2023-01-02T05:00:00Z"
    );

    // compare arrays
    assertEquals(expected.size(), activities.size());
    for (int i = 0; i < activities.size(); i++) {
      Instant activityStartTime = Duration.addToInstant(
          Instant.parse(planStartTimestamp),
          Duration.fromString(activities.get(i).startOffset())
      );
      assertEquals(activityStartTime.toString(), expected.get(i));
    }
  }

  @Test
  void testExternalEventAttribute() throws IOException {
    // first, run the goal
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalEventsEventAttributeQueryGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // ensure the orderings line up
    activities.sort(Comparator.comparing(Plan.ActivityDirective::startOffset));

    // get the set of start times we expect (select events with projectUser = UserA)
    List<String> expected = List.of(
      "2023-01-01T01:00:00Z",
      "2023-01-01T03:00:00Z",
      "2023-01-02T05:00:00Z"
    );

    // compare arrays
    assertEquals(expected.size(), activities.size());
    for (int i = 0; i < activities.size(); i++) {
      Instant activityStartTime = Duration.addToInstant(
          Instant.parse(planStartTimestamp),
          Duration.fromString(activities.get(i).startOffset())
      );
      assertEquals(activityStartTime.toString(), expected.get(i));
    }
  }

  // test based on source optional attribute
  @Test
  void testOptionalSourceAttribute() throws IOException {
    // first, run the goal
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalEventsSourceAttributeOptionalQueryGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // ensure the orderings line up
    activities.sort(Comparator.comparing(Plan.ActivityDirective::startOffset));

    // get the set of events we expect (select events with projectUser = UserA)
    List<String> expected = List.of(
      "2023-01-02T01:00:00Z",
      "2023-01-02T03:00:00Z",
      "2023-01-02T05:00:00Z"
    );

    // compare arrays
    assertEquals(expected.size(), activities.size());
    for (int i = 0; i < activities.size(); i++) {
      Instant activityStartTime = Duration.addToInstant(
          Instant.parse(planStartTimestamp),
          Duration.fromString(activities.get(i).startOffset())
      );
      assertEquals(activityStartTime.toString(), expected.get(i));
    }
  }

  @Test
  void testOptionalEventAttribute() throws IOException {
    // first, run the goal
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalEventsEventAttributeOptionalQueryGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // ensure the orderings line up
    activities.sort(Comparator.comparing(Plan.ActivityDirective::startOffset));

    // get the set of events we expect (select events with projectUser = UserA)
    List<String> expected = List.of(
      "2023-01-01T03:00:00Z",
      "2023-01-01T05:00:00Z",
      "2023-01-02T01:00:00Z"
    );


    // compare arrays
    assertEquals(expected.size(), activities.size());
    for (int i = 0; i < activities.size(); i++) {
      Instant activityStartTime = Duration.addToInstant(
          Instant.parse(planStartTimestamp),
          Duration.fromString(activities.get(i).startOffset())
      );
      assertEquals(activityStartTime.toString(), expected.get(i));
    }
  }
}
