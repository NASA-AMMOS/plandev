package gov.nasa.jpl.aerie.serializationbenchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson-based JSON encoding/decoding for SerializedValue.
 * Drop-in replacement for JsonEncoding that uses Jackson instead of javax.json.
 */
public final class JacksonEncoding {
  private static final ObjectMapper mapper = new ObjectMapper();

  public static JsonNode encode(final SerializedValue value) {
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
        final var arrayNode = mapper.createArrayNode();
        for (final var element : elements) {
          arrayNode.add(element.match(this));
        }
        return arrayNode;
      }

      @Override
      public JsonNode onMap(final Map<String, SerializedValue> fields) {
        final var objectNode = mapper.createObjectNode();
        for (final var entry : fields.entrySet()) {
          objectNode.set(entry.getKey(), entry.getValue().match(this));
        }
        return objectNode;
      }
    });
  }

  public static SerializedValue decode(final JsonNode node) {
    if (node.isNull()) {
      return SerializedValue.NULL;
    } else if (node.isBoolean()) {
      return SerializedValue.of(node.booleanValue());
    } else if (node.isNumber()) {
      return SerializedValue.of(node.decimalValue());
    } else if (node.isTextual()) {
      return SerializedValue.of(node.textValue());
    } else if (node.isArray()) {
      final var list = new ArrayList<SerializedValue>();
      for (final var element : node) {
        list.add(decode(element));
      }
      return SerializedValue.of(list);
    } else if (node.isObject()) {
      final var map = new HashMap<String, SerializedValue>();
      final var fields = node.fields();
      while (fields.hasNext()) {
        final var entry = fields.next();
        map.put(entry.getKey(), decode(entry.getValue()));
      }
      return SerializedValue.of(map);
    } else {
      throw new IllegalArgumentException("Unknown JsonNode type: " + node.getNodeType());
    }
  }

  /**
   * Encode to JSON string directly (for benchmarking).
   */
  public static String encodeToString(final SerializedValue value) {
    try {
      return mapper.writeValueAsString(encode(value));
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to encode to JSON string", e);
    }
  }

  /**
   * Decode from JSON string directly (for benchmarking).
   */
  public static SerializedValue decodeFromString(final String json) {
    try {
      return decode(mapper.readTree(json));
    } catch (IOException e) {
      throw new RuntimeException("Failed to decode from JSON string", e);
    }
  }
}
