package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.ErrorEstimates;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.monads.UnstructuredResourceApplicative;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.approximate;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.relative;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.IntervalFunctions.byBoundingError;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.secantApproximation;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

public final class UnstructuredResources {
  private UnstructuredResources() {}

  public static <A> Resource<Unstructured<A>> constant(A value) {
    var result = UnstructuredResourceApplicative.pure(value);
    // Use an edge in the naming graph directly to the value to lazily compute the name
    name(result, "%s", value);
    return result;
  }

  public static <A> Resource<Unstructured<A>> timeBased(Function<Duration, A> f) {
    // Put this in a cell so it'll be stepped up appropriately
    return resource(Unstructured.timeBased(f));
  }

  public static <A, D extends Dynamics<A, D>> Resource<Unstructured<A>> asUnstructured(Resource<D> resource) {
    return map(resource, Unstructured::unstructured);
  }

  /**
   * {@link UnstructuredResources#approximateAsLinear(Resource, double)}
   * with relativeError = 1e-2
   */
  public static Resource<Linear> approximateAsLinear(Resource<Unstructured<Double>> resource) {
    return approximateAsLinear(resource, 1e-2);
  }

  /**
   * {@link UnstructuredResources#approximateAsLinear(Resource, double, double)}
   * with epsilon = 1e-10
   */
  public static Resource<Linear> approximateAsLinear(Resource<Unstructured<Double>> resource, double relativeError) {
    return approximateAsLinear(resource, relativeError, 1e-10);
  }

  /**
   * Builds a linear approximation of resource, using generally acceptable default settings.
   * For more control over the approximation, see {@link Approximation#approximate} and related methods.
   *
   * @param resource The resource to approximate
   * @param relativeError The maximum relative error to tolerate in the approximation
   * @param epsilon The minimum positive value to distinguish from zero. This avoids oversampling near zero.
   *
   * @see Approximation#approximate
   * @see SecantApproximation#secantApproximation
   * @see IntervalFunctions#byBoundingError
   * @see IntervalFunctions#byUniformSampling
   * @see SecantApproximation.ErrorEstimates#errorByOptimization()
   * @see Approximation#relative
   */
  public static Resource<Linear> approximateAsLinear(Resource<Unstructured<Double>> resource, double relativeError, double epsilon) {
    return approximate(resource,
            secantApproximation(byBoundingError(
                    relativeError,
                    MINUTE,
                    duration(24 * 30, HOUR),
                    relative(ErrorEstimates.<Unstructured<Double>>errorByOptimization(), epsilon))));
  }
}
