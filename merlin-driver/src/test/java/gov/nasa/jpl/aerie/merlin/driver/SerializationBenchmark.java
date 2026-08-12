package gov.nasa.jpl.aerie.merlin.driver;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

/**
 * Benchmark comparing the legacy serialization path (serializeValue -> writeTo)
 * against the optimized writeJson path for representative resource types.
 *
 * Run with: ./gradlew merlin-driver:test --tests '*SerializationBenchmark'
 */
class SerializationBenchmark {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();
  private static final int WARMUP = 2_000;
  private static final int ITERATIONS = 50_000;

  // --- Representative resource types ---

  record ThermalState(double temperature, double heaterPower, boolean heaterOn) {}
  record TelemetryPacket(String name, int sequenceNumber, List<Double> channels) {}

  private static final RecordValueMapper<ThermalState> THERMAL_MAPPER = new RecordValueMapper<>(
      ThermalState.class, List.of(
          new RecordValueMapper.Component<>("temperature", ThermalState::temperature, new DoubleValueMapper()),
          new RecordValueMapper.Component<>("heaterPower", ThermalState::heaterPower, new DoubleValueMapper()),
          new RecordValueMapper.Component<>("heaterOn", ThermalState::heaterOn, new BooleanValueMapper())
      ));

  private static final RecordValueMapper<TelemetryPacket> TELEMETRY_MAPPER = new RecordValueMapper<>(
      TelemetryPacket.class, List.of(
          new RecordValueMapper.Component<>("name", TelemetryPacket::name, new StringValueMapper()),
          new RecordValueMapper.Component<>("sequenceNumber", TelemetryPacket::sequenceNumber, new IntegerValueMapper()),
          new RecordValueMapper.Component<>("channels", TelemetryPacket::channels, new ListValueMapper<>(new DoubleValueMapper()))
      ));

  // --- Benchmark: legacy path (serializeValue -> toJsonString) ---

  private static <T> long benchmarkLegacyPath(ValueMapper<T> mapper, T value, int iterations) throws IOException {
    // Warmup
    for (int i = 0; i < WARMUP; i++) {
      final SerializedValue sv = mapper.serializeValue(value);
      sv.toJsonString();
    }

    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      final SerializedValue sv = mapper.serializeValue(value);
      sv.toJsonString();
    }
    return System.nanoTime() - start;
  }

  // --- Benchmark: optimized writeJson path ---

  private static <T> long benchmarkWriteJsonPath(ValueMapper<T> mapper, T value, int iterations) throws IOException {
    // Warmup
    for (int i = 0; i < WARMUP; i++) {
      final var sw = new StringWriter(256);
      try (final var gen = JSON_FACTORY.createGenerator(sw)) {
        mapper.writeJson(value, gen);
      }
    }

    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      final var sw = new StringWriter(256);
      try (final var gen = JSON_FACTORY.createGenerator(sw)) {
        mapper.writeJson(value, gen);
      }
    }
    return System.nanoTime() - start;
  }

  // --- Benchmark: legacy path writing to shared generator ---

  private static <T> long benchmarkLegacyToGenerator(ValueMapper<T> mapper, T value, int iterations) throws IOException {
    // Warmup
    for (int i = 0; i < WARMUP; i++) {
      final var sw = new StringWriter(256);
      try (final var gen = JSON_FACTORY.createGenerator(sw)) {
        mapper.serializeValue(value).writeTo(gen);
      }
    }

    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      final var sw = new StringWriter(256);
      try (final var gen = JSON_FACTORY.createGenerator(sw)) {
        mapper.serializeValue(value).writeTo(gen);
      }
    }
    return System.nanoTime() - start;
  }

  @Test
  void benchmark() throws IOException {
    System.out.println("\n=== Serialization Benchmark (" + ITERATIONS + " iterations, " + WARMUP + " warmup) ===\n");

    // Integer primitive
    benchmarkMapper("IntegerValueMapper", new IntegerValueMapper(), 42);

    // Double primitive
    benchmarkMapper("DoubleValueMapper", new DoubleValueMapper(), 3.14159);

    // Boolean primitive
    benchmarkMapper("BooleanValueMapper", new BooleanValueMapper(), true);

    // String primitive
    benchmarkMapper("StringValueMapper", new StringValueMapper(), "sensor_active");

    // 3-field record (thermal state)
    benchmarkMapper("ThermalState (3 fields)",
        THERMAL_MAPPER, new ThermalState(25.3, 10.0, true));

    // Record with list (telemetry packet, 5-element channel list)
    benchmarkMapper("TelemetryPacket (3 fields + 5-elem list)",
        TELEMETRY_MAPPER, new TelemetryPacket("pkt_001", 42, List.of(1.0, 2.5, 3.7, 4.2, 5.9)));

    System.out.println("=== End Benchmark ===\n");
  }

  private static <T> void benchmarkMapper(String name, ValueMapper<T> mapper, T value) throws IOException {
    final long legacyNs = benchmarkLegacyPath(mapper, value, ITERATIONS);
    final long serializeThenStreamNs = benchmarkLegacyToGenerator(mapper, value, ITERATIONS);
    final long writeJsonNs = benchmarkWriteJsonPath(mapper, value, ITERATIONS);

    final double legacyMs = legacyNs / 1_000_000.0;
    final double serStreamMs = serializeThenStreamNs / 1_000_000.0;
    final double writeJsonMs = writeJsonNs / 1_000_000.0;
    final double speedupVsLegacy = legacyMs / writeJsonMs;
    final double speedupVsSerStream = serStreamMs / writeJsonMs;

    System.out.printf("%-42s  legacy=%.1fms  ser+stream=%.1fms  writeJson=%.1fms  speedup=%.2fx (vs legacy) %.2fx (vs ser+stream)%n",
        name, legacyMs, serStreamMs, writeJsonMs, speedupVsLegacy, speedupVsSerStream);
  }
}
