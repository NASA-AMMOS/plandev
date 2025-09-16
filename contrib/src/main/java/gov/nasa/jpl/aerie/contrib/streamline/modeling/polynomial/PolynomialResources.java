package gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial;

import gov.nasa.jpl.aerie.contrib.streamline.core.*;
import gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.CommutativityTestInput;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ErrorCatchingMonad;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.*;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.StandardUnits;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Unit;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAware;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAwareOperations;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAwareResources;
import gov.nasa.jpl.aerie.contrib.streamline.utils.DoubleUtils;
import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.mutable.MutableObject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.autoEffects;
import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.testing;
import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.expiring;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.neverExpiring;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.NEVER;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Reactions.wheneverDynamicsChange;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.*;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad.bindEffect;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.*;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.reduce;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Dependencies.addDependency;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.approximate;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.relative;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.DifferentiableResources.asDifferentiable;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.IntervalFunctions.byBoundingError;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.ErrorEstimates.errorByQuadraticApproximation;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.secantApproximation;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.ClockResources.clock;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.assertThat;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAwareResources.extend;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;
import static java.util.Arrays.stream;

public final class PolynomialResources {
  private PolynomialResources() {}

  public static Resource<Polynomial> constant(double value) {
    var result = pure(polynomial(value));
    name(result, Double.toString(value));
    return result;
  }

  public static UnitAware<Resource<Polynomial>> constant(UnitAware<Double> quantity) {
    var result = unitAware(constant(quantity.value()), quantity.unit());
    name(result, quantity.toString());
    return result;
  }

  public static MutableResource<Polynomial> polynomialResource(double... initialCoefficients) {
    return polynomialResource(polynomial(initialCoefficients));
  }

  public static MutableResource<Polynomial> polynomialResource(Polynomial initialDynamics) {
    return resource(initialDynamics, autoEffects(testing(
        (CommutativityTestInput<Polynomial> input) -> {
          Polynomial original = input.original();
          Polynomial left = input.leftResult();
          Polynomial right = input.rightResult();
          return left.degree() == right.degree() &&
                 IntStream.rangeClosed(0, left.degree()).allMatch(
                     i -> DoubleUtils.areEqualResults(
                         original.getCoefficient(i),
                         left.getCoefficient(i),
                         right.getCoefficient(i)));
        })));
  }

  /**
   * Treat a discrete resource as a polynomial with constant profile segments.
   */
  public static Resource<Polynomial> asPolynomial(Resource<Discrete<Double>> discrete) {
    var result = map(discrete, d -> polynomial(d.extract()));
    name(result, "%s", discrete);
    return result;
  }

  /**
   * Treat a discrete resource as a polynomial with constant profile segments.
   */
  public static UnitAware<Resource<Polynomial>> asUnitAwarePolynomial(UnitAware<? extends Resource<Discrete<Double>>> discrete) {
    return unitAware(asPolynomial(discrete.value()), discrete.unit());
  }

  /**
   * Treat a discrete resource as a polynomial with constant profile segments.
   * <p>
   *   Note that this requires dimension-checking every segment individually, which can degrade performance.
   *   If possible, try using <code>UnitAware&lt;Resource&lt;Discrete&lt;Double&gt;&gt;&gt;</code>
   * </p>
   */
  public static UnitAware<Resource<Polynomial>> asUnitAwarePolynomial(Resource<Discrete<UnitAware<Double>>> discrete) {
    var unit = currentValue(discrete).unit();
    return unitAware(asPolynomial(DiscreteResourceMonad.map(discrete, q -> q.value(unit))), unit);
  }

  /**
   * Treat a linear resource as a polynomial with linear profile segments.
   */
  public static Resource<Polynomial> asPolynomial$(Resource<Linear> linear) {
    return map(linear, l -> polynomial(l.extract(), l.rate()));
  }

  /**
   * Treat a linear resource as a polynomial with linear profile segments.
   */
  public static UnitAware<Resource<Polynomial>> asUnitAwarePolynomial$(UnitAware<? extends Resource<Linear>> linear) {
    return unitAware(asPolynomial$(linear.value()), linear.unit());
  }

