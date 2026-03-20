package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;

public final class SumParsers {
  private SumParsers() {}

  public record Variant<T>(String tag, Class<T> klass, JsonObjectParser<T> parser) {}

  public static <T>
  Variant<T> variant(final String tag, final Class<T> klass, final JsonObjectParser<T> parser) {
    return new Variant<>(tag, klass, parser);
  }

  public static <T>
  JsonParser<T> sumP(final String tagField, final Class<T> klass, final List<Variant<? extends T>> variants) {
    return new JsonObjectParser<T>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var arr = JsonNodeFactory.instance.arrayNode();
        for (final var variant : variants) {
          final var schema = variant.parser.getSchema(anchors);
          final var properties = (ObjectNode) schema.get("properties");
          final var requiredProperties = schema.get("required");

          // Build the augmented properties with tag field
          final var tagSchema = JsonNodeFactory.instance.objectNode();
          tagSchema.put("const", variant.tag());
          final var augProperties = properties.deepCopy();
          augProperties.set(tagField, tagSchema);

          // Build the augmented required array with tag field
          final var augRequired = JsonNodeFactory.instance.arrayNode();
          augRequired.add(tagField);
          if (requiredProperties != null && requiredProperties.isArray()) {
            for (final var req : requiredProperties) {
              augRequired.add(req);
            }
          }

          final var variantSchema = schema.deepCopy();
          variantSchema.set("properties", augProperties);
          variantSchema.set("required", augRequired);
          arr.add(variantSchema);
        }

        final var node = JsonNodeFactory.instance.objectNode();
        node.set("oneOf", arr);
        return node;
      }

      @Override
      public JsonParseResult<T> parse(final JsonNode json) {
        if (!json.isObject()) return JsonParseResult.failure("expected object");
        if (!json.has(tagField)) return JsonParseResult.failure("missing field `%s`".formatted(tagField));

        final var tag$ = stringP.parse(json.get(tagField));
        if (tag$ instanceof JsonParseResult.Failure<?> f) {
          return f.cast();
        } else if (tag$ instanceof JsonParseResult.Success<String> s) {
          final var tag = s.result();

          for (final var variant : variants) {
            if (!Objects.equals(variant.tag(), tag)) continue;

            // Create a copy without the tag field
            final var prunedObj = ((ObjectNode) json).deepCopy();
            prunedObj.remove(tagField);
            return variant.parser().parse(prunedObj).mapSuccess($ -> $);
          }

          return JsonParseResult.failure("invalid tag '%s'".formatted(tag));
        } else {
          throw new RuntimeException(
              "Unknown subclass %s of class %s with value %s"
                  .formatted(tag$.getClass(), JsonParseResult.class, tag$));
        }
      }

      @Override
      public ObjectNode unparse(final T value) {
        for (final var variant : variants) {
          if (!variant.klass.isAssignableFrom(value.getClass())) continue;

          final var obj = unsafeParseSubclass(variant, value);
          obj.put(tagField, variant.tag());
          return obj;
        }
          throw new RuntimeException(
              "Unknown subclass %s of class %s with value %s"
                  .formatted(value.getClass(), klass, value));
      }

      public <S extends T> ObjectNode unsafeParseSubclass(final Variant<S> variant, final T value) {
        return variant.parser().unparse(variant.klass().cast(value));
      }
    };
  }
}
