package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.json.JsonParser;
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
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphUnflattener;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.json.BasicParsers.intP;
import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.longP;
import static gov.nasa.jpl.aerie.json.BasicParsers.mapP;
import static gov.nasa.jpl.aerie.json.BasicParsers.nullableP;
import static gov.nasa.jpl.aerie.json.BasicParsers.productP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.json.Uncurry.Function7;
import static gov.nasa.jpl.aerie.json.Uncurry.Function9;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.timestampP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;

/**
 * Parses the JSON format produced by {@link gov.nasa.jpl.aerie.orchestration.simulation.SimulationResultsWriter}
 * into a {@link SimulationResults} object.
 *
 * The writer serializes:
 *   - profile segment extents as strings ("HH:MM:SS.ssssss") under the key "extent"
 *   - activity durations as strings under "duration"
 *   - timestamps as DOY strings ("YYYY-DDDT...") under "startTime"/"simulationStartTime"
 *   - parentId / directiveId as JSON null when absent
 */
public final class SimulationResultsParser {

  /** Parse a Duration from its toString() representation ("HH:MM:SS.ssssss") */
  private static final JsonParser<Duration> durationStringP =
      stringP.map(Duration::fromString, Duration::toString);

  /** Profile segment with "extent" key (string duration) and required "dynamics" value */
  private static final JsonParser<ProfileSegment<RealDynamics>> realSegmentP =
      productP
          .field("extent", durationStringP)
          .field("dynamics", realDynamicsP)
          .map(
              untuple(ProfileSegment::new),
              seg -> tuple(seg.extent(), seg.dynamics()));

  private static final JsonParser<ProfileSegment<SerializedValue>> discreteSegmentP =
      productP
          .field("extent", durationStringP)
          .field("dynamics", serializedValueP)
          .map(
              untuple(ProfileSegment::new),
              seg -> tuple(seg.extent(), seg.dynamics()));

  /** Named real profile entry */
  private static final JsonParser<Map.Entry<String, ResourceProfile<RealDynamics>>> realProfileEntryP =
      productP
          .field("name", stringP)
          .field("schema", valueSchemaP)
          .field("segments", listP(realSegmentP))
          .map(
              untuple((name, schema, segs) -> Map.entry(name, ResourceProfile.of(schema, segs))),
              e -> tuple(e.getKey(), e.getValue().schema(), e.getValue().segments()));

  /** Named discrete profile entry */
  private static final JsonParser<Map.Entry<String, ResourceProfile<SerializedValue>>> discreteProfileEntryP =
      productP
          .field("name", stringP)
          .field("schema", valueSchemaP)
          .field("segments", listP(discreteSegmentP))
          .map(
              untuple((name, schema, segs) -> Map.entry(name, ResourceProfile.of(schema, segs))),
              e -> tuple(e.getKey(), e.getValue().schema(), e.getValue().segments()));

  private static Map.Entry<Long, ActivityInstance> makeSimActivity(
      Long id, Optional<Long> directiveId, Optional<Long> parentId,
      List<Long> childIds, String type,
      Duration duration, SerializedValue attributes,
      Map<String, SerializedValue> arguments, Timestamp startTime
  ) {
    final Long parentIdVal = parentId.orElse(null);
    final Long directiveIdVal = directiveId.orElse(null);
    return Map.entry(id, new ActivityInstance(
        type, arguments, startTime.toInstant(), duration,
        parentIdVal == null ? null : new ActivityInstanceId(parentIdVal),
        childIds.stream().map(ActivityInstanceId::new).toList(),
        Optional.ofNullable(directiveIdVal).map(ActivityDirectiveId::new),
        attributes));
  }

  private static Map.Entry<Long, UnfinishedActivity> makeUnfinishedActivity(
      Long id, Optional<Long> directiveId, Optional<Long> parentId,
      List<Long> childIds, String type,
      Map<String, SerializedValue> arguments, Timestamp startTime
  ) {
    final Long parentIdVal = parentId.orElse(null);
    final Long directiveIdVal = directiveId.orElse(null);
    return Map.entry(id, new UnfinishedActivity(
        type, arguments, startTime.toInstant(),
        parentIdVal == null ? null : new ActivityInstanceId(parentIdVal),
        childIds.stream().map(ActivityInstanceId::new).toList(),
        Optional.ofNullable(directiveIdVal).map(ActivityDirectiveId::new)));
  }

