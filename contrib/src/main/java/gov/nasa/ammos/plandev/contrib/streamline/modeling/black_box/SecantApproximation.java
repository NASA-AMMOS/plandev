package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ExpiringMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.IntervalFunctions.ErrorEstimateInput;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.math3.exception.TooManyEvaluationsException;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.univariate.BrentOptimizer;
import org.apache.commons.math3.optim.univariate.SearchInterval;
import org.apache.commons.math3.optim.univariate.UnivariateObjectiveFunction;

import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.expiring;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.intervalApproximation;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear.linear;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

/**
 * Utilities to build a secant approximation of {@link Unstructured} or {@link Differentiable} resources.
 */
public final class SecantApproximation {
  private SecantApproximation() {}

  /**
   * Approximate a resource using secants.
   * Intervals for the secants are given using an interval function.
   * Pre-built interval functions are available in {@link IntervalFunctions}.
   * For example:
   * <pre>
   * Resource&lt;BlackBox&lt;Double&gt;&gt; real;
   * Resource&lt;Linear&gt; approx = approximate(resource, secantApproximation(byUniformSampling(Duration.HOUR)));
   * </pre>
   */
  public static <D extends Dynamics<Double, D>> Function<Expiring<D>, Expiring<Linear>> secantApproximation(Function<Expiring<D>, Duration> intervalFunction) {
    return intervalApproximation(SecantApproximation::secant, intervalFunction);
  }

  private static Expiring<Linear> secant(Expiring<? extends Dynamics<Double, ?>> d, Duration interval) {
    return ExpiringMonad.bind(d, d$ -> expiring(secant(d$, interval), interval));
  }

  public static Linear secant(Dynamics<Double, ?> d, Duration interval) {
    var s = d.extract();
    var e = d.step(interval).extract();
    return linear(s, (e - s) / interval.ratioOver(SECOND));
  }

  public static final class ErrorEstimates {
    private ErrorEstimates() {}

    /**
     * Expands a second-order Taylor approximation at the midpoint of a proposed interval
     * to estimate the error of taking a secant across that interval.
     */
    public static Function<ErrorEstimateInput<Differentiable>, Double> errorByQuadraticApproximation() {
      return input -> {
        var d = input.actualDynamics();
        var t = input.intervalInSeconds();

        // Shift d to the interval midpoint
        var dMidpoint = d.step(Duration.roundNearest(t / 2, SECOND));
        var dPrimeMidpoint = dMidpoint.derivative();

        // Taylor expansion at the midpoint
        var midpointValue = dMidpoint.extract();
        var midpointSlope = dPrimeMidpoint.extract();
        var midpointCurvature = dPrimeMidpoint.derivative().extract();

        // Secant across the interval
        var tDuration = Duration.roundNearest(t, SECOND);
        var secant = secant(d, tDuration);
        var secantStartValue = secant.extract();
        var secantSlope = secant.rate();
        var secantMidValue = secantStartValue + (secantSlope * t / 2);

        // Error is measured by Taylor expansion minus secant, which is a quadratic ax^2 + bx + c
        // Note that this curve is expressed with x = 0 at the interval midpoint
        var a = midpointCurvature / 2;
        var b = midpointSlope - secantSlope;
        var c = midpointValue - secantMidValue;

        double extremumError;
        if (a == 0) {
          extremumError = 0;
        } else {
          // Find the location of the extremum:
          var extremePoint = -b / (2 * a);
          // If the extreme point is within the interval, consider the error there
          extremumError = ((-t / 2) < extremePoint && extremePoint < (t / 2))
              ? Math.abs(c - (b * b / (4 * a))) : 0;
        }

        // Also evaluate the error function at +/- (t/2), the interval start and end points.
        var startSecantError = Math.abs((a * t * t / 4) - (b * t / 2) + c);
        var endSecantError = Math.abs((a * t * t / 4) + (b * t / 2) + c);
        // Compute the maximum error between the Taylor expansion and the secant
        var secantError = Math.max(extremumError, Math.max(startSecantError, endSecantError));

        // Assuming that the Taylor expansion monotonically diverges from the true value,
        // we can bound the error between the Taylor expansion and the true value by measuring the endpoints.
        var startTrueValue = d.extract();
        var endTrueValue = d.step(tDuration).extract();
        var startTaylorError = Math.abs(((midpointCurvature * t * t / 8) - (midpointSlope * t / 2) + midpointValue) - startTrueValue);
        var endTaylorError = Math.abs(((midpointCurvature * t * t / 8) + (midpointSlope * t / 2) + midpointValue) - endTrueValue);
        var taylorError = Math.max(startTaylorError, endTaylorError);

        // Finally, we can bound the error across the entire interval as
        // error between secant and Taylor expansion plus error between Taylor expansion and true value.
        return secantError + taylorError;
      };
    }

    /**
     * Uses a direct optimizer to numerically estimate the error of a secant interval.
     */
    public static <D extends Dynamics<Double, D>> Function<ErrorEstimateInput<D>, Double> errorByOptimization() {
      return input -> {
        var d = input.actualDynamics();
        var t = input.intervalInSeconds();
        var E = input.maximumError();

        var secant = secant(d, Duration.roundNearest(t, SECOND));
        var secantValue = secant.extract();
        var secantSlope = secant.rate();
        // We only need to compare error between secants, so the error calculation can be pretty coarse.
        var optimizer = new BrentOptimizer(1e-2, 1e-2 * E);
        try {
          return optimizer.optimize(
              GoalType.MAXIMIZE,
              new SearchInterval(0, t),
              new MaxEval(100),
              new UnivariateObjectiveFunction(
                  s -> Math.abs( (secantValue + secantSlope * s) - d.step(Duration.roundNearest(s, SECOND)).extract() )))
                          .getValue();
        } catch (TooManyEvaluationsException e) {
          // If we can't evaluate the error, play it safe by returning infinite error.
          return Double.POSITIVE_INFINITY;
        }
      };
    }
  }
}
