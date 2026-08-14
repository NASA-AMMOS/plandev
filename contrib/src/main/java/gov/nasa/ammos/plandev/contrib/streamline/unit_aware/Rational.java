package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

import static java.lang.Integer.signum;
import static org.apache.commons.math3.util.ArithmeticUtils.gcd;

/**
 * Lightweight exact rational number, used primarily for tracking dimensionality
 * in the QUDV system.
 */
public record Rational(int numerator, int denominator) implements Comparable<Rational> {
  public static final Rational ZERO = new Rational(0, 1);
  public static final Rational ONE = new Rational(1, 1);

  public Rational(final int numerator, final int denominator) {
    if (denominator == 0) {
      throw new ArithmeticException("Cannot create a Rational with 0 denominator.");
    }

    // Normalize by dividing by the GCD and forcing the denominator to be positive.
    final int gcd = gcd(numerator, denominator);
    final int s = signum(denominator);
    this.numerator = s * numerator / gcd;
    this.denominator = s * denominator / gcd;
  }

  public static Rational rational(final int numerator, final int denominator) {
    return new Rational(numerator, denominator);
  }

  public static Rational rational(final int value) {
    return new Rational(value, 1);
  }

  public Rational add(Rational other) {
    return new Rational(
        numerator * other.denominator + denominator * other.numerator,
        denominator * other.denominator);
  }

  public Rational negate() {
    return new Rational(-numerator, denominator);
  }

  public Rational subtract(Rational other) {
    return this.add(other.negate());
  }

  public Rational multiply(Rational other) {
    return new Rational(
        numerator * other.numerator,
        denominator * other.denominator);
  }

  /**
   * Flip numerator and divisor, equivalent to raising to the power -1.
   */
  public Rational invert() {
    return new Rational(denominator, numerator);
  }

  public Rational divide(Rational other) {
    return this.multiply(other.invert());
  }

  @Override
  public int compareTo(final Rational o) {
    return Integer.compare(numerator * o.denominator, denominator * o.numerator);
  }

  /**
   * Approximate this as a floating-point number.
   */
  public double doubleValue() {
    return ((double) numerator) / denominator;
  }
}
