package gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiry;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ExpiringMonad;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import org.apache.commons.math3.analysis.solvers.LaguerreSolver;
import org.apache.commons.math3.complex.Complex;

import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.expiring;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.NEVER;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.EPSILON;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static org.apache.commons.math3.analysis.polynomials.PolynomialsUtils.shift;

/**
 * An implementation of Polynomial Dynamics
 * @param coefficients an array of polynomial coefficients, where an entry's index in the array corresponds to the degree of that coefficient
 *
 * @apiNote The units of `t` are seconds
 */
public record Polynomial(double[] coefficients) implements Dynamics<Double, Polynomial> {

  // TODO: Add Duration parameter for unit of formal parameter?
  /**
   *
   * @param coefficients the polynomial coefficients, from least to most significant
   * @return a Polynomial with the given coefficients
   */
  public static Polynomial polynomial(double... coefficients) {
    int n = coefficients.length;
    if (n == 0) {
      return new Polynomial(new double[] { 0.0 });
    }
    while (n > 1 && coefficients[n - 1] == 0) --n;
    for (int m = 0; m < n; ++m) {
      // Any NaN coefficient invalidates the whole polynomial
      if (Double.isNaN(coefficients[m])) return new Polynomial(new double[] { Double.NaN });
      // Infinite coefficients invalidate later terms
      if (Double.isInfinite(coefficients[m])) {
        n = m + 1;
        break;
      }
    }
    return new Polynomial(Arrays.copyOf(coefficients, n));
  }

  @Override
  public Double extract() {
    return coefficients()[0];
  }

  @Override
  public Polynomial step(Duration t) {
    return t.equals(ZERO) ? this : polynomial(shift(coefficients(), t.ratioOver(SECOND)));
  }

  public int degree() {
    return coefficients().length - 1;
  }

  public boolean isConstant() {
    return degree() == 0;
  }

  public boolean isNonFinite() {
    return !Double.isFinite(coefficients[degree()]);
  }

  public Polynomial add(Polynomial other) {
    final double[] coefficients = coefficients();
    final double[] otherCoefficients = other.coefficients();
    final int minLength = Math.min(coefficients.length, otherCoefficients.length);
    final int maxLength = Math.max(coefficients.length, otherCoefficients.length);
    final double[] newCoefficients = new double[maxLength];
    for (int i = 0; i < minLength; ++i) {
      newCoefficients[i] = coefficients[i] + otherCoefficients[i];
    }
    if (coefficients.length > minLength)
      System.arraycopy(coefficients, minLength, newCoefficients, minLength, coefficients.length - minLength);
    if (otherCoefficients.length > minLength)
      System.arraycopy(
          otherCoefficients, minLength, newCoefficients, minLength, otherCoefficients.length - minLength);
    return polynomial(newCoefficients);
  }

  public Polynomial subtract(Polynomial other) {
    return add(other.multiply(polynomial(-1)));
  }

  public Polynomial multiply(Polynomial other) {
    final double[] coefficients = coefficients();
    final double[] otherCoefficients = other.coefficients();
    // Length = degree + 1, so
    // new length = 1 + new degree
    //   = 1 + (degree + other.degree)
    //   = 1 + (length - 1 + other.length - 1)
    //   = length + other.length - 1
    final double[] newCoefficients = new double[coefficients.length + otherCoefficients.length - 1];
    for (int exponent = 0; exponent < newCoefficients.length; ++exponent) {
      newCoefficients[exponent] = 0.0;
      // 0 <= k < length and 0 <= exponent - k < other.length
      // implies k >= 0, k > exponent - other.length,
      // k < length, and k <= exponent
      for (int k = Math.max(0, exponent - otherCoefficients.length + 1);
           k < Math.min(coefficients.length, exponent + 1);
           ++k) {
        newCoefficients[exponent] += coefficients[k] * otherCoefficients[exponent - k];
      }
    }
    return polynomial(newCoefficients);
  }

