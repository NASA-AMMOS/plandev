package gov.nasa.jpl.aerie.merlin.driver;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper;
import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;
import gov.nasa.jpl.aerie.merlin.driver.resources.DeferredSerializedValue;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Torture test exercising the Jackson streaming serialization pipeline at scale.
 *
 * Verifies:
 * 1. Correctness: writeJson output matches legacy serializeValue path for all mapper types
 * 2. Scale: handles 200+ resources × 500 segments without issues
 * 3. DeferredSerializedValue: lazy caching + direct streaming
 * 4. Large composite types: deeply nested records with collections
 */
class JacksonStreamingTortureTest {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  // --- Record types simulating realistic mission model resources ---

  record ThermalState(double temperature, double heaterPower, boolean heaterOn) {}
  record AttitudeState(double roll, double pitch, double yaw, double rate) {}
  record TelemetryPacket(String name, int sequenceNumber, long timestamp, List<Double> channels) {}
  record SubsystemStatus(String subsystem, boolean active, int errorCount, ThermalState thermal) {}

  // --- Mapper factories ---

  private static RecordValueMapper<ThermalState> thermalMapper() {
    return new RecordValueMapper<>(ThermalState.class, List.of(
        new RecordValueMapper.Component<>("temperature", ThermalState::temperature, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("heaterPower", ThermalState::heaterPower, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("heaterOn", ThermalState::heaterOn, new BooleanValueMapper())
    ));
  }

  private static RecordValueMapper<AttitudeState> attitudeMapper() {
    return new RecordValueMapper<>(AttitudeState.class, List.of(
        new RecordValueMapper.Component<>("roll", AttitudeState::roll, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("pitch", AttitudeState::pitch, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("yaw", AttitudeState::yaw, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("rate", AttitudeState::rate, new DoubleValueMapper())
    ));
  }

  private static RecordValueMapper<TelemetryPacket> telemetryMapper() {
    return new RecordValueMapper<>(TelemetryPacket.class, List.of(
        new RecordValueMapper.Component<>("name", TelemetryPacket::name, new StringValueMapper()),
        new RecordValueMapper.Component<>("sequenceNumber", TelemetryPacket::sequenceNumber, new IntegerValueMapper()),
        new RecordValueMapper.Component<>("timestamp", TelemetryPacket::timestamp, new LongValueMapper()),
        new RecordValueMapper.Component<>("channels", TelemetryPacket::channels, new ListValueMapper<>(new DoubleValueMapper()))
    ));
  }

  private static RecordValueMapper<SubsystemStatus> subsystemMapper() {
    return new RecordValueMapper<>(SubsystemStatus.class, List.of(
        new RecordValueMapper.Component<>("subsystem", SubsystemStatus::subsystem, new StringValueMapper()),
        new RecordValueMapper.Component<>("active", SubsystemStatus::active, new BooleanValueMapper()),
        new RecordValueMapper.Component<>("errorCount", SubsystemStatus::errorCount, new IntegerValueMapper()),
        new RecordValueMapper.Component<>("thermal", SubsystemStatus::thermal, thermalMapper())
    ));
  }

  // --- Helper to create an OutputType from a ValueMapper ---

  private static <T> OutputType<T> outputTypeFrom(ValueMapper<T> mapper) {
    return new OutputType<>() {
      @Override
      public ValueSchema getSchema() { return mapper.getValueSchema(); }
      @Override
      public SerializedValue serialize(T value) { return mapper.serializeValue(value); }
      @Override
      public void writeJson(T value, JsonGenerator gen) throws IOException { mapper.writeJson(value, gen); }
    };
  }

  // --- Correctness: writeJson vs serializeValue for every mapper ---

  @Test
  void allMappers_writeJsonMatchesSerialize_1000iterations() throws IOException {
    // Simulate 1000 steps × multiple resource types
    final var thermal = thermalMapper();
    final var attitude = attitudeMapper();
    final var telemetry = telemetryMapper();
    final var subsystem = subsystemMapper();
    final var intMapper = new IntegerValueMapper();
    final var dblMapper = new DoubleValueMapper();
    final var boolMapper = new BooleanValueMapper();
    final var strMapper = new StringValueMapper();

    for (int step = 0; step < 1000; step++) {
      final double t = step * 0.1;

      // Primitive resources
      assertJsonEquals(intMapper, step);
      assertJsonEquals(dblMapper, Math.sin(t) * 100);
      assertJsonEquals(boolMapper, step % 2 == 0);
      assertJsonEquals(strMapper, "mode_" + (step % 5));

      // Complex resources
      assertJsonEquals(thermal, new ThermalState(20.0 + t * 0.5, step % 10, step % 3 == 0));
      assertJsonEquals(attitude, new AttitudeState(t, -t * 0.5, t * 2, 0.01 * step));
      assertJsonEquals(telemetry, new TelemetryPacket(
          "pkt_" + step, step, System.nanoTime(),
          List.of(1.0 + t, 2.0 - t, 3.0 * t, 4.0 / (t + 1), 5.0)));
      assertJsonEquals(subsystem, new SubsystemStatus(
          "THERMAL", true, step % 100,
          new ThermalState(-40.0 + step * 0.1, 50.0, step % 7 != 0)));
    }
  }

  // --- Scale: simulate 200+ resources × 500 segments ---

  @Test
  void scaleTest_200Resources_500Segments() throws IOException {
    final int NUM_RESOURCES = 200;
    final int NUM_SEGMENTS = 500;

    // Create a mix of mappers representing the resource fleet
    @SuppressWarnings("unchecked")
    final ValueMapper<Object>[] mappers = new ValueMapper[NUM_RESOURCES];
    for (int i = 0; i < NUM_RESOURCES; i++) {
      mappers[i] = switch (i % 5) {
        case 0 -> (ValueMapper<Object>) (ValueMapper<?>) new IntegerValueMapper();
        case 1 -> (ValueMapper<Object>) (ValueMapper<?>) new DoubleValueMapper();
        case 2 -> (ValueMapper<Object>) (ValueMapper<?>) new BooleanValueMapper();
        case 3 -> (ValueMapper<Object>) (ValueMapper<?>) thermalMapper();
        case 4 -> (ValueMapper<Object>) (ValueMapper<?>) attitudeMapper();
        default -> throw new IllegalStateException();
      };
    }

    // Simulate writing all segments to a single large JSON stream
    final var sw = new StringWriter(1024 * 1024); // 1MB initial buffer
    try (final var gen = JSON_FACTORY.createGenerator(sw)) {
      gen.writeStartArray(); // array of resources

      for (int r = 0; r < NUM_RESOURCES; r++) {
        gen.writeStartObject();
        gen.writeStringField("name", "resource_" + r);
        gen.writeArrayFieldStart("segments");

        for (int s = 0; s < NUM_SEGMENTS; s++) {
          gen.writeStartObject();
          gen.writeStringField("extent", "00:00:01.000000");
          gen.writeFieldName("dynamics");

          final Object value = switch (r % 5) {
            case 0 -> s;
            case 1 -> s * 0.1;
            case 2 -> s % 2 == 0;
            case 3 -> new ThermalState(20.0 + s, s * 0.5, s % 3 == 0);
            case 4 -> new AttitudeState(s * 0.01, -s * 0.01, s * 0.02, 0.001);
            default -> throw new IllegalStateException();
          };

          mappers[r].writeJson(value, gen);
          gen.writeEndObject();
        }

        gen.writeEndArray(); // end segments
        gen.writeEndObject(); // end resource
      }

      gen.writeEndArray(); // end array of resources
    }

    final var json = sw.toString();
    // Verify it's valid JSON and has substantial content
    assertTrue(json.startsWith("["), "Output should start with array");
    assertTrue(json.endsWith("]"), "Output should end with array");
    assertTrue(json.length() > 100_000, "200×500 segments should produce substantial output, got " + json.length());
  }

  // --- DeferredSerializedValue: lazy caching + streaming ---

  @Test
  void deferredSerializedValue_writeJsonMatchesLazySerialization() throws IOException {
    final var mapper = subsystemMapper();
    final var outputType = outputTypeFrom(mapper);
    final var value = new SubsystemStatus("POWER", true, 5,
        new ThermalState(25.0, 10.0, true));

    // Create deferred value — no serialization yet
    final var deferred = new DeferredSerializedValue(value, outputType);

    // Stream directly via writeJson (bypasses SerializedValue entirely)
    final var directJson = writeToString(gen -> deferred.writeJson(gen));

    // Lazy serialize, then stream
    final var lazySerialized = deferred.toSerializedValue();
    final var lazyJson = writeToString(gen -> lazySerialized.writeTo(gen));

    // Both paths must produce semantically identical output (field order may differ)
    assertEquals(parseJson(lazyJson), parseJson(directJson));

    // Verify caching: second call returns same instance
    final var lazySerialized2 = deferred.toSerializedValue();
    assertTrue(lazySerialized == lazySerialized2, "toSerializedValue should return cached instance");
  }

  @Test
  void deferredSerializedValue_primitiveTypes() throws IOException {
    // Verify deferred works for each primitive type
    assertDeferredCorrect(new IntegerValueMapper(), 42);
    assertDeferredCorrect(new DoubleValueMapper(), 3.14);
    assertDeferredCorrect(new BooleanValueMapper(), true);
    assertDeferredCorrect(new StringValueMapper(), "hello");
    assertDeferredCorrect(new LongValueMapper(), 999999999999L);
  }

  @Test
  void deferredSerializedValue_stressWithManyInstances() throws IOException {
    // Simulate what happens during simulation: one DeferredSerializedValue per resource per step
    final var mapper = thermalMapper();
    final var outputType = outputTypeFrom(mapper);
    final int NUM_STEPS = 10_000;

    final var deferredValues = new ArrayList<DeferredSerializedValue>(NUM_STEPS);

    // Phase 1: Create deferred values (simulating simulation loop — no serialization)
    for (int i = 0; i < NUM_STEPS; i++) {
      deferredValues.add(new DeferredSerializedValue(
          new ThermalState(20.0 + i * 0.01, i * 0.5, i % 3 == 0),
          outputType));
    }

    // Phase 2: Stream all values (simulating post-simulation output)
    final var sw = new StringWriter(NUM_STEPS * 100);
    try (final var gen = JSON_FACTORY.createGenerator(sw)) {
      gen.writeStartArray();
      for (final var deferred : deferredValues) {
        deferred.writeJson(gen);
      }
      gen.writeEndArray();
    }

    final var json = sw.toString();
    assertTrue(json.startsWith("["));
    assertTrue(json.length() > NUM_STEPS * 10,
        "10K thermal states should produce substantial output, got " + json.length());
  }

  // --- JsonEncoding convenience methods ---

  @Test
  void jsonEncoding_writeToString_matchesWriteTo() throws IOException {
    final var complex = SerializedValue.of(Map.of(
        "data", SerializedValue.of(List.of(
            SerializedValue.of(1L),
            SerializedValue.of(2.5),
            SerializedValue.of("three"))),
        "meta", SerializedValue.of(Map.of(
            "count", SerializedValue.of(3L)))));

    final var viaHelper = JsonEncoding.writeToString(complex);
    final var viaMethod = complex.toJsonString();
    assertEquals(viaMethod, viaHelper);
  }

  // --- Helpers ---

  @FunctionalInterface
  private interface JsonWriterAction {
    void write(JsonGenerator gen) throws IOException;
  }

  private static String writeToString(JsonWriterAction action) throws IOException {
    final var sw = new StringWriter();
    try (final var gen = JSON_FACTORY.createGenerator(sw)) {
      action.write(gen);
    }
    return sw.toString();
  }

  private static SerializedValue parseJson(String json) throws IOException {
    try (final var parser = JSON_FACTORY.createParser(json)) {
      return parseValue(parser);
    }
  }

  private static SerializedValue parseValue(com.fasterxml.jackson.core.JsonParser parser) throws IOException {
    final var token = parser.nextToken();
    return parseFromToken(parser, token);
  }

  private static SerializedValue parseFromToken(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.core.JsonToken token) throws IOException {
    return switch (token) {
      case VALUE_NULL -> SerializedValue.NULL;
      case VALUE_TRUE -> SerializedValue.of(true);
      case VALUE_FALSE -> SerializedValue.of(false);
      case VALUE_NUMBER_INT -> SerializedValue.of(parser.getBigIntegerValue().longValueExact());
      case VALUE_NUMBER_FLOAT -> SerializedValue.of(parser.getDecimalValue());
      case VALUE_STRING -> SerializedValue.of(parser.getText());
      case START_ARRAY -> {
        final var list = new java.util.ArrayList<SerializedValue>();
        com.fasterxml.jackson.core.JsonToken t;
        while ((t = parser.nextToken()) != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
          list.add(parseFromToken(parser, t));
        }
        yield SerializedValue.of(list);
      }
      case START_OBJECT -> {
        final var map = new java.util.HashMap<String, SerializedValue>();
        com.fasterxml.jackson.core.JsonToken t;
        while ((t = parser.nextToken()) != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
          final var key = parser.getCurrentName();
          final var valToken = parser.nextToken();
          map.put(key, parseFromToken(parser, valToken));
        }
        yield SerializedValue.of(map);
      }
      default -> throw new IOException("Unexpected token: " + token);
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> void assertJsonEquals(ValueMapper<T> mapper, T value) throws IOException {
    final var viaWriteJson = writeToString(gen -> mapper.writeJson(value, gen));
    final var viaSerialized = mapper.serializeValue(value).toJsonString();
    assertEquals(parseJson(viaSerialized), parseJson(viaWriteJson),
        "Mismatch for " + mapper.getClass().getSimpleName() + " with value " + value);
  }

  private static <T> void assertDeferredCorrect(ValueMapper<T> mapper, T value) throws IOException {
    final var outputType = outputTypeFrom(mapper);
    final var deferred = new DeferredSerializedValue(value, outputType);

    // Direct streaming
    final var directJson = writeToString(gen -> deferred.writeJson(gen));
    // Via lazy serialization
    final var lazyJson = deferred.toSerializedValue().toJsonString();

    assertEquals(parseJson(lazyJson), parseJson(directJson),
        "Deferred mismatch for " + mapper.getClass().getSimpleName());
  }
}
