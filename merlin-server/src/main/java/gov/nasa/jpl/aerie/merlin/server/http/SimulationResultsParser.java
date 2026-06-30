package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.UnfinishedActivity;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.types.Timestamp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *   - activity durations/startOffsets as strings under "duration"/"startOffset"
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
                  profiles -> tuple(List.of(), List.of())))
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
                  spans -> tuple(List.of(), List.of())))
          .optionalField("simulationArguments", mapP(serializedValueP))
          .map(
              untuple((startTime, endTime, profiles, spans, simArgs) -> {
                final var duration = Duration.of(startTime.microsUntil(endTime), Duration.MICROSECONDS);
                return new SimulationResults(
                    profiles.getKey(),
                    profiles.getValue(),
                    spans.getKey(),
                    spans.getValue(),
                    startTime.toInstant(),
                    duration,
                    List.of(),
                    Map.of(),
                    simArgs.orElse(Map.of()));
              }),
              results -> tuple(
                  new Timestamp(results.startTime),
                  new Timestamp(results.startTime).plusMicros(results.duration.in(Duration.MICROSECONDS)),
                  Map.entry(Map.of(), Map.of()),
                  Map.entry(Map.of(), Map.of()),
                  Optional.ofNullable(results.simulationArguments.isEmpty() ? null : results.simulationArguments)));
}