  public Polynomial divide(double scalar) {
    final double[] coefficients = coefficients();
    final double[] newCoefficients = new double[coefficients.length];
    for (int i = 0; i < coefficients.length; ++i) {
      newCoefficients[i] = coefficients[i] / scalar;
    }
    return polynomial(newCoefficients);
  }

  public Polynomial integral(double startingValue) {
    final double[] coefficients = coefficients();
    final double[] newCoefficients = new double[coefficients.length + 1];
    newCoefficients[0] = startingValue;
    for (int i = 0; i < coefficients.length; ++i) {
      newCoefficients[i + 1] = coefficients[i] / (i + 1);
    }
    return polynomial(newCoefficients);
  }

  public Polynomial derivative() {
    final double[] coefficients = coefficients();
    final double[] newCoefficients = new double[coefficients.length - 1];
    for (int i = 1; i < coefficients.length; ++i) {
      newCoefficients[i - 1] = coefficients[i] * i;
    }
    return polynomial(newCoefficients);
  }

  public double evaluate(Duration t) {
    // Although there are more efficient ways to evaluate a polynomial,
    // it's *very* important to simulation stability that
    // evaluate(t) agrees exactly with step(t).extract()
    return step(t).extract();
  }

  /**
   * Helper method for other comparison methods.
   * Finds the first time the predicate is true, near the next root of this polynomial.
   */
  private Expiry findExpiryNearRoot(Predicate<Duration> expires) {
    Duration root, start, end;
    try {
      var t$ = findFutureRoots().findFirst();
      if (t$.isEmpty()) return NEVER;
      root = t$.get();

      // Do an exponential search to bracket the transition time
      boolean initialTestResult = expires.test(root);
      Duration rangeSize = EPSILON.times(initialTestResult ? -1 : 1);
      Duration testPoint = root.plus(rangeSize);
      while (expires.test(testPoint) == initialTestResult) {
        rangeSize = rangeSize.times(2);
        testPoint = root.plus(rangeSize);
      }
      if (initialTestResult) {
        start = testPoint;
        end = root;
      } else {
        start = root;
        end = testPoint;
      }

      // TODO: There's an unhandled edge case here, where timePredicate is satisfied in a period we jumped over.
      //   Maybe try to use the precision of the arguments and the finer resolution polynomial "this"
      //   to do a more thorough but still efficient search?
    } catch (ArithmeticException e) {
      // If we overflowed looking for a bracketing range, it effectively never transitions.
      return NEVER;
    }

    // Do a binary search to find the exact transition time
    while (end.longerThan(start.plus(EPSILON))) {
      Duration midpoint = start.plus(end.minus(start).dividedBy(2));
      if (expires.test(midpoint)) {
        end = midpoint;
      } else {
        start = midpoint;
      }
    }
    return Expiry.at(end);
  }

  private Expiring<Discrete<Boolean>> greaterThan(Polynomial other, boolean strict) {
    BiPredicate<Double, Double> comp = strict ? (x, y) -> x > y : (x, y) -> x >= y;
    boolean result = comp.test(this.extract(), other.extract());
    var expiry = this.subtract(other).findExpiryNearRoot(
        t -> comp.test(this.evaluate(t), other.evaluate(t)) != result);
    return expiring(discrete(result), expiry);
  }

  public Expiring<Discrete<Boolean>> greaterThan(Polynomial other) {
    return greaterThan(other, true);
  }

  public Expiring<Discrete<Boolean>> greaterThanOrEquals(Polynomial other) {
    return greaterThan(other, false);
  }

  public Expiring<Discrete<Boolean>> lessThan(Polynomial other) {
    return other.greaterThan(this);
  }

  public Expiring<Discrete<Boolean>> lessThanOrEquals(Polynomial other) {
    return other.greaterThanOrEquals(this);
  }

