package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.StartOffsetReducer;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfiles;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.types.Plan;
import org.apache.commons.lang3.tuple.Pair;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

/**
 * Drives an external ("foreign") model backend over HTTP for a plan's Simulate request.
 * Sends the plan's directives + config to the backend, then feeds the returned resource profiles
 * into the {@link SimulationResourceManager} (so they persist through the normal streaming path)
 * and returns a {@link SimulationResults} whose spans persist through the normal succeedWith path.
 */
public final class ExternalSimulationBackend {
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  public static SimulationResults simulate(
      final String backendUrl,
      final Plan plan,
      final SimulationResourceManager resourceManager)
  {
    // Offsets and results are keyed to the SIMULATION start (matching the jar simulate path in
    // SimulationDriver), which may differ from the plan start.
    final Instant simStart = plan.simulationStartInstant();
    final Duration simDuration = plan.simulationDuration();
    final long simDurationUs = simDuration.in(Duration.MICROSECONDS);
    final var schedule = plan.activityDirectives();

    // Resolve anchor chains to absolute offsets exactly as SimulationDriver does for jar models.
    var resolved = new StartOffsetReducer(plan.duration(), schedule).compute();
    // A directive anchored to the END of another activity resolves under a non-null parent key: its start
    // depends on the parent's SIMULATED duration, which a stateless external backend cannot pre-compute.
    // Reject rather than silently misplacing it (previously all offsets were sent raw, ignoring anchors).
    final var endAnchored = resolved.entrySet().stream()
        .filter(e -> e.getKey() != null)
        .flatMap(e -> e.getValue().stream().map(p -> p.getKey().id()))
        .sorted().toList();
    if (!endAnchored.isEmpty()) {
      throw new RuntimeException(
          "External models do not support directives anchored to the end of another activity "
          + "(their start depends on a simulated duration). Offending directive id(s): " + endAnchored);
    }
    // Shift plan-relative offsets to be simulation-start-relative, then drop anything before sim start.
    if (!resolved.isEmpty()) {
      resolved.put(null, StartOffsetReducer.adjustStartOffset(resolved.get(null), plan.simulationOffset()));
    }
    resolved = StartOffsetReducer.filterOutNegativeStartOffset(resolved);

    // --- build request (offsets are now simulation-start-relative) ---
    final var directivesB = Json.createArrayBuilder();
    for (final var pair : resolved.getOrDefault(null, List.of())) {
      final var dir = schedule.get(pair.getKey());
      directivesB.add(Json.createObjectBuilder()
          .add("id", pair.getKey().id())
          .add("type", dir.serializedActivity().getTypeName())
          .add("startOffset", pair.getValue().in(Duration.MICROSECONDS))
          .add("arguments", serializedValueP.unparse(SerializedValue.of(dir.serializedActivity().getArguments()))));
    }
    final var config = plan.simulationConfiguration();
    final var requestBody = Json.createObjectBuilder()
        .add("planStart", simStart.toString())
        .add("duration", simDurationUs)
        .add("configuration", serializedValueP.unparse(SerializedValue.of(config)))
        .add("directives", directivesB)
        .build().toString();

    // --- call the backend ---
    final JsonObject response;
    try {
      final var httpResponse = HTTP.send(
          HttpRequest.newBuilder(URI.create(backendUrl))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      if (httpResponse.statusCode() / 100 != 2) {
        throw new RuntimeException("External backend returned HTTP " + httpResponse.statusCode() + ": " + httpResponse.body());
      }
      try (final var reader = Json.createReader(new StringReader(httpResponse.body()))) {
        response = reader.readObject();
      }
    } catch (final java.io.IOException | InterruptedException ex) {
      throw new RuntimeException("Failed to reach external simulation backend at " + backendUrl, ex);
    }

    // --- parse profiles ---
    final var realProfiles = new HashMap<String, ResourceProfile<RealDynamics>>();
    final var discreteProfiles = new HashMap<String, ResourceProfile<SerializedValue>>();
    // time -> updates, for streaming into the resource manager (monotonic)
    final var realUpdatesByTime = new TreeMap<Long, Map<String, Pair<ValueSchema, RealDynamics>>>();
    final var discreteUpdatesByTime = new TreeMap<Long, Map<String, Pair<ValueSchema, SerializedValue>>>();

    final var realIn = response.getJsonObject("realProfiles");
    if (realIn != null) for (final var name : realIn.keySet()) {
      final var prof = realIn.getJsonObject(name);
      final var schema = valueSchemaP.parse(prof.get("schema")).getSuccessOrThrow();
      final var segs = new ArrayList<ProfileSegment<RealDynamics>>();
      long offset = 0;
      for (final var segV : prof.getJsonArray("segments")) {
        final var seg = segV.asJsonObject();
        final long durUs = seg.getJsonNumber("duration").longValue();
        final var dyn = seg.getJsonObject("dynamics");
        final var rd = RealDynamics.linear(dyn.getJsonNumber("initial").doubleValue(), dyn.getJsonNumber("rate").doubleValue());
        segs.add(new ProfileSegment<>(Duration.of(durUs, Duration.MICROSECONDS), rd));
        realUpdatesByTime.computeIfAbsent(offset, k -> new HashMap<>()).put(name, Pair.of(schema, rd));
        offset += durUs;
      }
      realProfiles.put(name, ResourceProfile.of(schema, segs));
    }

    final var discreteIn = response.getJsonObject("discreteProfiles");
    if (discreteIn != null) for (final var name : discreteIn.keySet()) {
      final var prof = discreteIn.getJsonObject(name);
      final var schema = valueSchemaP.parse(prof.get("schema")).getSuccessOrThrow();
      final var segs = new ArrayList<ProfileSegment<SerializedValue>>();
      long offset = 0;
      for (final var segV : prof.getJsonArray("segments")) {
        final var seg = segV.asJsonObject();
        final long durUs = seg.getJsonNumber("duration").longValue();
        final var val = serializedValueP.parse(seg.get("dynamics")).getSuccessOrThrow();
        segs.add(new ProfileSegment<>(Duration.of(durUs, Duration.MICROSECONDS), val));
        discreteUpdatesByTime.computeIfAbsent(offset, k -> new HashMap<>()).put(name, Pair.of(schema, val));
        offset += durUs;
      }
      discreteProfiles.put(name, ResourceProfile.of(schema, segs));
    }

    // --- stream profiles into the resource manager at each (monotonic) change time, then flush ---
    final var allTimes = new java.util.TreeSet<Long>();
    allTimes.addAll(realUpdatesByTime.keySet());
    allTimes.addAll(discreteUpdatesByTime.keySet());
    for (final long t : allTimes) {
      resourceManager.acceptUpdates(
          Duration.of(t, Duration.MICROSECONDS),
          realUpdatesByTime.getOrDefault(t, Map.of()),
          discreteUpdatesByTime.getOrDefault(t, Map.of()));
    }
    final ResourceProfiles flushed = resourceManager.computeProfiles(simDuration); // flushes to the streamer

    // --- parse spans -> simulated activities ---
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>();
    final var childIds = new HashMap<Long, List<ActivityInstanceId>>();
    final var spansArr = response.getJsonArray("spans");
    if (spansArr != null) {
      // first pass: collect children per parent
      for (final var spanV : spansArr) {
        final var span = spanV.asJsonObject();
        if (!span.isNull("parentId") && span.containsKey("parentId")) {
          final long parent = span.getJsonNumber("parentId").longValue();
          childIds.computeIfAbsent(parent, k -> new ArrayList<>()).add(new ActivityInstanceId(span.getJsonNumber("spanId").longValue()));
        }
      }
      for (final var spanV : spansArr) {
        final var span = spanV.asJsonObject();
        final long spanId = span.getJsonNumber("spanId").longValue();
        final long startUs = span.getJsonNumber("startOffset").longValue();
        final long durUs = span.getJsonNumber("duration").longValue();
        final var args = serializedValueP.parse(span.get("arguments")).getSuccessOrThrow().asMap().orElse(Map.of());
        final ActivityInstanceId parentId =
            (span.containsKey("parentId") && !span.isNull("parentId"))
                ? new ActivityInstanceId(span.getJsonNumber("parentId").longValue()) : null;
        // A top-level span carrying the originating PlanDev directive id links back to it, so the
        // simulated_activity view (attributes->>'directiveId') ties the sim result to its directive.
        final Optional<ActivityDirectiveId> directiveId =
            (span.containsKey("directiveId") && !span.isNull("directiveId"))
                ? Optional.of(new ActivityDirectiveId(span.getJsonNumber("directiveId").longValue()))
                : Optional.empty();
        simulatedActivities.put(new ActivityInstanceId(spanId), new ActivityInstance(
            span.getString("type"),
            args,
            simStart.plus(java.time.Duration.of(startUs, java.time.temporal.ChronoUnit.MICROS)),
            Duration.of(durUs, Duration.MICROSECONDS),
            parentId,
            childIds.getOrDefault(spanId, List.of()),
            directiveId,
            SerializedValue.of(Map.of())));
      }
    }

    return new SimulationResults(
        flushed.realProfiles(),
        flushed.discreteProfiles(),
        simulatedActivities,
        Map.of(),          // unfinished activities
        simStart,
        simDuration,
        List.of(),         // topics
        Map.of());         // events
  }

  private ExternalSimulationBackend() {}
}
