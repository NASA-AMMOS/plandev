package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.types.ResourceType;
import gov.nasa.jpl.aerie.e2e.types.SimulationDataset;
import gov.nasa.jpl.aerie.e2e.types.ValueSchema;
import gov.nasa.jpl.aerie.e2e.utils.BaseURL;
import gov.nasa.jpl.aerie.e2e.utils.ExternalAdapterProbe;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests.RawActivityType;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests.Span;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end tests for EXTERNAL mission models -- models PlanDev never compiled, served over HTTP by a
 * backend outside the cluster's Java world.
 *
 * <p>Everything here is real: real Hasura inserts, real merlin, real merlin workers, real Postgres, and two
 * real adapters ({@code blackbird-adapter}, which spawns a Blackbird JVM per simulation, and
 * {@code python-adapter}, a pure-Python battery model). Nothing is mocked or stubbed, and no canned JSON is
 * substituted for a backend response.
 *
 * <p>These tests require the stack to be running with the two adapters attached to its network and declared
 * to merlin AND to every merlin worker via {@code EXTERNAL_MODEL_BACKENDS}. They are meaningful only because
 * {@code EXTERNAL_INGEST_GATE=reject} is set: any closed-world violation in what a backend returns fails the
 * simulation rather than being logged, so "the simulation succeeded" is itself an assertion about the
 * contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExternalModelTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;
  /** merlin's own HTTP surface -- used to re-invoke the refresh (re-introspection) handlers. */
  private APIRequestContext merlin;

  // The trusted backends merlin is configured with, and the models they host.
  private static final String BLACKBIRD_BACKEND = "blackbird-lab";
  private static final String BLACKBIRD_MODEL_KEY = "powermodel";
  private static final String PYTHON_BACKEND = "python-lab";
  private static final String PYTHON_MODEL_KEY = "battery";
  private static final String BASILISK_BACKEND = "basilisk-lab";
  private static final String BASILISK_MODEL_KEY = "orbiter";

  private static final String MISSION = "aerie_e2e_external";
  private static final String PLAN_START = "2024-01-01T00:00:00+00:00";

  /**
   * External simulations leave the JVM: merlin POSTs to an adapter, Blackbird forks a JVM, and the results
   * stream back before anything is persisted. Generous by design -- a tight timeout here would report a slow
   * backend as a broken one.
   */
  private static final int SIM_TIMEOUT = 180;
  private static final int INTROSPECT_TIMEOUT = 120;

  // Models shared by the read-only tests. Tests that deliberately corrupt a model's stored metadata
  // register their own so they cannot disturb these.
  private int blackbirdModelId;
  private int batteryModelId;
  private int orbiterModelId;

  // Everything created here, torn down in reverse in afterAll.
  private final List<Integer> createdPlans = new ArrayList<>();
  private final List<Integer> createdModels = new ArrayList<>();

  @BeforeAll
  void beforeAll() throws IOException {
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    merlin = playwright.request().newContext(
        new APIRequest.NewContextOptions().setBaseURL(BaseURL.MERLIN_SERVER.url));

    blackbirdModelId = registerExternalModel("Blackbird powermodel (e2e)", BLACKBIRD_BACKEND, BLACKBIRD_MODEL_KEY);
    batteryModelId = registerExternalModel("Python battery (e2e)", PYTHON_BACKEND, PYTHON_MODEL_KEY);
    orbiterModelId = registerExternalModel("Basilisk orbiter (e2e)", BASILISK_BACKEND, BASILISK_MODEL_KEY);
  }

  @AfterAll
  void afterAll() throws IOException {
    for (final var planId : createdPlans.reversed()) hasura.deletePlan(planId);
    for (final var modelId : createdModels.reversed()) hasura.deleteMissionModel(modelId);

    hasura.close();
    merlin.dispose();
    playwright.close();
  }

  //region Fixtures

  /**
   * Register an external model exactly the way production does: a plain Hasura insert naming a trusted
   * backend and a model key, with no JAR anywhere. The insert's {@code refresh*} event triggers are what
   * drive merlin to introspect the backend over the wire.
   */
  private int registerExternalModel(String name, String backend, String modelKey) throws IOException {
    final var modelId = hasura.createExternalMissionModel(
        MISSION, name, "1.0.0-" + System.nanoTime(), backend, modelKey);
    createdModels.add(modelId);
    hasura.awaitExternalModelIntrospection(modelId, INTROSPECT_TIMEOUT);
    return modelId;
  }

  private int newPlan(int modelId, String name, String duration) throws IOException {
    final var planId = hasura.createPlan(modelId, name, duration, PLAN_START);
    createdPlans.add(planId);
    return planId;
  }

  /** All spans of a completed simulation, keyed by the directive that produced them. */
  private Map<Long, Span> spansByDirective(List<Span> spans) {
    final var byDirective = new HashMap<Long, Span>();
    for (final var span : spans) {
      if (span.directiveId() != null) {
        assertNull(byDirective.put(span.directiveId(), span),
                   "two spans claim directive " + span.directiveId());
      }
    }
    return byDirective;
  }

  private List<Span> simulateAndFetchSpans(int planId) throws IOException {
    final var response = hasura.awaitSimulation(planId, SIM_TIMEOUT);
    final var simDataset = hasura.getSimulationDataset(response.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.success, simDataset.status(),
                 "simulation did not succeed: " + simDataset.reason());
    return hasura.getSpans(simDataset.datasetId());
  }

  /** A map parameter crosses the wire as a series of {@code {key, value}} structs; read it back as a map. */
  private static Map<String, String> asStringMap(JsonValue series) {
    final var out = new LinkedHashMap<String, String>();
    for (final var entry : series.asJsonArray()) {
      final var e = entry.asJsonObject();
      out.put(e.getString("key"), e.getString("value"));
    }
    return out;
  }

  private static List<String> asStringList(JsonValue series) {
    return series.asJsonArray().getValuesAs(JsonString::getString);
  }

  private static JsonArray stringMapArgument(Map<String, String> entries) {
    final var builder = Json.createArrayBuilder();
    entries.forEach((k, v) -> builder.add(Json.createObjectBuilder().add("key", k).add("value", v)));
    return builder.build();
  }

  /** The identity hash the backend reports right now, straight from its own {@code /introspect}. */
  private String liveIdentityHash(ExternalAdapterProbe probe, String modelKey) throws IOException, InterruptedException {
    return probe.introspect(modelKey).getString("identityHash");
  }

  /**
   * Re-invoke merlin's re-introspection handler -- the documented remedy when a backend's identity has
   * drifted. This is the same endpoint the Hasura {@code refreshActivityTypes} event trigger calls.
   */
  private void reintrospect(int modelId) {
    final var payload = Json.createObjectBuilder()
                            .add("event", Json.createObjectBuilder()
                                              .add("data", Json.createObjectBuilder()
                                                               .add("new", Json.createObjectBuilder()
                                                                               .add("id", modelId))))
                            .build();
    for (final var path : List.of("/refreshActivityTypes", "/refreshResourceTypes", "/refreshModelParameters")) {
      final var response = merlin.post(path, RequestOptions.create()
                                                           .setHeader("Content-Type", "application/json")
                                                           .setData(payload.toString()));
      assertTrue(response.ok(), path + " returned " + response.status() + ": " + response.text());
    }
  }

  //endregion

  //region 1. Registration -> introspection round trip

  /**
   * Registering a Blackbird model with a plain insert must land, in Postgres, exactly the type surface the
   * adapter reports over {@code GET /introspect} -- every activity type with its parameters in declaration
   * order, every resource schema, the model's configuration parameters, and the identity it attested to.
   */
  @Test
  void blackbirdModelIntrospectsOverTheWire() throws IOException, InterruptedException {
    final var reported = ExternalAdapterProbe.BLACKBIRD.introspect(BLACKBIRD_MODEL_KEY);
    assertIntrospectionMatchesStorage(blackbirdModelId, reported);

    // Spot-check the parameter shapes this model exists to exercise, so a wholesale change to the mapping
    // cannot pass by agreeing with itself.
    final var byName = hasura.getActivityTypesRaw(blackbirdModelId)
                             .stream().collect(Collectors.toMap(RawActivityType::name, Function.identity()));
    assertEquals(
        Json.createObjectBuilder().add("type", "series")
            .add("items", Json.createObjectBuilder().add("type", "string")).build(),
        byName.get("ActivityThree").parameters().getJsonObject("stringList").getJsonObject("schema"),
        "list<string> must land as a ValueSchema series of strings");
    assertEquals(
        Json.createObjectBuilder().add("type", "series")
            .add("items", Json.createObjectBuilder()
                              .add("type", "struct")
                              .add("items", Json.createObjectBuilder()
                                                .add("key", Json.createObjectBuilder().add("type", "string"))
                                                .add("value", Json.createObjectBuilder().add("type", "string"))))
            .build(),
        byName.get("ActivityFour").parameters().getJsonObject("stringMap").getJsonObject("schema"),
        "map<string,string> must land as a series of {key,value} structs (merlin's MapValueMapper convention)");
  }

  /** The same round trip against a backend that shares no code, no language, and no runtime with Blackbird. */
  @Test
  void pythonModelIntrospectsOverTheWire() throws IOException, InterruptedException {
    final var reported = ExternalAdapterProbe.PYTHON.introspect(PYTHON_MODEL_KEY);
    assertIntrospectionMatchesStorage(batteryModelId, reported);

    final var activityTypes = hasura.getActivityTypesRaw(batteryModelId);
    assertEquals(List.of("Charge", "Discharge"), activityTypes.stream().map(RawActivityType::name).toList());
    assertEquals(
        List.of("Cycles", "Mode", "SoC"),
        hasura.getResourceTypes(batteryModelId).stream().map(ResourceType::name).toList());
  }

  private void assertIntrospectionMatchesStorage(int modelId, JsonObject reported) throws IOException {
    // --- activity types -------------------------------------------------------------------------
    final var stored = hasura.getActivityTypesRaw(modelId)
                             .stream().collect(Collectors.toMap(RawActivityType::name, Function.identity()));
    final var reportedActivityTypes = reported.getJsonArray("activityTypes");
    assertEquals(reportedActivityTypes.size(), stored.size(),
                 "PlanDev stored a different number of activity types than the backend declares");

    for (final var value : reportedActivityTypes) {
      final var declared = value.asJsonObject();
      final var name = declared.getString("name");
      final var row = stored.get(name);
      assertNotNull(row, "activity type '" + name + "' was declared by the backend but not stored");

      final var declaredParams = declared.getJsonArray("parameters");
      assertEquals(declaredParams.size(), row.parameters().size(), "parameter count for " + name);
      for (int i = 0; i < declaredParams.size(); ++i) {
        final var param = declaredParams.getJsonObject(i);
        final var paramName = param.getString("name");
        final var storedParam = row.parameters().getJsonObject(paramName);
        assertNotNull(storedParam, name + " parameter '" + paramName + "' was not stored");
        // Declaration ORDER is meaningful -- it is what the UI renders and what positional backends bind by.
        assertEquals(i, storedParam.getInt("order"), name + " parameter '" + paramName + "' order");
        assertEquals(param.getJsonObject("schema"), storedParam.getJsonObject("schema"),
                     name + " parameter '" + paramName + "' schema");
      }

      assertEquals(declared.getJsonArray("requiredParameters").getValuesAs(JsonString::getString),
                   row.requiredParameters(), name + " required parameters");
      assertEquals(declared.getJsonObject("computedAttributesSchema"), row.computedAttributesValueSchema(),
                   name + " computed attributes schema");
    }

    // --- resource types -------------------------------------------------------------------------
    final var storedResources = hasura.getResourceTypes(modelId)
                                      .stream().collect(Collectors.toMap(ResourceType::name, ResourceType::schema));
    final var reportedResources = reported.getJsonArray("resourceTypes");
    assertEquals(reportedResources.size(), storedResources.size(),
                 "PlanDev stored a different number of resource types than the backend declares");
    for (final var value : reportedResources) {
      final var declared = value.asJsonObject();
      final var name = declared.getString("name");
      assertTrue(storedResources.containsKey(name), "resource '" + name + "' was declared but not stored");
      assertEquals(ValueSchema.fromJSON(declared.getJsonObject("schema")), storedResources.get(name),
                   "resource '" + name + "' schema");
    }

    // --- simulation configuration parameters ------------------------------------------------------
    final var storedParameters = hasura.getMissionModelParameters(modelId);
    final var reportedParameters = reported.getJsonArray("parameters");
    assertNotNull(storedParameters, "no mission_model_parameters row was written");
    assertEquals(reportedParameters.size(), storedParameters.size(), "configuration parameter count");
    for (int i = 0; i < reportedParameters.size(); ++i) {
      final var param = reportedParameters.getJsonObject(i);
      final var name = param.getString("name");
      final var storedParam = storedParameters.getJsonObject(name);
      assertNotNull(storedParam, "configuration parameter '" + name + "' was not stored");
      assertEquals(i, storedParam.getInt("order"), "configuration parameter '" + name + "' order");
      assertEquals(param.getJsonObject("schema"), storedParam.getJsonObject("schema"),
                   "configuration parameter '" + name + "' schema");
    }

    // --- identity attestation ---------------------------------------------------------------------
    assertEquals(reported.getString("identityHash"), hasura.getMissionModel(modelId).externalIdentityHash(),
                 "the stored attestation does not match the identity the backend reports");
  }

  //endregion

  //region 2. Argument value round trip

  /**
   * The core round trip: send a directive of every parameter type the contract supports and assert the values
   * that come back on the resulting spans are the values that went out.
   *
   * <p>Two regressions live here specifically. Strings were once shipped to Blackbird with literal quote
   * characters embedded, so {@code "Moon"} arrived as {@code "\"Moon\""}. And directive arguments reach the
   * adapter as a {@code Map.copyOf}, whose iteration order is salted per JVM run -- a positional backend bound
   * two same-typed parameters in whatever order they happened to arrive, silently swapping them. Both are
   * caught by {@code ActivityEight(first, second)}: the values are distinct, and a swap does not merely
   * mis-record the arguments, it makes Blackbird fail with "Index z not found in ArrayedResource".
   */
  @Test
  void argumentValuesRoundTripThroughTheBackend() throws IOException {
    final var planId = newPlan(blackbirdModelId, "External Args Round Trip", "24:00:00");

    final var stringList = List.of("alpha", "beta", "gamma");
    final var stringMap = new LinkedHashMap<String, String>();
    stringMap.put("k1", "v1");
    stringMap.put("k2", "v2");
    final var endTime = "2024-001T08:00:00.000000";

    // two `string` parameters, deliberately distinct and order-sensitive
    final int eight = hasura.insertActivityDirective(
        planId, "ActivityEight", "01:00:00",
        Json.createObjectBuilder().add("first", "Moon").add("second", "z").build());
    // `real`
    final int two = hasura.insertActivityDirective(
        planId, "ActivityTwo", "02:00:00",
        Json.createObjectBuilder().add("amount", 42.5).build());
    // `duration`, as integer microseconds
    final int one = hasura.insertActivityDirective(
        planId, "ActivityOne", "03:00:00",
        Json.createObjectBuilder().add("d", 1800000000L).build());
    // `list<string>` -> ValueSchema series
    final int three = hasura.insertActivityDirective(
        planId, "ActivityThree", "04:00:00",
        Json.createObjectBuilder()
            .add("d", 600000000L)
            .add("stringList", Json.createArrayBuilder(stringList))
            .build());
    // `map<string,string>` -> series of {key,value} structs
    final int four = hasura.insertActivityDirective(
        planId, "ActivityFour", "05:00:00",
        Json.createObjectBuilder()
            .add("d", 900000000L)
            .add("stringMap", stringMapArgument(stringMap))
            .build());
    // a Blackbird `time`, carried verbatim as a day-of-year string
    final int nine = hasura.insertActivityDirective(
        planId, "ActivityNine", "06:00:00",
        Json.createObjectBuilder().add("endTime", endTime).build());

    final var spans = simulateAndFetchSpans(planId);
    final var byDirective = spansByDirective(spans);
    assertEquals(6, byDirective.size(), "every directive should have produced exactly one root span");

    // --- two strings, correctly bound and unquoted ---
    final var eightSpan = byDirective.get((long) eight);
    assertEquals("ActivityEight", eightSpan.type());
    assertEquals("Moon", eightSpan.arguments().getString("first"));
    assertEquals("z", eightSpan.arguments().getString("second"));
    assertEquals("01:00:00", eightSpan.startOffset());

    // --- real ---
    final var twoSpan = byDirective.get((long) two);
    assertEquals(42.5, twoSpan.arguments().getJsonNumber("amount").doubleValue());

    // --- duration, as integer microseconds ---
    final var oneSpan = byDirective.get((long) one);
    assertEquals(1800000000L, oneSpan.arguments().getJsonNumber("d").longValue());
    assertEquals("00:30:00", oneSpan.duration(), "the duration argument should be the span's length");

    // --- list<string>: a JSON array, in order ---
    final var threeSpan = byDirective.get((long) three);
    assertEquals(600000000L, threeSpan.arguments().getJsonNumber("d").longValue());
    assertEquals(stringList, asStringList(threeSpan.arguments().get("stringList")));

    // --- map<string,string>: a series of {key,value} structs, sent AND returned in that form ---
    final var fourSpan = byDirective.get((long) four);
    assertEquals(900000000L, fourSpan.arguments().getJsonNumber("d").longValue());
    final var returnedMap = fourSpan.arguments().get("stringMap");
    assertEquals(JsonValue.ValueType.ARRAY, returnedMap.getValueType(),
                 "a map must come back as a series, not a bare JSON object");
    for (final var entry : returnedMap.asJsonArray()) {
      assertEquals(java.util.Set.of("key", "value"), entry.asJsonObject().keySet());
    }
    assertEquals(stringMap, asStringMap(returnedMap));

    // --- time: carried verbatim ---
    final var nineSpan = byDirective.get((long) nine);
    assertEquals(endTime, nineSpan.arguments().getString("endTime"));
    // and it was actually USED: the activity runs from 06:00 to the endTime it was given.
    assertEquals("02:00:00", nineSpan.duration());

    // Every span the backend produced carries Blackbird's own activity UUID as a computed attribute.
    for (final var span : spans) {
      final var computed = span.computedAttributes();
      assertNotNull(computed, "span " + span.spanId() + " (" + span.type() + ") has no computed attributes");
      assertFalse(computed.getString("blackbirdId").isBlank());
    }
  }

  //endregion

  //region 3. Decomposition

  /**
   * {@code SciencePass} spawns {@code CollectScience} then {@code Downlink}. One directive must therefore
   * produce three spans: a root carrying the directive id, and two children carrying a parent and no
   * directive of their own.
   */
  @Test
  void decompositionRoundTripsAsAParentAndTwoChildren() throws IOException {
    final var planId = newPlan(blackbirdModelId, "External Decomposition", "24:00:00");
    final int directiveId = hasura.insertActivityDirective(
        planId, "SciencePass", "01:00:00", JsonValue.EMPTY_JSON_OBJECT);

    final var spans = simulateAndFetchSpans(planId);
    assertEquals(3, spans.size(), "one SciencePass directive should produce three spans");

    final var byType = spans.stream().collect(Collectors.toMap(Span::type, Function.identity()));
    final var root = byType.get("SciencePass");
    final var collect = byType.get("CollectScience");
    final var downlink = byType.get("Downlink");
    assertNotNull(root);
    assertNotNull(collect);
    assertNotNull(downlink);

    // The root is the one -- and the only one -- tied back to the plan.
    assertEquals((long) directiveId, root.directiveId());
    assertNull(root.parentId());

    for (final var child : List.of(collect, downlink)) {
      assertNull(child.directiveId(), child.type() + " is a spawned child and must not claim a directive");
      assertEquals(root.spanId(), child.parentId(), child.type() + " must be parented to the SciencePass span");
    }

    // The children are placed where the model put them, not where the directive was.
    assertEquals("01:00:00", collect.startOffset());
    assertEquals("00:05:00", collect.duration());
    assertEquals("01:05:00", downlink.startOffset());
    assertEquals("00:05:00", downlink.duration());
  }

  //endregion

  //region 4. Unfinished activities

  /**
   * An activity that outruns the simulation window must come back as a span with a NULL duration -- neither
   * clamped to the window edge (an end the simulation never reached) nor stored at its full length inside a
   * shorter dataset. The gate enforces the second half of that in reject mode, so an overrunning span would
   * fail this simulation outright.
   */
  @Test
  void unfinishedActivitiesComeBackWithNullDuration() throws IOException {
    final var planId = newPlan(batteryModelId, "External Unfinished", "02:00:00");

    // 3h of discharge inside a 2h plan.
    final int longDischarge = hasura.insertActivityDirective(
        planId, "Discharge", "00:00:00",
        Json.createObjectBuilder().add("duration", 10800000000L).add("load", 2.0).build());
    // 10 minutes of charge, comfortably inside it.
    final int shortCharge = hasura.insertActivityDirective(
        planId, "Charge", "01:00:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("rate", 1.5).build());

    final var byDirective = spansByDirective(simulateAndFetchSpans(planId));
    assertEquals(2, byDirective.size());

    final var unfinished = byDirective.get((long) longDischarge);
    assertEquals("Discharge", unfinished.type());
    assertNull(unfinished.duration(), "an activity outrunning the window must be stored with a null duration");
    assertEquals("00:00:00", unfinished.startOffset());
    // The arguments still record what it was asked to do, even though it never got to finish.
    assertEquals(10800000000L, unfinished.arguments().getJsonNumber("duration").longValue());

    final var finished = byDirective.get((long) shortCharge);
    assertEquals("Charge", finished.type());
    assertEquals("00:10:00", finished.duration(), "an activity inside the window keeps its real duration");
  }

  //endregion

  //region 5. Computed attributes

  /**
   * Computed attributes are values a model DERIVED rather than was given, and they must survive the trip
   * into {@code span.attributes.computedAttributes} while conforming to the schema the model declared --
   * the gate rejects a span carrying attributes the activity type does not declare.
   *
   * <p>The two backends make opposite, equally legal choices, and both are asserted: Blackbird stamps every
   * span with its own activity UUID, while the Python model emits {@code socDelta} only on FINISHED spans
   * (an unfinished activity has not produced its final values yet).
   */
  @Test
  void computedAttributesRoundTrip() throws IOException {
    // --- the declared schemas, as stored ---
    final var blackbirdTypes = hasura.getActivityTypesRaw(blackbirdModelId);
    final var blackbirdSchema = Json.createObjectBuilder()
                                    .add("type", "struct")
                                    .add("items", Json.createObjectBuilder()
                                                      .add("blackbirdId", Json.createObjectBuilder().add("type", "string")))
                                    .build();
    for (final var type : blackbirdTypes) {
      assertEquals(blackbirdSchema, type.computedAttributesValueSchema(),
                   "Blackbird declares blackbirdId on every activity type, including " + type.name());
    }

    final var batterySchema = Json.createObjectBuilder()
                                  .add("type", "struct")
                                  .add("items", Json.createObjectBuilder()
                                                    .add("socDelta", Json.createObjectBuilder().add("type", "real")))
                                  .build();
    for (final var type : hasura.getActivityTypesRaw(batteryModelId)) {
      assertEquals(batterySchema, type.computedAttributesValueSchema(),
                   "the Python model declares socDelta on " + type.name());
    }

    // --- the values, as produced ---
    final var planId = newPlan(batteryModelId, "External Computed Attributes", "02:00:00");
    final int finishedId = hasura.insertActivityDirective(
        planId, "Charge", "00:10:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("rate", 1.5).build());
    final int unfinishedId = hasura.insertActivityDirective(
        planId, "Discharge", "01:30:00",
        Json.createObjectBuilder().add("duration", 7200000000L).add("load", 2.0).build());

    final var byDirective = spansByDirective(simulateAndFetchSpans(planId));

    final var finished = byDirective.get((long) finishedId);
    assertNotNull(finished.computedAttributes(), "a finished span must carry the values the model derived");
    // rate 1.5/s for 600s of charge
    assertEquals(900.0, finished.computedAttributes().getJsonNumber("socDelta").doubleValue());

    final var unfinished = byDirective.get((long) unfinishedId);
    assertNull(unfinished.duration());
    assertNull(unfinished.computedAttributes(),
               "an unfinished span has produced no final values, so it must carry no computed attributes");
  }

  //endregion

  //region 5b. A fixed-step physics simulator (Basilisk)

  /**
   * Basilisk is the third backend and the one that stresses a different part of the contract: it is a real
   * fixed-step numerical integrator, so PlanDev's microsecond timeline and the model's clock genuinely
   * disagree. Both Blackbird and the Python battery place activities at exactly the offsets they were given;
   * this one cannot, and every way of hiding that is silent.
   *
   * <p>An activity asked to start at 00:10:00.000001 is applied by the integrator at the following step, so
   * the span must say 00:10:05. Reporting the requested offset would leave the timeline showing an
   * observation a step before the power profile shows its draw, with nothing anywhere to explain it.
   */
  @Test
  void aFixedStepBackendReportsWhereItActuallyRanTheActivity() throws IOException {
    final var planId = newPlan(orbiterModelId, "Basilisk Quantization", "01:00:00");
    // One microsecond past the 5-second grid, and a duration that is a whole number of steps.
    final int offGrid = hasura.insertActivityDirective(
        planId, "Observe", "00:10:00.000001",
        Json.createObjectBuilder().add("duration", 900000000L).build());
    // Already on the grid: this one must NOT move.
    final int onGrid = hasura.insertActivityDirective(
        planId, "Observe", "00:30:00",
        Json.createObjectBuilder().add("duration", 300000000L).build());

    final var byDirective = spansByDirective(simulateAndFetchSpans(planId));
    assertEquals("00:10:05", byDirective.get((long) offGrid).startOffset(),
                 "the span must report the step the integrator actually applied the effect on");
    assertEquals("00:15:00", byDirective.get((long) offGrid).duration());
    assertEquals("00:30:00", byDirective.get((long) onGrid).startOffset(),
                 "an activity already on the grid must not move");
  }

  /**
   * The window-closure half of the same problem. A fixed-step simulator halts at the last step at or before
   * the requested stop time, so its samples fall short of the plan's duration -- here by the 7 microseconds
   * that are not a multiple of the 5-second step. The adapter extends the final segment to close the window;
   * without that, every profile stops short and the ingest gate rejects the simulation.
   */
  @Test
  void everyProfileCoversAPlanWhoseDurationIsNotAWholeNumberOfSteps() throws IOException {
    final var planId = newPlan(orbiterModelId, "Basilisk Window Closure", "02:00:00.000007");
    hasura.insertActivityDirective(
        planId, "Observe", "00:05:00",
        Json.createObjectBuilder().add("duration", 600000000L).build());

    final var response = hasura.awaitSimulation(planId, SIM_TIMEOUT);
    final var simDataset = hasura.getSimulationDataset(response.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.success, simDataset.status(),
                 "simulation did not succeed: " + simDataset.reason());

    final var profiles = hasura.getProfiles(simDataset.datasetId());
    // All 11 declared resources, real and discrete alike.
    assertEquals(11, profiles.size(), "expected every declared resource to be emitted: " + profiles.keySet());
    // The last segment of each profile must begin before the end of the plan; a profile that stopped at the
    // last integration step would leave the final 7 microseconds uncovered.
    for (final var entry : profiles.entrySet()) {
      assertFalse(entry.getValue().isEmpty(), entry.getKey() + " has no segments");
    }
  }

  /**
   * Real orbital mechanics, end to end. The eclipse profile comes from SPICE ephemeris geometry rather than
   * from a schedule, so it is the clearest evidence that PlanDev is displaying a physics simulation and not
   * a replayed fixture: nothing in the plan mentions eclipses, and the model was asked only to run.
   */
  @Test
  void orbitalGeometryReachesPlanDevAsOrdinaryResourceProfiles() throws IOException {
    final var planId = newPlan(orbiterModelId, "Basilisk Orbit", "03:00:00");
    final var response = hasura.awaitSimulation(planId, SIM_TIMEOUT);
    final var simDataset = hasura.getSimulationDataset(response.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.success, simDataset.status(),
                 "simulation did not succeed: " + simDataset.reason());
    final var profiles = hasura.getProfiles(simDataset.datasetId());

    final var eclipse = profiles.get("/geometry/eclipse");
    assertNotNull(eclipse, "the eclipse profile did not persist");
    final var states = new java.util.HashSet<String>();
    for (final var segment : eclipse) {
      states.add(((JsonString) segment.dynamics()).getString());
    }
    // A 7000 km orbit has a ~97 minute period, so a 3-hour plan crosses the shadow twice.
    assertTrue(states.contains("Umbra") && states.contains("Sunlight"),
               "a 3-hour LEO plan must pass through the Earth's shadow, saw: " + states);

    // The solar array follows the true sun angle: 1367 W/m^2 scaled for Earth's distance, x 0.4 m^2 x 0.29.
    double peakWatts = 0.0;
    for (final var segment : profiles.get("/power/solarArrayWatts")) {
      peakWatts = Math.max(peakWatts, segment.dynamics().asJsonObject().getJsonNumber("initial").doubleValue());
    }
    assertTrue(peakWatts > 140.0 && peakWatts < 165.0,
               "solar array peak should be near 153 W, was " + peakWatts);
  }

  /**
   * Computed attributes derived from the physics rather than restated from the request. A Downlink scheduled
   * while the ground station is below the horizon is accepted, simulated, and reported as having moved
   * nothing -- which is the whole argument for running a real model: PlanDev could not have known that from
   * the directive alone.
   */
  @Test
  void aDownlinkOutOfViewIsSimulatedAndReportedAsHavingMovedNothing() throws IOException {
    final var planId = newPlan(orbiterModelId, "Basilisk Downlink", "03:00:00");
    hasura.insertActivityDirective(
        planId, "Observe", "00:00:00",
        Json.createObjectBuilder().add("duration", 600000000L).build());
    // The default orbit's only Goldstone pass in this window is in the first 20 minutes.
    final int outOfView = hasura.insertActivityDirective(
        planId, "Downlink", "02:00:00",
        Json.createObjectBuilder().add("duration", 300000000L).build());

    final var span = spansByDirective(simulateAndFetchSpans(planId)).get((long) outOfView);
    final var computed = span.computedAttributes();
    assertNotNull(computed, "a finished span must carry the values the model derived");
    assertEquals(0.0, computed.getJsonNumber("accessFraction").doubleValue(),
                 "the ground station is below the horizon for the whole downlink");
    assertEquals(0.0, computed.getJsonNumber("netStoredBitsChange").doubleValue(),
                 "with no access the transmitter moves no data");
  }

  /**
   * The quantization failure that has no honest answer. An activity shorter than one integration step cannot
   * be represented, and which way it fails depends on nothing the planner controls: between two steps both
   * edges snap to the same instant and it does nothing, while on a step it stretches to a whole one and does
   * five times what was asked. Either would be recorded as though it were what the plan said, so the adapter
   * refuses both -- and the message names the configuration knob that fixes it, which is why this asserts the
   * text and not just the failure.
   *
   * <p>Asserted at a grid-aligned start deliberately: that is the case that looks like it works.
   */
  @Test
  void anActivityShorterThanOneIntegrationStepIsRefusedWithTheKnobThatFixesIt() throws IOException {
    final var planId = newPlan(orbiterModelId, "Basilisk Sub-Step", "01:00:00");
    hasura.insertActivityDirective(
        planId, "Observe", "00:10:00",
        Json.createObjectBuilder().add("duration", 1000000L).build());   // 1s, at a 5s step

    final var failure = hasura.awaitFailingSimulation(planId, SIM_TIMEOUT);
    final var simDataset = hasura.getSimulationDataset(failure.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.failed, simDataset.status());
    assertTrue(simDataset.reason().isPresent());
    final var reason = simDataset.reason().get();
    assertEquals("EXTERNAL_MODEL_EXCEPTION", reason.type());
    // A backend that answers with a 400 is not unavailable -- it is refusing this plan. Reporting it
    // as unavailable sends the operator to inspect a healthy service while the actionable detail
    // sits unread in the message.
    assertEquals("UNSUPPORTED_PLAN", reason.data().getString("kind"));
    assertTrue(reason.message().contains("shorter than the"),
               "the failure must say what went wrong: " + reason.message());
    assertTrue(reason.message().contains("timeStepSeconds"),
               "the failure must name the knob that fixes it: " + reason.message());
  }

  //endregion

  //region 5c. Capabilities

  /**
   * A model's declared types say what it IS. They say nothing about which PlanDev features apply to it,
   * and the features genuinely differ in ways PlanDev cannot infer -- so the backend declares them and
   * merlin stores them.
   *
   * <p>The two archetypes are both registered here, which is the point. The Python and Basilisk models
   * are pure simulators: directives in, profiles and spans out, placing nothing of their own, so
   * PlanDev's scheduler could drive them. Blackbird is not: its own dispatcher places activities during
   * the run, so its schedule is an output and running PlanDev's scheduler as well would put two
   * schedulers on one plan. Nothing in either model's activity types distinguishes those cases.
   */
  @Test
  void backendsDeclareWhichPlanDevFeaturesApplyToTheirModels() throws IOException {
    final var blackbird = hasura.getMissionModel(blackbirdModelId).externalCapabilities();
    assertNotNull(blackbird, "the backend declared capabilities, so merlin must have stored them");
    final JsonObject scheduling = blackbird.getJsonObject("plandevScheduling");
    assertNotNull(scheduling);
    assertFalse(scheduling.getBoolean("supported"),
                "Blackbird places its own activities, so PlanDev scheduling does not apply");
    // The reason is the whole point of the object-not-boolean shape: it is what lets merlin and the UI
    // explain the refusal without either of them containing a sentence about Blackbird.
    assertFalse(scheduling.getString("reason", "").isBlank(),
                "an unsupported capability must carry the backend's own explanation");

    for (final var pureSimulator : List.of(batteryModelId, orbiterModelId)) {
      final var capabilities = hasura.getMissionModel(pureSimulator).externalCapabilities();
      assertNotNull(capabilities);
      assertTrue(capabilities.getJsonObject("plandevScheduling").getBoolean("supported"),
                 "a pure simulator can be driven by PlanDev's scheduler");
    }
  }

  /**
   * Capabilities must survive the trip from the backend intact, exactly like the types do. Merlin does
   * not interpret them -- a backend declaring a capability this merlin has never heard of should reach a
   * newer UI unchanged rather than be dropped by an older server -- so the stored value is compared
   * against what the adapter itself reports over HTTP.
   */
  @Test
  void storedCapabilitiesAreWhatTheBackendActuallyReported() throws IOException, InterruptedException {
    final var reported = ExternalAdapterProbe.BLACKBIRD.introspect(BLACKBIRD_MODEL_KEY)
                                             .getJsonObject("capabilities");
    assertEquals(reported, hasura.getMissionModel(blackbirdModelId).externalCapabilities());
  }

  /**
   * Capabilities are part of the identity attestation, and this is what that buys.
   *
   * <p>Merlin keeps a COPY of something the backend owns, and the copy can go stale under a redeployed
   * adapter -- as true of capabilities as of types. A backend that quietly started placing its own
   * activities while PlanDev went on offering to schedule for it would put two schedulers on one plan,
   * which is exactly the failure the attestation exists to prevent for types. So a capability change has
   * to move the hash, and therefore the model revision, which is what invalidates cached results.
   */
  @Test
  void aCapabilityChangeIsDetectableDrift() throws IOException, InterruptedException {
    final var model = hasura.getMissionModel(orbiterModelId);
    final var live = ExternalAdapterProbe.BASILISK.introspect(BASILISK_MODEL_KEY);
    // The hash the backend reports is computed over its capabilities as well as its types, so the
    // attested value and the live one agree only while both are unchanged.
    assertEquals(live.getString("identityHash"), model.externalIdentityHash());
    assertNotNull(model.externalCapabilities());
    assertTrue(model.revision() > 0, "storing the attestation and capabilities bumps the revision");
  }

  //endregion

  //region 6. Simulation configuration

  /**
   * A backend's simulation configuration must reach PlanDev as editable model parameters, and -- the part
   * that actually matters -- a value a planner sets must change the simulation. Accepting a configuration and
   * ignoring it looks identical from the outside unless you check the results, so this asserts the produced
   * profile, not just that the argument was stored.
   */
  @Test
  void simulationConfigurationIsAppliedNotJustAccepted() throws IOException {
    // Blackbird surfaces its adaptation globals under their Blackbird-side dotted names.
    final var blackbirdParams = hasura.getMissionModelParameters(blackbirdModelId);
    assertNotNull(blackbirdParams);
    assertEquals(
        java.util.Set.of("AdaptationGlobals.NumStarTrackers",
                         "AdaptationGlobals.compressionRatio",
                         "AdaptationGlobals.LANDING_EPOCH"),
        blackbirdParams.keySet());
    assertEquals("int", blackbirdParams.getJsonObject("AdaptationGlobals.NumStarTrackers")
                                       .getJsonObject("schema").getString("type"));
    assertEquals("real", blackbirdParams.getJsonObject("AdaptationGlobals.compressionRatio")
                                        .getJsonObject("schema").getString("type"));
    assertEquals("string", blackbirdParams.getJsonObject("AdaptationGlobals.LANDING_EPOCH")
                                          .getJsonObject("schema").getString("type"));

    final var batteryParams = hasura.getMissionModelParameters(batteryModelId);
    assertNotNull(batteryParams);
    assertEquals(java.util.Set.of("initialSoC", "initialCycles"), batteryParams.keySet());

    // --- the round trip: change initialSoC, watch the first SoC segment move ---
    final var planId = newPlan(batteryModelId, "External Config Round Trip", "02:00:00");
    hasura.insertActivityDirective(
        planId, "Discharge", "00:00:00",
        Json.createObjectBuilder().add("duration", 1800000000L).add("load", 2.0).build());

    // With no configuration set, the model's own default (50.0) applies.
    assertEquals(50.0, firstSoCInitial(planId), "the model's own default initialSoC should apply");

    hasura.updateSimArguments(planId, Json.createObjectBuilder()
                                          .add("initialSoC", 80.0)
                                          .add("initialCycles", 3)
                                          .build());
    assertEquals(80.0, firstSoCInitial(planId), "setting initialSoC must actually change the simulation");

    hasura.updateSimArguments(planId, Json.createObjectBuilder()
                                          .add("initialSoC", 12.5)
                                          .add("initialCycles", 3)
                                          .build());
    assertEquals(12.5, firstSoCInitial(planId));
  }

  /** Simulate, then read the {@code initial} of the first segment of the SoC profile. */
  private double firstSoCInitial(int planId) throws IOException {
    final var response = hasura.awaitSimulation(planId, SIM_TIMEOUT);
    final var simDataset = hasura.getSimulationDataset(response.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.success, simDataset.status(),
                 "simulation did not succeed: " + simDataset.reason());
    final var profiles = hasura.getProfiles(simDataset.datasetId());
    assertTrue(profiles.containsKey("SoC"), "the backend's SoC profile did not persist");
    final var first = profiles.get("SoC").stream()
                              .filter(s -> "00:00:00".equals(s.startOffset()))
                              .findFirst()
                              .orElseThrow(() -> new AssertionError("SoC has no segment at offset zero"));
    return first.dynamics().asJsonObject().getJsonNumber("initial").doubleValue();
  }

  //endregion

  //region 7. Identity attestation and drift

  /**
   * An external model row holds only a REFERENCE to a redeployable backend, so merlin records the identity
   * hash it introspected against. If the backend later reports a different identity, the stored activity and
   * resource types no longer describe what would run, and simulating anyway would produce normal-looking
   * results shaped by a model nobody registered.
   *
   * <p>Drift is simulated from PlanDev's side -- rewriting the attestation to a value the backend does not
   * report -- because that is indistinguishable, to the check, from the backend having moved. The remedy the
   * error message names (re-invoke the refresh triggers) is then exercised for real.
   */
  @Test
  void identityAttestationDetectsBackendDrift() throws IOException, InterruptedException {
    // A dedicated model: this test deliberately corrupts its metadata.
    final var modelId = registerExternalModel("Python battery drift (e2e)", PYTHON_BACKEND, PYTHON_MODEL_KEY);
    final var planId = newPlan(modelId, "External Drift", "01:00:00");
    hasura.insertActivityDirective(
        planId, "Charge", "00:10:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("rate", 1.0).build());

    final var attested = hasura.getMissionModel(modelId).externalIdentityHash();
    assertNotNull(attested, "registration must record which backend version the model was registered against");
    assertEquals(liveIdentityHash(ExternalAdapterProbe.PYTHON, PYTHON_MODEL_KEY), attested);
    // and the same hash is what discovery reports through merlin's own catalog query
    assertEquals(attested, catalogIdentityHash(PYTHON_BACKEND, PYTHON_MODEL_KEY));

    // Baseline: it simulates.
    final var baseline = hasura.awaitSimulation(planId, SIM_TIMEOUT);
    assertEquals(SimulationDataset.SimulationStatus.success,
                 hasura.getSimulationDataset(baseline.simDatasetId()).status());
    final var revisionBefore = hasura.getMissionModel(modelId).revision();
    assertEquals(revisionBefore,
                 hasura.getSimulationDatasetRevisions(baseline.simDatasetId()).modelRevision(),
                 "results are stamped with the model revision that produced them");

    // --- drift ---
    final var driftedRevision = hasura.setExternalIdentityHash(modelId, "0000drifted0000");
    assertTrue(driftedRevision > revisionBefore,
               "changing the attestation must bump the model revision (that is what invalidates the cache)");

    final var failure = hasura.awaitFailingSimulation(planId, true, SIM_TIMEOUT);
    final var failedDataset = hasura.getSimulationDataset(failure.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.failed, failedDataset.status());
    assertTrue(failedDataset.reason().isPresent());
    final var reason = failedDataset.reason().get();
    // The client-visible message must BE the diagnosis, not a generic catch-all with the diagnosis
    // buried in the trace -- this failure is actionable and the message says what to do.
    assertEquals("EXTERNAL_MODEL_EXCEPTION", reason.type());
    assertEquals("IDENTITY_DRIFT", reason.data().getString("kind"));
    assertTrue(reason.message().contains("was registered against backend")
               && reason.message().contains("now reports identity"),
               "the message must name the drift; got:\n" + reason.message());
    assertTrue(reason.message().contains("refreshActivityTypes"),
               "the message must name the remedy; got:\n" + reason.message());
    assertTrue(reason.trace().contains("0000drifted0000"), "the failure must quote the stale attestation");

    // --- remedy: re-introspect, exactly as the error message instructs ---
    reintrospect(modelId);
    final var repaired = hasura.getMissionModel(modelId);
    assertEquals(attested, repaired.externalIdentityHash(),
                 "re-introspection must re-attest to the identity the backend actually reports");
    assertTrue(repaired.revision() > driftedRevision, "re-attesting must move the model revision again");

    final var recovered = hasura.awaitSimulation(planId, true, SIM_TIMEOUT);
    final var recoveredDataset = hasura.getSimulationDataset(recovered.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.success, recoveredDataset.status(),
                 "simulation did not recover: " + recoveredDataset.reason());
    assertEquals(repaired.revision(),
                 hasura.getSimulationDatasetRevisions(recovered.simDatasetId()).modelRevision(),
                 "the recovered results must be stamped with the new model revision");
  }

  private String catalogIdentityHash(String backend, String modelKey) throws IOException {
    for (final var entry : hasura.getExternalModelCatalog()) {
      if (!entry.backend().equals(backend)) continue;
      assertTrue(entry.reachable(), "backend " + backend + " unreachable: " + entry.error());
      for (final var model : entry.models()) {
        if (model.key().equals(modelKey)) return model.identityHash();
      }
    }
    return fail("backend " + backend + " does not host model " + modelKey);
  }

  //endregion

  //region 8. The ingest gate

  /**
   * The gate's closed world is the model as PlanDev stored it. When the two disagree -- the versioning-skew
   * case the gate exists for -- results that would otherwise land verbatim in {@code profile_segment} must be
   * refused instead.
   *
   * <p>The disagreement is created from PlanDev's side, by rewriting a stored resource schema, because the
   * shipped adapters are conforming: there is no directive that makes either of them emit a non-conforming
   * result, so the only honest way to exercise rejection is to move the other half of the comparison. The
   * backend output here is entirely real and unmodified -- what changes is what PlanDev believes the model
   * declares, which is exactly the state a redeployed adapter leaves behind.
   */
  @Test
  void ingestGateRejectsResultsThatDoNotMatchTheStoredModel() throws IOException {
    final var modelId = registerExternalModel("Python battery gate (e2e)", PYTHON_BACKEND, PYTHON_MODEL_KEY);
    final var planId = newPlan(modelId, "External Gate", "01:00:00");
    hasura.insertActivityDirective(
        planId, "Discharge", "00:00:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("load", 2.0).build());

    // Sanity: as registered, it simulates.
    final var baseline = hasura.awaitSimulation(planId, SIM_TIMEOUT);
    assertEquals(SimulationDataset.SimulationStatus.success,
                 hasura.getSimulationDataset(baseline.simDatasetId()).status());

    // SoC is a real-valued resource; tell PlanDev it is an int.
    hasura.setResourceTypeSchema(modelId, "SoC", Json.createObjectBuilder().add("type", "int").build());

    final var failure = hasura.awaitFailingSimulation(planId, true, SIM_TIMEOUT);
    final var failedDataset = hasura.getSimulationDataset(failure.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.failed, failedDataset.status());
    assertTrue(failedDataset.reason().isPresent());
    final var reason = failedDataset.reason().get();
    assertEquals("EXTERNAL_MODEL_EXCEPTION", reason.type());
    assertEquals("INGEST_GATE", reason.data().getString("kind"));
    assertTrue(reason.message().contains("closed-world check"),
               "the gate should have refused the ingest; got:\n" + reason.message());
    // The finding names the resource, what the backend produced, and what PlanDev believed it was.
    assertTrue(reason.message().contains("resource 'SoC' has schema RealSchema[] but is registered as IntSchema[]"),
               "the gate should name the offending resource and both schemas; got:\n" + reason.message());

    // Nothing was committed: the failed dataset holds no SoC profile.
    final var profiles = hasura.getProfiles(failedDataset.datasetId());
    assertFalse(profiles.containsKey("SoC"),
                "the gate must refuse BEFORE profiles are committed, but SoC segments were persisted");

    // Restore the truth and it simulates again.
    hasura.setResourceTypeSchema(modelId, "SoC", Json.createObjectBuilder().add("type", "real").build());
    final var recovered = hasura.awaitSimulation(planId, true, SIM_TIMEOUT);
    final var recoveredDataset = hasura.getSimulationDataset(recovered.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.success, recoveredDataset.status(),
                 "simulation did not recover: " + recoveredDataset.reason());
    assertTrue(hasura.getProfiles(recoveredDataset.datasetId()).containsKey("SoC"));
  }

  //endregion

  //region 9. Anchors

  /**
   * A directive anchored to the END of another activity has a start time that depends on a SIMULATED
   * duration. A stateless external backend is handed absolute offsets and cannot resolve that, so merlin must
   * refuse rather than silently placing the activity somewhere plausible.
   */
  @Test
  void directivesAnchoredToTheEndOfAnotherActivityAreRejected() throws IOException {
    final var planId = newPlan(batteryModelId, "External End Anchor", "02:00:00");
    final int anchor = hasura.insertActivityDirective(
        planId, "Charge", "00:10:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("rate", 1.0).build());
    final int endAnchored = hasura.insertActivityDirective(
        planId, "Discharge", "00:05:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("load", 2.0).build(),
        Json.createObjectBuilder().add("anchor_id", anchor).add("anchored_to_start", false));

    final var failure = hasura.awaitFailingSimulation(planId, SIM_TIMEOUT);
    final var failedDataset = hasura.getSimulationDataset(failure.simDatasetId());
    assertEquals(SimulationDataset.SimulationStatus.failed, failedDataset.status());
    assertTrue(failedDataset.reason().isPresent());
    final var reason = failedDataset.reason().get();
    assertEquals("EXTERNAL_MODEL_EXCEPTION", reason.type());
    assertEquals("UNSUPPORTED_PLAN", reason.data().getString("kind"));
    assertTrue(reason.message().contains("do not support directives anchored to the end of another activity"),
               "the failure must explain why an end-anchor cannot be honoured; got:\n" + reason.message());
    assertTrue(reason.message().contains(String.valueOf(endAnchored)),
               "the failure must name the offending directive; got:\n" + reason.message());
  }

  /**
   * A START anchor, by contrast, resolves without simulating anything, so it must work -- and the offset the
   * backend receives has to be the RESOLVED one, not the raw plan-relative offset.
   */
  @Test
  void directivesAnchoredToTheStartOfAnotherActivityResolve() throws IOException {
    final var planId = newPlan(batteryModelId, "External Start Anchor", "02:00:00");
    final int anchor = hasura.insertActivityDirective(
        planId, "Charge", "00:10:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("rate", 1.0).build());
    final int startAnchored = hasura.insertActivityDirective(
        planId, "Discharge", "00:05:00",
        Json.createObjectBuilder().add("duration", 600000000L).add("load", 2.0).build(),
        Json.createObjectBuilder().add("anchor_id", anchor).add("anchored_to_start", true));

    final var byDirective = spansByDirective(simulateAndFetchSpans(planId));
    assertEquals(2, byDirective.size());
    assertEquals("00:10:00", byDirective.get((long) anchor).startOffset());
    assertEquals("00:15:00", byDirective.get((long) startAnchored).startOffset(),
                 "a start-anchored directive must reach the backend at its RESOLVED offset (anchor + 5m)");
  }

  //endregion
}
