package gov.nasa.jpl.aerie.merlin.protocol.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A serializable description of the structure of a value such as an activity parameter.
 *
 * For instance, if an activity accepts two parameters, each of which is a 3D point in space,
 * then the schema for each point will be a series of three real number primitives. The schema
 * for the activity itself will be a series of two instances of the schema for points.
 *
 * This is useful for providing information to mission-agnostic front-end applications, which
 * may (for instance) present a specialized UI for each primitive type, or a specialized means
 * of presenting trees of parameters.
 *
 * This class is implemented using the Visitor pattern, following the approach considered at
 * http://blog.higher-order.com/blog/2009/08/21/structural-pattern-matching-in-java/.
 */
// TODO: We will likely want to extend ValueSchema to support common semantic types
//   such as DateTime objects (which might otherwise be serialized as strings).
public sealed interface ValueSchema {

  /**
   * Calls the appropriate method of the passed Visitor depending on the kind of data
   * contained by this object.
   *
   * @param visitor The operation to be performed on the data contained by this object.
   * @param <T> The return type produced by the visiting operation.
   * @return The result of calling `visitor.onX()`, where `X` depends on the kind of data
   *   contained in this object.
   */
  <T> T match(Visitor<T> visitor);

  /**
   * An operation to be performed over the schema described by a ValueSchema.
   *
   * A method must be defined for each kind of data that a ValueSchema may describe.
   * This may be likened to the pattern-matching capability built into languages such as Rust
   * or Haskell.
   *
   * Most clients will prefer to inherit from {@link DefaultVisitor}, which returns `Optional.empty()`
   * for any unimplemented methods.
   *
   * @param <T> The return type of the operation represented by this Visitor.
   */
  interface Visitor<T> {
    T onReal();
    T onInt();
    T onBoolean();
    T onString();
    T onDuration();
    T onPath();
    T onSeries(ValueSchema value);
    T onStruct(Map<String, ValueSchema> value);
    T onVariant(List<Variant> variants);
    T onMeta(Map<String, SerializedValue> metadata, ValueSchema target);
  }

