package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.IntervalFunctions.ErrorEstimateInput;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.DifferentiableResources.asDifferentiable;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.ErrorEstimates.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;
import static org.junit.jupiter.api.Assertions.*;

class SecantApproximationTest {
  @Nested
  class ErrorEstimatesTest {
    @Test
    void quadratic_error_estimate_for_constant_is_zero() {
      var dynamics = asDifferentiable(polynomial(5));
      var result = errorByQuadraticApproximation().apply(
          new ErrorEstimateInput<>(
              dynamics,
              10.0,
              1e-6));
      assertEquals(0.0, result);
    }

    @Test
    void quadratic_error_estimate_for_linear_is_zero() {
      var dynamics = asDifferentiable(polynomial(5, -3));
      var result = errorByQuadraticApproximation().apply(
          new ErrorEstimateInput<>(
              dynamics,
              10.0,
              1e-6));
      assertEquals(0.0, result);
    }

    @Test
    void quadratic_error_estimate_for_quadratic_is_exact() {
      var dynamics = asDifferentiable(polynomial(1, -1, 0.5));
      var result = errorByQuadraticApproximation().apply(
          new ErrorEstimateInput<>(
              dynamics,
              2.0,
              1e-6));
      assertEquals(0.5, result);
    }
  }
}