  /** Simulated activity with its id (for keying into the map) */
  private static final JsonParser<Map.Entry<Long, ActivityInstance>> indexedSimulatedActivityP =
      productP
          .field("id", longP)
          .field("directiveId", nullableP(longP))
          .field("parentId", nullableP(longP))
          .field("childIds", listP(longP))
          .field("type", stringP)
          .field("duration", durationStringP)
          .field("attributes", serializedValueP)
          .field("arguments", mapP(serializedValueP))
          .field("startTime", timestampP)
          .rest()
          .map(
              untuple((Function9<Map.Entry<Long, ActivityInstance>,
                  Long, Optional<Long>, Optional<Long>, List<Long>, String,
                  Duration, SerializedValue, Map<String, SerializedValue>, Timestamp>)
                  SimulationResultsParser::makeSimActivity),
              e -> tuple(
                  e.getKey(),
                  Optional.ofNullable(e.getValue().directiveId().map(ActivityDirectiveId::id).orElse(null)),
                  Optional.ofNullable(e.getValue().parentId() == null ? null : e.getValue().parentId().id()),
                  e.getValue().childIds().stream().map(ActivityInstanceId::id).toList(),
                  e.getValue().type(),
                  e.getValue().duration(),
                  e.getValue().computedAttributes(),
                  e.getValue().arguments(),
                  new Timestamp(e.getValue().start())));

  /** Unfinished activity with its id */
  private static final JsonParser<Map.Entry<Long, UnfinishedActivity>> indexedUnfinishedActivityP =
      productP
          .field("id", longP)
          .field("directiveId", nullableP(longP))
          .field("parentId", nullableP(longP))
          .field("childIds", listP(longP))
          .field("type", stringP)
          .field("arguments", mapP(serializedValueP))
          .field("startTime", timestampP)
          .rest()
          .map(
              untuple((Function7<Map.Entry<Long, UnfinishedActivity>,
                  Long, Optional<Long>, Optional<Long>, List<Long>, String,
                  Map<String, SerializedValue>, Timestamp>)
                  SimulationResultsParser::makeUnfinishedActivity),
              e -> tuple(
                  e.getKey(),
                  Optional.ofNullable(e.getValue().directiveId().map(ActivityDirectiveId::id).orElse(null)),
                  Optional.ofNullable(e.getValue().parentId() == null ? null : e.getValue().parentId().id()),
                  e.getValue().childIds().stream().map(ActivityInstanceId::id).toList(),
                  e.getValue().type(),
                  e.getValue().arguments(),
                  new Timestamp(e.getValue().start())));

  /** Parser for a single flat event object */
  private record FlatEvent(
      String causalTime, Timestamp realTime, int transactionIndex,
      SerializedValue value, String topic, Optional<Long> spanId
  ) {}

  private static final JsonParser<FlatEvent> flatEventP =
      productP
          .field("causalTime", stringP)
          .field("realTime", timestampP)
          .field("transactionIndex", intP)
          .field("value", serializedValueP)
          .field("topic", stringP)
          .field("spanId", nullableP(longP))
          .map(
              untuple((causal, real, txIdx, val, topic, span) ->
                  new FlatEvent(causal, real, txIdx, val, topic, span)),
              fe -> tuple(fe.causalTime, fe.realTime, fe.transactionIndex,
                          fe.value, fe.topic, fe.spanId));

  /**
   * Reconstruct topics and events from the parsed JSON structures.
   *
   * Topics come as a map of {name -> {schema}} and get converted to
   * List<Triple<Integer, String, ValueSchema>> with synthesized indices.
   *
   * Events come as a flat list, grouped by (realTime, transactionIndex),
   * then unflattened back into EventGraph<EventRecord> objects.
   */
  private static List<Triple<Integer, String, ValueSchema>> buildTopics(
      Map<String, ValueSchema> topicMap
  ) {
    // Synthesized indices are arbitrary — buildEvents resolves topics by name,
    // so the order here does not need to match the original simulation's topic ordering.
    final var topics = new ArrayList<Triple<Integer, String, ValueSchema>>();
    int idx = 0;
    for (final var entry : topicMap.entrySet()) {
      topics.add(Triple.of(idx++, entry.getKey(), entry.getValue()));
    }
    return topics;
  }

