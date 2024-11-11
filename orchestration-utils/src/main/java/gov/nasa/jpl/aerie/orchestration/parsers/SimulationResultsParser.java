package gov.nasa.jpl.aerie.orchestration.parsers;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.UnfinishedActivity;
import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphUnflattener;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;


// TODO: COMMENT THIS CLASS
public final class SimulationResultsParser {
  private SimulationResultsParser() {}

  public static SimulationResults parseSimulationResults(Path filePath) {
    try (final var fileReader = new FileReader(filePath.toString())) {
      final var parser = Json.createParser(fileReader);
      parser.next();
      return parseSimulationResults(parser.getObject());
    } catch (final FileNotFoundException e) {
      throw new RuntimeException("Specified simulation results JSON file does not exist: " + filePath);
    } catch (final Exception e) {
      throw new RuntimeException("Error while reading simulation results JSON file: " + filePath, e);
    }
  }

  public static SimulationResults parseSimulationResults(JsonObject results) {
    final var startTime = Instant.parse(results.getString("simulationStartTime"));
    final var endTime = Instant.parse(results.getString("simulationEndTime"));
    final var duration = Duration.microseconds(startTime.until(endTime, ChronoUnit.MICROS));

    final var profiles = results.getJsonObject("profiles");
    final var realProfiles = parseProfiles(profiles.getJsonArray("realProfiles"), realDynamicsP);
    final var discreteProfiles = parseProfiles(profiles.getJsonArray("discreteProfiles"), serializedValueP);

    final var spans = results.getJsonObject("spans");
    final var simulatedActivities = parseSimulatedActivities(spans.getJsonArray("simulatedActivities"));
    final var unfinishedActivities = parseUnfinishedActivities(spans.getJsonArray("unfinishedActivities"));

    final var topics = parseTopics(results.getJsonObject("topics"));
    final var events = parseEvents(results.getJsonArray("events"), topics);

    return new SimulationResults(
        realProfiles,
        discreteProfiles,
        simulatedActivities,
        unfinishedActivities,
        startTime,
        duration,
        topics,
        events
    );
  }

  private static <Dynamics> Map<String, ResourceProfile<Dynamics>> parseProfiles(
      final JsonArray profilesJSON,
      final JsonParser<Dynamics> segmentsParser
  ) {
    final Map<String, ResourceProfile<Dynamics>> parsedProfiles = new HashMap<>(profilesJSON.size());
    for (final var p : profilesJSON.getValuesAs(JsonValue::asJsonObject)) {
      final var segments = p.asJsonObject()
                             .getJsonArray("segments")
                             .getValuesAs(s -> new ProfileSegment<>(
                                 Duration.fromString(s.asJsonObject().getString("extent")),
                                 segmentsParser.parse(p.asJsonObject().getJsonObject("dynamics")).getSuccessOrThrow()
                             ));
      final var schema = valueSchemaP.parse(p.getJsonObject("schema")).getSuccessOrThrow();
      final var profile = ResourceProfile.of(schema, segments);
      parsedProfiles.put(p.getString("name"), profile);
    }
    return parsedProfiles;
  }

  private static Map<ActivityInstanceId, ActivityInstance> parseSimulatedActivities(final JsonArray simulatedActivitiesJson) {
    final Map<ActivityInstanceId, ActivityInstance> parsedActivities = new HashMap<>(simulatedActivitiesJson.size());
    for (final var s : simulatedActivitiesJson.getValuesAs(JsonValue::asJsonObject)) {
      parsedActivities.put(
          new ActivityInstanceId(s.getInt("id")),
          new ActivityInstance(
              s.getString("type"),
              activityArgumentsP.parse(s.getJsonObject("arguments")).getSuccessOrThrow(),
              Instant.parse(s.getString("start")),
              Duration.fromString(s.getString("duration")),
              new ActivityInstanceId(s.getInt("parentId")),
              s.getJsonArray("childIds")
               .getValuesAs(JsonNumber::intValue)
               .stream()
               .map(ActivityInstanceId::new)
               .toList(),
              s.isNull("directiveId")
                  ? Optional.empty()
                  : Optional.of(new ActivityDirectiveId(s.getInt("directiveId"))),
              serializedValueP.parse(s.getJsonObject("attributes")).getSuccessOrThrow())
      );
    }
    return parsedActivities;
  }