  /**
   * Computes if this polynomial "dominates" another right now.
   * <p>
   *     A polynomial P dominates Q in this context if it is greater than Q in the first coefficient that differs,
   *     or equal to Q exactly.
   * </p>
   * <p>
   *     Intuitively, P dominates Q when max(P, Q) = P, potentially with a shorter expiry if Q crosses P sometime in the future.
   *     Consider using {@link Polynomial#dominates(Polynomial)} instead to get this expiry / crossover time as well.
   * </p>
   */
  public boolean dominates$(Polynomial other) {
    for (int i = 0; i <= Math.max(this.degree(), other.degree()); ++i) {
      if (this.getCoefficient(i) > other.getCoefficient(i)) return true;
      if (this.getCoefficient(i) < other.getCoefficient(i)) return false;
    }
    // Equal, so either answer is correct
    return true;
  }

  /**
   * Returns a boolean dynamics, describing the value of {@link Polynomial#dominates$(Polynomial)} and when it will change.
   */
  public Expiring<Discrete<Boolean>> dominates(Polynomial other) {
    boolean result = this.dominates$(other);
    var expiry = this.subtract(other).findExpiryNearRoot(t -> this.step(t).dominates$(other.step(t)) != result);
    return expiring(discrete(result), expiry);
  }

  public Expiring<Polynomial> min(Polynomial other) {
    return ExpiringMonad.map(this.dominates(other), d -> d.extract() ? other : this);
  }

  public Expiring<Polynomial> max(Polynomial other) {
    return ExpiringMonad.map(this.dominates(other), d -> d.extract() ? this : other);
  }

  /**
   * Finds all roots of this function in the future
   */
  private Stream<Duration> findFutureRoots() {
    // TODO: In some sense, isn't having an infinite coefficient the same as a vertical line,
    //   hence the same as having a root at x = 0?
    //   Unless the value itself is non-finite, that is...
    // If this polynomial can never have a root, fail immediately
    if (this.isNonFinite() || this.isConstant()) {
      return Stream.empty();
    }

    if (coefficients[0] == 0.0) {
      return Stream.of(ZERO);
    }

    // If the polynomial is linear, solve it analytically for performance
    if (this.degree() <= 1) {
      double t = -getCoefficient(0) / getCoefficient(1);
      if (t >= -ABSOLUTE_ACCURACY_FOR_DURATIONS / 2 && t <= MAX_SECONDS_FOR_DURATION) {
        return Stream.of(Duration.roundNearest(t, SECOND));
      } else {
        return Stream.empty();
      }
    }

    // Condition the problem by dividing through by the first coefficient:
    double[] conditionedCoefficients = Arrays.stream(coefficients).map(c -> c / coefficients[0]).toArray();
    // Defining epsilon keeps the Laguerre solver faster and more stable for poorly-behaved polynomials.
    final double epsilon = 2 * Arrays.stream(conditionedCoefficients).map(Math::ulp).max().orElseThrow();
    final Complex[] solutions = new LaguerreSolver(0, ABSOLUTE_ACCURACY_FOR_DURATIONS, epsilon)
            .solveAllComplex(conditionedCoefficients, 0);
    return Arrays.stream(solutions)
                 .filter(solution -> Math.abs(solution.getImaginary()) < epsilon)
                 .map(Complex::getReal)
                 .filter(t -> t >= -ABSOLUTE_ACCURACY_FOR_DURATIONS / 2 && t <= MAX_SECONDS_FOR_DURATION)
                 .sorted()
                 .map(t -> Duration.roundNearest(t, SECOND));
  }
  private static final double ABSOLUTE_ACCURACY_FOR_DURATIONS = EPSILON.ratioOver(SECOND);
  private static final double MAX_SECONDS_FOR_DURATION = Duration.MAX_VALUE.ratioOver(SECOND);

  /**
   * Get the nth coefficient.
   * @param n the n.
   * @return the nth coefficient
   */
  public double getCoefficient(int n) {
    return n >= coefficients().length ? 0.0 : coefficients()[n];
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Polynomial that = (Polynomial) o;
    return Arrays.equals(coefficients, that.coefficients);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(coefficients);
  }

  @Override
  public String toString() {
    return "Polynomial{" +
           "coefficients=" + Arrays.toString(coefficients) +
           '}';
  }
}
