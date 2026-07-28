package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;

import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraDownloadSimulationDatasetActionP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DownloadSimulationDatasetParserTest {

  private static JsonObject action(final JsonObject input, final JsonObject session) {
    return Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "downloadSimulationDataset"))
        .add("input", input)
        .add("session_variables", session)
        .add("request_query", "query { downloadSimulationDataset }")
        .build();
  }

  private static JsonObject input(final long planId, final long simulationDatasetId) {
    return Json.createObjectBuilder()
        .add("planId", planId)
        .add("simulationDatasetId", simulationDatasetId)
        .build();
  }

  private static JsonObject adminSession() {
    return Json.createObjectBuilder()
        .add("x-hasura-role", "aerie_admin")
        .add("x-hasura-user-id", "test-user")
        .build();
  }

  @Test
  public void testParseValidDownloadSimulationDatasetAction() {
    final var json = action(input(123, 456), adminSession());

    final var parsedAction = hasuraDownloadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertEquals("downloadSimulationDataset", parsedAction.name());
    assertEquals(new PlanId(123L), parsedAction.input().planId());
    assertEquals(456L, parsedAction.input().simulationDatasetId());
    assertEquals("aerie_admin", parsedAction.session().hasuraRole());
    assertEquals("test-user", parsedAction.session().hasuraUserId());
  }

  @Test
  public void testParseWithoutUserId() {
    // The user id is optional in the Hasura session; downloading only requires a role.
    final var json = action(
        input(1, 2),
        Json.createObjectBuilder().add("x-hasura-role", "viewer").build());

    final var parsedAction = hasuraDownloadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertEquals("viewer", parsedAction.session().hasuraRole());
    assertNull(parsedAction.session().hasuraUserId());
  }

  @Test
  public void testParseSimulationDatasetIdBeyondIntRange() {
    // simulationDatasetId is parsed as a long, so ids past Integer.MAX_VALUE must survive intact
    final var largeId = ((long) Integer.MAX_VALUE) + 1L;
    final var json = action(input(123, largeId), adminSession());

    final var parsedAction = hasuraDownloadSimulationDatasetActionP.parse(json).getSuccessOrThrow();

    assertEquals(largeId, parsedAction.input().simulationDatasetId());
  }

  @Test
  public void testParseMissingSimulationDatasetId() {
    final var json = action(
        Json.createObjectBuilder().add("planId", 123).build(),
        adminSession());

    final var result = hasuraDownloadSimulationDatasetActionP.parse(json);

    assertTrue(result.isFailure(), "Parser should fail when simulationDatasetId is missing");
  }

  @Test
  public void testParseMissingPlanId() {
    final var json = action(
        Json.createObjectBuilder().add("simulationDatasetId", 456).build(),
        adminSession());

    final var result = hasuraDownloadSimulationDatasetActionP.parse(json);

    assertTrue(result.isFailure(), "Parser should fail when planId is missing");
  }

  @Test
  public void testParseNonNumericSimulationDatasetId() {
    final var json = action(
        Json.createObjectBuilder()
            .add("planId", 123)
            .add("simulationDatasetId", "456")
            .build(),
        adminSession());

    final var result = hasuraDownloadSimulationDatasetActionP.parse(json);

    assertTrue(result.isFailure(), "Parser should fail when simulationDatasetId is not a number");
  }

  @Test
  public void testParseMissingSessionVariables() {
    final var json = Json.createObjectBuilder()
        .add("action", Json.createObjectBuilder().add("name", "downloadSimulationDataset"))
        .add("input", input(123, 456))
        .add("request_query", "query { downloadSimulationDataset }")
        .build();

    final var result = hasuraDownloadSimulationDatasetActionP.parse(json);

    assertTrue(result.isFailure(), "Parser should fail when session_variables is missing");
  }
}
