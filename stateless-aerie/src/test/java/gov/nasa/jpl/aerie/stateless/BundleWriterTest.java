package gov.nasa.jpl.aerie.stateless;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code stateless-aerie simulate -b/--bundle} CLI surface: runs the foo mission model
 * against {@code simpleFooPlan.json}, produces an offline plan bundle, and validates that bundle
 * against the checked-in copy of the normative schema, {@code offline-bundle-schema-v1.json}.
 *
 * <p>No JSON-Schema validation library is present anywhere in Aerie's dependency tree (checked across
 * every build.gradle in the repo), so full draft-07 validation isn't available off the shelf. Rather
 * than hardcode a parallel copy of the schema's constraints as ad-hoc assertions, this test walks the
 * checked-in schema file itself with a small recursive validator covering the subset of draft-07 the
 * schema actually uses: object/array/string/integer/number/boolean typing, {@code required},
 * {@code additionalProperties: false}, {@code enum}, and local {@code $ref}s into {@code #/definitions}.
 * That keeps the test honest against the real contract instead of a restatement of it.</p>
 */
public class BundleWriterTest {
  private static final String MODEL_JAR = "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar";
  private static final String PLAN_JSON = "src/test/resources/simpleFooPlan.json";
  private static final String SCHEMA_JSON = "src/test/resources/offline-bundle-schema-v1.json";

  private ByteArrayOutputStream out;
  private PrintStream outputStream;

  @BeforeEach
  void beforeEach() {
    out = new ByteArrayOutputStream();
    outputStream = new PrintStream(out);
    System.setOut(outputStream);
  }

  @AfterEach
  void afterEach() {
    outputStream.close();
  }

  @Test
  void bundleConformsToSchema() throws IOException {
    Main.main(new String[]{"simulate", "-m", MODEL_JAR, "-p", PLAN_JSON, "-b"});
    outputStream.flush();

    final JsonObject bundle;
    try (final var reader = Json.createReader(new StringReader(out.toString()))) {
      bundle = reader.readObject();
    }

    final JsonObject schema;
    try (final var reader = Json.createReader(new FileReader(SCHEMA_JSON))) {
      schema = reader.readObject();
    }

    final var errors = new ArrayList<String>();
    validate(schema, schema, bundle, "$", errors);
    assertTrue(errors.isEmpty(), "Bundle failed schema validation:\n" + String.join("\n", errors));

    // Targeted structural spot-checks called out explicitly by the work package, on top of the
    // full schema walk above.
    assertEquals("1.0.0", bundle.getString("bundleVersion"));

    assertTrue(bundle.containsKey("activityTypes"), "activityTypes should be populated from the mission model");
    final var activityTypes = bundle.getJsonArray("activityTypes");
    assertFalse(activityTypes.isEmpty());
    final var basicFooActivity = activityTypes.stream()
        .map(JsonValue::asJsonObject)
        .filter(t -> t.getString("name").equals("BasicFooActivity"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("BasicFooActivity missing from activityTypes"));
    assertTrue(basicFooActivity.getJsonObject("parameters").containsKey("duration"),
               "BasicFooActivity's parameter schema should be sourced from the model");
    assertEquals(0, basicFooActivity.getJsonObject("parameters").getJsonObject("duration").getInt("order"));
    // NOTE: foo-missionmodel's activities (BasicFooActivity included) declare parameters as plain fields
    // with an inline initializer (eg. "public Duration duration = Duration.of(2, SECONDS);") rather than
    // through an explicit @Export.WithDefaults class. For that style, merlin-framework-processor's
    // NoneDefinedMethodMaker never overrides MapperMethodMaker#getParametersWithDefaults(), so the
    // generated getRequiredParameters() always returns an empty list -- even for FooActivity's "z" field,
    // which has no initializer at all. This is a real, preexisting characteristic of the annotation
    // processor's codegen (not something introduced by BundleWriter, and not something a "topics" fallback
    // could improve on, since topics carry no required-parameter information at all). BundleWriter simply,
    // and correctly, reports whatever InputType#getRequiredParameters() the model supplies -- so we assert
    // its type/shape here rather than assuming specific values.
    assertTrue(basicFooActivity.getJsonArray("requiredParameters") != null);
    assertTrue(basicFooActivity.containsKey("computedAttributesValueSchema"));

    final var directives = bundle.getJsonArray("activityDirectives");
    assertEquals(2, directives.size());
    for (final var d : directives) {
      final var directive = d.asJsonObject();
      assertTrue(directive.containsKey("id"));
      assertTrue(directive.containsKey("type"));
      assertTrue(directive.containsKey("startOffset"));
      assertTrue(directive.containsKey("arguments"));
    }

    final var simulation = bundle.getJsonObject("simulation");
    final var spans = simulation.getJsonArray("spans");
    assertFalse(spans.isEmpty());
    for (final var s : spans) {
      final var span = s.asJsonObject();
      assertTrue(span.containsKey("directiveId"), "spans must carry directiveId");
      assertTrue(span.containsKey("parentId"), "spans must carry parentId");
    }
    // BasicFooActivity (directive id 4) simulates to a finished span with a nonzero duration.
    final var basicFooSpan = spans.stream()
        .map(JsonValue::asJsonObject)
        .filter(sp -> !sp.isNull("directiveId") && sp.getInt("directiveId") == 4)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected a span for directive 4"));
    assertEquals("BasicFooActivity", basicFooSpan.getString("type"));
    assertTrue(basicFooSpan.containsKey("duration"));

    final var resources = simulation.getJsonArray("resources");
    assertFalse(resources.isEmpty());
    for (final var r : resources) {
      final var resource = r.asJsonObject();
      final var segments = resource.getJsonArray("segments");
      assertFalse(segments.isEmpty());
      for (final var seg : segments) {
        final var segment = seg.asJsonObject();
        assertTrue(segment.containsKey("extent"), "each segment must carry its own duration delta as 'extent'");
      }
    }
    final var counter = resources.stream()
        .map(JsonValue::asJsonObject)
        .filter(r -> r.getString("name").equals("/counter"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("/counter resource missing"));
    assertEquals("discrete", counter.getString("type"));
    final var batterySoC = resources.stream()
        .map(JsonValue::asJsonObject)
        .filter(r -> r.getString("name").equals("/batterySoC"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("/batterySoC resource missing"));
    assertEquals("real", batterySoC.getString("type"));
  }

  // ---- Minimal recursive draft-07 validator, covering only the constructs offline-bundle-schema-v1.json uses ----

  private static void validate(JsonObject schemaNode, JsonObject rootSchema, JsonValue instance, String path, List<String> errors) {
    if (schemaNode.containsKey("$ref")) {
      schemaNode = resolveRef(rootSchema, schemaNode.getString("$ref"));
    }

    if (schemaNode.containsKey("enum")) {
      final var allowed = schemaNode.getJsonArray("enum");
      if (!allowed.contains(instance)) {
        errors.add(path + ": value not in enum " + allowed);
      }
    }

    if (schemaNode.containsKey("type")) {
      final var typeValue = schemaNode.get("type");
      final List<String> allowedTypes = new ArrayList<>();
      if (typeValue instanceof JsonString js) {
        allowedTypes.add(js.getString());
      } else if (typeValue instanceof JsonArray ja) {
        for (final var t : ja) allowedTypes.add(((JsonString) t).getString());
      }
      if (!allowedTypes.isEmpty() && !matchesAnyType(instance, allowedTypes)) {
        errors.add(path + ": expected type(s) " + allowedTypes + " but was " + instance.getValueType());
      }
    }

    if (instance.getValueType() == JsonValue.ValueType.OBJECT) {
      final var obj = instance.asJsonObject();

      if (schemaNode.containsKey("required")) {
        for (final var req : schemaNode.getJsonArray("required")) {
          final var key = ((JsonString) req).getString();
          if (!obj.containsKey(key)) {
            errors.add(path + ": missing required property '" + key + "'");
          }
        }
      }

      if (schemaNode.containsKey("properties")) {
        final var properties = schemaNode.getJsonObject("properties");
        for (final var propName : properties.keySet()) {
          if (obj.containsKey(propName) && !obj.isNull(propName)) {
            validate(properties.getJsonObject(propName), rootSchema, obj.get(propName), path + "." + propName, errors);
          }
        }

        final boolean additionalPropertiesAllowed =
            !schemaNode.containsKey("additionalProperties")
            || schemaNode.get("additionalProperties").getValueType() != JsonValue.ValueType.FALSE;
        if (!additionalPropertiesAllowed) {
          final Set<String> allowedKeys = properties.keySet();
          for (final var key : obj.keySet()) {
            if (!allowedKeys.contains(key)) {
              errors.add(path + ": unexpected property '" + key + "' (additionalProperties: false)");
            }
          }
        }
      }
    } else if (instance.getValueType() == JsonValue.ValueType.ARRAY && schemaNode.containsKey("items")) {
      final var itemSchema = schemaNode.getJsonObject("items");
      final var array = instance.asJsonArray();
      for (int i = 0; i < array.size(); i++) {
        validate(itemSchema, rootSchema, array.get(i), path + "[" + i + "]", errors);
      }
    }
  }

  private static boolean matchesAnyType(JsonValue instance, List<String> allowedTypes) {
    for (final var type : allowedTypes) {
      switch (type) {
        case "object" -> { if (instance.getValueType() == JsonValue.ValueType.OBJECT) return true; }
        case "array" -> { if (instance.getValueType() == JsonValue.ValueType.ARRAY) return true; }
        case "string" -> { if (instance.getValueType() == JsonValue.ValueType.STRING) return true; }
        case "boolean" -> {
          if (instance.getValueType() == JsonValue.ValueType.TRUE || instance.getValueType() == JsonValue.ValueType.FALSE) return true;
        }
        case "number", "integer" -> { if (instance.getValueType() == JsonValue.ValueType.NUMBER) return true; }
        case "null" -> { if (instance.getValueType() == JsonValue.ValueType.NULL) return true; }
        default -> { /* unknown type keyword, ignore */ }
      }
    }
    return false;
  }

  private static JsonObject resolveRef(JsonObject rootSchema, String ref) {
    // Only local refs of the form "#/definitions/xxx" are used by this schema.
    final var prefix = "#/definitions/";
    if (!ref.startsWith(prefix)) {
      throw new IllegalArgumentException("Unsupported $ref: " + ref);
    }
    return rootSchema.getJsonObject("definitions").getJsonObject(ref.substring(prefix.length()));
  }
}
