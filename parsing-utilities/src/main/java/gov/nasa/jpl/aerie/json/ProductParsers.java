package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public abstract class ProductParsers {
  private ProductParsers() {}

  public static final EmptyProductParser productP = new EmptyProductParser();


  public static final class EmptyProductParser implements JsonObjectParser<Unit> {
    private EmptyProductParser() {}

    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "object");
      node.put("additionalProperties", false);
      return node;
    }

    @Override
    public JsonParseResult<Unit> parse(final JsonNode json) {
      if (!json.isObject()) return JsonParseResult.failure("expected object");
      if (json.size() != 0) return JsonParseResult.failure("expected empty object");

      return JsonParseResult.success(Unit.UNIT);
    }

    @Override
    public ObjectNode unparse(final Unit value) {
      return JsonNodeFactory.instance.objectNode();
    }

    public <S> VariadicProductParser<S> field(final String key, final JsonParser<S> valueParser) {
      return new VariadicProductParser<>(List.of(new FieldSpec<>(key, valueParser, false)), false);
    }

    public <S> VariadicProductParser<Optional<S>> optionalField(final String key, final JsonParser<S> valueParser) {
      return new VariadicProductParser<>(List.of(new FieldSpec<>(key, valueParser, true)), false);
    }

    public JsonObjectParser<Unit> rest() {
      return new JsonObjectParser<>() {
        @Override
        public ObjectNode getSchema(final SchemaCache anchors) {
          final var node = JsonNodeFactory.instance.objectNode();
          node.put("type", "object");
          return node;
        }

        @Override
        public JsonParseResult<Unit> parse(final JsonNode json) {
          if (!json.isObject()) return JsonParseResult.failure("expected object");
          return JsonParseResult.success(Unit.UNIT);
        }

        @Override
        public ObjectNode unparse(final Unit value) {
          return JsonNodeFactory.instance.objectNode();
        }
      };
    }
  }

  // INVARIANT: T must be of the form Pair<...Pair<Pair<T1, T2>, T3>..., Tn>.
  public static final class VariadicProductParser<T> implements JsonObjectParser<T> {
    // INVARIANT: `fields` must be non-empty.
    private final List<FieldSpec<?>> fields;
    private final boolean acceptUnspecified;

    /** @param fields must be non-empty. */
    private VariadicProductParser(final @Owned List<FieldSpec<?>> fields, final boolean acceptUnspecified) {
      this.fields = fields;
      this.acceptUnspecified = acceptUnspecified;
    }

    @Override
    public JsonParseResult<T> parse(final JsonNode json) {
      if (!json.isObject()) return JsonParseResult.failure("expected object");

      if (!this.acceptUnspecified) {
        // Detect unexpected fields in the json
        final var fieldNames = json.fieldNames();
        while (fieldNames.hasNext()) {
          final var name = fieldNames.next();

          if (getFieldSpec(name).isEmpty()) {
            return JsonParseResult
                .<T>failure("Unexpected field present")
                .prependBreadcrumb(
                    Breadcrumb.ofString(name)
                );
          }
        }
      }

      // Parse the fields
      final var iter = this.fields.iterator();
      var accumulator = parseField(iter.next(), json);
      while (iter.hasNext()) {
        accumulator = accumulator.parWith(parseField(iter.next(), json)).mapSuccess(x -> x);
      }

      return accumulator.mapSuccess(result -> {
        // SAFETY: established by loop invariant.
        @SuppressWarnings("unchecked")
        final var tmp = (T) result;
        return tmp;
      });
    }

    @Override
    public ObjectNode unparse(final T value) {
      final var obj = JsonNodeFactory.instance.objectNode();

      unparse(obj, value, fields.size());

      return obj;
    }

    private void unparse(final ObjectNode obj, Object value, int i) {
      if (i <= 0) return; // This shouldn't happen, but doing nothing is a safe behavior.

      final Object element;
      if (i == 1) { // type(value) = Ti
        element = value;
      } else { // type(value) = Pair<..., Ti>
        final var pair = (Pair<?, ?>) value;

        element = pair.getRight();
        unparse(obj, pair.getLeft(), i - 1);
      }

      unparseField(obj, this.fields.get(i - 1), element);
    }

    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var fieldSchemas = JsonNodeFactory.instance.objectNode();
      for (final var field : this.fields) {
        fieldSchemas.set(field.name, anchors.lookup(field.valueParser));
      }

      final var requiredFields = JsonNodeFactory.instance.arrayNode();
      for (final var field : this.fields) {
        if (!field.isOptional) requiredFields.add(field.name);
      }

      final var node = JsonNodeFactory.instance.objectNode();
      node.put("type", "object");
      node.set("properties", fieldSchemas);
      node.set("required", requiredFields);
      node.set("additionalProperties",
               (this.acceptUnspecified) ? BooleanNode.TRUE : BooleanNode.FALSE);
      return node;
    }

    private Optional<FieldSpec<?>> getFieldSpec(final String name) {
      for (final var field : this.fields) {
        if (field.name.equals(name)) return Optional.of(field);
      }
      return Optional.empty();
    }

    private static JsonParseResult<?> parseField(final FieldSpec<?> field, final JsonNode obj) {
      final JsonParseResult<?> result;
      if (field.isOptional) {
        if (!obj.has(field.name)) {
          result = JsonParseResult.success(Optional.empty());
        } else {
          result = field.valueParser.parse(obj.get(field.name)).mapSuccess(Optional::of);
        }
      } else {
        if (!obj.has(field.name)) {
          result = JsonParseResult.failure("required field not present");
        } else {
          result = field.valueParser.parse(obj.get(field.name));
        }
      }

      return result.prependBreadcrumb(Breadcrumb.ofString(field.name));
    }

    // PRECONDITION: `value` is of type `Ti` or `Optional<Ti>` (depending on `field.isOptional`).
    private static <Ti>
    void unparseField(final ObjectNode obj, final FieldSpec<Ti> field, final Object value) {
      if (field.isOptional) { // type(value) = Optional<Ti>
        // SAFETY: By precondition.
        @SuppressWarnings("unchecked")
        final var result = (Optional<Ti>) value;

        result.ifPresent($ -> obj.set(field.name, field.valueParser.unparse($)));
      } else { // type(value) = Ti
        // SAFETY: By precondition.
        @SuppressWarnings("unchecked")
        final var result = (Ti) value;

        obj.set(field.name, field.valueParser.unparse(result));
      }
    }

    public <S>
    VariadicProductParser<Pair<T, S>> field(final String key, final JsonParser<S> valueParser) {
      throwIfKeyExists(key);

      return new VariadicProductParser<>(
          extend(this.fields, new FieldSpec<>(key, valueParser, false)),
          this.acceptUnspecified);
    }

    public <S>
    VariadicProductParser<Pair<T, Optional<S>>> optionalField(final String key, final JsonParser<S> valueParser) {
      throwIfKeyExists(key);

      return new VariadicProductParser<>(
          extend(this.fields, new FieldSpec<>(key, valueParser, true)),
          this.acceptUnspecified);
    }

    private void throwIfKeyExists(final String key) {
      for (final var field : fields) {
        if (Objects.equals(field.name, key)) {
          throw new RuntimeException("Parser already defined for key `" + key + "`");
        }
      }
    }

    public JsonParser<T> rest() {
      return new VariadicProductParser<>(this.fields, true);
    }

    private static <T> List<T> extend(final List<T> list, final T element) {
      final var fields = new ArrayList<>(list);
      fields.add(element);
      return fields;
    }
  }

  private record FieldSpec<S>(String name, JsonParser<S> valueParser, boolean isOptional) {}

  /**
   * Documents a parameter that takes ownership of a provided value.
   */
  // Amusingly, TYPE_USE is necessary for IntelliJ to display the annotation in completions.
  @Target(ElementType.TYPE_USE)
  private @interface Owned {}
}
