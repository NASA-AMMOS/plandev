package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.UnfinishedActivity;
import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphFlattener;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;

import javax.json.JsonObject;
import javax.json.JsonValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.merlin.server.http.ResponseSerializers.serializeSimulationResultsForDownload;
import static gov.nasa.jpl.aerie.merlin.server.http.SimulationResultsParser.simulationResultsP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the JSON produced by the downloadSimulationDataset endpoint.
 *
 * The download format is the same one {@link SimulationResultsParser} consumes on upload,
 * so every fixture here is also checked to round-trip back into an equal {@link SimulationResults}.
 */
public final class DownloadSimulationDatasetSerializerTest {
  private static final Instant SIM_START = Instant.parse("2024-01-01T00:00:00Z");

  private static SimulationResults emptyResults() {
    return new SimulationResults(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        SIM_START,
        Duration.of(24, Duration.HOURS),
        List.of(),
        new TreeMap<>());
  }

  private static JsonObject serialize(final SimulationResults results) {
    return serializeSimulationResultsForDownload(results).asJsonObject();
  }

  /** Serialize, then parse back with the upload parser, asserting the results survive the trip. */
  private static void assertRoundTrips(final SimulationResults results) {
    final var parsed = simulationResultsP.parse(serialize(results)).getSuccessOrThrow();

    // Compared field by field so a failure points at the section that did not survive
    assertEquals(results.startTime, parsed.startTime, "startTime");
    assertEquals(results.duration, parsed.duration, "duration");
    assertEquals(results.realProfiles, parsed.realProfiles, "realProfiles");
    assertEquals(results.discreteProfiles, parsed.discreteProfiles, "discreteProfiles");
    assertEquals(results.simulatedActivities, parsed.simulatedActivities, "simulatedActivities");
    assertEquals(results.unfinishedActivities, parsed.unfinishedActivities, "unfinishedActivities");
    assertEquals(results.simulationArguments, parsed.simulationArguments, "simulationArguments");

    // The download format names topics rather than numbering them, and the parser synthesizes fresh
    // indices when reading them back, so topics and events are compared by topic name instead.
    assertEquals(topicSchemasByName(results), topicSchemasByName(parsed), "topics");
    assertEquals(describeEvents(results), describeEvents(parsed), "events");
  }

  private static Map<String, ValueSchema> topicSchemasByName(final SimulationResults results) {
    final var schemas = new LinkedHashMap<String, ValueSchema>();
    for (final var topic : results.topics) schemas.put(topic.getMiddle(), topic.getRight());
    return schemas;
  }

  /**
   * Describe every event as "offset/transaction/causalTime topic=value span", which captures the
   * event graph's shape and its topic association without depending on synthesized topic indices.
   */
  private static List<String> describeEvents(final SimulationResults results) {
    final var topicNames = new HashMap<Integer, String>();
    for (final var topic : results.topics) topicNames.put(topic.getLeft(), topic.getMiddle());

    final var descriptions = new ArrayList<String>();
    for (final var timeEntry : new TreeMap<>(results.events).entrySet()) {
      var transactionIndex = 0;
      for (final var graph : timeEntry.getValue()) {
        for (final var flatEvent : EventGraphFlattener.flatten(graph)) {
          final var event = flatEvent.getRight();
          descriptions.add("%s/%d/%s %s=%s %s".formatted(
              timeEntry.getKey(),
              transactionIndex,
              flatEvent.getLeft(),
              topicNames.getOrDefault(event.topicId(), "<unknown>"),
              event.value(),
              event.spanId()));
        }
        transactionIndex += 1;
      }
    }
    return descriptions;
  }

  @Test
  public void testSerializeEmptyResults() {
    final var json = serialize(emptyResults());

    assertEquals("2024-001T00:00:00", json.getString("simulationStartTime"));
    assertEquals("2024-002T00:00:00", json.getString("simulationEndTime"));

    final var profiles = json.getJsonObject("profiles");
    assertTrue(profiles.getJsonArray("realProfiles").isEmpty());
    assertTrue(profiles.getJsonArray("discreteProfiles").isEmpty());

    final var spans = json.getJsonObject("spans");
    assertTrue(spans.getJsonArray("simulatedActivities").isEmpty());
    assertTrue(spans.getJsonArray("unfinishedActivities").isEmpty());

    // Optional sections are omitted entirely when empty
    assertFalse(json.containsKey("simulationArguments"));
    assertFalse(json.containsKey("topics"));
    assertFalse(json.containsKey("events"));
  }

  @Test
  public void testSerializeEmptyResultsRoundTrips() {
    assertRoundTrips(emptyResults());
  }

