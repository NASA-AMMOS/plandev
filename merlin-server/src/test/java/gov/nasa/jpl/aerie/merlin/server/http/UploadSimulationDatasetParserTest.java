package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.time.Instant;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraUploadSimulationDatasetActionP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class UploadSimulationDatasetParserTest {

  private static javax.json.JsonObject minimalSimulationResults(String start, String end) {
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

  @Test
  public void testParseValidUploadSimulationDatasetAction() {
    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 123)
            .add("simulationResults", minimalSimulationResults("2024-001T00:00:00.000", "2024-002T00:00:00.000")))
        .add("session_variables", Json.createObjectBuilder()
            .add("x-hasura-role", "aerie_admin")
            .add("x-hasura-user-id", "test-user"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertEquals("uploadSimulationDataset", action.name());
    assertEquals(new PlanId(123L), action.input().planId());
    assertEquals("aerie_admin", action.session().hasuraRole());
    assertEquals("test-user", action.session().hasuraUserId());

    final var results = action.input().simulationResults();
    assertEquals(Instant.parse("2024-01-01T00:00:00.000Z"), results.startTime);
    assertTrue(results.realProfiles.isEmpty());
    assertTrue(results.discreteProfiles.isEmpty());
    assertTrue(results.simulatedActivities.isEmpty());
    assertTrue(results.unfinishedActivities.isEmpty());
    assertTrue(results.topics.isEmpty());
    assertTrue(results.events.isEmpty());
  }

  @Test
  public void testParseWithRealProfile() {
    final var simResults = Json.createObjectBuilder()
        .add("simulationStartTime", "2024-001T00:00:00.000")
        .add("simulationEndTime", "2024-001T01:00:00.000")
        .add("profiles", Json.createObjectBuilder()
            .add("realProfiles", Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                    .add("name", "/battery")
                    .add("schema", Json.createObjectBuilder().add("type", "real"))
                    .add("segments", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                            .add("extent", "01:00:00")
                            .add("dynamics", Json.createObjectBuilder()
                                .add("initial", 100.0)
                                .add("rate", -0.5))))))
            .add("discreteProfiles", Json.createArrayBuilder()))
        .add("spans", Json.createObjectBuilder()
            .add("simulatedActivities", Json.createArrayBuilder())
            .add("unfinishedActivities", Json.createArrayBuilder()))
        .build();

    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 456)
            .add("simulationResults", simResults))
        .add("session_variables", Json.createObjectBuilder().add("x-hasura-role", "user"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertEquals(new PlanId(456L), action.input().planId());
    final var results = action.input().simulationResults();
    assertEquals(1, results.realProfiles.size());
    assertTrue(results.realProfiles.containsKey("/battery"));
  }

  @Test
  public void testParseMissingSimulationResults() {
    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 123))
        .add("session_variables", Json.createObjectBuilder().add("x-hasura-role", "aerie_admin"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var result = hasuraUploadSimulationDatasetActionP.parse(json);

    assertTrue(result.isFailure(), "Parser should fail when simulationResults is missing");
  }

  @Test
  public void testParseInvalidTimestamp() {
    final var simResults = Json.createObjectBuilder()
        .add("simulationStartTime", "not-a-timestamp")
        .add("simulationEndTime", "2024-001T00:00:00.000")
        .add("profiles", Json.createObjectBuilder()
            .add("realProfiles", Json.createArrayBuilder())
            .add("discreteProfiles", Json.createArrayBuilder()))
        .add("spans", Json.createObjectBuilder()
            .add("simulatedActivities", Json.createArrayBuilder())
            .add("unfinishedActivities", Json.createArrayBuilder()))
        .build();

    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 123)
            .add("simulationResults", simResults))
        .add("session_variables", Json.createObjectBuilder().add("x-hasura-role", "aerie_admin"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var result = hasuraUploadSimulationDatasetActionP.parse(json);

    assertTrue(result.isFailure(), "Parser should fail with invalid timestamp");
  }

  @Test
  public void testParseWithActivities() {
    final var simResults = Json.createObjectBuilder()
        .add("simulationStartTime", "2024-001T00:00:00.000")
        .add("simulationEndTime", "2024-002T00:00:00.000")
        .add("profiles", Json.createObjectBuilder()
            .add("realProfiles", Json.createArrayBuilder())
            .add("discreteProfiles", Json.createArrayBuilder()))
        .add("spans", Json.createObjectBuilder()
            .add("simulatedActivities", Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                    .add("id", 1)
                    .add("directiveId", 42)
                    .addNull("parentId")
                    .add("childIds", Json.createArrayBuilder())
                    .add("type", "MyActivity")
                    .add("duration", "00:30:00")
                    .add("attributes", Json.createObjectBuilder().add("type", "string").add("value", "done"))
                    .add("arguments", Json.createObjectBuilder())
                    .add("startTime", "2024-001T01:00:00.000")))
            .add("unfinishedActivities", Json.createArrayBuilder()))
        .build();

    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 789)
            .add("simulationResults", simResults))
        .add("session_variables", Json.createObjectBuilder().add("x-hasura-role", "user"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertEquals(new PlanId(789L), action.input().planId());
    assertEquals(1, action.input().simulationResults().simulatedActivities.size());
  }

  @Test
  public void testParseWithSimulationArguments() {
    final var simResults = Json.createObjectBuilder()
        .add("simulationStartTime", "2024-001T00:00:00.000")
        .add("simulationEndTime", "2024-002T00:00:00.000")
        .add("profiles", Json.createObjectBuilder()
            .add("realProfiles", Json.createArrayBuilder())
            .add("discreteProfiles", Json.createArrayBuilder()))
        .add("spans", Json.createObjectBuilder()
            .add("simulatedActivities", Json.createArrayBuilder())
            .add("unfinishedActivities", Json.createArrayBuilder()))
        .add("simulationArguments", Json.createObjectBuilder()
            .add("batteryCapacity", Json.createObjectBuilder().add("type", "real").add("value", 30.0)))
        .build();

    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 999)
            .add("simulationResults", simResults))
        .add("session_variables", Json.createObjectBuilder().add("x-hasura-role", "user"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertEquals(new PlanId(999L), action.input().planId());
    final var results = action.input().simulationResults();
    assertEquals(1, results.simulationArguments.size());
    assertTrue(results.simulationArguments.containsKey("batteryCapacity"));
  }

  @Test
  public void testParseWithTopicsAndEvents() {
    final var simResults = Json.createObjectBuilder()
        .add("simulationStartTime", "2024-001T00:00:00.000")
        .add("simulationEndTime", "2024-002T00:00:00.000")
        .add("profiles", Json.createObjectBuilder()
            .add("realProfiles", Json.createArrayBuilder())
            .add("discreteProfiles", Json.createArrayBuilder()))
        .add("spans", Json.createObjectBuilder()
            .add("simulatedActivities", Json.createArrayBuilder())
            .add("unfinishedActivities", Json.createArrayBuilder()))
        .add("topics", Json.createObjectBuilder()
            .add("MyTopic", Json.createObjectBuilder()
                .add("schema", Json.createObjectBuilder().add("type", "string"))))
        .add("events", Json.createArrayBuilder()
            .add(Json.createObjectBuilder()
                .add("causalTime", ".1")
                .add("realTime", "2024-001T00:00:01.000")
                .add("transactionIndex", 0)
                .add("value", Json.createObjectBuilder().add("type", "string").add("value", "hello"))
                .add("topic", "MyTopic")
                .addNull("spanId")))
        .build();

    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 42)
            .add("simulationResults", simResults))
        .add("session_variables", Json.createObjectBuilder()
            .add("x-hasura-role", "aerie_admin")
            .add("x-hasura-user-id", "test-user"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();
    final var results = action.input().simulationResults();

    assertFalse(results.topics.isEmpty(), "topics should be populated");
    assertFalse(results.events.isEmpty(), "events should be populated");
    assertEquals(1, results.topics.size());
    assertEquals("MyTopic", results.topics.get(0).getMiddle());
  }

  @Test
  public void testParseWithoutTopicsAndEventsDefaultsToEmpty() {
    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 123)
            .add("simulationResults", minimalSimulationResults("2024-001T00:00:00.000", "2024-002T00:00:00.000")))
        .add("session_variables", Json.createObjectBuilder()
            .add("x-hasura-role", "aerie_admin")
            .add("x-hasura-user-id", "test-user"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();
    final var results = action.input().simulationResults();

    assertTrue(results.topics.isEmpty(), "topics should default to empty list");
    assertTrue(results.events.isEmpty(), "events should default to empty map");
  }

  @Test
  public void testParseWithoutSimulationArgumentsDefaultsToEmpty() {
    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "uploadSimulationDataset"))
        .add("input", Json.createObjectBuilder()
            .add("planId", 123)
            .add("simulationResults", minimalSimulationResults("2024-001T00:00:00.000", "2024-002T00:00:00.000")))
        .add("session_variables", Json.createObjectBuilder()
            .add("x-hasura-role", "aerie_admin")
            .add("x-hasura-user-id", "test-user"))
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertTrue(action.input().simulationResults().simulationArguments.isEmpty(),
        "simulationArguments should default to empty map when not provided");
  }
}
