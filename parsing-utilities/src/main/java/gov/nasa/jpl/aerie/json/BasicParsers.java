package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import gov.nasa.jpl.aerie.json.ProductParsers.EmptyProductParser;

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

  public static final JsonParser<JsonNode> anyP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      // The empty object represents no constraints on JSON to be parsed.
      return JsonNodeFactory.instance.objectNode();
    }

    @Override
    public JsonParseResult<JsonNode> parse(final JsonNode json) {
      return JsonParseResult.success(json);
    }

    @Override
    public JsonNode unparse(final JsonNode value) {
      return value;
    }
  };

  public static final JsonParser<Void> noneP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      // The schema `{"not": {}}` validates no JSON document.
      final var node = JsonNodeFactory.instance.objectNode();
      node.set("not", JsonNodeFactory.instance.objectNode());
      return node;
    }

    @Override
    public JsonParseResult<Void> parse(final JsonNode json) {
      return JsonParseResult.failure();
    }

    @Override
    public JsonNode unparse(final Void value) {
      throw new IllegalArgumentException("There are no valid instances of Void.");
    }
  };

  public static final JsonParser<Boolean> boolP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "boolean");
      return node;
    }

    @Override
    public JsonParseResult<Boolean> parse(final JsonNode json) {
      if (!json.isBoolean()) return JsonParseResult.failure("expected boolean");
      return JsonParseResult.success(json.booleanValue());
    }

    @Override
    public JsonNode unparse(final Boolean value) {
      return BooleanNode.valueOf(value);
    }
  };

  public static final JsonParser<String> stringP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "string");
      return node;
    }

    @Override
    public JsonParseResult<String> parse(final JsonNode json) {
      if (!json.isTextual()) return JsonParseResult.failure("expected string");
      return JsonParseResult.success(json.textValue());
    }

    @Override
    public JsonNode unparse(final String value) {
      return TextNode.valueOf(value);
    }
  };

  public static final JsonParser<Instant> instantP = stringP.map(
      Instant::parse,
      Instant::toString
  );

  public static final JsonParser<Integer> intP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "integer");
      node.put("minimum", Integer.MIN_VALUE);
      node.put("maximum", Integer.MAX_VALUE);
      return node;
    }

    @Override
    public JsonParseResult<Integer> parse(final JsonNode json) {
      if (!json.isNumber()) return JsonParseResult.failure("expected int");
      if (!json.isIntegralNumber()) return JsonParseResult.failure("expected integral number");

      try {
        final var val = json.bigIntegerValue();
        return JsonParseResult.success(val.intValueExact());
      } catch (final ArithmeticException ex) {
        return JsonParseResult.failure("integer is outside of the expected range");
      }
    }

    @Override
    public JsonNode unparse(final Integer value) {
      return IntNode.valueOf(value);
    }
  };

  public static final JsonParser<Long> longP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "integer");
      node.put("minimum", Long.MIN_VALUE);
      node.put("maximum", Long.MAX_VALUE);
      return node;
    }

    @Override
    public JsonParseResult<Long> parse(final JsonNode json) {
      if (!json.isNumber()) return JsonParseResult.failure("expected long");
      if (!json.isIntegralNumber()) return JsonParseResult.failure("expected integral number");

      return JsonParseResult.success(json.longValue());
    }

    @Override
    public JsonNode unparse(final Long value) {
      return LongNode.valueOf(value);
    }
  };

  public static final JsonParser<Double> doubleP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "number");
      node.put("minimum", -Double.MAX_VALUE);
      node.put("maximum", +Double.MAX_VALUE);
      return node;
    }

    @Override
    public JsonParseResult<Double> parse(final JsonNode json) {
      if (!json.isNumber()) return JsonParseResult.failure("expected double");

      return JsonParseResult.success(json.doubleValue());
    }

    @Override
    public JsonNode unparse(final Double value) {
      return DoubleNode.valueOf(value);
    }
  };

  public static <T> JsonParser<Optional<T>> nullableP(final JsonParser<T> parser) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var arr = JsonNodeFactory.instance.arrayNode();
        arr.add(NullNode.getInstance());
        arr.add(parser.getSchema());
        final var node = JsonNodeFactory.instance.objectNode();
        node.set("oneOf", arr);
        return node;
      }

      @Override
      public JsonParseResult<Optional<T>> parse(final JsonNode json) {
        if (json.isNull()) return JsonParseResult.success(Optional.empty());

        return parser.parse(json).mapSuccess(Optional::of);
      }

      @Override
      public JsonNode unparse(final Optional<T> value) {
        return value.map(parser::unparse).orElse(NullNode.getInstance());
      }
    };
  }

  public static final JsonParser<Unit> nullP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "null");
      return node;
    }

    @Override
    public JsonParseResult<Unit> parse(final JsonNode json) {
      if (!json.isNull()) return JsonParseResult.failure("expected null");

      return JsonParseResult.success(null);
    }

    @Override
    public JsonNode unparse(final Unit value) {
      return NullNode.getInstance();
    }
  };

  public static <E extends Enum<E>> JsonParser<E> enumP(final Class<E> klass, final Function<E, String> valueOf) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var arr = JsonNodeFactory.instance.arrayNode();
        for (final var enumConstant : klass.getEnumConstants()) {
          arr.add(valueOf.apply(enumConstant));
        }

        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "string");
        node.set("enum", arr);
        return node;
      }

      @Override
      public JsonParseResult<E> parse(final JsonNode json) {
        if (!json.isTextual()) return JsonParseResult.failure("expected string");

        final var str = json.textValue();
        for (final var enumConstant : klass.getEnumConstants()) {
          if (!Objects.equals(valueOf.apply(enumConstant), str)) continue;

          return JsonParseResult.success(enumConstant);
        }

        return JsonParseResult.failure("unknown enum variant");
      }

      @Override
      public JsonNode unparse(final E enumConstant) {
        return TextNode.valueOf(valueOf.apply(enumConstant));
      }
    };
  }

  public static JsonParser<Unit> literalP(final String x) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.set("const", unparse(Unit.UNIT));
        return node;
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonNode json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("string literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonNode unparse(final Unit value) {
        return TextNode.valueOf(x);
      }
    };
  }

  public static JsonParser<Unit> literalP(final long x) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.set("const", unparse(Unit.UNIT));
        return node;
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonNode json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("long literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonNode unparse(final Unit value) {
        return LongNode.valueOf(x);
      }
    };
  }

  public static JsonParser<Unit> literalP(final double x) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.set("const", unparse(Unit.UNIT));
        return node;
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonNode json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("double literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonNode unparse(final Unit value) {
        return DoubleNode.valueOf(x);
      }
    };
  }

  public static JsonParser<Unit> literalP(final boolean x) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.set("const", unparse(Unit.UNIT));
        return node;
      }

      @Override
      public JsonParseResult<Unit> parse(final JsonNode json) {
        if (!Objects.equals(json, unparse(Unit.UNIT))) {
          return JsonParseResult.failure("boolean literal does not match expected value: \"" + x + "\"");
        }

        return JsonParseResult.success(Unit.UNIT);
      }

      @Override
      public JsonNode unparse(final Unit value) {
        return BooleanNode.valueOf(x);
      }
    };
  }

  public static <T> JsonParser<List<T>> listP(final JsonParser<T> elementParser) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "array");
        node.set("items", anchors.lookup(elementParser));
        return node;
      }

      @Override
      public JsonParseResult<List<T>> parse(final JsonNode json) {
        if (!json.isArray()) return JsonParseResult.failure("expected list");

        final var list = new ArrayList<T>(json.size());
        for (int index = 0; index < json.size(); index++) {
          final var element = json.get(index);
          final var result = elementParser.parse(element).prependBreadcrumb(Breadcrumb.ofInteger(index));

          if (result instanceof JsonParseResult.Failure<?> f) {
            return f.cast();
          }

          list.add(result.getSuccessOrThrow());
        }

        return JsonParseResult.success(list);
      }

      @Override
      public JsonNode unparse(final List<T> values) {
        final var arr = JsonNodeFactory.instance.arrayNode(values.size());
        for (final var value : values) arr.add(elementParser.unparse(value));
        return arr;
      }
    };
  }

  public static <S> JsonParser<Map<String, S>> mapP(final JsonParser<S> fieldParser) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("type", "object");
        node.set("additionalProperties", anchors.lookup(fieldParser));
        return node;
      }

      @Override
      public JsonParseResult<Map<String, S>> parse(final JsonNode json) {
        if (!json.isObject()) return JsonParseResult.failure("expected object");

        final var map = new HashMap<String, S>(json.size());
        final var fields = json.fields();
        while (fields.hasNext()) {
          final var field = fields.next();
          final var result = fieldParser.parse(field.getValue()).prependBreadcrumb(Breadcrumb.ofString(field.getKey()));

          if (result instanceof JsonParseResult.Failure<?> f) {
            return f.cast();
          }

          map.put(field.getKey(), result.getSuccessOrThrow());
        }

        return JsonParseResult.success(map);
      }

      @Override
      public JsonNode unparse(final Map<String, S> values) {
        final var obj = JsonNodeFactory.instance.objectNode();
        for (final var entry : values.entrySet()) obj.set(entry.getKey(), fieldParser.unparse(entry.getValue()));
        return obj;
      }
    };
  }

  public static <S> JsonParser<S> recursiveP(final Function<JsonParser<S>, JsonParser<S>> scope) {
    return new JsonParser<>() {
      private final JsonParser<S> target = scope.apply(this);

      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        return this.target.getSchema(anchors);
      }

      @Override
      public JsonParseResult<S> parse(final JsonNode json) {
        return this.target.parse(json);
      }

      @Override
      public JsonNode unparse(final S value) {
        return this.target.unparse(value);
      }
    };
  }

  @SafeVarargs
  public static <T> JsonParser<T> chooseP(final JsonParser<? extends T>... options) {
    return new JsonParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        final var optionSchemas = JsonNodeFactory.instance.arrayNode();
        for (final var option : options) {
          optionSchemas.add(anchors.lookup(option));
        }

        final var node = JsonNodeFactory.instance.objectNode();
        node.set("oneOf", optionSchemas);
        return node;
      }

      @Override
      public JsonParseResult<T> parse(final JsonNode json) {
        for (final var option : options) {
          final var result = option.parse(json);
          if (result.isFailure()) continue;
          return result.mapSuccess(x -> (T) x);
        }

        return JsonParseResult.failure("not parsable into acceptable type");
      }

      // TODO: Figure out a better way to define choice parsers that doesn't fundamentally rely on unsafe casts.
      @Override
      public JsonNode unparse(final T value) {
        for (final JsonParser<? extends T> option : options) {
          final var result = unsafeUnparse(option, value);
          if (result.isEmpty()) continue;
          return result.get();
        }

        throw new RuntimeException("No choice of parser can unparse this value.");
      }

      // SAFETY: Class cast exceptions are isolated to this method and are handled appropriately.
      @SuppressWarnings("unchecked")
      private static <S extends T, T> Optional<JsonNode> unsafeUnparse(JsonParser<S> parser, T value) {
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
