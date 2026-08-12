package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * For each mapper, verifies that writeJson output matches serializeValue -> writeTo output.
 * This is the key correctness invariant: the optimized streaming path must produce
 * identical JSON to the legacy serialize-then-stream path.
 */
class WriteJsonTest {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private static <T> String viaWriteJson(ValueMapper<T> mapper, T value) throws IOException {
    final var sw = new StringWriter();
    try (final var gen = JSON_FACTORY.createGenerator(sw)) {
      mapper.writeJson(value, gen);
    }
    return sw.toString();
  }

  private static <T> String viaSerializeValue(ValueMapper<T> mapper, T value) throws IOException {
    return mapper.serializeValue(value).toJsonString();
  }

  private static <T> void assertWriteJsonMatchesSerialize(ValueMapper<T> mapper, T value) throws IOException {
    final var fromWriteJson = viaWriteJson(mapper, value);
    final var fromSerialize = viaSerializeValue(mapper, value);
    // Compare semantically: parse both JSON strings back to SerializedValue and check equality.
    // This handles legitimate field ordering differences (HashMap vs component order).
    final var parsedWriteJson = parseJson(fromWriteJson);
    final var parsedSerialize = parseJson(fromSerialize);
    assertEquals(parsedSerialize, parsedWriteJson,
        "writeJson output must be semantically equal to serializeValue->toJsonString for " + value
        + "\n  writeJson:  " + fromWriteJson
        + "\n  serialize:  " + fromSerialize);
  }

  private static SerializedValue parseJson(String json) throws IOException {
    try (final var parser = JSON_FACTORY.createParser(json)) {
      return parseValue(parser);
    }
  }

