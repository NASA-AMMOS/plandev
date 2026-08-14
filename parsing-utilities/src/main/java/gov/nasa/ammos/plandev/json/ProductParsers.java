package gov.nasa.jpl.aerie.json;

import org.apache.commons.lang3.tuple.Pair;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
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
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder()
          .add("type", "object")
          .add("additionalProperties", JsonValue.FALSE)
          .build();
    }

    @Override
    public JsonParseResult<Unit> parse(final JsonValue json) {
      if (!(json instanceof JsonObject)) return JsonParseResult.failure("expected object");
      if (!json.asJsonObject().isEmpty()) return JsonParseResult.failure("expected empty object");

      return JsonParseResult.success(Unit.UNIT);
    }

    @Override
    public JsonObject unparse(final Unit value) {
      return Json.createObjectBuilder().build();
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
        public JsonObject getSchema(final SchemaCache anchors) {
          return Json
              .createObjectBuilder()
              .add("type", "object")
              .build();
        }

        @Override
        public JsonParseResult<Unit> parse(final JsonValue json) {
          if (!(json instanceof JsonObject)) return JsonParseResult.failure("expected object");
          return JsonParseResult.success(Unit.UNIT);
        }

        @Override
        public JsonObject unparse(final Unit value) {
          return Json.createObjectBuilder().build();
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
    public JsonParseResult<T> parse(final JsonValue json) {
      if (!(json instanceof JsonObject obj)) return JsonParseResult.failure("expected object");

      if (!this.acceptUnspecified) {
        // Detect unexpected fields in the json
        // TODO: We should return all unexpected fields, but currently
        //       we can only return one failure reason, with one set of
        //       breadcrumbs. When we allow multiple failure reasons
        //       this should be updated to build a failure reason for
        //       each unexpected parameter provided
        for (final var param : obj.entrySet()) {
          final var name = param.getKey();

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
      //
      // PRECONDITION: accumulated result is of type T1
      // INVARIANT: accumulated result is of type Pair<...Pair<T1, T2>..., Ti>
      //   where `i` is the number of fields iterated through.
      // POSTCONDITION: accumulated result is of type T = Pair<...Pair<Pair<T1, T2>, T3>..., Tn>.
      final var iter = this.fields.iterator();
      var accumulator = parseField(iter.next(), obj);
      while (iter.hasNext()) {
        accumulator = accumulator.parWith(parseField(iter.next(), obj)).mapSuccess(x -> x);
      }

      return accumulator.mapSuccess(result -> {
        // SAFETY: established by loop invariant.
        @SuppressWarnings("unchecked")
        final var tmp = (T) result;
        return tmp;
      });
    }

    @Override
    public JsonObject unparse(final T value) {
      final var builder = Json.createObjectBuilder();

      unparse(builder, value, fields.size());

      return builder.build();
    }

    private JsonObjectBuilder unparse(final JsonObjectBuilder builder, Object value, int i) {
      if (i <= 0) return builder; // This shouldn't happen, but doing nothing is a safe behavior.

      final Object element;
      final JsonObjectBuilder resultBuilder;
      if (i == 1) { // type(value) = Ti
        element = value;
        resultBuilder = builder;
      } else { // type(value) = Pair<..., Ti>
        final var pair = (Pair<?, ?>) value;

        element = pair.getRight();
        resultBuilder = unparse(builder, pair.getLeft(), i - 1);
      }

      return unparseField(resultBuilder, this.fields.get(i - 1), element);
    }

    @Override
    public JsonObject getSchema(final SchemaCache anchors) {
      final var fieldSchemas = Json.createObjectBuilder();
      for (final var field : this.fields) {
        fieldSchemas.add(field.name, anchors.lookup(field.valueParser));
      }

      final var requiredFields = Json.createArrayBuilder();
      for (final var field : this.fields) {
        if (!field.isOptional) requiredFields.add(field.name);
      }

      return Json
          .createObjectBuilder()
          // an object containing all and only the listed properties
          .add("type", "object")
          .add("properties", fieldSchemas)
          .add("required", // all
               requiredFields)
          .add("additionalProperties", // and only
               (this.acceptUnspecified) ? JsonValue.TRUE : JsonValue.FALSE)
          .build();
    }

    private Optional<FieldSpec<?>> getFieldSpec(final String name) {
      for (final var field : this.fields) {
        if (field.name.equals(name)) return Optional.of(field);
      }
      return Optional.empty();
    }

    private static JsonParseResult<?> parseField(final FieldSpec<?> field, final JsonObject obj) {
      final JsonParseResult<?> result;
      if (field.isOptional) {
        if (!obj.containsKey(field.name)) {
          result = JsonParseResult.success(Optional.empty());
        } else {
          result = field.valueParser.parse(obj.get(field.name)).mapSuccess(Optional::of);
        }
      } else {
        if (!obj.containsKey(field.name)) {
          result = JsonParseResult.failure("required field not present");
        } else {
          result = field.valueParser.parse(obj.get(field.name));
        }
      }

      return result.prependBreadcrumb(Breadcrumb.ofString(field.name));
    }

    // PRECONDITION: `value` is of type `Ti` or `Optional<Ti>` (depending on `field.isOptional`).
    private static <Ti>
    JsonObjectBuilder unparseField(final JsonObjectBuilder builder, final FieldSpec<Ti> field, final Object value) {
      if (field.isOptional) { // type(value) = Optional<Ti>
        // SAFETY: By precondition.
        @SuppressWarnings("unchecked")
        final var result = (Optional<Ti>) value;

        result.ifPresent($ -> builder.add(field.name, field.valueParser.unparse($)));
        return builder;
      } else { // type(value) = Ti
        // SAFETY: By precondition.
        @SuppressWarnings("unchecked")
        final var result = (Ti) value;

        return builder.add(field.name, field.valueParser.unparse(result));
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
      // It's pretty inefficient to copy the set of field specs every time.
      // We don't expect our sets to grow very large, so this probably isn't a problem.
      // In the future, we can use a persistent collections library to efficiently append.
      final var fields = new ArrayList<>(list);
      fields.add(element);
      return fields;
    }
  }

  private record FieldSpec<S>(String name, JsonParser<S> valueParser, boolean isOptional) {}

  /**
   * Documents a parameter that takes ownership of a provided value.
   *
   * For instance, after invoking a method {@code void foo(@Owned List<?> list)} as {@code foo(xs)},
   * the value held by the variable {@code xs} should be considered moved and inaccessible to the caller.
   */
  // Amusingly, TYPE_USE is necessary for IntelliJ to display the annotation in completions.
  @Target(ElementType.TYPE_USE)
  private @interface Owned {}
}
