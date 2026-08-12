package gov.nasa.jpl.aerie.merlin.protocol.types;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A serializable representation of a mission model-specific activity parameter domain object.
 *
 * A {@link SerializedValue} is a mission model-agnostic representation of the data in such an
 * activity parameter, structured as serializable primitives composed using sequences and maps.
 *
 * This class is implemented using the Visitor pattern, following the approach considered at
 * http://blog.higher-order.com/blog/2009/08/21/structural-pattern-matching-in-java/.
 * Because a (de)serialization format (such as JSON) may have a fixed set of primitives types
 * from which data may be composed. SerializedValue ensures that all data boils down to
 * this fixed set of primitives.
 *
 * Note that, if the disk representation of a {@link SerializedValue} could have multiple parses
 * -- multiple Java objects that it could deserialize to -- then there would an unresolvable
 * ambiguity in how to deserialize that disk representation. If {@link SerializedValue} could be
 * freely subclassed, then such ambiguities would be inevitable (not to mention that deserialization
 * code would need to know about all possible subclasses for deserialization). The Visitor
 * pattern on a class closed to extension allows us to guarantee that no ambiguity occurs.
 */
public sealed interface SerializedValue extends Comparable<SerializedValue> {
  SerializedValue NULL = SerializedValue.ofNull();

  /**
   * Calls the appropriate method of the passed {@link Visitor} depending on the kind of data
   * contained by this object.
   *
   * @param visitor The operation to be performed on the data contained by this object.
   * @param <T> The return type produced by the visiting operation.
   * @return The result of calling {@code visitor.onX()}, where {@code X} depends on the
   *   kind of data contained in this object.
   */
  <T> T match(Visitor<T> visitor);

  Object getValue();

  /**
   * An operation to be performed on the data contained in a {@link SerializedValue}.
   *
   * A method must be defined for each kind of data that a {@link SerializedValue} may contain.
   * This may be likened to the pattern-matching capability built into languages such as Rust
   * or Haskell.
   *
   * Most clients will prefer to inherit from {@link OptionalVisitor}, which returns `Optional.empty()`
   * for any unimplemented methods.
   *
   * @param <T> The return type of the operation represented by this {@link Visitor}.
   */
  interface Visitor<T> {
    T onNull();
    T onNumeric(BigDecimal value);
    T onDouble(double value);
    T onBoolean(boolean value);
    T onString(String value);
    T onMap(Map<String, SerializedValue> value);
    T onList(List<SerializedValue> value);
  }

  @Override
  default int compareTo(final SerializedValue o) {
    return gov.nasa.jpl.aerie.merlin.protocol.types.ObjectComparator.getInstance().compare(this.getValue(), o.getValue());
  }


  record NullValue() implements SerializedValue {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onNull();
    }

    @Override
    public Object getValue() {
      return null;
    }

