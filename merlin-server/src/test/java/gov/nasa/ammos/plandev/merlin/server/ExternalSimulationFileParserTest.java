package gov.nasa.ammos.plandev.merlin.server;

import gov.nasa.ammos.plandev.merlin.driver.engine.ProfileSegment;
import gov.nasa.ammos.plandev.merlin.protocol.types.RealDynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.PostgresProfileStreamer;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.PostgresSpanStreamer;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.SpanRecord;
import gov.nasa.ammos.plandev.types.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSimulationFileParserTest {

  /**
   * Stub implementation of PostgresSpanStreamer for testing.
   * Captures all accepted spans instead of writing to database.
   */
  private static class StubSpanStreamer extends PostgresSpanStreamer {
    public final List<SpanCapture> capturedSpans = new ArrayList<>();
    private boolean closed = false;

    public StubSpanStreamer() {
      super(null, 1L, Timestamp.fromString("2024-001T00:00:00"));
    }

    @Override
    public void accept(long spanId, SpanRecord span) {
      if (closed) throw new IllegalStateException("Streamer is closed");
      capturedSpans.add(new SpanCapture(spanId, span));
    }

    @Override
    public void close() {
      closed = true;
    }

    public record SpanCapture(long spanId, SpanRecord span) {}
  }

  /**
   * Stub implementation of PostgresProfileStreamer for testing.
   * Captures all accepted profile segments instead of writing to database.
   */
  private static class StubProfileStreamer extends PostgresProfileStreamer {
    public final List<RealSegmentCapture> capturedRealSegments = new ArrayList<>();
    public final List<DiscreteSegmentCapture> capturedDiscreteSegments = new ArrayList<>();
    private boolean closed = false;

    public StubProfileStreamer() {
      super(null, 1L);
    }

    @Override
    public void acceptRealSegment(
        String profileName,
        ValueSchema schema,
        ProfileSegment<Optional<RealDynamics>> segment
    ) {
      if (closed) throw new IllegalStateException("Streamer is closed");
      capturedRealSegments.add(new RealSegmentCapture(profileName, schema, segment));
    }

    @Override
    public void acceptDiscreteSegment(
        String profileName,
        ValueSchema schema,
        ProfileSegment<Optional<SerializedValue>> segment
    ) {
      if (closed) throw new IllegalStateException("Streamer is closed");
      capturedDiscreteSegments.add(new DiscreteSegmentCapture(profileName, schema, segment));
    }

    @Override
    public void close() {
      closed = true;
    }

    public record RealSegmentCapture(
        String profileName,
        ValueSchema schema,
        ProfileSegment<Optional<RealDynamics>> segment
    ) {}

    public record DiscreteSegmentCapture(
        String profileName,
        ValueSchema schema,
        ProfileSegment<Optional<SerializedValue>> segment
    ) {}
  }

  /**
   * Testable subclass that overrides factory methods to return stub streamers.
   */
  private static class TestableParser extends ExternalSimulationFileParser {
    private final StubSpanStreamer spanStreamer;
    private final StubProfileStreamer profileStreamer;

    public TestableParser(StubSpanStreamer spanStreamer, StubProfileStreamer profileStreamer) {
      super(null); // No real connection needed
      this.spanStreamer = spanStreamer;
      this.profileStreamer = profileStreamer;
    }

    @Override
    protected PostgresSpanStreamer createSpanStreamer(long datasetId, Timestamp simulationStart) {
      return spanStreamer;
    }

    @Override
    protected PostgresProfileStreamer createProfileStreamer(long datasetId) {
      return profileStreamer;
    }
  }

  @Test
  void testParseValidFile() throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = Path.of("src/test/resources/simulation-files/valid-simulation.json");
    var datasetId = 1L;
    var simulationStart = Timestamp.fromString("2024-001T00:00:00");

    // Act
    parser.parse(testFile, datasetId, simulationStart);

    // Assert - Verify spans were captured
    assertEquals(2, spanStreamer.capturedSpans.size(), "Should capture 2 spans");

    var firstSpan = spanStreamer.capturedSpans.get(0);
    assertEquals(1, firstSpan.spanId());
    assertEquals("TestActivity", firstSpan.span().type());

    var secondSpan = spanStreamer.capturedSpans.get(1);
    assertEquals(2, secondSpan.spanId());
    assertEquals("ChildActivity", secondSpan.span().type());

    // Assert - Verify profiles were captured
    assertEquals(1, profileStreamer.capturedRealSegments.size(), "Should capture 1 real profile");
    assertEquals(2, profileStreamer.capturedDiscreteSegments.size(), "Should capture 2 discrete segments");

    var realProfile = profileStreamer.capturedRealSegments.get(0);
    assertEquals("battery_level", realProfile.profileName());

    var discreteProfile1 = profileStreamer.capturedDiscreteSegments.get(0);
    assertEquals("system_mode", discreteProfile1.profileName());
  }

  @Test
  void testParseEmptySpansAndProfiles(@TempDir Path tempDir) throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = tempDir.resolve("empty.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("{\"spans\": [], \"profiles\": {}}");
    }

    // Act
    parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"));

    // Assert
    assertTrue(spanStreamer.capturedSpans.isEmpty(), "Should have no spans");
    assertTrue(profileStreamer.capturedRealSegments.isEmpty(), "Should have no real profiles");
    assertTrue(profileStreamer.capturedDiscreteSegments.isEmpty(), "Should have no discrete profiles");
  }

  @Test
  void testParseInvalidKey() throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = Path.of("src/test/resources/simulation-files/invalid-key.json");

    // Act & Assert
    var exception = assertThrows(IllegalArgumentException.class, () ->
        parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );

    assertTrue(exception.getMessage().contains("Unexpected key"));
    assertTrue(exception.getMessage().contains("invalidKey"));
  }

  @Test
  void testParseInvalidJsonStructure(@TempDir Path tempDir) throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = tempDir.resolve("invalid-structure.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("[\"this is an array, not an object\"]");
    }

    // Act & Assert
    assertThrows(IllegalStateException.class, () ->
        parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );
  }

  @Test
  void testParseProfileSegmentsBeforeTypeAndSchema(@TempDir Path tempDir) throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = tempDir.resolve("segments-before-schema.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("""
          {
            "spans": [],
            "profiles": {
              "bad_profile": {
                "segments": []
              }
            }
          }
          """);
    }

    // Act & Assert
    var exception = assertThrows(IllegalStateException.class, () ->
        parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );

    assertTrue(exception.getMessage().contains("segments for profile"));
    assertTrue(exception.getMessage().contains("before type and schema"));
  }

  @Test
  void testParseOnlySpans(@TempDir Path tempDir) throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = tempDir.resolve("only-spans.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("""
          {
            "spans": [
              {
                "span_id": 1,
                "type": "TestActivity",
                "start_offset": 0,
                "arguments": {}
              }
            ]
          }
          """);
    }

    // Act
    parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"));

    // Assert
    assertEquals(1, spanStreamer.capturedSpans.size());
    assertTrue(profileStreamer.capturedRealSegments.isEmpty());
    assertTrue(profileStreamer.capturedDiscreteSegments.isEmpty());
  }

  @Test
  void testParseOnlyProfiles(@TempDir Path tempDir) throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = tempDir.resolve("only-profiles.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("""
          {
            "profiles": {
              "test_profile": {
                "type": "discrete",
                "schema": {
                  "type": "string"
                },
                "segments": [
                  {
                    "duration": 3600000000,
                    "dynamics": "TEST"
                  }
                ]
              }
            }
          }
          """);
    }

    // Act
    parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"));

    // Assert
    assertTrue(spanStreamer.capturedSpans.isEmpty());
    assertEquals(1, profileStreamer.capturedDiscreteSegments.size());
  }

  @Test
  void testParseMissingRequiredSpanFields(@TempDir Path tempDir) throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    // Test missing 'type' field
    var testFile = tempDir.resolve("missing-span-type.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("""
          {
            "spans": [
              {
                "span_id": 1,
                "start_offset": 0,
                "arguments": {}
              }
            ]
          }
          """);
    }

    // Act & Assert
    var exception = assertThrows(Exception.class, () ->
        parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );
    assertTrue(exception.getMessage().contains("type") || exception.getCause() != null);

    // Test missing 'span_id' field
    var testFile2 = tempDir.resolve("missing-span-id.json");
    try (var writer = new FileWriter(testFile2.toFile())) {
      writer.write("""
          {
            "spans": [
              {
                "type": "TestActivity",
                "start_offset": 0,
                "arguments": {}
              }
            ]
          }
          """);
    }

    exception = assertThrows(Exception.class, () ->
        parser.parse(testFile2, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );
    assertTrue(exception.getMessage().contains("span_id") || exception.getCause() != null);

    // Test missing 'start_offset' field
    var testFile3 = tempDir.resolve("missing-start-offset.json");
    try (var writer = new FileWriter(testFile3.toFile())) {
      writer.write("""
          {
            "spans": [
              {
                "span_id": 1,
                "type": "TestActivity",
                "arguments": {}
              }
            ]
          }
          """);
    }

    exception = assertThrows(Exception.class, () ->
        parser.parse(testFile3, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );
    assertTrue(exception.getMessage().contains("start_offset") || exception.getCause() != null);
  }

  @Test
  void testResourceCleanupOnException(@TempDir Path tempDir) throws Exception {
    // Arrange - Create a streamer that throws an exception
    var spanStreamer = new StubSpanStreamer() {
      @Override
      public void accept(long spanId, SpanRecord span) {
        super.accept(spanId, span);
        throw new RuntimeException("Simulated streamer error");
      }
    };
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    var testFile = tempDir.resolve("valid-span.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("""
          {
            "spans": [
              {
                "span_id": 1,
                "type": "TestActivity",
                "start_offset": 0,
                "arguments": {}
              }
            ]
          }
          """);
    }

    // Act & Assert - Verify exception is thrown
    assertThrows(RuntimeException.class, () ->
        parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );

    // Assert - Verify streamers were closed despite the exception
    // The stub streamers track their closed state, so attempting to accept after close will throw
    assertThrows(IllegalStateException.class, () ->
        spanStreamer.accept(1L, null)
    );
    assertThrows(IllegalStateException.class, () ->
        profileStreamer.acceptDiscreteSegment(null, null, null)
    );
  }

  @Test
  void testInvalidProfileTypeValue(@TempDir Path tempDir) throws Exception {
    // Arrange
    var spanStreamer = new StubSpanStreamer();
    var profileStreamer = new StubProfileStreamer();
    var parser = new TestableParser(spanStreamer, profileStreamer);

    // Test with completely invalid type
    var testFile = tempDir.resolve("invalid-profile-type.json");
    try (var writer = new FileWriter(testFile.toFile())) {
      writer.write("""
          {
            "profiles": {
              "test_profile": {
                "type": "continuous",
                "schema": {
                  "type": "string"
                },
                "segments": []
              }
            }
          }
          """);
    }

    // Act & Assert
    var exception = assertThrows(IllegalArgumentException.class, () ->
        parser.parse(testFile, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );
    assertTrue(exception.getMessage().contains("continuous") ||
               exception.getMessage().contains("ProfileType"));

    // Test with wrong case (valueOf is case-sensitive)
    var testFile2 = tempDir.resolve("wrong-case-profile-type.json");
    try (var writer = new FileWriter(testFile2.toFile())) {
      writer.write("""
          {
            "profiles": {
              "test_profile": {
                "type": "REAL",
                "schema": {
                  "type": "real"
                },
                "segments": []
              }
            }
          }
          """);
    }

    exception = assertThrows(IllegalArgumentException.class, () ->
        parser.parse(testFile2, 1L, Timestamp.fromString("2024-001T00:00:00"))
    );
    assertTrue(exception.getMessage().contains("REAL") ||
               exception.getMessage().contains("ProfileType"));
  }
}
