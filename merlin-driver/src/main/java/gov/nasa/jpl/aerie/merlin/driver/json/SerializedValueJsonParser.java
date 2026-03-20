package gov.nasa.jpl.aerie.merlin.driver.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SerializedValueJsonParser implements JsonParser<SerializedValue> {
  public static final JsonParser<SerializedValue> serializedValueP = new SerializedValueJsonParser();

  @Override
  public ObjectNode getSchema(final SchemaCache anchors) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("type", "any");
    return node;
  }

  @Override
  public JsonParseResult<SerializedValue> parse(final JsonNode json) {
    return JsonParseResult.success(this.parseInfallible(json));
  }

  private SerializedValue parseInfallible(final JsonNode node) {
    if (node.isNull()) {
      return SerializedValue.NULL;
    } else if (node.isBoolean()) {
      return SerializedValue.of(node.booleanValue());
    } else if (node.isTextual()) {
      return SerializedValue.of(node.textValue());
    } else if (node.isNumber()) {
      return SerializedValue.of(node.decimalValue());
    } else if (node.isArray()) {
      final var list = new ArrayList<SerializedValue>(node.size());
      for (final var element : node) list.add(this.parseInfallible(element));
      return SerializedValue.of(list);
    } else if (node.isObject()) {
      final var map = new HashMap<String, SerializedValue>(node.size());
      final var fields = node.fields();
      while (fields.hasNext()) {
        final var entry = fields.next();
        map.put(entry.getKey(), this.parseInfallible(entry.getValue()));
      }
      return SerializedValue.of(map);
    } else {
      throw new IllegalArgumentException("Unknown JsonNode type: " + node.getNodeType());
    }
  }

  @Override
  public JsonNode unparse(final SerializedValue value) {
    return value.match(new SerializedValue.Visitor<>() {
      @Override
      public JsonNode onNull() {
        return NullNode.getInstance();
      }

      @Override
      public JsonNode onBoolean(final boolean value) {
        return BooleanNode.valueOf(value);
      }

      @Override
      public JsonNode onNumeric(final BigDecimal value) {
        return DecimalNode.valueOf(value);
      }

      @Override
      public JsonNode onString(final String value) {
        return TextNode.valueOf(value);
      }

      @Override
      public JsonNode onList(final List<SerializedValue> elements) {
        final var arr = JsonNodeFactory.instance.arrayNode(elements.size());
        for (final var element : elements) arr.add(element.match(this));
        return arr;
      }

      @Override
      public JsonNode onMap(final Map<String, SerializedValue> fields) {
        final var obj = JsonNodeFactory.instance.objectNode();
        for (final var entry : fields.entrySet()) obj.set(entry.getKey(), entry.getValue().match(this));
        return obj;
      }
    });
  }
}
