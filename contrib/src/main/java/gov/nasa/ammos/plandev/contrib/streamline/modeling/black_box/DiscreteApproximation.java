package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.divergingApproximation;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;

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
