package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.types.ActivityType;
import gov.nasa.jpl.aerie.e2e.types.ActivityType.Parameter;
import gov.nasa.jpl.aerie.e2e.types.ResourceType;
import gov.nasa.jpl.aerie.e2e.types.ValueSchema;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.e2e.types.ValueSchema.*;
import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MissionModelTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  // Cross-test Constants
  private int modelId;

  @BeforeAll
  void beforeAll() throws IOException, InterruptedException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);

    // Upload Mission Model
    // Done in BeforeAll rather than BeforeEach because all the tests
    // in this class are meant to be examining the same model
    try (final var gateway = new GatewayRequests(playwright)) {
      modelId = hasura.createMissionModel(
          gateway.uploadJarFile(),
          "Banananation (e2e tests)",
          "aerie_e2e_tests",
          "Mission Model Tests");
    }
  }

  @AfterAll
  void afterAll() throws IOException {
    // Delete Mission Model
    hasura.deleteMissionModel(modelId);

    // Cleanup Requests
    hasura.close();
    playwright.close();
  }

  /**
   * Create an alphabetized list of the resource types in Banananation
   */
  private ArrayList<ResourceType> expectedResourceTypesBanananation(){
    final var resourceTypes = new ArrayList<ResourceType>();
    resourceTypes.add(new ResourceType("/data/line_count", VALUE_SCHEMA_INT));
    resourceTypes.add(new ResourceType(
        "/flag",
        new ValueSchemaVariant(List.of(new Variant("A", "A"), new Variant("B", "B")))));
    resourceTypes.add(new ResourceType("/flag/conflicted", VALUE_SCHEMA_BOOLEAN));
    resourceTypes.add(new ResourceType(
        "/fruit",
        new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "bananas")).build()), new ValueSchemaStruct(Map.of("rate", VALUE_SCHEMA_REAL, "initial", VALUE_SCHEMA_REAL)))));
    resourceTypes.add(new ResourceType("/peel", new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "kg")).build()), VALUE_SCHEMA_REAL)));
    resourceTypes.add(new ResourceType("/plant", new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "count")).build()), VALUE_SCHEMA_INT)));
    resourceTypes.add(new ResourceType("/producer", VALUE_SCHEMA_STRING));
    return resourceTypes;
  }

  /**
   * Create an alphabetized list of the activity types in Banananation
   */
  private ArrayList<ActivityType> expectedActivityTypesBanananation() {
    final var activityTypes = new ArrayList<ActivityType>();
    activityTypes.add(new ActivityType(
        "BakeBananaBread",
        Map.of(
            "tbSugar", new Parameter(1, VALUE_SCHEMA_INT),
            "glutenFree", new Parameter(2, VALUE_SCHEMA_BOOLEAN),
            "temperature", new Parameter(0, VALUE_SCHEMA_REAL)),
        VALUE_SCHEMA_INT,
        "Prepare"));
    activityTypes.add(new ActivityType("BananaNap", Map.of()));
    activityTypes.add(new ActivityType(
        "BiteBanana",
        Map.of("biteSize", new Parameter(0, new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "m")).build(), "banannotation", Json.createObjectBuilder().add("value", Json.createValue("Specifies the size of bite to take")).build()), VALUE_SCHEMA_REAL))),
        new ValueSchemaMeta(Map.of("item_order", Json.createArrayBuilder().add("biteSizeWasBig").add("newFlag").build()), new ValueSchemaStruct(Map.of("biteSizeWasBig", VALUE_SCHEMA_BOOLEAN, "newFlag", new ValueSchemaVariant(List.of(new Variant("A", "A"), new Variant("B", "B")))))),
        "Eat"
    ));
    activityTypes.add(new ActivityType("ChangeProducer", Map.of("producer", new Parameter(0, VALUE_SCHEMA_STRING))));
    activityTypes.add(new ActivityType("child", Map.of("counter", new Parameter(0, VALUE_SCHEMA_INT))));
    activityTypes.add(new ActivityType("ControllableDurationActivity", Map.of("duration", new Parameter(0, VALUE_SCHEMA_DURATION))));
    activityTypes.add(new ActivityType("DecomposingSpawnChild", Map.of("counter", new Parameter(0, VALUE_SCHEMA_INT))));
    activityTypes.add(new ActivityType("DecomposingSpawnParent", Map.of("label", new Parameter(0, VALUE_SCHEMA_STRING))));
    activityTypes.add(new ActivityType(
        "DownloadBanana",
        Map.of("connection",
               new Parameter(0, new ValueSchemaVariant(List.of(
                   new Variant("DSL", "DSL"),
                   new Variant("FiberOptic", "FiberOptic"),
                   new Variant("DietaryFiberOptic", "DietaryFiberOptic")))))));
    activityTypes.add(new ActivityType(
        "DurationParameterActivity",
        Map.of("duration", new Parameter(0, VALUE_SCHEMA_DURATION)),
        new ValueSchemaMeta(
            Map.of("item_order", Json.createArrayBuilder().add("duration").add("durationInSeconds").build()),
            new ValueSchemaStruct(Map.of("duration", VALUE_SCHEMA_DURATION,
            "durationInSeconds", new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "s")).build()), VALUE_SCHEMA_REAL)))),
        null));
    activityTypes.add(new ActivityType("ExceptionActivity", Map.of("throwException", new Parameter(0, VALUE_SCHEMA_BOOLEAN))));
    activityTypes.add(new ActivityType("grandchild", Map.of("counter", new Parameter(0, VALUE_SCHEMA_INT))));
    activityTypes.add(new ActivityType("GrowBanana", Map.of(
        "quantity", new Parameter(0, VALUE_SCHEMA_INT),
        "growingDuration", new Parameter(1, VALUE_SCHEMA_DURATION))));
    activityTypes.add(new ActivityType("LineCount", Map.of("path", new Parameter(0, VALUE_SCHEMA_PATH))));
    final var allSubs = new String[] {"primitiveDouble",
                                      "primitiveFloat",
                                      "primitiveByte",
                                      "primitiveShort",
                                      "primitiveInt",
                                      "primitiveLong",
                                      "primitiveChar",
                                      "primitiveBoolean",
                                      "boxedDouble",
                                      "boxedFloat",
                                      "boxedByte",
                                      "boxedShort",
                                      "boxedInt",
                                      "boxedLong",
                                      "boxedChar",
                                      "boxedBoolean",
                                      "string",
                                      "doubleArray",
                                      "floatArray",
                                      "byteArray",
                                      "shortArray",
                                      "intArray",
                                      "longArray",
                                      "charArray",
                                      "booleanArray",
                                      "stringArray",
                                      "primDoubleArray",
                                      "primFloatArray",
                                      "primByteArray",
                                      "primShortArray",
                                      "primIntArray",
                                      "primLongArray",
                                      "primCharArray",
                                      "primBooleanArray",
                                      "doubleList",
                                      "floatList",
                                      "byteList",
                                      "shortList",
                                      "intList",
                                      "longList",
                                      "charList",
                                      "booleanList",
                                      "stringList",
                                      "doubleMap",
                                      "floatMap",
                                      "byteMap",
                                      "shortMap",
                                      "intMap",
                                      "longMap",
                                      "charMap",
                                      "booleanMap",
                                      "stringMap",
                                      "testDuration",
                                      "testEnum",
                                      "mappyBoi",
                                      "doublePrimIntArray",
                                      "intListArrayArray",
                                      "obnoxious",
                                      "nested",
                                      "genericParameter"};
    final var parameterTestItemOrder = Json.createArrayBuilder();
    for(final var param: allSubs){
      parameterTestItemOrder.add(param);
    }
    activityTypes.add(new ActivityType(
        "ParameterTest",
        //region ParameterTest Parameters
        Map.<String, Parameter>ofEntries(
            entry("intMap", new Parameter(47, new ValueSchemaSeries(new ValueSchemaStruct(Map.of("key", VALUE_SCHEMA_INT, "value", VALUE_SCHEMA_INT))))),
            // region record
            entry("record",
            new Parameter(
                58,
                new ValueSchemaMeta(Map.of("item_order", parameterTestItemOrder.build()), new ValueSchemaStruct(Map.<String, ValueSchema>ofEntries(
                    entry("intMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_INT,
                        "value", VALUE_SCHEMA_INT)))),
                    entry("nested", new ValueSchemaMeta(
                     Map.of( "item_order", Json.createArrayBuilder().add("a").add("b").build()),
                     new ValueSchemaStruct(Map.of(
                        "a", VALUE_SCHEMA_STRING,
                        "b", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                            "key", VALUE_SCHEMA_INT,
                            "value", VALUE_SCHEMA_STRING))))))),
                    entry("string", VALUE_SCHEMA_STRING),
                    entry("byteMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_INT,
                        "value", VALUE_SCHEMA_INT)))),
                    entry("charMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_STRING,
                        "value", VALUE_SCHEMA_STRING)))),
                    entry("intList", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("longMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_INT,
                        "value", VALUE_SCHEMA_INT)))),
                    entry("boxedInt", VALUE_SCHEMA_INT),
                    entry("byteList", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("charList", new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                    entry("floatMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_REAL,
                        "value", VALUE_SCHEMA_REAL)))),
                    entry("intArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("longList", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("mappyBoi", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_INT,
                        "value", new ValueSchemaSeries(VALUE_SCHEMA_STRING))))),
                    entry("shortMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_INT,
                        "value", VALUE_SCHEMA_INT)))),
                    entry("testEnum", new ValueSchemaVariant(List.of(
                        new Variant("A", "A"),
                        new Variant("B", "B"),
                        new Variant("C", "C")))),
                    entry("boxedByte", VALUE_SCHEMA_INT),
                    entry("boxedChar", VALUE_SCHEMA_STRING),
                    entry("boxedLong", VALUE_SCHEMA_INT),
                    entry("byteArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("charArray", new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                    entry("doubleMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_REAL,
                        "value", VALUE_SCHEMA_REAL)))),
                    entry("floatList", new ValueSchemaSeries(VALUE_SCHEMA_REAL)),
                    entry("longArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("obnoxious", new ValueSchemaSeries(new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                        "value", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                            "key", VALUE_SCHEMA_INT,
                            "value", new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_REAL)))))))))),
                    entry("shortList", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("stringMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_STRING,
                        "value", VALUE_SCHEMA_STRING)))),
                    entry("booleanMap", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_BOOLEAN,
                        "value", VALUE_SCHEMA_BOOLEAN)))),
                    entry("boxedFloat", VALUE_SCHEMA_REAL),
                    entry("boxedShort", VALUE_SCHEMA_INT),
                    entry("doubleList", new ValueSchemaSeries(VALUE_SCHEMA_REAL)),
                    entry("floatArray", new ValueSchemaSeries(VALUE_SCHEMA_REAL)),
                    entry("shortArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("stringList", new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                    entry("booleanList", new ValueSchemaSeries(VALUE_SCHEMA_BOOLEAN)),
                    entry("boxedDouble", VALUE_SCHEMA_REAL),
                    entry("doubleArray", new ValueSchemaSeries(VALUE_SCHEMA_REAL)),
                    entry("stringArray", new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                    entry("booleanArray", new ValueSchemaSeries(VALUE_SCHEMA_BOOLEAN)),
                    entry("boxedBoolean", VALUE_SCHEMA_BOOLEAN),
                    entry("primIntArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("primitiveInt", VALUE_SCHEMA_INT),
                    entry("testDuration", VALUE_SCHEMA_DURATION),
                    entry("primByteArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("primCharArray", new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                    entry("primLongArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("primitiveByte", VALUE_SCHEMA_INT),
                    entry("primitiveChar", VALUE_SCHEMA_STRING),
                    entry("primitiveLong", VALUE_SCHEMA_INT),
                    entry("primFloatArray", new ValueSchemaSeries(VALUE_SCHEMA_REAL)),
                    entry("primShortArray", new ValueSchemaSeries(VALUE_SCHEMA_INT)),
                    entry("primitiveFloat", VALUE_SCHEMA_REAL),
                    entry("primitiveShort", VALUE_SCHEMA_INT),
                    entry("primDoubleArray", new ValueSchemaSeries(VALUE_SCHEMA_REAL)),
                    entry("primitiveDouble", VALUE_SCHEMA_REAL),
                    entry("genericParameter", new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                    entry("primBooleanArray", new ValueSchemaSeries(VALUE_SCHEMA_BOOLEAN)),
                    entry("primitiveBoolean", VALUE_SCHEMA_BOOLEAN),
                    entry("intListArrayArray", new ValueSchemaSeries(new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_INT)))),
                    entry("doublePrimIntArray", new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_INT)))))))),
            //endregion record
            entry("string", new Parameter(16, VALUE_SCHEMA_STRING)),
            entry("byteMap", new Parameter(45,new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", VALUE_SCHEMA_INT,
                "value", VALUE_SCHEMA_INT))))),
            entry("charMap", new Parameter(49, new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", VALUE_SCHEMA_STRING,
                "value", VALUE_SCHEMA_STRING))))),
            entry("intList", new Parameter(38, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("longMap", new Parameter(48, new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", VALUE_SCHEMA_INT,
                "value", VALUE_SCHEMA_INT))))),
            entry("boxedInt", new Parameter(12, VALUE_SCHEMA_INT)),
            entry("byteList", new Parameter(36, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("charList", new Parameter(40, new ValueSchemaSeries(VALUE_SCHEMA_STRING))),
            entry("floatMap", new Parameter(44,new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", VALUE_SCHEMA_REAL,
                "value", VALUE_SCHEMA_REAL))))),
            entry("intArray", new Parameter(21, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("longList", new Parameter(39, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("mappyBoi", new Parameter(54,new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", VALUE_SCHEMA_INT,
                "value", new ValueSchemaSeries(VALUE_SCHEMA_STRING)))))),
            entry("shortMap", new Parameter(46, new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                        "key", VALUE_SCHEMA_INT,
                        "value", VALUE_SCHEMA_INT))))),
            entry("testEnum", new Parameter(53, new ValueSchemaVariant(List.of(
                new Variant("A", "A"),
                new Variant("B", "B"),
                new Variant("C", "C"))))),
            entry("boxedByte", new Parameter(10, VALUE_SCHEMA_INT)),
            entry("boxedChar", new Parameter(14, VALUE_SCHEMA_STRING)),
            entry("boxedLong", new Parameter(13, VALUE_SCHEMA_INT)),
            entry("byteArray", new Parameter(19,new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("charArray", new Parameter(23, new ValueSchemaSeries(VALUE_SCHEMA_STRING))),
            entry("doubleMap", new Parameter(43,new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "s")).build()), VALUE_SCHEMA_REAL),
                "value", new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "W")).build()), VALUE_SCHEMA_REAL)))))),
            entry("floatList", new Parameter(35, new ValueSchemaSeries(VALUE_SCHEMA_REAL))),
            entry("longArray", new Parameter(22, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("obnoxious", new Parameter(57, new ValueSchemaSeries(new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_STRING)),
                "value", new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                    "key", VALUE_SCHEMA_INT,
                    "value", new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_REAL))))))))))),
            entry("shortList", new Parameter(37, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("stringMap", new Parameter(51, new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", VALUE_SCHEMA_STRING,
                "value", VALUE_SCHEMA_STRING))))),
            entry("booleanMap", new Parameter(50, new ValueSchemaSeries(new ValueSchemaStruct(Map.of(
                "key", VALUE_SCHEMA_BOOLEAN,
                "value", VALUE_SCHEMA_BOOLEAN))))),
            entry("boxedFloat", new Parameter(9, VALUE_SCHEMA_REAL)),
            entry("boxedShort", new Parameter(11, VALUE_SCHEMA_INT)),
            entry("doubleList", new Parameter(34, new ValueSchemaSeries(new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "m")).build()), VALUE_SCHEMA_REAL)))),
            entry("floatArray", new Parameter(18, new ValueSchemaSeries(VALUE_SCHEMA_REAL))),
            entry("shortArray", new Parameter(20, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("stringList", new Parameter(42, new ValueSchemaSeries(VALUE_SCHEMA_STRING))),
            entry("booleanList", new Parameter(41, new ValueSchemaSeries(VALUE_SCHEMA_BOOLEAN))),
            entry("boxedDouble", new Parameter(8, VALUE_SCHEMA_REAL)),
            entry("doubleArray", new Parameter(17, new ValueSchemaSeries(VALUE_SCHEMA_REAL))),
            entry("stringArray", new Parameter(25, new ValueSchemaSeries(VALUE_SCHEMA_STRING))),
            entry("booleanArray", new Parameter(24, new ValueSchemaSeries(VALUE_SCHEMA_BOOLEAN))),
            entry("boxedBoolean", new Parameter(15, VALUE_SCHEMA_BOOLEAN)),
            entry("primIntArray", new Parameter(30, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("primitiveInt", new Parameter(4, VALUE_SCHEMA_INT)),
            entry("testDuration", new Parameter(52, VALUE_SCHEMA_DURATION)),
            entry("primByteArray", new Parameter(28, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("primCharArray", new Parameter(32, new ValueSchemaSeries(VALUE_SCHEMA_STRING))),
            entry("primLongArray", new Parameter(31, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("primitiveByte", new Parameter(2, VALUE_SCHEMA_INT)),
            entry("primitiveChar", new Parameter(6, VALUE_SCHEMA_STRING)),
            entry("primitiveLong", new Parameter(5, VALUE_SCHEMA_INT)),
            entry("primFloatArray", new Parameter(27, new ValueSchemaSeries(VALUE_SCHEMA_REAL))),
            entry("primShortArray", new Parameter(29, new ValueSchemaSeries(VALUE_SCHEMA_INT))),
            entry("primitiveFloat", new Parameter(1, VALUE_SCHEMA_REAL)),
            entry("primitiveShort", new Parameter(3, VALUE_SCHEMA_INT)),
            entry("primDoubleArray", new Parameter(26, new ValueSchemaSeries(VALUE_SCHEMA_REAL))),
            entry("primitiveDouble", new Parameter(0, VALUE_SCHEMA_REAL)),
            entry("primBooleanArray", new Parameter(33, new ValueSchemaSeries(VALUE_SCHEMA_BOOLEAN))),
            entry("primitiveBoolean", new Parameter(7, VALUE_SCHEMA_BOOLEAN)),
            entry("intListArrayArray", new Parameter(56, new ValueSchemaSeries(new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_INT))))),
            entry("doublePrimIntArray", new Parameter(55, new ValueSchemaSeries(new ValueSchemaSeries(VALUE_SCHEMA_INT)))))));
        //endregion
    activityTypes.add(new ActivityType("parent", Map.of("label", new Parameter(0, VALUE_SCHEMA_STRING))));
    activityTypes.add(new ActivityType(
        "PeelBanana",
        Map.of("peelDirection",
               new Parameter(0,
                   new ValueSchemaMeta(Map.of("unit", Json.createObjectBuilder(Map.of("value", "direction")).build()),
                   new ValueSchemaVariant(List.of(
                       new Variant("fromStem", "fromStem"),
                       new Variant("fromTip", "fromTip")))))),
        "Prepare"
    ));
    activityTypes.add(new ActivityType("PickBanana", Map.of("quantity", new Parameter(0, VALUE_SCHEMA_INT))));
    activityTypes.add(new ActivityType("RipenBanana", Map.of(), "Prepare"));
    activityTypes.add(new ActivityType("ThrowBanana", Map.of("speed", new Parameter(0, VALUE_SCHEMA_REAL))));
    return activityTypes;
  }

  @Test
  void resourcesTypesAreUploaded() throws IOException {
    final var resourceTypes = hasura.getResourceTypes(modelId);

    // resourceTypes is alphabetized by name
    final var expectedTypes = expectedResourceTypesBanananation();

    // Assert that all the types were uploaded
    assertEquals(expectedTypes.size(), resourceTypes.size());
    for (int i = 0; i < expectedTypes.size(); ++i) {
      assertEquals(expectedTypes.get(i), resourceTypes.get(i));
    }
  }

  @Test
  void activityTypesAreUploaded() throws IOException{
    final var activityTypes = hasura.getActivityTypes(modelId);

    // activityTypes is alphabetized by name
    final var expectedTypes = expectedActivityTypesBanananation();

    // Assert that all the types were uploaded
    assertEquals(expectedTypes.size(), activityTypes.size());
    for (int i = 0; i < expectedTypes.size(); ++i) {
      assertEquals(expectedTypes.get(i).name(), activityTypes.get(i).name());

      // Assert the Parameters all match
      // Done in this way as the Parameter Maps may be ordered differently
      final var expectedParams = expectedTypes.get(i).parameters();
      final var actualParams = activityTypes.get(i).parameters();
      assertEquals(expectedParams.size(), actualParams.size());
      for(final var key : expectedParams.keySet()){
        assertTrue(actualParams.containsKey(key));
        assertEquals(expectedParams.get(key), actualParams.get(key));
      }

      final var expectedComputedAttributes = expectedTypes.get(i).computedAttributes();
      final var actualComputedAttributes = activityTypes.get(i).computedAttributes();
      assertEquals(expectedComputedAttributes, actualComputedAttributes);
    }
  }

  /**
   * The logs for the Hasura events triggered during model upload are accessible.
   */
  @Test
  void hasuraEventLogsAreAccessible() throws IOException {
    final var modelLogs = hasura.awaitModelEventLogs(modelId);

    assertEquals(modelId, modelLogs.modelId());
    assertEquals("Banananation (e2e tests)", modelLogs.modelName());
    assertEquals("Mission Model Tests", modelLogs.modelVersion());

    // Check Activity Type Refresh Event Logs
    final var activityTypeRefreshLogs = modelLogs.refreshActivityTypesLogs();
    assertEquals(1, activityTypeRefreshLogs.size());
    final var activityTypeLog = activityTypeRefreshLogs.getFirst();

    assertEquals("Aerie Legacy", activityTypeLog.triggeringUser());

    assertFalse(activityTypeLog.pending());
    assertTrue(activityTypeLog.delivered());
    assertTrue(activityTypeLog.success());
    assertEquals(1, activityTypeLog.tries());

    assertTrue(activityTypeLog.status().isPresent());
    assertEquals(200, activityTypeLog.status().get());

    assertTrue(activityTypeLog.error().isEmpty());
    assertTrue(activityTypeLog.errorMessage().isEmpty());
    assertTrue(activityTypeLog.errorType().isEmpty());

    // Check Model Parameter Refresh Event Logs
    final var modelParamRefreshLogs = modelLogs.refreshModelParamsLogs();
    assertEquals(1, modelParamRefreshLogs.size());
    final var modelParamLog = modelParamRefreshLogs.getFirst();

    assertEquals("Aerie Legacy", modelParamLog.triggeringUser());

    assertFalse(modelParamLog.pending());
    assertTrue(modelParamLog.delivered());
    assertTrue(modelParamLog.success());
    assertEquals(1, modelParamLog.tries());

    assertTrue(modelParamLog.status().isPresent());
    assertEquals(200, modelParamLog.status().get());

    assertTrue(modelParamLog.error().isEmpty());
    assertTrue(modelParamLog.errorMessage().isEmpty());
    assertTrue(modelParamLog.errorType().isEmpty());

    // Check Resource Type Refresh Event Logs
    final var resourceTypeRefreshLogs = modelLogs.refreshResourceTypesLogs();
    assertEquals(1, resourceTypeRefreshLogs.size());
    final var resourceTypeLog = resourceTypeRefreshLogs.getFirst();

    assertEquals("Aerie Legacy", resourceTypeLog.triggeringUser());

    assertFalse(resourceTypeLog.pending());
    assertTrue(resourceTypeLog.delivered());
    assertTrue(resourceTypeLog.success());
    assertEquals(1, resourceTypeLog.tries());

    assertTrue(resourceTypeLog.status().isPresent());
    assertEquals(200, resourceTypeLog.status().get());

    assertTrue(resourceTypeLog.error().isEmpty());
    assertTrue(resourceTypeLog.errorMessage().isEmpty());
    assertTrue(resourceTypeLog.errorType().isEmpty());
  }
}
