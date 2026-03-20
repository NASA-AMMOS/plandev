package gov.nasa.jpl.aerie.merlin.driver.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.json.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

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
  public ObjectNode getSchema(final SchemaCache anchors) {
    // TODO: Figure out what this should be
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("type", "any");
    return node;
  }

  @Override
  public JsonParseResult<ValueSchema> parse(final JsonNode json) {
    if (!json.isObject()) return JsonParseResult.failure("Expected object");
    if (!json.has("type")) return JsonParseResult.failure("Expected field \"type\"");
    final var type = json.get("type");
    if (!type.isTextual()) return JsonParseResult.failure("\"type\" field must be a string");

    JsonParseResult<ValueSchema> result = switch (type.textValue()) {
      case "real" -> JsonParseResult.success(ValueSchema.REAL);
      case "int" -> JsonParseResult.success(ValueSchema.INT);
      case "boolean" -> JsonParseResult.success(ValueSchema.BOOLEAN);
      case "string" -> JsonParseResult.success(ValueSchema.STRING);
      case "duration" -> JsonParseResult.success(ValueSchema.DURATION);
      case "path" -> JsonParseResult.success(ValueSchema.PATH);
      case "series" -> parseSeries(json);
      case "struct" -> parseStruct(json);
      case "variant" -> parseVariant(json);
      default -> JsonParseResult.failure("Unrecognized value schema type");
    };

    if (json.has("metadata")) {
      final var metadata = mapP(serializedValueP).parse(json.get("metadata"));
      return result.mapSuccess($ -> new ValueSchema.MetaSchema(metadata.getSuccessOrThrow(), $));
    }

    return result;
  }

  private JsonParseResult<ValueSchema> parseSeries(final JsonNode obj) {
    if (!obj.has("items")) return JsonParseResult.failure("\"series\" value schema requires field \"items\"");
    return parse(obj.get("items")).mapSuccess(ValueSchema::ofSeries);
  }

  private JsonParseResult<ValueSchema> parseStruct(final JsonNode obj) {
    if (!obj.has("items")) return JsonParseResult.failure("\"struct\" value schema requires field \"items\"");
    final var items = obj.get("items");
    if (!items.isObject()) return JsonParseResult.failure("\"items\" field of \"struct\" must be an object");

    final var itemSchemas = new HashMap<String, ValueSchema>();
    final var fields = items.fields();
    while (fields.hasNext()) {
      final var entry = fields.next();
      final var schema$ = parse(entry.getValue());
      if (schema$.isFailure()) return schema$;
      itemSchemas.put(entry.getKey(), schema$.getSuccessOrThrow());
    }

    return JsonParseResult.success(ValueSchema.ofStruct(itemSchemas));
  }

  private JsonParseResult<ValueSchema> parseVariant(final JsonNode obj) {
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
  public JsonNode unparse(final ValueSchema schema) {
    if (schema == null) return NullNode.getInstance();

    return schema.match(new ValueSchema.Visitor<>() {
      @Override
      public JsonNode onReal() {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "real");
        return node;
      }

      @Override
      public JsonNode onInt() {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "int");
        return node;
      }

      @Override
      public JsonNode onBoolean() {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "boolean");
        return node;
      }

      @Override
      public JsonNode onString() {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "string");
        return node;
      }

      @Override
      public JsonNode onDuration() {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "duration");
        return node;
      }

      @Override
      public JsonNode onPath() {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "path");
        return node;
      }

      @Override
      public JsonNode onSeries(final ValueSchema itemSchema) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "series");
        node.set("items", itemSchema.match(this));
        return node;
      }

      @Override
      public JsonNode onStruct(final Map<String, ValueSchema> parameterSchemas) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "struct");
        node.set("items", serializeMap(x -> x.match(this), parameterSchemas));
        return node;
      }

      @Override
      public JsonNode onVariant(final List<ValueSchema.Variant> variants) {
        final var arr = JsonNodeFactory.instance.arrayNode();
        for (final var v : variants) {
          final var variantNode = JsonNodeFactory.instance.objectNode();
          variantNode.put("key", v.key());
          variantNode.put("label", v.label());
          arr.add(variantNode);
        }

        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "variant");
        node.set("variants", arr);
        return node;
      }

      @Override
      public JsonNode onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) {
        final var targetNode = (ObjectNode) target.match(this);
        targetNode.set("metadata", mapP(new SerializedValueJsonParser()).unparse(metadata));
        return targetNode;
      }
    });
  }

  public static <T> JsonNode
  serializeIterable(final Function<T, JsonNode> elementSerializer, final Iterable<T> elements) {
    if (elements == null) return NullNode.getInstance();

    final var arr = JsonNodeFactory.instance.arrayNode();
    for (final var element : elements) arr.add(elementSerializer.apply(element));
    return arr;
  }

  public static <T> JsonNode serializeMap(final Function<T, JsonNode> fieldSerializer, final Map<String, T> fields) {
    if (fields == null) return NullNode.getInstance();

    final var obj = JsonNodeFactory.instance.objectNode();
    for (final var entry : fields.entrySet()) obj.set(entry.getKey(), fieldSerializer.apply(entry.getValue()));
    return obj;
  }
}
