package gov.nasa.ammos.plandev.contrib.streamline.modeling.black_box;

import gov.nasa.ammos.plandev.contrib.streamline.core.Dynamics;
import gov.nasa.ammos.plandev.contrib.streamline.core.Expiring;
import gov.nasa.ammos.plandev.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.ammos.plandev.contrib.streamline.modeling.black_box.Approximation.divergingApproximation;
import static gov.nasa.ammos.plandev.contrib.streamline.modeling.discrete.Discrete.discrete;

/**
 * Utilities to build discrete approximations of {@link Unstructured} resources.
 */
public final class DiscreteApproximation {
  private DiscreteApproximation() {}

  /**
   * Build an approximation function, for use with {@link Approximation#approximate}, which takes discrete samples.
   * Uses the provided divergence estimator to determine when each sample expires.
   *
   * <p>
   *   Pre-built divergence estimators are available in {@link DivergenceEstimators}.
   * </p>
   */
  public static <V, D extends Dynamics<V, D>> Function<Expiring<D>, Expiring<Discrete<V>>> discreteApproximation(
      BiFunction<D, Discrete<V>, Duration> divergenceEstimator) {
    return divergingApproximation(d -> discrete(d.extract()), divergenceEstimator);
  }
}
