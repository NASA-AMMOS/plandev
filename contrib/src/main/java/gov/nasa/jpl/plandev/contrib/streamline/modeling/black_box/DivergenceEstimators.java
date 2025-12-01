package gov.nasa.jpl.plandev.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.plandev.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.plandev.contrib.streamline.core.Expiring;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

import java.util.function.BiFunction;
import java.util.function.Supplier;

import static gov.nasa.jpl.plandev.merlin.protocol.types.Duration.SECOND;

public final class DivergenceEstimators {
  private DivergenceEstimators() {}

  /**
   * Uses a direct solver to estimate when an approximation diverges beyond a maximum tolerable error.
   * Assumes approximation diverges monotonically from the true value.
   * Requires minimum and maximum sample period to ensure efficient convergence.
   */
  public static <A, B, D extends Dynamics<A, D>, E extends Dynamics<B, E>> BiFunction<D, E, Duration> byBoundingError(double maximumError, Duration minimumSamplePeriod, Duration maximumSamplePeriod, BiFunction<A, B, Double> errorEstimate) {
    // Calculating divergence time is just calculating a sample interval, with a slightly different signature.
    return (d, e) -> IntervalFunctions.<D>byBoundingError(
        maximumError,
        minimumSamplePeriod,
        maximumSamplePeriod,
        input -> {
          var T = Duration.roundNearest(input.intervalInSeconds(), SECOND);
          return errorEstimate.apply(d.step(T).extract(), e.step(T).extract());
        }).apply(Expiring.neverExpiring(d));
  }

  /**
   * Approximation diverges in a time independent of approximation itself.
   */
  public static <D, E> BiFunction<D, E, Duration> byTime(Supplier<Duration> divergenceTime) {
    return (d, e) -> divergenceTime.get();
  }
}
