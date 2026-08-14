package gov.nasa.jpl.aerie.json;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
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
      public JsonObject getSchema(final SchemaCache anchors) {
        final var builder = Json.createArrayBuilder();
        for (final var variant : variants) {
          final var schema = variant.parser.getSchema(anchors);
          final var properties = schema.getJsonObject("properties");
          final var requiredProperties = schema.getJsonArray("required");

          builder.add(Json
              .createObjectBuilder(schema)
              .add("properties", Json
                  .createObjectBuilder()
                  .add(tagField, Json
                      .createObjectBuilder()
                      .add("const", Json.createValue(variant.tag()))
                      .build())
                  .addAll(Json.createObjectBuilder(properties)))
              .add("required", Json
                  .createArrayBuilder()
                  .add(tagField)
                  .addAll(Json.createArrayBuilder(requiredProperties)))
              .build());
        }

        return Json
            .createObjectBuilder()
            .add("oneOf", builder.build())
            .build();
      }

      @Override
      public JsonParseResult<T> parse(final JsonValue json) {
        if (!(json instanceof JsonObject obj)) return JsonParseResult.failure("expected object");
        if (!obj.containsKey(tagField)) return JsonParseResult.failure("missing field `%s`".formatted(tagField));

        final var tag$ = stringP.parse(obj.get(tagField));
        if (tag$ instanceof JsonParseResult.Failure<?> f) {
          return f.cast();
        } else if (tag$ instanceof JsonParseResult.Success<String> s) {
          final var tag = s.result();

          for (final var variant : variants) {
            if (!Objects.equals(variant.tag(), tag)) continue;

            final var prunedObj = Json.createObjectBuilder(obj).remove(tagField).build();
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
      public JsonObject unparse(final T value) {
        for (final var variant : variants) {
          if (!variant.klass.isAssignableFrom(value.getClass())) continue;

          return Json
              .createObjectBuilder(unsafeParseSubclass(variant, value))
              .add(tagField, variant.tag())
              .build();
        }
          throw new RuntimeException(
              "Unknown subclass %s of class %s with value %s"
                  .formatted(value.getClass(), klass, value));
      }

      public <S extends T> JsonObject unsafeParseSubclass(final Variant<S> variant, final T value) {
        return variant.parser().unparse(variant.klass().cast(value));
      }
    };
  }
}