  /**
   * Assume that polynomial is in fact linear.
   *
   * <p>
   *     This method is very fast, but will throw an error if polynomial is not actually linear.
   *     To convert polynomials that may not be linear, try {@link PolynomialResources#approximateAsLinear}
   * </p>
   */
  public static Resource<Linear> assumeLinear(Resource<Polynomial> polynomial) {
    var result = map(polynomial, p -> {
      if (p.degree() <= 1) {
        return Linear.linear(p.getCoefficient(0), p.getCoefficient(1));
      } else {
        throw new IllegalStateException(
                "%s was assumed to be linear, but was actually degree %d".formatted(
                        getName(polynomial).orElse("Anonymous resource"),
                        p.degree()));
      }
    });
    // Since this method is often used to register a polynomial,
    // propagate names backwards from the linear result to the polynomial input.
    name(polynomial, "%s", result);
    return result;
  }

  /**
   * {@link PolynomialResources#approximateAsLinear(Resource, double)}
   * with relativeError = 1e-2
   */
  public static Resource<Linear> approximateAsLinear(Resource<Polynomial> polynomial) {
    return approximateAsLinear(polynomial, 1e-2);
  }

  /**
   * {@link PolynomialResources#approximateAsLinear(Resource, double, double)}
   * with epsilon = 1e-10
   */
  public static Resource<Linear> approximateAsLinear(Resource<Polynomial> polynomial, double relativeError) {
    return approximateAsLinear(polynomial, relativeError, 1e-10);
  }

  /**
   * Builds a linear approximation of polynomial, using generally acceptable default settings.
   * For more control over the approximation, see {@link Approximation#approximate} and related methods.
   *
   * @param polynomial The resource to approximate
   * @param relativeError The maximum relative error to tolerate in the approximation
   * @param epsilon The minimum positive value to distinguish from zero. This avoids oversampling near zero.
   *
   * @see Approximation#approximate
   * @see SecantApproximation#secantApproximation
   * @see IntervalFunctions#byBoundingError
   * @see IntervalFunctions#byUniformSampling
   * @see SecantApproximation.ErrorEstimates#errorByQuadraticApproximation()
   * @see SecantApproximation.ErrorEstimates#errorByOptimization()
   * @see Approximation#relative
   */
  public static Resource<Linear> approximateAsLinear(Resource<Polynomial> polynomial, double relativeError, double epsilon) {
    return approximate(asDifferentiable(polynomial),
            secantApproximation(byBoundingError(
                    relativeError,
                    MINUTE,
                    duration(24 * 30, HOUR),
                    relative(errorByQuadraticApproximation(), epsilon))));
  }