    @Override
    public int compareTo(final SerializedValue o) {
      if (o instanceof NullValue) return 0;
      return -1;
    }
  }

  record NumericValue(BigDecimal value) implements SerializedValue {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onNumeric(value);
    }

    @Override
    public BigDecimal getValue() {
      return value;
    }

    // `BigDecimal#equals` is too strict -- values differing only in representation need to be considered the same.
    @Override
    public boolean equals(final Object obj) {
      if (!(obj instanceof NumericValue other)) return false;
      return (this.value.compareTo(other.value) == 0);
    }

    @Override
    public int hashCode() {
      return this.value.stripTrailingZeros().hashCode();
    }
  }

  record DoubleValue(double value) implements SerializedValue {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onDouble(value);
    }

    @Override
    public Double getValue() {
      return value;
    }

    @Override
    public boolean equals(final Object obj) {
      if (!(obj instanceof DoubleValue other)) return false;
      return Double.compare(this.value, other.value) == 0;
    }

    @Override
    public int hashCode() {
      return Double.hashCode(value);
    }
  }

  record BooleanValue(boolean value) implements SerializedValue {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onBoolean(value);
    }
    @Override
    public Boolean getValue() {
      return value;
    }
  }

  record StringValue(String value) implements SerializedValue {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onString(value);
    }
    @Override
    public String getValue() {
      return value;
    }
  }

  record MapValue(Map<String, SerializedValue> map) implements SerializedValue {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onMap(map);
    }
    @Override
    public Map<String, SerializedValue> getValue() {
      return map;
    }
  }

  record ListValue(List<SerializedValue> list) implements SerializedValue {
    @Override
    public <T> T match(final Visitor<T> visitor) {
      return visitor.onList(list);
    }
    @Override
    public List<SerializedValue> getValue() {
      return list;
    }
  }

  /**
   * Creates a {@link SerializedValue} containing a null value.
   *
   * @return A new {@link SerializedValue} containing a null value.
   */
  private static SerializedValue ofNull() {
    return new NullValue();
  }

  /**
   * Creates a {@link SerializedValue} containing an arbitrary-precision number.
   *
   * @param value Any {@link BigDecimal} value.
   * @return A new {@link SerializedValue} containing an arbitrary-precision number.
   */
  static SerializedValue of(final BigDecimal value) {
    return new NumericValue(value);
  }

  /**
   * Creates a {@link SerializedValue} containing a real number.
   *
   * @param value Any double} value.
   * @return A new {@link SerializedValue} containing a real number.
   */
  static SerializedValue of(final double value) {
    return new DoubleValue(value);
  }

  /**
   * Creates a {@link SerializedValue} containing an integral number.
   *
   * @param value Any long value.
   * @return A new {@link SerializedValue} containing an integral number.
   */
  static SerializedValue of(final long value) {
    return new NumericValue(BigDecimal.valueOf(value));
  }

  /**
   * Creates a {@link SerializedValue} containing a boolean.
   *
   * @param value Any boolean value.
   * @return A new {@link SerializedValue} containing a boolean.
   */
  static SerializedValue of(final boolean value) {
    return new BooleanValue(value);
  }

  /**
   * Creates a {@link SerializedValue} containing a {@link String}.
   *
   * @param value Any {@link String} value.
   * @return A new {@link SerializedValue} containing a {@link String}.
   */
  static SerializedValue of(final String value) {
    Objects.requireNonNull(value);
    return new StringValue(value);
  }

  /**
   * Creates a {@link SerializedValue} containing a set of named {@link SerializedValue}s.
   *
   * @param map Any set of named {@link SerializedValue}s.
   * @return A new {@link SerializedValue} containing a set of named {@link SerializedValue}s.
   */
  static SerializedValue of(final Map<String, SerializedValue> map) {
    for (final var v : Objects.requireNonNull(map).values()) Objects.requireNonNull(v);
    final var value = Map.copyOf(map);
    return new MapValue(value);
  }

  /**
   * Creates a {@link SerializedValue} containing a list of {@link SerializedValue}s.
   *
   * @param list Any list of {@link SerializedValue}s.
   * @return A new SerializedValue containing a list of {@link SerializedValue}s.
   */
  static SerializedValue of(final List<SerializedValue> list) {
    for (final var v : Objects.requireNonNull(list)) Objects.requireNonNull(v);
    final var value = List.copyOf(list);
    return new ListValue(value);
  }


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
    public T onNull() {
      return this.onDefault();
    }

    @Override
    public T onNumeric(final BigDecimal value) {
      return this.onDefault();
    }

    @Override
    public T onDouble(final double value) {
      return this.onDefault();
    }

    @Override
    public T onBoolean(final boolean value) {
      return this.onDefault();
    }

    @Override
    public T onString(final String value) {
      return this.onDefault();
    }

    @Override
    public T onMap(final Map<String, SerializedValue> value) {
      return this.onDefault();
    }

    @Override
    public T onList(final List<SerializedValue> value) {
      return this.onDefault();
    }
  }

  /**
   * A helper base class implementing {@code Visitor<Optional<T>>} for any result type {@code T}.
   *
   * By default, all variants return {@code Optional.empty}.
   */
  abstract class OptionalVisitor<T> extends DefaultVisitor<Optional<T>> {
    @Override
    protected Optional<T> onDefault() {
      return Optional.empty();
    }
  }

  /**
   * Determines if this object represents a null value.
   *
   * @return True if this object represents a null value, and false otherwise.
   */
  default boolean isNull() {
    return this.match(new DefaultVisitor<>() {
      @Override
      public Boolean onNull() {
        return true;
      }

      @Override
      protected Boolean onDefault() {
        return false;
      }
    });
  }

  /**
   * Attempts to access the data in this object as an arbitrary-precision number.
   *
   * @return An {@link Optional} containing a BigDecimal if this object contains an arbitrary-precision number.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<BigDecimal> asNumeric() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<BigDecimal> onNumeric(final BigDecimal value) {
        return Optional.of(value);
      }

      @Override
      public Optional<BigDecimal> onDouble(final double value) {
        return Optional.of(BigDecimal.valueOf(value));
      }
    });
  }

  /**
   * Attempts to access the data in this object as a real number.
   *
   * @return An {@link Optional} containing a double if this object contains a real number.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Double> asReal() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Double> onNumeric(final BigDecimal value) {
        return Optional.of(value.doubleValue());
      }

      @Override
      public Optional<Double> onDouble(final double value) {
        return Optional.of(value);
      }
    });
  }

  /**
   * Attempts to access the data in this object as an integral number.
   *
   * @return An {@link Optional} containing a long if this object contains an integral number.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Long> asInt() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Long> onNumeric(final BigDecimal value) {
        try {
          return Optional.of(value.longValueExact());
        } catch (final ArithmeticException ex) {
          return Optional.empty();
        }
      }

      @Override
      public Optional<Long> onDouble(final double value) {
        if (value % 1 == 0) return Optional.of((long) value);
        return Optional.empty();
      }
    });
  }

  /**
   * Attempts to access the data in this object as a boolean.
   *
   * @return An {@link Optional} containing a boolean if this object contains a boolean.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Boolean> asBoolean() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Boolean> onBoolean(final boolean value) {
        return Optional.of(value);
      }
    });
  }

  /**
   * Attempts to access the data in this object as a {@link String}.
   *
   * @return An {@link Optional} containing a {@link String} if this object contains a {@link String}.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<String> asString() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<String> onString(final String value) {
        return Optional.of(value);
      }
    });
  }

  /**
   * Attempts to access the data in this object as a map of named {@code SerializedValue}s.
   *
   * @return An {@link Optional} containing a map if this object contains a map.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<Map<String, SerializedValue>> asMap() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<Map<String, SerializedValue>> onMap(final Map<String, SerializedValue> value) {
        return Optional.of(value);
      }
    });
  }

  /**
   * Attempts to access the data in this object as a list of {@code SerializedValue}s.
   *
   * @return An {@link Optional} containing a list if this object contains a list.
   *   Otherwise, returns an empty {@link Optional}.
   */
  default Optional<List<SerializedValue>> asList() {
    return this.match(new OptionalVisitor<>() {
      @Override
      public Optional<List<SerializedValue>> onList(final List<SerializedValue> value) {
        return Optional.of(value);
      }
    });
  }

  /**
   * Writes this value directly to a Jackson {@link JsonGenerator}, bypassing intermediate JsonNode allocations.
   */
  default void writeTo(final JsonGenerator gen) throws IOException {
    try {
      this.match(new Visitor<Void>() {
        @Override
        public Void onNull() {
          try { gen.writeNull(); } catch (final IOException e) { throw new UncheckedIOException(e); }
          return null;
        }
        @Override
        public Void onNumeric(final BigDecimal value) {
          try { gen.writeNumber(value); } catch (final IOException e) { throw new UncheckedIOException(e); }
          return null;
        }
        @Override
        public Void onDouble(final double value) {
          try { gen.writeNumber(value); } catch (final IOException e) { throw new UncheckedIOException(e); }
          return null;
        }
        @Override
        public Void onBoolean(final boolean value) {
          try { gen.writeBoolean(value); } catch (final IOException e) { throw new UncheckedIOException(e); }
          return null;
        }
        @Override
        public Void onString(final String value) {
          try { gen.writeString(value); } catch (final IOException e) { throw new UncheckedIOException(e); }
          return null;
        }
        @Override
        public Void onMap(final Map<String, SerializedValue> value) {
          try {
            gen.writeStartObject();
            for (final var entry : value.entrySet()) {
              gen.writeFieldName(entry.getKey());
              entry.getValue().writeTo(gen);
            }
            gen.writeEndObject();
          } catch (final IOException e) { throw new UncheckedIOException(e); }
          return null;
        }
        @Override
        public Void onList(final List<SerializedValue> value) {
          try {
            gen.writeStartArray();
            for (final var element : value) {
              element.writeTo(gen);
            }
            gen.writeEndArray();
          } catch (final IOException e) { throw new UncheckedIOException(e); }
          return null;
        }
      });
    } catch (final UncheckedIOException e) {
      throw e.getCause();
    }
  }

  final class UncheckedIOException extends RuntimeException {
    UncheckedIOException(final IOException cause) { super(cause); }
    @Override public IOException getCause() { return (IOException) super.getCause(); }
  }

  /**
   * Serializes this value to a JSON string using Jackson streaming.
   */
  default String toJsonString() throws IOException {
    try {
      final var writer = new StringWriter();
      try (final var gen = new com.fasterxml.jackson.core.JsonFactory().createGenerator(writer)) {
        this.writeTo(gen);
      }
      return writer.toString();
    } catch (final UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
