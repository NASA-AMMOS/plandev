package gov.nasa.jpl.aerie.e2e.procedural.constraints;

import gov.nasa.jpl.aerie.e2e.procedural.ProceduralSetup;
import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.types.Plan;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExternalEventsTests extends ProceduralSetup {

  // External Source Variables
  private final static String SOURCE_TYPE = "TestType";
  private final static String EVENT_TYPE = "TestType";
  private final static String ADDITIONAL_EVENT_TYPE = EVENT_TYPE + "_2";
  private final static String SOURCE_KEY = "Test.json";
  private final static String ADDITIONAL_SOURCE_KEY = "NewTest.json";
  private final static String DERIVATION_GROUP = "TestGroup";
  private final static String ADDITIONAL_DERIVATION_GROUP = DERIVATION_GROUP + "_2";

  // Constraint Variables
  private int constraintId;
  private int invocationId;
  private int simulationDatasetId;

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
    final String eventsA = """
        [
          {
            "attributes": {
              "projectUser": "UserA",
              "code": "A"
            },
            "duration": "01:00:00",
            "event_type_name": "%s",
            "key": "Event_01",
            "start_time": "2023-01-01T01:00:00+00:00"
          },
          {
            "attributes": {
              "projectUser": "UserA",
              "code": "A",
              "optional": "present"
            },
            "duration": "01:00:00",
            "event_type_name": "%s",
            "key": "Event_02",
            "start_time": "2023-01-01T03:00:00+00:00"
          },
          {
            "attributes": {
              "projectUser": "UserB",
              "code": "B",
              "optional": "present"
            },
            "duration": "01:00:00",
            "event_type_name": "%s",
            "key": "Event_03",
            "start_time": "2023-01-01T05:00:00+00:00"
          }
        ]
        """.formatted(EVENT_TYPE, EVENT_TYPE, EVENT_TYPE);


    final String eventsB = """
        [
          {
            "attributes": {
              "projectUser": "UserB",
              "code": "B",
              "optional": "present"
            },
            "duration": "01:00:00",
            "event_type_name": "%s",
            "key": "Event_01",
            "start_time": "2023-01-02T01:00:00+00:00"
          },
          {
            "attributes": {
              "projectUser": "UserB",
              "code": "B"
            },
            "duration": "01:00:00",
            "event_type_name": "%s",
            "key": "Event_02",
            "start_time": "2023-01-02T03:00:00+00:00"
          },
          {
            "attributes": {
              "projectUser": "UserA",
              "code": "A"
            },
            "duration": "01:00:00",
            "event_type_name": "%s",
            "key": "Event_03",
            "start_time": "2023-01-02T05:00:00+00:00"
          }
        ]
        """.formatted(EVENT_TYPE, ADDITIONAL_EVENT_TYPE, ADDITIONAL_EVENT_TYPE);

    final String sourceA = """
        {
          "attributes": { "version": 1 },
          "derivation_group_name": "%s",
          "period": {
            "start_time": "2023-01-01T00:00:00+00:00",
            "end_time": "2023-01-08T00:00:00+00:00"
          },
          "key": "%s",
          "source_type_name": "%s",
          "valid_at": "2024-01-01T00:00:00+00:00"
        }
        """.formatted(DERIVATION_GROUP, SOURCE_KEY, SOURCE_TYPE);

    final String sourceB = """
        {
          "attributes": { "version": 2, "optional": "present" },
          "derivation_group_name": "%s",
          "period": {
            "start_time": "2023-01-01T00:00:00+00:00",
            "end_time": "2023-01-08T00:00:00+00:00"
          },
          "key": "%s",
          "source_type_name": "%s",
          "valid_at": "2024-01-01T00:00:00+00:00"
        }
        """.formatted(ADDITIONAL_DERIVATION_GROUP, ADDITIONAL_SOURCE_KEY, SOURCE_TYPE);

    final JsonObject externalSourceA = Json.createObjectBuilder()
            .add("source", sourceA)
            .add("events", eventsA)
            .build();

    final JsonObject externalSourceB = Json.createObjectBuilder()
            .add("source", sourceB)
            .add("events", eventsB)
            .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource(externalSourceA);
      gateway.uploadExternalSource(externalSourceB);
    }
  }

  void uploadConstraint(String constraintName) throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      // Upload JAR file
      int procedureJarId = gateway.uploadJarFile("build/libs/" + constraintName + ".jar");

      // Create Constraint Procedure
      constraintId = hasura.createConstraintProcedure(constraintName, procedureJarId);

      // Link it to plan's constraint spec
      invocationId = hasura.createConstraintProcedureSpec(planId, constraintId);

      // Get Simulation Id
      simulationDatasetId = hasura.awaitSimulation(planId).simDatasetId();
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

  // verify that we can access external events. Do so with a simple constraint that just checks the count of events
  @Test
  void testExternalEventCount() throws IOException {
    // upload the jar
    uploadConstraint("EventCounter");

    // set parameters
    final var args = Json.createObjectBuilder().add("quantity", 4).build();
    hasura.updateConstraintSpecArguments(invocationId, args);

    // run constraint check
    final var resp = hasura.checkConstraints(planId, simulationDatasetId).constraintsRun();
    assertEquals(1, resp.size());

    // it should pass
    final var constraintResponse = resp.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(constraintId, constraintResponse.constraintId());
    assertEquals("EventCounter", constraintResponse.constraintName());
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();

    // Violations
    assertEquals(0, constraintResult.violations().size());

    // Gaps (NOTE: not sure how to fill these up)
    assertTrue(constraintResult.gaps().isEmpty());

    // delete constraint!
    hasura.deleteConstraint(constraintId);
  }

  @Test
  void testExternalEventAttributes() throws IOException {
    // upload the jar
    uploadConstraint("EventsAndAttributes");

    // set parameters
    final var args = Json.createObjectBuilder()
                         .add("eventAttributeCount", 3)
                         .add("sourceAttributeCount", 3)
                         .build();
    hasura.updateConstraintSpecArguments(invocationId, args);

    // check constraints
    final var resp = hasura.checkConstraints(planId, simulationDatasetId).constraintsRun();
    assertEquals(1, resp.size());

    // it should pass
    final var constraintResponse = resp.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(constraintId, constraintResponse.constraintId());
    assertEquals("EventsAndAttributes", constraintResponse.constraintName());
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();

    // Violations
    assertEquals(0, constraintResult.violations().size());

    // Gaps (NOTE: not sure how to fill these up)
    assertTrue(constraintResult.gaps().isEmpty());

    // delete constraint!
    hasura.deleteConstraint(constraintId);
  }
}