  record RealSchema() implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onReal();
    }
  }

  record IntSchema() implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onInt();
    }
  }

  record BooleanSchema() implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onBoolean();
    }
  }

  record StringSchema() implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onString();
    }
  }

  record DurationSchema() implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onDuration();
    }
  }

  record PathSchema() implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onPath();
    }
  }

  record SeriesSchema(ValueSchema value) implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onSeries(value);
    }
  }

  record StructSchema(Map<String, ValueSchema> value) implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onStruct(value);
    }
  }

  record VariantSchema(List<Variant> variants) implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onVariant(variants);
    }
  }

  record MetaSchema(Map<String, SerializedValue> metadata, ValueSchema target) implements ValueSchema {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onMeta(metadata, target);
    }
  }

  /**
   * Creates a {@link ValueSchema} representing a homogeneous list of elements matching
   *   the provided {@link ValueSchema}s.
   *
   * @param value A {@link ValueSchema}.
   * @return A new {@link ValueSchema} representing a homogeneous list of elements.
   */
  static ValueSchema ofSeries(final ValueSchema value) {
    Objects.requireNonNull(value);
    return new SeriesSchema(value);
  }

  /**
   * Creates a {@link ValueSchema} representing a heterogeneous set of named elements matching
   *   the associated {@link ValueSchema}s.
   *
   * @param map Any set of named {@link ValueSchema}s.
   * @return A new {@link ValueSchema} representing a heterogeneous set of named {@link ValueSchema}s.
   */
  static ValueSchema ofStruct(final Map<String, ValueSchema> map) {
    for (final var v : Objects.requireNonNull(map).values()) Objects.requireNonNull(v);
    final var value = Map.copyOf(map);
    return new StructSchema(value);
  }

  /**
   * Creates a {@link ValueSchema} representing an {@link Enum} type.
   *
   * @return A new {@link ValueSchema} representing an {@link Enum} type.
   */
  static ValueSchema ofVariant(final List<Variant> variants) {
    Objects.requireNonNull(variants);
    return new VariantSchema(variants);
  }

  /**
   * Augments a {@link ValueSchema} with the given metadata.
   * If the underlying ValueSchema is already a MetaSchema, the new MetaSchema will be merged with the underlying one.
   * In the event of a key collision, the given `metadata` takes precedence over the target's `metadata`
   * @return A new {@link ValueSchema} wrapping the original schema.
   */
  static ValueSchema withMeta(final String key, final SerializedValue metadata, final ValueSchema target) {
    final var metaSchema = target.asMeta();
    if (metaSchema.isPresent()) {
      final var newMap = new HashMap<>(metaSchema.get().metadata);
      newMap.put(key, metadata);
      return new MetaSchema(newMap, metaSchema.get().target());
    } else {
      return new MetaSchema(Map.of(key, metadata), target);
    }
  }

  ValueSchema REAL = new RealSchema();
  ValueSchema INT = new IntSchema();
  ValueSchema BOOLEAN = new BooleanSchema();
  ValueSchema STRING = new StringSchema();
  ValueSchema DURATION = new DurationSchema();
  ValueSchema PATH = new PathSchema();

  /**
   * Provides a default case on top of the base Visitor.
   *
   * This interface routes all cases to the `onDefault` implementation by default. Each case may be overridden
   * independently to give distinct behavior.
   *
   * @param <T> The return type of the operation represented by this {@link Visitor}.
   */
  abstract class DefaultVisitor<T> implements Visitor<T> {
    protected abstract T onDefault();

    @Override
    public T onReal() {
      return this.onDefault();
    }

    @Override
    public T onInt() {
      return this.onDefault();
    }

    @Override
    public T onBoolean() {
      return this.onDefault();
    }

    @Override
    public T onDuration() {
      return this.onDefault();
    }

    @Override
    public T onString() {
      return this.onDefault();
    }

    @Override
    public T onPath() {
      return this.onDefault();
    }

    @Override
    public T onSeries(final ValueSchema value) {
      return this.onDefault();
    }

    @Override
    public T onStruct(final Map<String, ValueSchema> value) {
      return this.onDefault();
    }

    @Override
    public T onVariant(final List<Variant> variants) {
      return this.onDefault();
    }

    @Override
    public T onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) { return this.onDefault(); }
  }

  /**
   * A helper base class implementing {@code Visitor<Optional<T>>} for any result type {@code T}.
   *
   * By default, all variants return {@code Optional.empty}.
   */
  public static class OptionalVisitor<T> extends DefaultVisitor<Optional<T>> {
    @Override
    public final Optional<T> onDefault() {
      return Optional.empty();
    }

    @Override
    public Optional<T> onVariant(final List<Variant> variants) {
      return onDefault();
    }
  }

  /**
   * Asserts that this object represents a real number.
   *
   * @return A non-empty {@link Optional} if this object represents a real number.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Unit> asReal() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Unit> onReal() {
        return Optional.of(Unit.UNIT);
      }
    });
  }

  /**
   * Asserts that this object represents an integer.
   *
   * @return A non-empty {@link Optional} if this object represents an integer.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Unit> asInt() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Unit> onInt() {
        return Optional.of(Unit.UNIT);
      }
    });
  }

  /**
   * Asserts that this object represents a boolean.
   *
   * @return A non-empty {@link Optional} if this object represents an boolean.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Unit> asBoolean() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Unit> onBoolean() {
        return Optional.of(Unit.UNIT);
      }
    });
  }

  /**
   * Asserts that this object represents a String.
   *
   * @return A non-empty {@link Optional} if this object represents a String.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Unit> asString() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Unit> onString() {
        return Optional.of(Unit.UNIT);
      }
    });
  }

  /**
   * Asserts that this object represents a Duration.
   *
   * @return A non-empty {@link Optional} if this object represents a Duration.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Unit> asDuration() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Unit> onDuration() {
        return Optional.of(Unit.UNIT);
      }
    });
  }

  /**
   * Asserts that this object represents a Path.
   *
   * @return A non-empty {@link Optional} if this object represents a Path.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Unit> asPath() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Unit> onPath() {
        return Optional.of(Unit.UNIT);
      }
    });
  }

  /**
   * Attempts to access this schema as a list of {@code ValueSchema}s.
   *
   * @return An {@link Optional} containing a schema for elements of a homogeneous list if this
   *   object represents a series. Otherwise, returns an empty {@link Optional}.
   */
  default Optional<ValueSchema> asSeries() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<ValueSchema> onSeries(final ValueSchema value) {
        return Optional.of(value);
      }
    });
  }

  /**
   * Attempts to access this schema as a map of named {@code ValueSchema}s.
   *
   * @return An {@link Optional} containing a map if this object represents a map.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Map<String, ValueSchema>> asStruct() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Map<String, ValueSchema>> onStruct(final Map<String, ValueSchema> value) {
        return Optional.of(value);
      }
    });
  }

  /**
   * Asserts that this object represents an enumeration.
   *
   * @return An {@link Optional} containing an enum if this object represents an enumeration.
   *   Otherwise, returns an empty {@link Optional}
   */
  default Optional<List<Variant>> asVariant() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<List<Variant>> onVariant(final List<Variant> variants) {
        return Optional.of(variants);
      }
    });
  }

  /**
   * Asserts that this object represents a metadata node.
   *
   * @return An {@link Optional} containing the Metadata node
   *   Otherwise, returns an empty {@link Optional}
   */
  default Optional<MetaSchema> asMeta() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<MetaSchema> onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) {
        return Optional.of(new MetaSchema(metadata, target));
      }
    });
  }

  /**
   * @param key The unique internal name of this variant
   * @param label The user-facing presentation of this variant
   */
  record Variant(String key, String label) {}
}
