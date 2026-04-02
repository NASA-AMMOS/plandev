package gov.nasa.jpl.aerie.merlin.protocol.types;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that SerializedValue.writeTo(JsonGenerator) produces identical output
 * to the legacy javax.json-based serialization path.
 */
class SerializedValueStreamingTest {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private static String writeToString(SerializedValue value) throws IOException {
    final var sw = new StringWriter();
    try (final var gen = JSON_FACTORY.createGenerator(sw)) {
      value.writeTo(gen);
    }
    return sw.toString();
  }

  // --- Primitive types ---

  @Test
  void writeTo_null() throws IOException {
    assertEquals("null", writeToString(SerializedValue.NULL));
  }

  @Test
  void writeTo_boolean_true() throws IOException {
    assertEquals("true", writeToString(SerializedValue.of(true)));
  }

  @Test
  void writeTo_boolean_false() throws IOException {
    assertEquals("false", writeToString(SerializedValue.of(false)));
  }

  @Test
  void writeTo_integer() throws IOException {
    assertEquals("42", writeToString(SerializedValue.of(42L)));
  }

  @Test
  void writeTo_negative_integer() throws IOException {
    assertEquals("-7", writeToString(SerializedValue.of(-7L)));
  }

  @Test
  void writeTo_zero() throws IOException {
    assertEquals("0", writeToString(SerializedValue.of(0L)));
  }

  @Test
  void writeTo_double() throws IOException {
    assertEquals("3.14", writeToString(SerializedValue.of(3.14)));
  }

  @Test
  void writeTo_bigDecimal() throws IOException {
    assertEquals(
        "123456789.987654321",
        writeToString(SerializedValue.of(new BigDecimal("123456789.987654321"))));
  }

  @Test
  void writeTo_string() throws IOException {
    assertEquals("\"hello world\"", writeToString(SerializedValue.of("hello world")));
  }

  @Test
  void writeTo_string_with_escapes() throws IOException {
    final var json = writeToString(SerializedValue.of("line1\nline2\ttab\"quote"));
    assertEquals("\"line1\\nline2\\ttab\\\"quote\"", json);
  }

  @Test
  void writeTo_empty_string() throws IOException {
    assertEquals("\"\"", writeToString(SerializedValue.of("")));
  }

  // --- Composite types ---

  @Test
  void writeTo_empty_list() throws IOException {
    assertEquals("[]", writeToString(SerializedValue.of(List.of())));
  }

  @Test
  void writeTo_list_of_primitives() throws IOException {
    final var value = SerializedValue.of(List.of(
        SerializedValue.of(1L),
        SerializedValue.of("two"),
        SerializedValue.of(true),
        SerializedValue.NULL
    ));
    assertEquals("[1,\"two\",true,null]", writeToString(value));
  }

  @Test
  void writeTo_empty_map() throws IOException {
    assertEquals("{}", writeToString(SerializedValue.of(Map.of())));
  }

  @Test
  void writeTo_simple_map() throws IOException {
    // Use a single-entry map for deterministic key order
    final var json = writeToString(SerializedValue.of(Map.of("key", SerializedValue.of("value"))));
    assertEquals("{\"key\":\"value\"}", json);
  }

  // --- Nested structures ---

  @Test
  void writeTo_nested_list_of_maps() throws IOException {
    final var value = SerializedValue.of(List.of(
        SerializedValue.of(Map.of("x", SerializedValue.of(1L))),
        SerializedValue.of(Map.of("y", SerializedValue.of(2L)))
    ));
    assertEquals("[{\"x\":1},{\"y\":2}]", writeToString(value));
  }

  @Test
  void writeTo_deeply_nested() throws IOException {
    // {"a":{"b":{"c":[1,2,3]}}}
    final var innerList = SerializedValue.of(List.of(
        SerializedValue.of(1L), SerializedValue.of(2L), SerializedValue.of(3L)));
    final var innerMap = SerializedValue.of(Map.of("c", innerList));
    final var middleMap = SerializedValue.of(Map.of("b", innerMap));
    final var outerMap = SerializedValue.of(Map.of("a", middleMap));

    assertEquals("{\"a\":{\"b\":{\"c\":[1,2,3]}}}", writeToString(outerMap));
  }

  // --- Round-trip consistency: toJsonString() should match writeTo() ---

  @Test
  void toJsonString_matches_writeTo() throws IOException {
    final var complex = SerializedValue.of(Map.of(
        "name", SerializedValue.of("test"),
        "values", SerializedValue.of(List.of(
            SerializedValue.of(1L),
            SerializedValue.of(2.5),
            SerializedValue.NULL
        ))
    ));

    assertEquals(writeToString(complex), complex.toJsonString());
  }
}