  @Test
  public void testSerializeRealProfile() {
    final var results = new SimulationResults(
        Map.of("/battery", ResourceProfile.of(
            ValueSchema.REAL,
            List.of(
                new ProfileSegment<>(Duration.of(1, Duration.HOURS), RealDynamics.linear(100.0, -0.5)),
                new ProfileSegment<>(Duration.of(1, Duration.HOURS), RealDynamics.constant(99.5))))),
        Map.of(),
        Map.of(),
        Map.of(),
        SIM_START,
        Duration.of(2, Duration.HOURS),
        List.of(),
        new TreeMap<>());

    final var realProfiles = serialize(results).getJsonObject("profiles").getJsonArray("realProfiles");
    assertEquals(1, realProfiles.size());

    final var profile = realProfiles.getJsonObject(0);
    assertEquals("/battery", profile.getString("name"));
    assertEquals("real", profile.getJsonObject("schema").getString("type"));

    final var segments = profile.getJsonArray("segments");
    assertEquals(2, segments.size());
    assertEquals("+01:00:00.000000", segments.getJsonObject(0).getString("extent"));
    assertEquals(100.0, segments.getJsonObject(0).getJsonObject("dynamics").getJsonNumber("initial").doubleValue());
    assertEquals(-0.5, segments.getJsonObject(0).getJsonObject("dynamics").getJsonNumber("rate").doubleValue());
    assertEquals(0.0, segments.getJsonObject(1).getJsonObject("dynamics").getJsonNumber("rate").doubleValue());

    assertRoundTrips(results);
  }

  @Test
  public void testSerializeDiscreteProfile() {
    final var results = new SimulationResults(
        Map.of(),
        Map.of("/mode", ResourceProfile.of(
            ValueSchema.STRING,
            List.of(
                new ProfileSegment<>(Duration.of(30, Duration.MINUTES), SerializedValue.of("IDLE")),
                new ProfileSegment<>(Duration.of(30, Duration.MINUTES), SerializedValue.of("ACTIVE"))))),
        Map.of(),
        Map.of(),
        SIM_START,
        Duration.of(1, Duration.HOURS),
        List.of(),
        new TreeMap<>());

    final var discreteProfiles = serialize(results).getJsonObject("profiles").getJsonArray("discreteProfiles");
    assertEquals(1, discreteProfiles.size());

    final var profile = discreteProfiles.getJsonObject(0);
    assertEquals("/mode", profile.getString("name"));
    assertEquals("string", profile.getJsonObject("schema").getString("type"));

    final var segments = profile.getJsonArray("segments");
    assertEquals(2, segments.size());
    assertEquals("+00:30:00.000000", segments.getJsonObject(0).getString("extent"));
    assertEquals("IDLE", ((javax.json.JsonString) segments.getJsonObject(0).get("dynamics")).getString());
    assertEquals("ACTIVE", ((javax.json.JsonString) segments.getJsonObject(1).get("dynamics")).getString());

    assertRoundTrips(results);
  }

  @Test
  public void testSerializeSimulatedActivity() {
    final var results = new SimulationResults(
        Map.of(),
        Map.of(),
        Map.of(new ActivityInstanceId(1L), new ActivityInstance(
            "BiteBanana",
            Map.of("biteSize", SerializedValue.of(1.0)),
            SIM_START.plusSeconds(3600),
            Duration.of(30, Duration.MINUTES),
            null,
            List.of(new ActivityInstanceId(2L)),
            Optional.of(new ActivityDirectiveId(42L)),
            SerializedValue.of("done"))),
        Map.of(),
        SIM_START,
        Duration.of(24, Duration.HOURS),
        List.of(),
        new TreeMap<>());

    final var activities = serialize(results).getJsonObject("spans").getJsonArray("simulatedActivities");
    assertEquals(1, activities.size());

    final var activity = activities.getJsonObject(0);
    assertEquals(1, activity.getJsonNumber("id").longValue());
    assertEquals(42, activity.getJsonNumber("directiveId").longValue());
    assertEquals(JsonValue.NULL, activity.get("parentId"));
    assertEquals(1, activity.getJsonArray("childIds").size());
    assertEquals(2, activity.getJsonArray("childIds").getJsonNumber(0).longValue());
    assertEquals("BiteBanana", activity.getString("type"));
    assertEquals("+00:30:00.000000", activity.getString("duration"));
    assertEquals("2024-001T01:00:00", activity.getString("startTime"));
    assertTrue(activity.getJsonObject("arguments").containsKey("biteSize"));

    assertRoundTrips(results);
  }

  @Test
  public void testSerializeUnfinishedActivity() {
    final var results = new SimulationResults(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(new ActivityInstanceId(7L), new UnfinishedActivity(
            "GrowBanana",
            Map.of("growingDuration", SerializedValue.of(300)),
            SIM_START.plusSeconds(120),
            new ActivityInstanceId(3L),
            List.of(),
            Optional.empty())),
        SIM_START,
        Duration.of(24, Duration.HOURS),
        List.of(),
        new TreeMap<>());

    final var activities = serialize(results).getJsonObject("spans").getJsonArray("unfinishedActivities");
    assertEquals(1, activities.size());

    final var activity = activities.getJsonObject(0);
    assertEquals(7, activity.getJsonNumber("id").longValue());
    assertEquals(JsonValue.NULL, activity.get("directiveId"));
    assertEquals(3, activity.getJsonNumber("parentId").longValue());
    assertEquals("GrowBanana", activity.getString("type"));
    assertEquals("2024-001T00:02:00", activity.getString("startTime"));
    // An unfinished activity has no end, so it carries no duration
    assertFalse(activity.containsKey("duration"));

    assertRoundTrips(results);
  }

