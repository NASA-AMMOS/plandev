package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.time.Instant;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraUploadSimulationDatasetActionP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class UploadSimulationDatasetParserTest {

  @Test
  public void testParseValidUploadSimulationDatasetAction() {
    // GIVEN
    final var json = Json
        .createObjectBuilder()
        .add("action", Json
            .createObjectBuilder()
            .add("name", "uploadSimulationDataset")
            .build())
        .add("input", Json
            .createObjectBuilder()
            .add("planId", 123)
            .add("simulationStart", "2024-001T00:00:00.000")
            .add("simulationEnd", "2024-002T00:00:00.000")
            .add("arguments", Json
                .createObjectBuilder()
                .add("param1", "value1")
                .add("param2", 42)
                .build())
            .add("profileSet", Json.createObjectBuilder().build())
            .build())
        .add("session_variables", Json
            .createObjectBuilder()
            .add("x-hasura-role", "aerie_admin")
            .add("x-hasura-user-id", "test-user")
            .build())
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    // WHEN
    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    // THEN
    
    assertEquals("uploadSimulationDataset", action.name());
    assertEquals(new PlanId(123L), action.input().planId());
    assertEquals(new Timestamp(Instant.parse("2024-01-01T00:00:00.000Z")), action.input().simulationStart());
    assertEquals(new Timestamp(Instant.parse("2024-01-02T00:00:00.000Z")), action.input().simulationEnd());
    assertEquals("aerie_admin", action.session().hasuraRole());
    assertEquals("test-user", action.session().hasuraUserId());
    
    // Verify arguments
    final var arguments = action.input().arguments();
    assertEquals(2, arguments.size());
    assertTrue(arguments.containsKey("param1"));
    assertTrue(arguments.containsKey("param2"));
    
    // Verify profileSet
    final var profileSet = action.input().profileSet();
    assertEquals(0, profileSet.realProfiles().size());
    assertEquals(0, profileSet.discreteProfiles().size());
    
    // Verify MVP: activities, topics, events are empty
    assertTrue(action.input().activities().isEmpty(), "Activities should be empty in MVP");
    assertTrue(action.input().topics().isEmpty(), "Topics should be empty in MVP");
    assertTrue(action.input().events().isEmpty(), "Events should be empty in MVP");
  }

  @Test
  public void testParseWithProfiles() {
    // GIVEN
    final var json = Json
        .createObjectBuilder()
        .add("action", Json
            .createObjectBuilder()
            .add("name", "uploadSimulationDataset")
            .build())
        .add("input", Json
            .createObjectBuilder()
            .add("planId", 456)
            .add("simulationStart", "2024-001T00:00:00.000")
            .add("simulationEnd", "2024-001T01:00:00.000")
            .add("arguments", Json.createObjectBuilder().build())
            .add("profileSet", Json
                .createObjectBuilder()
                .add("/battery", Json
                    .createObjectBuilder()
                    .add("type", "real")
                    .add("schema", Json
                        .createObjectBuilder()
                        .add("type", "real")
                        .build())
                    .add("segments", Json.createArrayBuilder().build())
                    .build())
                .build())
            .build())
        .add("session_variables", Json
            .createObjectBuilder()
            .add("x-hasura-role", "user")
            .build())
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    // WHEN
    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    // THEN
    
    assertEquals(new PlanId(456L), action.input().planId());
    final var profileSet = action.input().profileSet();
    assertEquals(1, profileSet.realProfiles().size(), "Should have one real profile");
    assertTrue(profileSet.realProfiles().containsKey("/battery"));
  }

  @Test
  public void testParseMissingRequiredField() {
    // GIVEN - missing simulationEnd
    final var json = Json
        .createObjectBuilder()
        .add("action", Json
            .createObjectBuilder()
            .add("name", "uploadSimulationDataset")
            .build())
        .add("input", Json
            .createObjectBuilder()
            .add("planId", 123)
            .add("simulationStart", "2024-001T00:00:00.000")
            // Missing simulationEnd
            .add("arguments", Json.createObjectBuilder().build())
            .add("profileSet", Json.createObjectBuilder().build())
            .build())
        .add("session_variables", Json
            .createObjectBuilder()
            .add("x-hasura-role", "aerie_admin")
            .build())
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    // WHEN
    final var result = hasuraUploadSimulationDatasetActionP.parse(json);

    // THEN
    assertTrue(result.isFailure(), "Parser should fail when required field is missing");
  }

  @Test
  public void testParseInvalidTimestamp() {
    // GIVEN - invalid timestamp format
    final var json = Json
        .createObjectBuilder()
        .add("action", Json
            .createObjectBuilder()
            .add("name", "uploadSimulationDataset")
            .build())
        .add("input", Json
            .createObjectBuilder()
            .add("planId", 123)
            .add("simulationStart", "not-a-timestamp")
            .add("simulationEnd", "2024-001T00:00:00.000")
            .add("arguments", Json.createObjectBuilder().build())
            .add("profileSet", Json.createObjectBuilder().build())
            .build())
        .add("session_variables", Json
            .createObjectBuilder()
            .add("x-hasura-role", "aerie_admin")
            .build())
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    // WHEN
    final var result = hasuraUploadSimulationDatasetActionP.parse(json);

    // THEN
    assertTrue(result.isFailure(), "Parser should fail with invalid timestamp");
  }

  @Test
  public void testParseEmptyArguments() {
    // GIVEN - empty arguments map
    final var json = Json
        .createObjectBuilder()
        .add("action", Json
            .createObjectBuilder()
            .add("name", "uploadSimulationDataset")
            .build())
        .add("input", Json
            .createObjectBuilder()
            .add("planId", 789)
            .add("simulationStart", "2024-001T00:00:00.000")
            .add("simulationEnd", "2024-001T12:00:00.000")
            .add("arguments", Json.createObjectBuilder().build())
            .add("profileSet", Json.createObjectBuilder().build())
            .build())
        .add("session_variables", Json
            .createObjectBuilder()
            .add("x-hasura-role", "user")
            .build())
        .add("request_query", "mutation { uploadSimulationDataset }")
        .build();

    // WHEN
    final var action = hasuraUploadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    // THEN
    assertTrue(action.input().arguments().isEmpty(), "Arguments should be empty");
  }
}
