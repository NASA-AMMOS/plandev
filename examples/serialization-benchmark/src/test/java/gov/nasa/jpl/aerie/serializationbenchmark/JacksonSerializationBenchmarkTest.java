package gov.nasa.jpl.aerie.serializationbenchmark;

import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.serializationbenchmark.DataTypes.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark test using Jackson for JSON serialization (vs javax.json).
 *
 * Run with: ./gradlew :serialization-benchmark:test --tests JacksonSerializationBenchmarkTest
 */
public class JacksonSerializationBenchmarkTest {

  private static final int WARMUP_ITERATIONS = 100;
  private static final int BENCHMARK_ITERATIONS = 1000;

  @Test
  public void benchmarkSimpleRecordSerialization() {
    var mapper = createVector3DMapper();
    var testData = new Vector3D(1.0, 2.0, 3.0);

    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      mapper.deserializeValue(decoded);
    }

    // Benchmark serialization
    long startSerialize = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      mapper.serializeValue(testData);
    }
    long endSerialize = System.nanoTime();
    double serializeTimeMs = (endSerialize - startSerialize) / 1_000_000.0;

    // Benchmark JSON encoding
    var serialized = mapper.serializeValue(testData);
    long startEncode = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      JacksonEncoding.encode(serialized);
    }
    long endEncode = System.nanoTime();
    double encodeTimeMs = (endEncode - startEncode) / 1_000_000.0;

    // Benchmark JSON decoding
    var json = JacksonEncoding.encode(serialized);
    long startDecode = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      JacksonEncoding.decode(json);
    }
    long endDecode = System.nanoTime();
    double decodeTimeMs = (endDecode - startDecode) / 1_000_000.0;

    // Benchmark deserialization
    var decoded = JacksonEncoding.decode(json);
    long startDeserialize = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      mapper.deserializeValue(decoded);
    }
    long endDeserialize = System.nanoTime();
    double deserializeTimeMs = (endDeserialize - startDeserialize) / 1_000_000.0;

    System.out.printf("""

        === [JACKSON] Simple Record (Vector3D) Benchmark ===
        Iterations: %d
        Serialize (Object -> SerializedValue): %.2f ms (%.2f µs/op)
        Encode (SerializedValue -> JSON):      %.2f ms (%.2f µs/op)
        Decode (JSON -> SerializedValue):      %.2f ms (%.2f µs/op)
        Deserialize (SerializedValue -> Object): %.2f ms (%.2f µs/op)
        Total Round-trip:                       %.2f ms (%.2f µs/op)

        """,
        BENCHMARK_ITERATIONS,
        serializeTimeMs, serializeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        encodeTimeMs, encodeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        decodeTimeMs, decodeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        deserializeTimeMs, deserializeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        serializeTimeMs + encodeTimeMs + decodeTimeMs + deserializeTimeMs,
        (serializeTimeMs + encodeTimeMs + decodeTimeMs + deserializeTimeMs) * 1000 / BENCHMARK_ITERATIONS
    );
  }

  @Test
  public void benchmarkComplexNestedSerialization() {
    var mapper = createComplexActivityStateMapper();
    var testData = createComplexActivityState();

    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      mapper.deserializeValue(decoded);
    }

    // Benchmark serialization
    long startSerialize = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      mapper.serializeValue(testData);
    }
    long endSerialize = System.nanoTime();
    double serializeTimeMs = (endSerialize - startSerialize) / 1_000_000.0;

    // Benchmark JSON encoding
    var serialized = mapper.serializeValue(testData);
    long startEncode = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      JacksonEncoding.encode(serialized);
    }
    long endEncode = System.nanoTime();
    double encodeTimeMs = (endEncode - startEncode) / 1_000_000.0;

    // Benchmark JSON decoding
    var json = JacksonEncoding.encode(serialized);
    long startDecode = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      JacksonEncoding.decode(json);
    }
    long endDecode = System.nanoTime();
    double decodeTimeMs = (endDecode - startDecode) / 1_000_000.0;

    // Benchmark deserialization
    var decoded = JacksonEncoding.decode(json);
    long startDeserialize = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      mapper.deserializeValue(decoded);
    }
    long endDeserialize = System.nanoTime();
    double deserializeTimeMs = (endDeserialize - startDeserialize) / 1_000_000.0;

    System.out.printf("""

        === [JACKSON] Complex Nested Record (ComplexActivityState) Benchmark ===
        Iterations: %d
        Serialize (Object -> SerializedValue): %.2f ms (%.2f µs/op)
        Encode (SerializedValue -> JSON):      %.2f ms (%.2f µs/op)
        Decode (JSON -> SerializedValue):      %.2f ms (%.2f µs/op)
        Deserialize (SerializedValue -> Object): %.2f ms (%.2f µs/op)
        Total Round-trip:                       %.2f ms (%.2f µs/op)

        """,
        BENCHMARK_ITERATIONS,
        serializeTimeMs, serializeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        encodeTimeMs, encodeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        decodeTimeMs, decodeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        deserializeTimeMs, deserializeTimeMs * 1000 / BENCHMARK_ITERATIONS,
        serializeTimeMs + encodeTimeMs + decodeTimeMs + deserializeTimeMs,
        (serializeTimeMs + encodeTimeMs + decodeTimeMs + deserializeTimeMs) * 1000 / BENCHMARK_ITERATIONS
    );

    // Print JSON size
    System.out.printf("JSON Size: %d bytes\n\n", JacksonEncoding.encodeToString(serialized).length());
  }

  @Test
  public void benchmarkLargeRecordSerialization() {
    var mapper = createLargeRecordMapper();
    var testData = createLargeRecord();

    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      mapper.deserializeValue(decoded);
    }

    // Benchmark full round-trip
    long startRoundtrip = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      var result = mapper.deserializeValue(decoded);
      assertThat(result.getKind()).isEqualTo(Result.Kind.Success);
    }
    long endRoundtrip = System.nanoTime();
    double roundtripTimeMs = (endRoundtrip - startRoundtrip) / 1_000_000.0;

    System.out.printf("""

        === [JACKSON] Large Record Benchmark ===
        Iterations: %d
        Full Round-trip Time: %.2f ms (%.2f µs/op)
        Throughput: %.2f ops/sec

        """,
        BENCHMARK_ITERATIONS,
        roundtripTimeMs,
        roundtripTimeMs * 1000 / BENCHMARK_ITERATIONS,
        BENCHMARK_ITERATIONS / (roundtripTimeMs / 1000.0)
    );
  }

  @Test
  public void benchmarkListSerialization() {
    // Test serialization of a list of complex objects
    var mapper = new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
        createTelemetryPointMapper()
    );

    var testData = new ArrayList<TelemetryPoint>();
    for (int i = 0; i < 100; i++) {
      testData.add(new TelemetryPoint(
          System.currentTimeMillis() + i,
          "channel_" + i,
          Math.random() * 100,
          "units",
          "OK"
      ));
    }

    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      mapper.deserializeValue(decoded);
    }

    // Benchmark
    long start = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      mapper.deserializeValue(decoded);
    }
    long end = System.nanoTime();
    double timeMs = (end - start) / 1_000_000.0;

    var jsonString = JacksonEncoding.encodeToString(mapper.serializeValue(testData));
    System.out.printf("""

        === [JACKSON] List Serialization Benchmark (100 TelemetryPoints) ===
        Iterations: %d
        Full Round-trip Time: %.2f ms (%.2f µs/op)
        JSON Size: %d bytes
        Throughput: %.2f MB/sec

        """,
        BENCHMARK_ITERATIONS,
        timeMs,
        timeMs * 1000 / BENCHMARK_ITERATIONS,
        jsonString.length(),
        (jsonString.length() * BENCHMARK_ITERATIONS) / (timeMs * 1000)
    );
  }

  @Test
  public void benchmarkMapSerialization() {
    // Test serialization of maps
    var mapper = new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(
        new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(),
        new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()
    );

    var testData = new HashMap<String, Double>();
    for (int i = 0; i < 100; i++) {
      testData.put("key_" + i, Math.random() * 100);
    }

    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      mapper.deserializeValue(decoded);
    }

    // Benchmark
    long start = System.nanoTime();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      var serialized = mapper.serializeValue(testData);
      var json = JacksonEncoding.encode(serialized);
      var decoded = JacksonEncoding.decode(json);
      mapper.deserializeValue(decoded);
    }
    long end = System.nanoTime();
    double timeMs = (end - start) / 1_000_000.0;

    System.out.printf("""

        === [JACKSON] Map Serialization Benchmark (100 entries) ===
        Iterations: %d
        Full Round-trip Time: %.2f ms (%.2f µs/op)

        """,
        BENCHMARK_ITERATIONS,
        timeMs,
        timeMs * 1000 / BENCHMARK_ITERATIONS
    );
  }

  // Helper methods to create test data
  private ComplexActivityState createComplexActivityState() {
    var telemetry = new ArrayList<TelemetryPoint>();
    for (int i = 0; i < 10; i++) {
      telemetry.add(new TelemetryPoint(
          System.currentTimeMillis() + i,
          "channel_" + i,
          Math.random() * 100,
          "units",
          "OK"
      ));
    }

    return new ComplexActivityState(
        "activity_123",
        "executing",
        List.of("arg1", "arg2", "arg3"),
        Map.of("metric1", 1.5, "metric2", 2.7, "metric3", 3.9),
        telemetry,
        new Attitude(
            new Quaternion(0.707, 0.707, 0.0, 0.0),
            new Vector3D(0.1, 0.2, 0.3)
        ),
        new Ephemeris(
            System.currentTimeMillis(),
            new Vector3D(1000.0, 2000.0, 3000.0),
            new Vector3D(7.5, 8.5, 9.5),
            "J2000"
        )
    );
  }

  private LargeRecord createLargeRecord() {
    return new LargeRecord(
        "field1_value_with_some_length",
        "field2_value_with_some_length",
        "field3_value_with_some_length",
        "field4_value_with_some_length",
        "field5_value_with_some_length",
        1.23456789, 2.34567890, 3.45678901, 4.56789012, 5.67890123,
        System.currentTimeMillis(), System.currentTimeMillis() + 1000, System.currentTimeMillis() + 2000,
        true, false,
        List.of("item1", "item2", "item3", "item4", "item5"),
        List.of(1.1, 2.2, 3.3, 4.4, 5.5),
        Map.of("key1", "value1", "key2", "value2", "key3", "value3"),
        new Vector3D(100.0, 200.0, 300.0),
        new Quaternion(0.5, 0.5, 0.5, 0.5)
    );
  }

  // Mapper creation methods (same as in Mission.java)
  private static RecordValueMapper<Vector3D> createVector3DMapper() {
    return new RecordValueMapper<>(
        Vector3D.class,
        List.of(
            new RecordValueMapper.Component<>("x", Vector3D::x,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("y", Vector3D::y,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("z", Vector3D::z,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())
        )
    );
  }

  private static RecordValueMapper<Quaternion> createQuaternionMapper() {
    return new RecordValueMapper<>(
        Quaternion.class,
        List.of(
            new RecordValueMapper.Component<>("w", Quaternion::w,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("x", Quaternion::x,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("y", Quaternion::y,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("z", Quaternion::z,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())
        )
    );
  }

  private static RecordValueMapper<Attitude> createAttitudeMapper() {
    return new RecordValueMapper<>(
        Attitude.class,
        List.of(
            new RecordValueMapper.Component<>("quaternion", Attitude::quaternion, createQuaternionMapper()),
            new RecordValueMapper.Component<>("angularVelocity", Attitude::angularVelocity, createVector3DMapper())
        )
    );
  }

  private static RecordValueMapper<Ephemeris> createEphemerisMapper() {
    return new RecordValueMapper<>(
        Ephemeris.class,
        List.of(
            new RecordValueMapper.Component<>("timestamp", Ephemeris::timestamp,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("position", Ephemeris::position, createVector3DMapper()),
            new RecordValueMapper.Component<>("velocity", Ephemeris::velocity, createVector3DMapper()),
            new RecordValueMapper.Component<>("frame", Ephemeris::frame,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())
        )
    );
  }

  private static RecordValueMapper<TelemetryPoint> createTelemetryPointMapper() {
    return new RecordValueMapper<>(
        TelemetryPoint.class,
        List.of(
            new RecordValueMapper.Component<>("timestamp", TelemetryPoint::timestamp,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("channel", TelemetryPoint::channel,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("value", TelemetryPoint::value,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("unit", TelemetryPoint::unit,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("status", TelemetryPoint::status,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())
        )
    );
  }

  private static RecordValueMapper<ComplexActivityState> createComplexActivityStateMapper() {
    return new RecordValueMapper<>(
        ComplexActivityState.class,
        List.of(
            new RecordValueMapper.Component<>("activityId", ComplexActivityState::activityId,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("status", ComplexActivityState::status,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("arguments", ComplexActivityState::arguments,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())),
            new RecordValueMapper.Component<>("metrics", ComplexActivityState::metrics,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(),
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())),
            new RecordValueMapper.Component<>("telemetry", ComplexActivityState::telemetry,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(createTelemetryPointMapper())),
            new RecordValueMapper.Component<>("attitude", ComplexActivityState::attitude, createAttitudeMapper()),
            new RecordValueMapper.Component<>("ephemeris", ComplexActivityState::ephemeris, createEphemerisMapper())
        )
    );
  }

  private static RecordValueMapper<LargeRecord> createLargeRecordMapper() {
    return new RecordValueMapper<>(
        LargeRecord.class,
        List.of(
            new RecordValueMapper.Component<>("field1", LargeRecord::field1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field2", LargeRecord::field2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field3", LargeRecord::field3,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field4", LargeRecord::field4,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field5", LargeRecord::field5,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("value1", LargeRecord::value1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value2", LargeRecord::value2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value3", LargeRecord::value3,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value4", LargeRecord::value4,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value5", LargeRecord::value5,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("timestamp1", LargeRecord::timestamp1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("timestamp2", LargeRecord::timestamp2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("timestamp3", LargeRecord::timestamp3,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("flag1", LargeRecord::flag1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper()),
            new RecordValueMapper.Component<>("flag2", LargeRecord::flag2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper()),
            new RecordValueMapper.Component<>("stringList", LargeRecord::stringList,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())),
            new RecordValueMapper.Component<>("doubleList", LargeRecord::doubleList,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())),
            new RecordValueMapper.Component<>("stringMap", LargeRecord::stringMap,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(),
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())),
            new RecordValueMapper.Component<>("vector", LargeRecord::vector, createVector3DMapper()),
            new RecordValueMapper.Component<>("quaternion", LargeRecord::quaternion, createQuaternionMapper())
        )
    );
  }
}