  @Test
  public void testSerializeSimulationArguments() {
    final var results = new SimulationResults(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        SIM_START,
        Duration.of(24, Duration.HOURS),
        List.of(),
        new TreeMap<>(),
        Map.of("initialPlantCount", SerializedValue.of(200)));

    final var simulationArguments = serialize(results).getJsonObject("simulationArguments");
    assertEquals(1, simulationArguments.size());
    assertEquals(200, simulationArguments.getJsonNumber("initialPlantCount").intValue());

    assertRoundTrips(results);
  }

  @Test
  public void testSerializeTopicsAndEvents() {
    final var topics = List.of(Triple.of(0, "MyTopic", ValueSchema.STRING));
    final var events = new TreeMap<Duration, List<EventGraph<EventRecord>>>();
    events.put(
        Duration.of(1, Duration.SECONDS),
        List.of(EventGraph.atom(new EventRecord(0, Optional.of(5L), SerializedValue.of("hello")))));

    final var results = new SimulationResults(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        SIM_START,
        Duration.of(24, Duration.HOURS),
        topics,
        events);

    final var json = serialize(results);

    final var serializedTopics = json.getJsonObject("topics");
    assertEquals(1, serializedTopics.size());
    assertEquals("string", serializedTopics.getJsonObject("MyTopic").getJsonObject("schema").getString("type"));

    final var serializedEvents = json.getJsonArray("events");
    assertEquals(1, serializedEvents.size());

    final var event = serializedEvents.getJsonObject(0);
    assertEquals("MyTopic", event.getString("topic"));
    // Event times are absolute in the download format, offset from the simulation start
    assertEquals("2024-001T00:00:01", event.getString("realTime"));
    assertEquals(0, event.getJsonNumber("transactionIndex").intValue());
    assertEquals(5, event.getJsonNumber("spanId").longValue());
    assertTrue(event.containsKey("causalTime"));

    assertRoundTrips(results);
  }

  @Test
  public void testSerializeConcurrentEventsInOneTransaction() {
    final var topics = List.of(
        Triple.of(0, "TopicA", ValueSchema.STRING),
        Triple.of(1, "TopicB", ValueSchema.INT));
    final var events = new TreeMap<Duration, List<EventGraph<EventRecord>>>();
    events.put(
        Duration.ZERO,
        List.of(EventGraph.concurrently(
            EventGraph.atom(new EventRecord(0, Optional.empty(), SerializedValue.of("a"))),
            EventGraph.atom(new EventRecord(1, Optional.empty(), SerializedValue.of(2))))));

    final var results = new SimulationResults(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        SIM_START,
        Duration.of(1, Duration.HOURS),
        topics,
        events);

    final var serializedEvents = serialize(results).getJsonArray("events");
    assertEquals(2, serializedEvents.size());
    // Concurrent events share a transaction, and are told apart by their causal time
    assertEquals(0, serializedEvents.getJsonObject(0).getJsonNumber("transactionIndex").intValue());
    assertEquals(0, serializedEvents.getJsonObject(1).getJsonNumber("transactionIndex").intValue());
    assertEquals(JsonValue.NULL, serializedEvents.getJsonObject(0).get("spanId"));
    assertFalse(
        serializedEvents.getJsonObject(0).getString("causalTime")
            .equals(serializedEvents.getJsonObject(1).getString("causalTime")),
        "Concurrent events should have distinct causal times");

    assertRoundTrips(results);
  }

  @Test
  public void testSerializeFullResultsRoundTrips() {
    final var topics = List.of(Triple.of(0, "MyTopic", ValueSchema.STRING));
    final var events = new TreeMap<Duration, List<EventGraph<EventRecord>>>();
    events.put(
        Duration.of(30, Duration.MINUTES),
        List.of(EventGraph.atom(new EventRecord(0, Optional.of(1L), SerializedValue.of("hello")))));

    final var results = new SimulationResults(
        Map.of("/battery", ResourceProfile.of(
            ValueSchema.REAL,
            List.of(new ProfileSegment<>(Duration.of(1, Duration.HOURS), RealDynamics.linear(100.0, -0.5))))),
        Map.of("/mode", ResourceProfile.of(
            ValueSchema.STRING,
            List.of(new ProfileSegment<>(Duration.of(1, Duration.HOURS), SerializedValue.of("IDLE"))))),
        Map.of(new ActivityInstanceId(1L), new ActivityInstance(
            "BiteBanana",
            Map.of("biteSize", SerializedValue.of(1.0)),
            SIM_START.plusSeconds(600),
            Duration.of(5, Duration.MINUTES),
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(42L)),
            SerializedValue.of("done"))),
        Map.of(new ActivityInstanceId(2L), new UnfinishedActivity(
            "GrowBanana",
            Map.of(),
            SIM_START.plusSeconds(900),
            null,
            List.of(),
            Optional.empty())),
        SIM_START,
        Duration.of(2, Duration.HOURS),
        topics,
        events,
        Map.of("initialPlantCount", SerializedValue.of(200)));

    assertRoundTrips(results);
  }
}
