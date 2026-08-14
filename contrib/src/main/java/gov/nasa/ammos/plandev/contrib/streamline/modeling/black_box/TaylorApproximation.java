package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;

public final class TaylorApproximation {
  private TaylorApproximation() {}

  /**
   * Fixed-degree Taylor approximation.
   */
  public static Function<Expiring<Differentiable>, Expiring<Polynomial>> taylorApproximation(int degree, BiFunction<Differentiable, Polynomial, Duration> divergenceEstimator) {
    return divergingApproximation(d -> expand(d, degree), divergenceEstimator);
  }

  public static Polynomial expand(Differentiable d, int degree) {
    double[] coefficients = new double[degree + 1];
    int iFactorial = 1;
    for (int i = 0; i <= degree; ++i) {
      coefficients[i] = d.extract() / iFactorial;
      iFactorial *= i + 1;
    }
    return polynomial(coefficients);
  }
}