  /**
   * Returns a continuous resource that follows a precomputed sequence of values.
   * Before the first key in segments, value is the first entry in segments.
   * Between keys in segments, a linear interpolation between the two adjacent entries is used.
   * After the last key in segments, value is the last entry in segments.
   */
  public static Resource<Polynomial> precomputed(final NavigableMap<Duration, Double> segments) {
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("Segments map must have at least one segment");
    }
    var clock = clock();
    return signalling(bind(clock, (Clock clock$) -> {
      var t = clock$.extract();
      var start = segments.floorEntry(t);
      var end = segments.higherEntry(t);
      Expiring<Polynomial> result;
      if (end == null) {
        result = neverExpiring(polynomial(start.getValue()));
      } else if (start == null) {
        result = expiring(polynomial(end.getValue()), end.getKey().minus(t));
      } else {
        // interpolate between start and end
        var startTime = start.getKey();
        var endTime = end.getKey();
        var slope = (end.getValue() - start.getValue()) / endTime.minus(startTime).ratioOver(SECOND);
        var data = polynomial(start.getValue(), slope).step(t.minus(startTime));
        result = expiring(data, endTime.minus(t));
      }
      return pure(result);
    }));
  }

  /**
   * Returns a continuous resource that follows a precomputed sequence of values.
   * Before the first key in segments, value is valueBeforeFirstEntry.
   * Between keys in segments, a linear interpolation between the two adjacent entries is used.
   * After the last key in segments, value is the last entry in segments.
   */
  public static Resource<Polynomial> precomputed(
      final NavigableMap<Instant, Double> segments, final Instant simulationStartTime) {
    var segmentsUsingDurationKeys = new TreeMap<Duration, Double>();
    for (var entry : segments.entrySet()) {
      segmentsUsingDurationKeys.put(
          Duration.of(
              ChronoUnit.MICROS.between(simulationStartTime, entry.getKey()),
              Duration.MICROSECONDS),
          entry.getValue());
    }
    return precomputed(segmentsUsingDurationKeys);
  }

  /**
   * Add polynomial resources.
   */
  @SafeVarargs
  public static Resource<Polynomial> add(Resource<Polynomial>... summands) {
    return sum(stream(summands));
  }

  public static Resource<Polynomial> sum(Stream<? extends Resource<Polynomial>> summands) {
    return reduce(summands.toList(), polynomial(0), Polynomial::add, "Sum");
  }

  /**
   * Subtract polynomial resources.
   */
  public static Resource<Polynomial> subtract(Resource<Polynomial> p, Resource<Polynomial> q) {
    var result = map(p, q, Polynomial::subtract);
    name(result, "(%s) - (%s)", p, q);
    return result;
  }

  /**
   * Flip the sign of a polynomial resource.
   */
  public static Resource<Polynomial> negate(Resource<Polynomial> p) {
    var result = multiply(constant(-1), p);
    name(result, "-(%s)", p);
    return result;
  }

  public static Resource<Polynomial> scale(Resource<Polynomial> p, double scalar) {
    return multiply(p, constant(scalar));
  }

  /**
   * Multiply polynomial resources.
   */
  @SafeVarargs
  public static Resource<Polynomial> multiply(Resource<Polynomial>... factors) {
    return product(stream(factors));
  }

  /**
   * Multiply polynomial resources.
   */
  public static Resource<Polynomial> product(Stream<? extends Resource<Polynomial>> factors) {
    return reduce(factors.toList(), polynomial(1), Polynomial::multiply, "Product");
  }

  /**
   * Divide a polynomial by a discrete resource.
   * <p>
   *   The divisor must be discrete, because the quotient of two polynomials is not necessarily a polynomial.
   * </p>
   */
  public static Resource<Polynomial> divide(Resource<Polynomial> p, Resource<Discrete<Double>> q) {
    var result = map(p, q, (p$, q$) -> p$.divide(q$.extract()));
    name(result, "(%s) / (%s)", p, q);
    return result;
  }

  /**
   * Compute the integral of integrand, starting at startingValue.
   * <p>
   *   This method allocates a cell, so must be called during initialization, not simulation.
   * </p>
   */
  public static Resource<Polynomial> integrate(Resource<Polynomial> integrand, double startingValue) {
    var cell = resource(DynamicsMonad.map(integrand.getDynamics(), (Polynomial $) -> $.integral(startingValue)));
    // Use integrand's expiry but not integral's, since we're refreshing the integral
    wheneverDynamicsChange(integrand, integrandDynamics ->
        cell.emit(bindEffect(integral -> DynamicsMonad.map(integrandDynamics, integrand$ ->
            integrand$.integral(integral.extract())))));
    name(cell, "Integral (%s)", integrand);
    addDependency(cell, integrand);
    return cell;
  }

  /**
   * Compute the integral of integrand, starting at startingValue.
   * Also clamp the integral between lowerBound and upperBound (inclusive).
   * <p>
   *   Note that <code>clampedIntegrate(r, l, u, s)</code> differs from
   *   <code>clamp(integrate(r, s), l, u)</code> in how they handle reversing from a boundary.
   * </p>
   * <p>
   *   To see how, consider bounds of [0, 5], with an integrand of 1 for 10 seconds, then -1 for 10 seconds.
   * </p>
   * <p>
   *   clamp and integrate:
   * </p>
   * <pre>
   *   5       /----------\
   *          /            \
   *         /              \
   *        /                \
   *   0   /                  \
   * time  0        10        20
   * </pre>
   * <p>
   *   clampedIntegrate:
   * </p>
   * <pre>
   *   5       /-----\
   *          /       \
   *         /         \
   *        /           \
   *   0   /             \-----
   * time  0        10        20
   * </pre>
   * <p>
   *     NOTE: This method assumes that lowerBound <= upperBound at all times.
   *     Failure to meet this precondition may lead to incorrect outputs, crashing the simulation, stalling in an infinite loop,
   *     or other misbehavior.
   *     If this condition cannot be guaranteed a priori, consider using
   *     {@link gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources#assertThat(String, Resource)}
   *     to guarantee this condition at runtime.
   * </p>
   */
  public static ClampedIntegrateResult clampedIntegrate(
          Resource<Polynomial> integrand, Resource<Polynomial> lowerBound, Resource<Polynomial> upperBound, double startingValue) {
    // There are more elegant ways to write this method, but profiling suggests this is often a bottleneck to models that use it.
    // So, we're writing it very carefully to maximize performance.

    MutableResource<Polynomial> integral = polynomialResource(startingValue);
    MutableResource<Polynomial> overflow = polynomialResource(0);
    MutableResource<Polynomial> underflow = polynomialResource(0);

    MutableObject<Condition> condition = new MutableObject<>(Condition.TRUE); // Run once immediately to get the loop started.
    Reactions.whenever(condition::getValue, () -> {
      // Note we need to call dynamicsChange on each iteration because it depends on the resource's current value,
      // so *don't* factor these out of the loop.
      Condition someInputChanges = dynamicsChange(integrand)
              .or(dynamicsChange(lowerBound))
              .or(dynamicsChange(upperBound));

      // Get all dynamics once per update loop to minimize sampling cost.
      ErrorCatching<Expiring<ClampedIntegrateInternalResult>> result = DynamicsMonad.map(
              integrand.getDynamics(),
              lowerBound.getDynamics(),
              upperBound.getDynamics(),
              (integrandDynamics$, lowerBoundDynamics$, upperBoundDynamics$) -> {
                // Clamp the integral value to take care of small overshoots due to the discretization of time
                // and discrete changes in bounds that cut into the integral.
                // Also, just get the current value, don't try to derive a value.
                // This intentionally erases expiry info from the integral since this is a resource-graph back-edge,
                // and will throw and blow up this resource if integral fails.
                var integralValue = Double.max(
                        Double.min(currentValue(integral), upperBoundDynamics$.extract()),
                        lowerBoundDynamics$.extract());
                var integralDynamics$ = integrandDynamics$.integral(integralValue);

                if (lowerBoundDynamics$.dominates$(integralDynamics$)) {
                  // Lower bound dominates "real" integral, so we clamp to the lower bound
                  // We stop clamping to the lower bound when the integrand rate exceeds the lowerBound rate.
                  // Since we already know that lowerBound dominates integral and integral value is at least lower value,
                  // we know that lower rate dominates integrand. Hence, there's no need to check that value here.
                  var lowerRate = lowerBoundDynamics$.derivative();
                  var clampingStops = fixedTimeCondition(lowerRate.dominates(integrandDynamics$).expiry().value());
                  // We change out of this condition when we stop clamping to the lower bound, or something changes.
                  return new ClampedIntegrateInternalResult(
                          lowerBoundDynamics$,
                          polynomial(0),
                          lowerRate.subtract(integrandDynamics$),
                          clampingStops.or(someInputChanges));
                }

                if (!upperBoundDynamics$.dominates$(integralDynamics$)) {
                  // Upper bound doesn't dominate integral, so we clamp to the upper bound
                  // We stop clamping to the upper bound when the integrand rate falls below the upperBound rate.
                  // Since we already know that lowerBound dominates integral and integral value is at least lower value,
                  // we know that lower rate dominates integrand. Hence, there's no need to check that value here.
                  var upperRate = upperBoundDynamics$.derivative();
                  var clampingStops = fixedTimeCondition(upperRate.dominates(integrandDynamics$).expiry().value());
                  // We change out of this condition when we stop clamping to the upper bound, or something changes.
                  return new ClampedIntegrateInternalResult(
                          upperBoundDynamics$,
                          integrandDynamics$.subtract(upperRate),
                          polynomial(0),
                          clampingStops.or(someInputChanges));
                }

                // Otherwise, the integral is between the bounds, so we just set it as-is.
                // We start clamping when one or the other bound impacts this integral, i.e., when a dominates value changes.
                // Although we re-compute the value of dominates$ here, that's cheap.
                // We avoid computing the more expensive expiry when we're clamping, which is a win overall.
                var startClampingToLowerBound = lowerBoundDynamics$.dominates(integralDynamics$).expiry();
                var startClampingToUpperBound = upperBoundDynamics$.dominates(integralDynamics$).expiry();
                var clampingStarts = fixedTimeCondition(startClampingToLowerBound.or(startClampingToUpperBound).value());
                return new ClampedIntegrateInternalResult(
                        integralDynamics$,
                        polynomial(0),
                        polynomial(0),
                        clampingStarts.or(someInputChanges)
                );
              });

      var newIntegralDynamics = DynamicsMonad.map(result, ClampedIntegrateInternalResult::integral);
      var newOverflowDynamics = DynamicsMonad.map(result, ClampedIntegrateInternalResult::overflow);
      var newUnderflowDynamics = DynamicsMonad.map(result, ClampedIntegrateInternalResult::underflow);
      // If there was an error, that disturbs the integral value, and we can't recover. Don't bother retrying in that case.
      var newRetryCondition = result.match($ -> $.data().retryCondition(), $ -> Condition.FALSE);

      integral.emit("Update clamped integral", $ -> newIntegralDynamics);
      overflow.emit("Update clamped integral overflow", $ -> newOverflowDynamics);
      underflow.emit("Update clamped integral underflow", $ -> newUnderflowDynamics);
      condition.setValue(newRetryCondition);
    });

    return new ClampedIntegrateResult(integral, overflow, underflow);
  }

  private static Condition fixedTimeCondition(Optional<Duration> time) {
    return time.<Condition>map(time$ -> {
      var targetTime = Resources.currentTime().plus(time$);
      return (positive, atEarliest, atLatest) -> {
        var timeRemaining = targetTime.minus(Resources.currentTime());
        if (positive) {
          return Optional.of(timeRemaining).filter(atLatest::noShorterThan).map(t -> Duration.max(atEarliest, t));
        } else {
          return Optional.of(atEarliest).filter(timeRemaining::longerThan);
        }
      };
    }).orElse(Condition.FALSE);
  }
  private record ClampedIntegrateInternalResult(
          Polynomial integral,
          Polynomial overflow,
          Polynomial underflow,
          Condition retryCondition) {
  }

  /**
   * The result of a {@link PolynomialResources#clampedIntegrate(Resource, Resource, Resource, double)} call.
   *
   * @param integral    The clamped integral value.
   * @param overflow    The rate of overflow, when integral hits upper bound.
   *                    Integrate this to get cumulative overflow.
   * @param underflow   The rate of underflow, when integral hits lower bound.
   *                    Integrate this to get cumulative underflow.
   */
  public record ClampedIntegrateResult(
      Resource<Polynomial> integral,
      Resource<Polynomial> overflow,
      Resource<Polynomial> underflow
  ) {}

  /**
   * Returns the derivative of this resource.
   */
  public static Resource<Polynomial> differentiate(Resource<Polynomial> p) {
    var result = map(p, Polynomial::derivative);
    name(result, "Derivative (%s)", p);
    return result;
  }

  /**
   * Return a resource which is the average of the operand over the last interval time.
   */
  public static Resource<Polynomial> movingAverage(Resource<Polynomial> p, Duration interval) {
    var pIntegral = integrate(p, 0);
    var shiftedIntegral = shift(pIntegral, interval, polynomial(0));
    var result = divide(subtract(pIntegral, shiftedIntegral), DiscreteResourceMonad.pure(interval.ratioOver(SECOND)));
    name(result, "Moving Average (%s)", p);
    return result;
  }

  public static Resource<Discrete<Boolean>> greaterThan(Resource<Polynomial> p, double threshold) {
    return greaterThan(p, constant(threshold));
  }

  public static Resource<Discrete<Boolean>> greaterThanOrEquals(Resource<Polynomial> p, double threshold) {
    return greaterThanOrEquals(p, constant(threshold));
  }

  public static Resource<Discrete<Boolean>> lessThan(Resource<Polynomial> p, double threshold) {
    return lessThan(p, constant(threshold));
  }

  public static Resource<Discrete<Boolean>> lessThanOrEquals(Resource<Polynomial> p, double threshold) {
    return lessThanOrEquals(p, constant(threshold));
  }

  public static Resource<Discrete<Boolean>> greaterThan(Resource<Polynomial> p, Resource<Polynomial> q) {
    var result = signalling(bind(p, q, (Polynomial p$, Polynomial q$) -> pure(p$.greaterThan(q$))));
    name(result, "(%s) > (%s)", p, q);
    return result;
  }

  public static Resource<Discrete<Boolean>> greaterThanOrEquals(Resource<Polynomial> p, Resource<Polynomial> q) {
    var result = signalling(bind(p, q, (Polynomial p$, Polynomial q$) -> pure(p$.greaterThanOrEquals(q$))));
    name(result, "(%s) >= (%s)", p, q);
    return result;
  }

  public static Resource<Discrete<Boolean>> lessThan(Resource<Polynomial> p, Resource<Polynomial> q) {
    var result = signalling(bind(p, q, (Polynomial p$, Polynomial q$) -> pure(p$.lessThan(q$))));
    name(result, "(%s) < (%s)", p, q);
    return result;
  }

  public static Resource<Discrete<Boolean>> lessThanOrEquals(Resource<Polynomial> p, Resource<Polynomial> q) {
    var result = signalling(bind(p, q, (Polynomial p$, Polynomial q$) -> pure(p$.lessThanOrEquals(q$))));
    name(result, "(%s) <= (%s)", p, q);
    return result;
  }

  /**
   * Bin values of p like a histogram.
   *
   * @param p Polynomial to use as a key
   * @param bins Map from inclusive lower bound of a range, to the label for that range.
   *             The next entry in the map is the exclusive upper bound of that range.
   */
  public static <A> Resource<Discrete<A>> binned(Resource<Polynomial> p, Resource<Discrete<NavigableMap<Double, A>>> bins) {
    return signalling(bind(p, bins, (Polynomial p$, Discrete<NavigableMap<Double, A>> bins$) -> {
      var entry = bins$.extract().floorEntry(p$.extract());
      if (entry == null) {
        throw new IllegalStateException(
                "%s did not contain an entry for value %f".formatted(
                        Naming.getName(bins).orElse("Bins"), p$.extract()));
      }
      Double cutoff = bins$.extract().higherKey(p$.extract());
      var upperExpiry = cutoff == null ? NEVER : p$.greaterThanOrEquals(polynomial(cutoff)).expiry();
      var lowerExpiry = p$.lessThan(polynomial(entry.getKey())).expiry();
      return pure(expiring(discrete(entry.getValue()), upperExpiry.or(lowerExpiry)));
    }));
  }

  @SafeVarargs
  public static Resource<Polynomial> min(Resource<Polynomial>... args) {
    return min(stream(args));
  }

  public static Resource<Polynomial> min(Stream<Resource<Polynomial>> args) {
    // The awkward wrapped-dynamics level reduction is necessary because
    // the min operation introduces expiry, so a map-style lift isn't possible.
    return signalling(reduce(
            args.toList(),
            DynamicsMonad.pure(polynomial(Double.POSITIVE_INFINITY)),
            DynamicsMonad.bind((p, q) -> ErrorCatchingMonad.pure(p.min(q))),
            "Min"));
  }

  @SafeVarargs
  public static Resource<Polynomial> max(Resource<Polynomial>... args) {
    return max(stream(args));
  }

  public static Resource<Polynomial> max(Stream<Resource<Polynomial>> args) {
    // The awkward wrapped-dynamics level reduction is necessary because
    // the max operation introduces expiry, so a map-style lift isn't possible.
    return signalling(reduce(
            args.toList(),
            DynamicsMonad.pure(polynomial(Double.NEGATIVE_INFINITY)),
            DynamicsMonad.bind((p, q) -> ErrorCatchingMonad.pure(p.max(q))),
            "Max"));
  }

  /**
   * Absolute value
   */
  public static Resource<Polynomial> abs(Resource<Polynomial> p) {
    var result = max(p, negate(p));
    name(result, "| %s |", p);
    return result;
  }

  /**
   * Returns min(max(p, lowerBound), upperBound).
   * <p>
   *   If lowerBound ever exceeds upperBound, this resource fails.
   * </p>
   */
  public static Resource<Polynomial> clamp(Resource<Polynomial> p, Resource<Polynomial> lowerBound, Resource<Polynomial> upperBound) {
    // Bind an assertion into the resource to error it out if the bounds cross over each other.
    var value = max(lowerBound, min(upperBound, p));
    var result = map(
            assertThat(
                    "Clamp lowerBound must be less than or equal to upperBound",
                    lessThanOrEquals(lowerBound, upperBound)),
            value,
            (a, v) -> v);
    name(result, "Clamp (%s)", p);
    return result;
  }

  private static Polynomial scalePolynomial(Polynomial p, double s) {
    return p.multiply(polynomial(s));
  }

  /**
   * Add units to a polynomial resource.
   */
  public static UnitAware<Resource<Polynomial>> unitAware(Resource<Polynomial> p, Unit unit) {
    return UnitAwareResources.unitAware(p, unit, PolynomialResources::scalePolynomial);
  }

  /**
   * Add units to a polynomial resource.
   */
  public static UnitAware<MutableResource<Polynomial>> unitAware(MutableResource<Polynomial> p, Unit unit) {
    return UnitAwareResources.unitAware(p, unit, PolynomialResources::scalePolynomial);
  }

  /**
   * Add polynomial resources.
   */
  @SafeVarargs
  public static UnitAware<Resource<Polynomial>> add(UnitAware<? extends Resource<Polynomial>>... summands) {
    if (summands.length == 0) {
      throw new IllegalArgumentException("Cannot perform unit-aware addition of zero arguments.");
    }
    final Unit unit = summands[0].unit();
    return unitAware(sum(stream(summands).map(r -> r.value(unit))), unit);
  }

  /**
   * Subtract polynomial resources.
   */
  public static UnitAware<Resource<Polynomial>> subtract(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> q) {
    return UnitAwareOperations.subtract(extend(PolynomialResources::scalePolynomial),
                                        PolynomialResources::subtract,
                                        p, q);
  }

  /**
   * Multiply polynomial resources.
   */
  @SafeVarargs
  public static UnitAware<Resource<Polynomial>> multiply(UnitAware<? extends Resource<Polynomial>>... factors) {
    return unitAware(
        product(stream(factors).map(UnitAware::value)),
        stream(factors).map(UnitAware::unit).reduce(Unit.SCALAR, Unit::multiply));
  }

  /**
   * Divide a polynomial by a discrete resource.
   * <p>
   *   The divisor must be discrete, because the quotient of two polynomials is not necessarily a polynomial.
   * </p>
   */
  public static UnitAware<Resource<Polynomial>> divide(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Discrete<Double>>> q) {
    return UnitAwareOperations.divide(extend(PolynomialResources::scalePolynomial), PolynomialResources::divide, p, q);
  }

  /**
   * Compute the integral of integrand, starting at startingValue.
   * <p>
   *   This method allocates a cell, so must be called during initialization, not simulation.
   * </p>
   */
  public static UnitAware<Resource<Polynomial>> integrate(UnitAware<? extends Resource<Polynomial>> p, UnitAware<Double> startingValue) {
    return UnitAwareOperations.integrate(extend(PolynomialResources::scalePolynomial),
                                         PolynomialResources::integrate,
                                         p, startingValue);
  }

  /**
   * Compute the integral of integrand, starting at startingValue.
   * Also clamp the integral between lowerBound and upperBound (inclusive).
   * <p>
   *   Note that <code>clampedIntegrate(r, l, u, s)</code> differs from
   *   <code>clamp(integrate(r, s), l, u)</code> in how they handle reversing from a boundary.
   * </p>
   * <p>
   *   To see how, consider bounds of [0, 5], with an integrand of 1 for 10 seconds, then -1 for 10 seconds.
   * </p>
   * <p>
   *   clamp and integrate:
   * </p>
   * <pre>
   *   5       /----------\
   *          /            \
   *         /              \
   *        /                \
   *   0   /                  \
   * time  0        10        20
   * </pre>
   * <p>
   *   clampedIntegrate:
   * </p>
   * <pre>
   *   5       /-----\
   *          /       \
   *         /         \
   *        /           \
   *   0   /             \-----
   * time  0        10        20
   * </pre>
   */
  public static UnitAwareClampedIntegrateResult clampedIntegrate(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> lowerBound, UnitAware<? extends Resource<Polynomial>> upperBound, UnitAware<Double> startingValue) {
    final Unit resultUnit = p.unit().multiply(StandardUnits.SECOND);
    var unitNaiveResult = clampedIntegrate(
            p.value(),
            lowerBound.value(resultUnit),
            upperBound.value(resultUnit),
            startingValue.value(resultUnit));
    return new UnitAwareClampedIntegrateResult(
        unitAware(unitNaiveResult.integral(), resultUnit),
        unitAware(unitNaiveResult.overflow(), p.unit()),
        unitAware(unitNaiveResult.underflow(), p.unit()));
  }

  /**
   * The result of a {@link PolynomialResources#clampedIntegrate(UnitAware, UnitAware, UnitAware, UnitAware)} call.
   *
   * @param integral    The clamped integral value.
   * @param overflow    The rate of overflow, when integral hits upper bound.
   *                    Integrate this to get cumulative overflow.
   * @param underflow   The rate of underflow, when integral hits lower bound.
   *                    Integrate this to get cumulative underflow.
   */
  public record UnitAwareClampedIntegrateResult(
      UnitAware<Resource<Polynomial>> integral,
      UnitAware<Resource<Polynomial>> overflow,
      UnitAware<Resource<Polynomial>> underflow
  ) {}

  /**
   * Returns the derivative of this resource.
   */
  public static UnitAware<Resource<Polynomial>> differentiate(UnitAware<? extends Resource<Polynomial>> p) {
    return UnitAwareOperations.differentiate(extend(PolynomialResources::scalePolynomial),
                                             PolynomialResources::differentiate,
                                             p);
  }

  // Ugly $ suffix is to avoid ambiguous overloading after erasure.
  public static Resource<Discrete<Boolean>> greaterThan$(UnitAware<? extends Resource<Polynomial>> p, UnitAware<Double> threshold) {
    return greaterThan(p.value(), threshold.value(p.unit()));
  }

  public static Resource<Discrete<Boolean>> greaterThanOrEquals$(UnitAware<? extends Resource<Polynomial>> p, UnitAware<Double> threshold) {
    return greaterThanOrEquals(p.value(), threshold.value(p.unit()));
  }

  public static Resource<Discrete<Boolean>> lessThan$(UnitAware<? extends Resource<Polynomial>> p, UnitAware<Double> threshold) {
    return lessThan(p.value(), threshold.value(p.unit()));
  }

  public static Resource<Discrete<Boolean>> lessThanOrEquals$(UnitAware<? extends Resource<Polynomial>> p, UnitAware<Double> threshold) {
    return lessThanOrEquals(p.value(), threshold.value(p.unit()));
  }

  public static Resource<Discrete<Boolean>> greaterThan(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> q) {
    return greaterThan(subtract(p, q).value(), 0);
  }

  public static Resource<Discrete<Boolean>> greaterThanOrEquals(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> q) {
    return greaterThanOrEquals(subtract(p, q).value(), 0);
  }

  public static Resource<Discrete<Boolean>> lessThan(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> q) {
    return lessThan(subtract(p, q).value(), 0);
  }

  public static Resource<Discrete<Boolean>> lessThanOrEquals(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> q) {
    return lessThanOrEquals(subtract(p, q).value(), 0);
  }

  public static UnitAware<Resource<Polynomial>> min(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> q) {
    return unitAware(min(p.value(), q.value(p.unit())), p.unit());
  }

  public static UnitAware<Resource<Polynomial>> max(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> q) {
    return unitAware(max(p.value(), q.value(p.unit())), p.unit());
  }

  /**
   * Returns min(max(p, lowerBound), upperBound).
   * <p>
   *   If lowerBound ever exceeds upperBound, this resource fails.
   * </p>
   */
  public static UnitAware<Resource<Polynomial>> clamp(UnitAware<? extends Resource<Polynomial>> p, UnitAware<? extends Resource<Polynomial>> lowerBound, UnitAware<? extends Resource<Polynomial>> upperBound) {
    return unitAware(clamp(p.value(), lowerBound.value(p.unit()), upperBound.value(p.unit())), p.unit());
  }
}
