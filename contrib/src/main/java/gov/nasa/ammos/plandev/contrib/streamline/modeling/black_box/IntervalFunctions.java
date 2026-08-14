package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.solvers.BrentSolver;
import org.apache.commons.math3.exception.NoBracketingException;
import org.apache.commons.math3.exception.NumberIsTooLargeException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;

import java.util.function.Function;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

public final class IntervalFunctions {
  private IntervalFunctions() {}

  /**
   * Use uniform point spacing for an approximation
   */
  public static <D> Function<Expiring<D>, Duration> byUniformSampling(Duration samplePeriod) {
    return exp -> Duration.min(samplePeriod, exp.expiry().value().orElse(Duration.MAX_VALUE));
  }

  /**
   * Chooses sample intervals which attempt to bound the maximum error.
   * To ensure efficient convergence, a minimum and maximum size for the interval must be supplied.
   * Error is measured by the provided error estimate function.
   * Pre-built error functions are available in {@link SecantApproximation.ErrorEstimates}.
   * For example,
   * <pre>
   * Resource&lt;Differentiable&gt; real;
   * Resource&lt;Linear&gt; approx = secantApproximation(real,
   *     byBoundingError(1e-4, Duration.MINUTE, Duration.HOUR,
   *         relative(errorByQuadraticApproximation(), 1e-8)));
   * </pre>
   */
  public static <D extends Dynamics<?, D>> Function<Expiring<D>, Duration> byBoundingError(
      double maximumError,
      Duration minimumSamplePeriod,
      Duration maximumSamplePeriod,
      Function<ErrorEstimateInput<D>, Double> errorEstimate)
  {
    if (maximumError <= 0) {
      throw new IllegalArgumentException("maximumError must be positive");
    }
    if (!minimumSamplePeriod.isPositive()) {
      throw new IllegalArgumentException("minimumSamplePeriod must be positive");
    }
    if (maximumSamplePeriod.shorterThan(minimumSamplePeriod)) {
      throw new IllegalArgumentException("maximumSamplePeriod must be at least minimumSamplePeriod");
    }

    return exp -> {
      var e = exp.expiry().value().orElse(Duration.MAX_VALUE);
      var solver = new BrentSolver();
      UnivariateFunction errorFn = t -> maximumError - errorEstimate.apply(new ErrorEstimateInput<>(
          exp.data(),
          t,
          maximumError));
      var effectiveMinSamplePeriod = Duration.min(e, minimumSamplePeriod);
      var effectiveMaxSamplePeriod = Duration.min(e, maximumSamplePeriod);

      try {
        double intervalSize = solver.solve(
            100,
            errorFn,
            effectiveMinSamplePeriod.ratioOver(SECOND),
            effectiveMaxSamplePeriod.ratioOver(SECOND));
        return Duration.roundNearest(intervalSize, SECOND);
      } catch (NoBracketingException x) {
        if (errorFn.value(minimumSamplePeriod.ratioOver(SECOND)) > 0) {
          // maximum error > estimated error, best case
          return effectiveMaxSamplePeriod;
        } else {
          // maximum error < estimated error, worst case
          return effectiveMinSamplePeriod;
        }
      } catch (TooManyEvaluationsException | NumberIsTooLargeException x) {
        return effectiveMinSamplePeriod;
      }
    };
  }

  public record ErrorEstimateInput<D>(D actualDynamics, Double intervalInSeconds, Double maximumError) {}
}