  private static Map<Duration, List<EventGraph<EventRecord>>> buildEvents(
      List<FlatEvent> flatEvents,
      List<Triple<Integer, String, ValueSchema>> topics,
      Timestamp simulationStart
  ) {
    // Build topic name -> index lookup
    final var topicIndexByName = new HashMap<String, Integer>();
    for (final var t : topics) {
      topicIndexByName.put(t.getMiddle(), t.getLeft());
    }

    // Group flat events by (realTime, transactionIndex)
    // Key: Duration offset from sim start; inner key: transactionIndex
    final SortedMap<Duration, Map<Integer, List<Pair<String, EventRecord>>>> grouped = new TreeMap<>();

    for (final var fe : flatEvents) {
      final var offset = Duration.of(
          simulationStart.microsUntil(fe.realTime), Duration.MICROSECONDS);
      final var topicIdx = topicIndexByName.getOrDefault(fe.topic, -1);
      final var record = new EventRecord(topicIdx, fe.spanId, fe.value);

      grouped
          .computeIfAbsent(offset, k -> new TreeMap<>())
          .computeIfAbsent(fe.transactionIndex, k -> new ArrayList<>())
          .add(Pair.of(fe.causalTime, record));
    }

    // Convert grouped events into EventGraph structures
    final var result = new TreeMap<Duration, List<EventGraph<EventRecord>>>();
    for (final var timeEntry : grouped.entrySet()) {
      final var duration = timeEntry.getKey();
      final var transactions = timeEntry.getValue();

      final var graphList = new ArrayList<EventGraph<EventRecord>>();
      // Iterate in transaction index order
      for (final var txEntry : transactions.entrySet()) {
        try {
          graphList.add(EventGraphUnflattener.unflatten(txEntry.getValue()));
        } catch (EventGraphUnflattener.InvalidTagException e) {
          throw new RuntimeException("Failed to reconstruct event graph from uploaded events", e);
        }
      }
      result.put(duration, graphList);
    }

    return result;
  }

  /** Top-level parser for a complete simulation results JSON blob. */
  public static final JsonParser<SimulationResults> simulationResultsP =
      productP
          .field("simulationStartTime", timestampP)
          .field("simulationEndTime", timestampP)
          .field("profiles", productP
              .field("realProfiles", listP(realProfileEntryP))
              .field("discreteProfiles", listP(discreteProfileEntryP))
              .map(
                  untuple((realList, discreteList) -> {
                    final var real = new HashMap<String, ResourceProfile<RealDynamics>>();
                    realList.forEach(e -> real.put(e.getKey(), e.getValue()));
                    final var discrete = new HashMap<String, ResourceProfile<SerializedValue>>();
                    discreteList.forEach(e -> discrete.put(e.getKey(), e.getValue()));
                    return Map.<Map<String, ResourceProfile<RealDynamics>>, Map<String, ResourceProfile<SerializedValue>>>entry(real, discrete);
                  }),
                  profiles -> { throw new UnsupportedOperationException("simulationResultsP is parse-only"); }))
          .field("spans", productP
              .field("simulatedActivities", listP(indexedSimulatedActivityP))
              .field("unfinishedActivities", listP(indexedUnfinishedActivityP))
              .map(
                  untuple((simList, unfinList) -> {
                    final var simMap = new HashMap<ActivityInstanceId, ActivityInstance>();
                    simList.forEach(e -> simMap.put(new ActivityInstanceId(e.getKey()), e.getValue()));
                    final var unfinMap = new HashMap<ActivityInstanceId, UnfinishedActivity>();
                    unfinList.forEach(e -> unfinMap.put(new ActivityInstanceId(e.getKey()), e.getValue()));
                    return Map.<Map<ActivityInstanceId, ActivityInstance>, Map<ActivityInstanceId, UnfinishedActivity>>entry(simMap, unfinMap);
                  }),
                  spans -> { throw new UnsupportedOperationException("simulationResultsP is parse-only"); }))
          .optionalField("simulationArguments", mapP(serializedValueP))
          .optionalField("topics", mapP(productP
              .field("schema", valueSchemaP)
              .map(untuple(schema -> schema), s -> tuple(s))))
          .optionalField("events", listP(flatEventP))
          .map(
              untuple((startTime, endTime, profiles, spans, simArgs, topicsOpt, eventsOpt) -> {
                final var duration = Duration.of(startTime.microsUntil(endTime), Duration.MICROSECONDS);

                final List<Triple<Integer, String, ValueSchema>> topics;
                final Map<Duration, List<EventGraph<EventRecord>>> events;

                if (topicsOpt.isPresent() && eventsOpt.isPresent()) {
                  topics = buildTopics(topicsOpt.get());
                  events = buildEvents(eventsOpt.get(), topics, startTime);
                } else {
                  topics = List.of();
                  events = Map.of();
                }

                return new SimulationResults(
                    profiles.getKey(),
                    profiles.getValue(),
                    spans.getKey(),
                    spans.getValue(),
                    startTime.toInstant(),
                    duration,
                    topics,
                    events,
                    simArgs.orElse(Map.of()));
              }),
              results -> { throw new UnsupportedOperationException(
                  "simulationResultsP is parse-only; use ResponseSerializers.serializeSimulationResultsForDownload() to serialize"); });
}
