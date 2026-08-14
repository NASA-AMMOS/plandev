package gov.nasa.jpl.aerie.json;

/**
 * A type with only one non-null value. This is a convenient alternative to using a null {@code Object} in situations
 * where no meaningful information can be communicated, and yet `void` is not an appropriate type, such as in generic
 * contexts.
 *
 * <p> This type should only be referenced in a JSON parsing context.
 * If a unit type is desired for other purposes, one should be defined for that context specifically.
 * No other component should depend on this library just because it defines this type. </p>
 */
public enum Unit { UNIT }