  private static SerializedValue parseValue(com.fasterxml.jackson.core.JsonParser parser) throws IOException {
    final var token = parser.nextToken();
    return switch (token) {
      case VALUE_NULL -> SerializedValue.NULL;
      case VALUE_TRUE -> SerializedValue.of(true);
      case VALUE_FALSE -> SerializedValue.of(false);
      case VALUE_NUMBER_INT -> SerializedValue.of(parser.getBigIntegerValue().longValueExact());
      case VALUE_NUMBER_FLOAT -> SerializedValue.of(parser.getDecimalValue());
      case VALUE_STRING -> SerializedValue.of(parser.getText());
      case START_ARRAY -> {
        final var list = new java.util.ArrayList<SerializedValue>();
        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
          list.add(parseCurrentValue(parser));
        }
        yield SerializedValue.of(list);
      }
      case START_OBJECT -> {
        final var map = new java.util.HashMap<String, SerializedValue>();
        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
          final var key = parser.getCurrentName();
          parser.nextToken();
          map.put(key, parseCurrentValue(parser));
        }
        yield SerializedValue.of(map);
      }
      default -> throw new IOException("Unexpected token: " + token);
    };
  }

  private static SerializedValue parseCurrentValue(com.fasterxml.jackson.core.JsonParser parser) throws IOException {
    final var token = parser.currentToken();
    return switch (token) {
      case VALUE_NULL -> SerializedValue.NULL;
      case VALUE_TRUE -> SerializedValue.of(true);
      case VALUE_FALSE -> SerializedValue.of(false);
      case VALUE_NUMBER_INT -> SerializedValue.of(parser.getBigIntegerValue().longValueExact());
      case VALUE_NUMBER_FLOAT -> SerializedValue.of(parser.getDecimalValue());
      case VALUE_STRING -> SerializedValue.of(parser.getText());
      case START_ARRAY -> {
        final var list = new java.util.ArrayList<SerializedValue>();
        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
          list.add(parseCurrentValue(parser));
        }
        yield SerializedValue.of(list);
      }
      case START_OBJECT -> {
        final var map = new java.util.HashMap<String, SerializedValue>();
        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
          final var key = parser.getCurrentName();
          parser.nextToken();
          map.put(key, parseCurrentValue(parser));
        }
        yield SerializedValue.of(map);
      }
      default -> throw new IOException("Unexpected token: " + token);
    };
  }

  // --- Primitive mappers ---

  @Test
  void integerMapper() throws IOException {
    final var mapper = new IntegerValueMapper();
    assertWriteJsonMatchesSerialize(mapper, 0);
    assertWriteJsonMatchesSerialize(mapper, 42);
    assertWriteJsonMatchesSerialize(mapper, -100);
    assertWriteJsonMatchesSerialize(mapper, Integer.MAX_VALUE);
    assertWriteJsonMatchesSerialize(mapper, Integer.MIN_VALUE);
  }

  @Test
  void longMapper() throws IOException {
    final var mapper = new LongValueMapper();
    assertWriteJsonMatchesSerialize(mapper, 0L);
    assertWriteJsonMatchesSerialize(mapper, 999999999999L);
    assertWriteJsonMatchesSerialize(mapper, -1L);
    assertWriteJsonMatchesSerialize(mapper, Long.MAX_VALUE);
  }

  @Test
  void doubleMapper() throws IOException {
    final var mapper = new DoubleValueMapper();
    assertWriteJsonMatchesSerialize(mapper, 0.0);
    assertWriteJsonMatchesSerialize(mapper, 3.14159265358979);
    assertWriteJsonMatchesSerialize(mapper, -1.0e10);
    assertWriteJsonMatchesSerialize(mapper, Double.MAX_VALUE);
  }

  @Test
  void booleanMapper() throws IOException {
    final var mapper = new BooleanValueMapper();
    assertWriteJsonMatchesSerialize(mapper, true);
    assertWriteJsonMatchesSerialize(mapper, false);
  }

  @Test
  void stringMapper() throws IOException {
    final var mapper = new StringValueMapper();
    assertWriteJsonMatchesSerialize(mapper, "");
    assertWriteJsonMatchesSerialize(mapper, "hello");
    assertWriteJsonMatchesSerialize(mapper, "line1\nline2\ttab\"quote\\backslash");
    assertWriteJsonMatchesSerialize(mapper, "unicode: \u00e9\u00e8\u00ea");
  }

  // --- Collection mappers ---

  @Test
  void listMapper_empty() throws IOException {
    final var mapper = new ListValueMapper<>(new IntegerValueMapper());
    assertWriteJsonMatchesSerialize(mapper, List.of());
  }

  @Test
  void listMapper_integers() throws IOException {
    final var mapper = new ListValueMapper<>(new IntegerValueMapper());
    assertWriteJsonMatchesSerialize(mapper, List.of(1, 2, 3, 4, 5));
  }

  @Test
  void listMapper_strings() throws IOException {
    final var mapper = new ListValueMapper<>(new StringValueMapper());
    assertWriteJsonMatchesSerialize(mapper, List.of("a", "b", "c"));
  }

  @Test
  void listMapper_nested() throws IOException {
    final var mapper = new ListValueMapper<>(new ListValueMapper<>(new BooleanValueMapper()));
    assertWriteJsonMatchesSerialize(mapper, List.of(
        List.of(true, false),
        List.of(false, true, true)
    ));
  }

  // --- Record mappers ---

  record Point(double x, double y) {}
  record Labeled(String label, Point point) {}
  record Config(String name, int count, boolean enabled, List<Double> weights) {}

  private static RecordValueMapper<Point> pointMapper() {
    return new RecordValueMapper<>(Point.class, List.of(
        new RecordValueMapper.Component<>("x", Point::x, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("y", Point::y, new DoubleValueMapper())
    ));
  }

  private static RecordValueMapper<Labeled> labeledMapper() {
    return new RecordValueMapper<>(Labeled.class, List.of(
        new RecordValueMapper.Component<>("label", Labeled::label, new StringValueMapper()),
        new RecordValueMapper.Component<>("point", Labeled::point, pointMapper())
    ));
  }

  private static RecordValueMapper<Config> configMapper() {
    return new RecordValueMapper<>(Config.class, List.of(
        new RecordValueMapper.Component<>("name", Config::name, new StringValueMapper()),
        new RecordValueMapper.Component<>("count", Config::count, new IntegerValueMapper()),
        new RecordValueMapper.Component<>("enabled", Config::enabled, new BooleanValueMapper()),
        new RecordValueMapper.Component<>("weights", Config::weights, new ListValueMapper<>(new DoubleValueMapper()))
    ));
  }

  @Test
  void recordMapper_simplePoint() throws IOException {
    assertWriteJsonMatchesSerialize(pointMapper(), new Point(1.0, 2.5));
  }

  @Test
  void recordMapper_nestedRecord() throws IOException {
    assertWriteJsonMatchesSerialize(
        labeledMapper(),
        new Labeled("origin", new Point(0.0, 0.0)));
  }

  @Test
  void recordMapper_withCollectionField() throws IOException {
    assertWriteJsonMatchesSerialize(
        configMapper(),
        new Config("test", 5, true, List.of(0.1, 0.2, 0.7)));
  }

  // --- Map mappers ---

  @Test
  void mapMapper_stringToInt() throws IOException {
    final var mapper = new MapValueMapper<>(new StringValueMapper(), new IntegerValueMapper());
    // Single entry for deterministic ordering
    assertWriteJsonMatchesSerialize(mapper, Map.of("one", 1));
  }

  @Test
  void mapMapper_empty() throws IOException {
    final var mapper = new MapValueMapper<>(new StringValueMapper(), new IntegerValueMapper());
    assertWriteJsonMatchesSerialize(mapper, Map.of());
  }

  // --- Stress: large data volumes ---

  @Test
  void listMapper_largeList() throws IOException {
    final var mapper = new ListValueMapper<>(new DoubleValueMapper());
    // 10,000 element list
    final var list = new java.util.ArrayList<Double>(10_000);
    for (int i = 0; i < 10_000; i++) list.add(i * 0.001);
    assertWriteJsonMatchesSerialize(mapper, list);
  }

  @Test
  void recordMapper_repeated() throws IOException {
    // Serialize same record 1000 times — simulates per-step overhead
    final var mapper = configMapper();
    final var value = new Config("sensor_data", 42, true, List.of(1.0, 2.0, 3.0, 4.0, 5.0));

    for (int i = 0; i < 1000; i++) {
      assertWriteJsonMatchesSerialize(mapper, value);
    }
  }

  // --- Default fallback behavior ---

  @Test
  void defaultWriteJson_matchesSerializeValue() throws IOException {
    // A mapper that does NOT override writeJson should still produce correct output via the default
    final var fallbackMapper = new ValueMapper<String>() {
      @Override
      public gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema getValueSchema() {
        return gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.STRING;
      }
      @Override
      public gov.nasa.jpl.aerie.merlin.framework.Result<String, String> deserializeValue(SerializedValue serializedValue) {
        return serializedValue.asString()
            .map(gov.nasa.jpl.aerie.merlin.framework.Result::<String, String>success)
            .orElseGet(() -> gov.nasa.jpl.aerie.merlin.framework.Result.failure("not a string"));
      }
      @Override
      public SerializedValue serializeValue(String value) {
        return SerializedValue.of(value);
      }
      // NOTE: No writeJson override — uses default from ValueMapper interface
    };

    assertWriteJsonMatchesSerialize(fallbackMapper, "testing default path");
  }
}
