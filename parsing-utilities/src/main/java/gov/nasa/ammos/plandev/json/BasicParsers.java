package gov.nasa.jpl.aerie.json;

import gov.nasa.jpl.aerie.json.ProductParsers.EmptyProductParser;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * A namespace for primitive parsers and essential combinators.
 *
 * Non-primitive mappers and niche combinators should be given their own top-level classes.
 */
public abstract class BasicParsers {
  private BasicParsers() {}

  public static final JsonParser<JsonValue> anyP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      // The empty object represents no constraints on JSON to be parsed.
      return Json.createObjectBuilder().build();
    }

    @Override
    public JsonParseResult<JsonValue> parse(final JsonValue json) {
      return JsonParseResult.success(json);
    }

    @Override
    public JsonValue unparse(final JsonValue value) {
      return value;
    }
  };

  public static final JsonParser<Void> noneP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      // The schema `{"not": {}}` validates no JSON document.
      return Json.createObjectBuilder().add("not", Json.createObjectBuilder()).build();
    }

    @Override
    public JsonParseResult<Void> parse(final JsonValue json) {
      return JsonParseResult.failure();
    }

    @Override
    public JsonValue unparse(final Void value) {
      throw new IllegalArgumentException("There are no valid instances of Void.");
    }
  };

  public static final JsonParser<Boolean> boolP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder()
          .add("type", "boolean")
          .build();
    }

    @Override
    public JsonParseResult<Boolean> parse(final JsonValue json) {
      if (Objects.equals(json, JsonValue.TRUE)) return JsonParseResult.success(true);
      if (Objects.equals(json, JsonValue.FALSE)) return JsonParseResult.success(false);
      return JsonParseResult.failure("expected boolean");
    }

    @Override
    public JsonValue unparse(final Boolean value) {
      return (value) ? JsonValue.TRUE : JsonValue.FALSE;
    }
  };

  public static final JsonParser<String> stringP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder()
          .add("type", "string")
          .build();
    }

    @Override
    public JsonParseResult<String> parse(final JsonValue json) {
      if (!(json instanceof JsonString)) return JsonParseResult.failure("expected string");

      return JsonParseResult.success(((JsonString) json).getString());
    }

    @Override
    public JsonValue unparse(final String value) {
      return Json.createValue(value);
    }
  };

  public static final JsonParser<Instant> instantP = stringP.map(
      Instant::parse,
      Instant::toString
  );

  public static final JsonParser<Integer> intP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder()
          // must be an integer
          .add("type", "integer")
          // within the range of Java integers
          .add("minimum", Integer.MIN_VALUE)
          .add("maximum", Integer.MAX_VALUE)
          .build();
    }

    @Override
    public JsonParseResult<Integer> parse(final JsonValue json) {
      if (!(json instanceof JsonNumber n)) return JsonParseResult.failure("expected int");
      if (!n.isIntegral()) return JsonParseResult.failure("expected integral number");

      try {
        return JsonParseResult.success(n.intValueExact());
      } catch (final ArithmeticException ex) {
        return JsonParseResult.failure("integer is outside of the expected range");
      }
    }

    @Override
    public JsonValue unparse(final Integer value) {
      return Json.createValue(value);
    }
  };

  public static final JsonParser<Long> longP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder()
          // must be an integer
          .add("type", "integer")
          // within the range of Java longs
          .add("minimum", Long.MIN_VALUE)
          .add("maximum", Long.MAX_VALUE)
          .build();
    }

    @Override
    public JsonParseResult<Long> parse(final JsonValue json) {
      if (!(json instanceof JsonNumber)) return JsonParseResult.failure("expected long");
      if (!((JsonNumber) json).isIntegral()) return JsonParseResult.failure("expected integral number");

      return JsonParseResult.success(((JsonNumber) json).longValue());
    }

    @Override
    public JsonValue unparse(final Long value) {
      return Json.createValue(value);
    }
  };

  public static final JsonParser<Double> doubleP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder()
          // must be a number
          .add("type", "number")
          // within the range of Java doubles
          // (don't use MIN_VALUE; it's actually the _minimum magnitude_, i.e. the positive value closest to zero.)
          .add("minimum", -Double.MAX_VALUE)
          .add("maximum", +Double.MAX_VALUE)
          .build();
    }

    @Override
    public JsonParseResult<Double> parse(final JsonValue json) {
      if (!(json instanceof JsonNumber)) return JsonParseResult.failure("expected double");

      return JsonParseResult.success(((JsonNumber) json).doubleValue());
    }

    @Override
    public JsonValue unparse(final Double value) {
      return Json.createValue(value);
    }
  };

  public static <T> JsonParser<Optional<T>> nullableP(final JsonParser<T> parser) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return Json.createObjectBuilder()
                   .add("oneOf", Json.createArrayBuilder()
                                   .add(JsonValue.NULL)
                                   .add(parser.getSchema()))
                   .build();
      }

      @Override
      public JsonParseResult<Optional<T>> parse(final JsonValue json) {
        if (json.getValueType() == JsonValue.ValueType.NULL) return JsonParseResult.success(Optional.empty());

        return parser.parse(json).mapSuccess(Optional::of);
      }

      @Override
      public JsonValue unparse(final Optional<T> value) {
        return value.map(parser::unparse).orElse(JsonValue.NULL);
      }
    };
  }

  public static final JsonParser<Unit> nullP = new JsonParser<>() {
    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder()
          .add("type", "null")
          .build();
    }

    @Override
    public JsonParseResult<Unit> parse(final JsonValue json) {
      if (!Objects.equals(json, JsonValue.NULL)) return JsonParseResult.failure("expected null");

      return JsonParseResult.success(null);
    }

    @Override
    public JsonValue unparse(final Unit value) {
      return JsonValue.NULL;
    }
  };

  public static <E extends Enum<E>> JsonParser<E> enumP(final Class<E> klass, final Function<E, String> valueOf) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        final var builder = Json.createArrayBuilder();
        for (final var enumConstant : klass.getEnumConstants()) {
          builder.add(Json.createValue(valueOf.apply(enumConstant)));
        }
        builder.build();

        return Json
            .createObjectBuilder()
            .add("type", "string")
            .add("enum", builder.build())
            .build();
      }

      @Override
      public JsonParseResult<E> parse(final JsonValue json) {
        if (!(json instanceof JsonString str)) return JsonParseResult.failure("expected string");

        for (final var enumConstant : klass.getEnumConstants()) {
          if (!Objects.equals(valueOf.apply(enumConstant), str.getString())) continue;

          return JsonParseResult.success(enumConstant);
        }

        return JsonParseResult.failure("unknown enum variant");
      }

      @Override
      public JsonValue unparse(final E enumConstant) {
        return Json.createValue(valueOf.apply(enumConstant));
      }
    };
  }

  public static JsonParser<Unit> literalP(final String x) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return Json
            .createObjectBuilder()
            .add("const", unparse(Unit.UNIT))
            .build();
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonValue json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("string literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonValue unparse(final Unit value) {
        return Json.createValue(x);
      }
    };
  }

  public static JsonParser<Unit> literalP(final long x) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return Json
            .createObjectBuilder()
            .add("const", unparse(Unit.UNIT))
            .build();
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonValue json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("long literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonValue unparse(final Unit value) {
        return Json.createValue(x);
      }
    };
  }

  public static JsonParser<Unit> literalP(final double x) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return Json
            .createObjectBuilder()
            .add("const", unparse(Unit.UNIT))
            .build();
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonValue json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("double literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonValue unparse(final Unit value) {
        return Json.createValue(x);
      }
    };
  }

  public static JsonParser<Unit> literalP(final boolean x) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return Json
            .createObjectBuilder()
            .add("const", unparse(Unit.UNIT))
            .build();
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonValue json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("boolean literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonValue unparse(final Unit value) {
        return (x) ? JsonValue.TRUE : JsonValue.FALSE;
      }
    };
  }

  public static <T> JsonParser<List<T>> listP(final JsonParser<T> elementParser) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return Json
            .createObjectBuilder()
            .add("type", "array")
            .add("items", anchors.lookup(elementParser))
            .build();
      }

      @Override
      public JsonParseResult<List<T>> parse(final JsonValue json) {
        if (!(json instanceof JsonArray)) return JsonParseResult.failure("expected list");

        final var jsonArray = json.asJsonArray();
        final var list = new ArrayList<T>(jsonArray.size());
        for (int index = 0; index < jsonArray.size(); index++) {
          final var element = jsonArray.get(index);
          final var result = elementParser.parse(element).prependBreadcrumb(Breadcrumb.ofInteger(index));

          if (result instanceof JsonParseResult.Failure<?> f) {
            return f.cast();
          }

          list.add(result.getSuccessOrThrow());
        }

        return JsonParseResult.success(list);
      }

      @Override
      public JsonValue unparse(final List<T> values) {
        final var builder = Json.createArrayBuilder();
        for (final var value : values) builder.add(elementParser.unparse(value));
        return builder.build();
      }
    };
  }

  public static <S> JsonParser<Map<String, S>> mapP(final JsonParser<S> fieldParser) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return Json
            .createObjectBuilder()
            .add("type", "object")
            .add("additionalProperties", anchors.lookup(fieldParser))
            .build();
      }

      @Override
      public JsonParseResult<Map<String, S>> parse(final JsonValue json) {
        if (!(json instanceof JsonObject)) return JsonParseResult.failure("expected object");

        final var map = new HashMap<String, S>(json.asJsonObject().size());
        for (final var field : json.asJsonObject().entrySet()) {
          final var result = fieldParser.parse(field.getValue()).prependBreadcrumb(Breadcrumb.ofString(field.getKey()));

          if (result instanceof JsonParseResult.Failure<?> f) {
            return f.cast();
          }

          map.put(field.getKey(), result.getSuccessOrThrow());
        }

        return JsonParseResult.success(map);
      }

      @Override
      public JsonValue unparse(final Map<String, S> values) {
        final var builder = Json.createObjectBuilder();
        for (final var entry : values.entrySet()) builder.add(entry.getKey(), fieldParser.unparse(entry.getValue()));
        return builder.build();
      }
    };
  }

  public static <S> JsonParser<S> recursiveP(final Function<JsonParser<S>, JsonParser<S>> scope) {
    return new JsonParser<>() {
      private final JsonParser<S> target = scope.apply(this);

      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        return this.target.getSchema(anchors);
      }

      @Override
      public JsonParseResult<S> parse(final JsonValue json) {
        return this.target.parse(json);
      }

      @Override
      public JsonValue unparse(final S value) {
        return this.target.unparse(value);
      }
    };
  }

  @SafeVarargs
  public static <T> JsonParser<T> chooseP(final JsonParser<? extends T>... options) {
    return new JsonParser<>() {
      @Override
      public JsonObject getSchema(final SchemaCache anchors) {
        final var optionSchemas = Json.createArrayBuilder();
        for (final var option : options) {
          optionSchemas.add(anchors.lookup(option));
        }

        return Json
            .createObjectBuilder()
            .add("oneOf", optionSchemas)
            .build();
      }

      @Override
      public JsonParseResult<T> parse(final JsonValue json) {
        for (final var option : options) {
          final var result = option.parse(json);
          if (result.isFailure()) continue;
          return result.mapSuccess(x -> (T) x);
        }

        return JsonParseResult.failure("not parsable into acceptable type");
      }

      // TODO: Figure out a better way to define choice parsers that doesn't fundamentally rely on unsafe casts.
      @Override
      public JsonValue unparse(final T value) {
        for (final JsonParser<? extends T> option : options) {
          final var result = unsafeUnparse(option, value);
          if (result.isEmpty()) continue;
          return result.get();
        }

        throw new RuntimeException("No choice of parser can unparse this value.");
      }

      // SAFETY: Class cast exceptions are isolated to this method and are handled appropriately.
      @SuppressWarnings("unchecked")
      private static <S extends T, T> Optional<JsonValue> unsafeUnparse(JsonParser<S> parser, T value) {
        try {
          return Optional.of(parser.unparse((S) value));
        } catch (final ClassCastException ignored) {
          return Optional.empty();
        }
      }
    };
  }

  public static EmptyProductParser productP = ProductParsers.productP;
}