  private static Map<ActivityInstanceId, UnfinishedActivity> parseUnfinishedActivities(final JsonArray unfinishedActivitiesJson) {
    final Map<ActivityInstanceId, UnfinishedActivity> parsedActivities = new HashMap<>(unfinishedActivitiesJson.size());
    for (final var u : unfinishedActivitiesJson.getValuesAs(JsonValue::asJsonObject)) {
      parsedActivities.put(
          new ActivityInstanceId(u.getInt("id")),
          new UnfinishedActivity(
              u.getString("type"),
              activityArgumentsP.parse(u.getJsonObject("arguments")).getSuccessOrThrow(),
              Instant.parse(u.getString("start")),
              new ActivityInstanceId(u.getInt("parentId")),
              u.getJsonArray("childIds")
               .getValuesAs(JsonNumber::intValue)
               .stream()
               .map(ActivityInstanceId::new)
               .toList(),
              u.isNull("directiveId")
                  ? Optional.empty()
                  : Optional.of(new ActivityDirectiveId(u.getInt("directiveId"))))
      );
    }
    return parsedActivities;
  }

  private static SortedMap<Duration, List<EventGraph<EventRecord>>> parseEvents(final JsonArray eventsJSON, final List<Triple<Integer, String, ValueSchema>> topics) {
    final var events = new TreeMap<Duration, List<EventGraph<EventRecord>>>();

    // Build intermediate map
    final var transactionsByTime = new HashMap<Duration, SortedMap<Integer, List<Pair<String, EventRecord>>>>();

    for (final var e : eventsJSON.getValuesAs(JsonValue::asJsonObject)) {
      // Event Transaction data
      final var transactionIndex = e.getInt("transactionIndex");
      final var realTime = Duration.fromString(e.getString("realTime"));
      final var causalTime = e.getString("causalTime");

      // Event Record data
      final var topic = topics.stream().findFirst().filter( t -> t.getMiddle().equals(e.getString("topic"))).get();
      final Optional<Long> spanId = e.isNull("spanId") ? Optional.empty() : Optional.of(e.getJsonNumber("spanId").longValue());
      final var value = serializedValueP.parse(e.getJsonObject("value")).getSuccessOrThrow();

      final var event = new EventRecord(topic.getLeft(), spanId, value);

      transactionsByTime
          .computeIfAbsent(realTime, x -> new TreeMap<>())
          .computeIfAbsent(transactionIndex, x -> new ArrayList<>())
          .add(Pair.of(causalTime, event));
    }

    // Reconstruct Event Graph
    transactionsByTime.forEach((time, transactions) -> transactions.forEach(($, value) -> {
      try {
        events.computeIfAbsent(time, x -> new ArrayList<>())
              .add(EventGraphUnflattener.unflatten(value));
      } catch (final EventGraphUnflattener.InvalidTagException e) {
        throw new Error("Failed to unflatten EventGraph due to invalid tag at time point " + time, e);
      }
    }));

    return events;
  }

  private static List<Triple<Integer, String, ValueSchema>> parseTopics(final JsonObject topicsJson) {
    final var topics = new ArrayList<Triple<Integer, String, ValueSchema>>();
    int i = 1;
    for (final var key : topicsJson.keySet()) {
      final var t = topicsJson.getJsonObject(key);
      topics.add(Triple.of(i, key, valueSchemaP.parse(t.get("schema")).getSuccessOrThrow()));
      i++;
    }
    return topics;
  }
}


