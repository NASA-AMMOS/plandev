package gov.nasa.jpl.aerie.merlin.driver.json;

import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.json.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.literalP;
import static gov.nasa.jpl.aerie.json.BasicParsers.mapP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.json.ProductParsers.productP;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

public final class ValueSchemaJsonParser implements JsonParser<ValueSchema> {
  public static final JsonParser<ValueSchema> valueSchemaP = new ValueSchemaJsonParser();

  @Override
  public JsonObject getSchema(final SchemaCache anchors) {
    // TODO: Figure out what this should be
    return Json.createObjectBuilder().add("type", "any").build();
  }

  @Override
  public JsonParseResult<ValueSchema> parse(final JsonValue json) {
    if (!json.getValueType().equals(JsonValue.ValueType.OBJECT)) return JsonParseResult.failure("Expected object");
    final var obj = json.asJsonObject();
    if (!obj.containsKey("type")) return JsonParseResult.failure("Expected field \"type\"");
    final var type = obj.get("type");
    if (!type.getValueType().equals(JsonValue.ValueType.STRING)) return JsonParseResult.failure("\"type\" field must be a string");

    JsonParseResult<ValueSchema> result = switch (obj.getString("type")) {
      case "real" -> JsonParseResult.success(ValueSchema.REAL);
      case "int" -> JsonParseResult.success(ValueSchema.INT);
      case "boolean" -> JsonParseResult.success(ValueSchema.BOOLEAN);
      case "string" -> JsonParseResult.success(ValueSchema.STRING);
      case "duration" -> JsonParseResult.success(ValueSchema.DURATION);
      case "path" -> JsonParseResult.success(ValueSchema.PATH);
      case "series" -> parseSeries(obj);
      case "struct" -> parseStruct(obj);
      case "variant" -> parseVariant(obj);
      default -> JsonParseResult.failure("Unrecognized value schema type");
    };

    if (obj.containsKey("metadata")) {
      final var metadata = mapP(serializedValueP).parse(obj.getJsonObject("metadata"));
      return result.mapSuccess($ -> new ValueSchema.MetaSchema(metadata.getSuccessOrThrow(), $));
    }

    return result;
  }

  private JsonParseResult<ValueSchema> parseSeries(final JsonObject obj) {
    if (!obj.containsKey("items")) return JsonParseResult.failure("\"series\" value schema requires field \"items\"");
    return parse(obj.get("items")).mapSuccess(ValueSchema::ofSeries);
  }

  private JsonParseResult<ValueSchema> parseStruct(final JsonObject obj) {
    if (!obj.containsKey("items")) return JsonParseResult.failure("\"struct\" value schema requires field \"items\"");
    final var items = obj.get("items");
    if (!items.getValueType().equals(JsonValue.ValueType.OBJECT)) return JsonParseResult.failure("\"items\" field of \"struct\" must be an object");

    final var itemSchemas = new HashMap<String, ValueSchema>();
    for (final var entry : items.asJsonObject().entrySet()) {
      final var schema$ = parse(entry.getValue());
      if (schema$.isFailure()) return schema$;
      itemSchemas.put(entry.getKey(), schema$.getSuccessOrThrow());
    }

    return JsonParseResult.success(ValueSchema.ofStruct(itemSchemas));
  }

  private JsonParseResult<ValueSchema> parseVariant(final JsonObject obj) {
    final JsonParser<ValueSchema.Variant> variantP =
        productP
            .field("key", stringP)
            .field("label", stringP)
            .map(
                untuple(ValueSchema.Variant::new),
                $ -> tuple($.key(), $.label()));
    final JsonParser<ValueSchema> variantsP =
        productP
            .field("type", literalP("variant"))
            .field("variants", listP(variantP))
            .rest()
            .map(
                untuple((type, variants) -> ValueSchema.ofVariant(variants)),
                $ -> tuple(Unit.UNIT, $.asVariant().get()));

    return variantsP.parse(obj);
  }

  @Override
  public JsonValue unparse(final ValueSchema schema) {
    if (schema == null) return JsonValue.NULL;

    return schema.match(new ValueSchema.Visitor<>() {
      @Override
      public JsonValue onReal() {
        return Json
            .createObjectBuilder()
            .add("type", "real")
            .build();
      }

      @Override
      public JsonValue onInt() {
        return Json
            .createObjectBuilder()
            .add("type", "int")
            .build();
      }

      @Override
      public JsonValue onBoolean() {
        return Json
            .createObjectBuilder()
            .add("type", "boolean")
            .build();
      }

      @Override
      public JsonValue onString() {
        return Json
            .createObjectBuilder()
            .add("type", "string")
            .build();
      }

      @Override
      public JsonValue onDuration() {
        return Json
            .createObjectBuilder()
            .add("type", "duration")
            .build();
      }

      @Override
      public JsonValue onPath() {
        return Json
            .createObjectBuilder()
            .add("type", "path")
            .build();
      }

      @Override
      public JsonValue onSeries(final ValueSchema itemSchema) {
        return Json
            .createObjectBuilder()
            .add("type", "series")
            .add("items", itemSchema.match(this))
            .build();
      }

      @Override
      public JsonValue onStruct(final Map<String, ValueSchema> parameterSchemas) {
        return Json
            .createObjectBuilder()
            .add("type", "struct")
            .add("items", serializeMap(x -> x.match(this), parameterSchemas))
            .build();
      }

      @Override
      public JsonValue onVariant(final List<ValueSchema.Variant> variants) {
        return Json
            .createObjectBuilder()
            .add("type", "variant")
            .add("variants", serializeIterable(
                v -> Json
                    .createObjectBuilder()
                    .add("key", v.key())
                    .add("label", v.label())
                    .build(),
                variants))
            .build();
      }

      @Override
      public JsonValue onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) {
        return Json
            .createObjectBuilder(target.match(this).asJsonObject())
            .add("metadata", mapP(new SerializedValueJsonParser()).unparse(metadata))
            .build();
      }
    });
  }

  public static <T> JsonValue
  serializeIterable(final Function<T, JsonValue> elementSerializer, final Iterable<T> elements) {
    if (elements == null) return JsonValue.NULL;

    final var builder = Json.createArrayBuilder();
    for (final var element : elements) builder.add(elementSerializer.apply(element));
    return builder.build();
  }

  public static <T> JsonValue serializeMap(final Function<T, JsonValue> fieldSerializer, final Map<String, T> fields) {
    if (fields == null) return JsonValue.NULL;

    final var builder = Json.createObjectBuilder();
    for (final var entry : fields.entrySet()) builder.add(entry.getKey(), fieldSerializer.apply(entry.getValue()));
    return builder.build();
  }
}
