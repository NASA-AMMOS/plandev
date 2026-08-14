package gov.nasa.jpl.aerie.merlin.protocol.types;

/**
 * A <a href="https://en.wikipedia.org/wiki/Unit_type">unit type</a> with only one value (which can thus hold
 * no useful information).
 *
 * <p>{@code Unit} is useful as a return type for generic functions that would otherwise return {@code void},
 * and is preferable to Java's {@link Void} type (which is inhabited only by {@code null}).</p>
 */
public enum Unit { UNIT }
