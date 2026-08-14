package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraAction;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraMissionModelEvent;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonValue;
import java.util.List;
import java.util.Objects;

import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.longP;
import static gov.nasa.jpl.aerie.json.BasicParsers.recursiveP;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.*;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsersTest.NestedLists.nestedList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class MerlinParsersTest {
  public record NestedLists(List<NestedLists> lists) {

    @Override
      public boolean equals(final Object obj) {
        if (!(obj instanceof final NestedLists other)) return false;
        return Objects.equals(this.lists, other.lists);
      }

    public static NestedLists nestedList(NestedLists... lists) {
        return new NestedLists(List.of(lists));
      }
    }

  @Test
  public void testRecursiveList() {
    final var listsP =
        recursiveP((JsonParser<NestedLists> self) -> listP(self).map(NestedLists::new, $ -> $.lists));

    final var foo = Json
        . createArrayBuilder()
        . add(Json
            . createArrayBuilder()
            . add(Json.createArrayBuilder().build())
            . build())
        . add(Json
            . createArrayBuilder()
            . add(Json.createArrayBuilder().build())
            . add(Json.createArrayBuilder().build())
            . add(Json.createArrayBuilder().build())
            . build())
        . build();

    final var expected = nestedList(
            nestedList(nestedList()),
            nestedList(nestedList(), nestedList(), nestedList()));
    assertEquals(expected, listsP.parse(foo).getSuccessOrThrow());
  }

  @Test
  public void testSerializedReal() {
    final var expected = SerializedValue.of(3.14);
    final var actual = serializedValueP.parse(Json.createValue(3.14)).getSuccessOrThrow();

    assertEquals(expected, actual);
  }

  @Test
  public void testRealIsNotALong() {
    assertTrue(longP.parse(Json.createValue(3.14)).isFailure());
  }

  @Test
  public void testHasuraActionParsers() {
    {
      final var json = Json
          .createObjectBuilder()
          .add("action", Json
              .createObjectBuilder()
              .add("name", "testAction")
              .build())
          .add("input", Json
              .createObjectBuilder()
              .add("missionModelId", 1)
              .build())
          .add("session_variables", Json
              .createObjectBuilder()
              .add("x-hasura-role", "aerie_admin")
              .build())
          .add("request_query", "query { someValue }")
          .build();

      final var expected = new HasuraAction<>(
          "testAction",
          new HasuraAction.MissionModelInput(new MissionModelId(1L)),
          new HasuraAction.Session("aerie_admin", null));

      assertEquals(expected, hasuraMissionModelActionP.parse(json).getSuccessOrThrow());
    }

    {
      final var json = Json
          .createObjectBuilder()
          .add("action", Json
              .createObjectBuilder()
              .add("name", "testAction")
              .build())
          .add("input", Json
              .createObjectBuilder()
              .add("missionModelId", 1)
              .build())
          .add("session_variables", Json
              .createObjectBuilder()
              .add("x-hasura-role", "aerie_admin")
              .add("x-hasura-user-id", "userId")
              .build())
          .add("request_query", "query { someValue }")
          .build();

      final var expected = new HasuraAction<>(
          "testAction",
          new HasuraAction.MissionModelInput(new MissionModelId(1L)),
          new HasuraAction.Session("aerie_admin", "userId"));

      assertEquals(expected, hasuraMissionModelActionP.parse(json).getSuccessOrThrow());
    }
  }

  @Test
  public void testHasuraMissionModelEventParser() {
    final var json = Json
        .createObjectBuilder()
        .add("event", Json
            .createObjectBuilder()
            .add("data", Json
            .createObjectBuilder()
                .add("new", Json
                    .createObjectBuilder()
                    .add("id", 1)
                    .build())
                .add("old", JsonValue.NULL)
                .build())
            .add("op", "INSERT")
            .build())
        .add("id", "8907a407-28a5-440a-8de6-240b80c58a8b")
        .build();

    final var expected = new HasuraMissionModelEvent(new MissionModelId(1L));

    assertEquals(expected, hasuraMissionModelEventTriggerP.parse(json).getSuccessOrThrow());
  }
}
