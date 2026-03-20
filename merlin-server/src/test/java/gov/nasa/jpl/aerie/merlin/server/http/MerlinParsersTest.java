package gov.nasa.jpl.aerie.merlin.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraAction;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraMissionModelEvent;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.longP;
import static gov.nasa.jpl.aerie.json.BasicParsers.recursiveP;
import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;

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

    final var foo = JsonNodeFactory.instance.arrayNode()
        .add(JsonNodeFactory.instance.arrayNode()
            .add(JsonNodeFactory.instance.arrayNode()))
        .add(JsonNodeFactory.instance.arrayNode()
            .add(JsonNodeFactory.instance.arrayNode())
            .add(JsonNodeFactory.instance.arrayNode())
            .add(JsonNodeFactory.instance.arrayNode()));

    final var expected = nestedList(
            nestedList(nestedList()),
            nestedList(nestedList(), nestedList(), nestedList()));
    assertEquals(expected, listsP.parse(foo).getSuccessOrThrow());
  }

  @Test
  public void testSerializedReal() {
    final var expected = SerializedValue.of(3.14);
    final var actual = JsonEncoding.decode(DecimalNode.valueOf(new BigDecimal("3.14")));

    assertEquals(expected, actual);
  }

  @Test
  public void testRealIsNotALong() {
    assertTrue(longP.parse(DecimalNode.valueOf(new BigDecimal("3.14"))).isFailure());
  }

  @Test
  public void testHasuraActionParsers() {
    {
      final var inputNode = JsonNodeFactory.instance.objectNode();
      inputNode.put("missionModelId", 1);

      final var actionNode = JsonNodeFactory.instance.objectNode();
      actionNode.put("name", "testAction");

      final var sessionNode = JsonNodeFactory.instance.objectNode();
      sessionNode.put("x-hasura-role", "aerie_admin");

      final var json = JsonNodeFactory.instance.objectNode();
      json.set("action", actionNode);
      json.set("input", inputNode);
      json.set("session_variables", sessionNode);
      json.put("request_query", "query { someValue }");

      final var expected = new HasuraAction<>(
          "testAction",
          new HasuraAction.MissionModelInput(new MissionModelId(1L)),
          new HasuraAction.Session("aerie_admin", null));

      assertEquals(expected, hasuraMissionModelActionP.parse(json).getSuccessOrThrow());
    }

    {
      final var inputNode = JsonNodeFactory.instance.objectNode();
      inputNode.put("missionModelId", 1);

      final var actionNode = JsonNodeFactory.instance.objectNode();
      actionNode.put("name", "testAction");

      final var sessionNode = JsonNodeFactory.instance.objectNode();
      sessionNode.put("x-hasura-role", "aerie_admin");
      sessionNode.put("x-hasura-user-id", "userId");

      final var json = JsonNodeFactory.instance.objectNode();
      json.set("action", actionNode);
      json.set("input", inputNode);
      json.set("session_variables", sessionNode);
      json.put("request_query", "query { someValue }");

      final var expected = new HasuraAction<>(
          "testAction",
          new HasuraAction.MissionModelInput(new MissionModelId(1L)),
          new HasuraAction.Session("aerie_admin", "userId"));

      assertEquals(expected, hasuraMissionModelActionP.parse(json).getSuccessOrThrow());
    }
  }

  @Test
  public void testHasuraMissionModelEventParser() {
    final var newNode = JsonNodeFactory.instance.objectNode();
    newNode.put("id", 1);

    final var dataNode = JsonNodeFactory.instance.objectNode();
    dataNode.set("new", newNode);
    dataNode.set("old", NullNode.getInstance());

    final var eventNode = JsonNodeFactory.instance.objectNode();
    eventNode.set("data", dataNode);
    eventNode.put("op", "INSERT");

    final var json = JsonNodeFactory.instance.objectNode();
    json.set("event", eventNode);
    json.put("id", "8907a407-28a5-440a-8de6-240b80c58a8b");

    final var expected = new HasuraMissionModelEvent(new MissionModelId(1L));

    assertEquals(expected, hasuraMissionModelEventTriggerP.parse(json).getSuccessOrThrow());
  }
}
