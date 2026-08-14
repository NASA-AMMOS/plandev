package gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete;

import gov.nasa.jpl.aerie.contrib.streamline.core.*;
import gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.CommutativityTestInput;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteDynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.utils.DoubleUtils;
import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Unit;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAware;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAwareResources;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.autoEffects;
import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.testing;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.expiring;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.expiry;
import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Reactions.every;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Reactions.whenever;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.*;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.bind;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.pure;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Dependencies.addDependency;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.ClockResources.clock;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.set;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad.*;
import static java.util.Arrays.stream;

public final class DiscreteResources {
  private DiscreteResources() {}

  public static <T> Resource<Discrete<T>> constant(T value) {
    var result = DiscreteResourceMonad.pure(value);
    name(result, value.toString());
    return result;
  }

  // General discrete cell resource constructor
  public static <T> MutableResource<Discrete<T>> discreteResource(T initialValue) {
    return resource(discrete(initialValue));
  }

  // Annoyingly, we need to repeat the specialization for integer resources, so that
  // discreteMutableResource(42) doesn't become a double resource, due to the next overload
  public static MutableResource<Discrete<Integer>> discreteResource(int initialValue) {
    return resource(discrete(initialValue));
  }

  // specialized constructor for doubles, because they require a toleranced equality comparison
  public static MutableResource<Discrete<Double>> discreteResource(double initialValue) {
    return resource(discrete(initialValue), autoEffects(testing(
        (CommutativityTestInput<Discrete<Double>> input) -> DoubleUtils.areEqualResults(
            input.original().extract(),
            input.leftResult().extract(),
            input.rightResult().extract()))));
  }

  /**
   * Returns a condition that's satisfied whenever this resource is true.
   */
  public static Condition when(Resource<Discrete<Boolean>> resource) {
    Condition result = (positive, atEarliest, atLatest) ->
        resource.getDynamics().match(
            dynamics -> Optional.of(atEarliest).filter($ -> dynamics.data().extract() == positive),
            error -> Optional.empty());
    name(result, "when %s", resource);
    return result;
  }

  /**
   * Cache resource, updating the cache when updatePredicate(cached value, resource value) is true.
   */
  public static <V> Resource<Discrete<V>> cache(Resource<Discrete<V>> resource, BiPredicate<V, V> updatePredicate) {
    final var cell = resource(resource.getDynamics());
    // TODO: Does the update predicate need to propagate expiry information?
    BiPredicate<ErrorCatching<Expiring<Discrete<V>>>, ErrorCatching<Expiring<Discrete<V>>>> liftedUpdatePredicate = (eCurrent, eNew) ->
        eCurrent.match(
            current -> eNew.match(
                value -> updatePredicate.test(current.data().extract(), value.data().extract()),
                newException -> true),
            currentException -> eNew.match(
                value -> true,
                newException -> !equivalentExceptions(currentException, newException)));
    whenever(() -> {
      var currentDynamics = resource.getDynamics();
      return when(() -> DiscreteDynamicsMonad.pure(liftedUpdatePredicate.test(
          currentDynamics,
          resource.getDynamics())));
    }, () -> {
      final var newDynamics = resource.getDynamics();
      cell.emit($ -> newDynamics);
    });
    name(cell, "Cache (%s)", resource);
    addDependency(cell, resource);
    return cell;
  }

  /**
   * Sample valueSupplier once every samplePeriod.
   */
  public static <V, T extends Dynamics<Duration, T>> Resource<Discrete<V>> sampled(Supplier<V> valueSupplier, Resource<T> samplePeriod) {
    var result = discreteResource(valueSupplier.get());
    every(() -> currentValue(samplePeriod, Duration.MAX_VALUE),
          () -> set(result, valueSupplier.get()));
    return result;
  }

  /**
   * Returns a discrete resource that follows a precomputed sequence of values.
   * Resource value is the value associated with the greatest key in segments not exceeding
   * the current simulation time, or valueBeforeFirstEntry if every key exceeds current simulation time.
   */
  public static <V> Resource<Discrete<V>> precomputed(
      final V valueBeforeFirstEntry, final NavigableMap<Duration, V> segments) {
    var clock = clock();
    return signalling(bind(clock, (Clock clock$) -> {
      var t = clock$.extract();
      var entry = segments.floorEntry(t);
      var value = entry == null ? valueBeforeFirstEntry : entry.getValue();
      var nextTime = expiry(Optional.ofNullable(segments.higherKey(t)));
      return pure(expiring(discrete(value), nextTime.minus(t)));
    }));
  }

  /**
   * Returns a discrete resource that follows a precomputed sequence of values.
   * Resource value is the value associated with the greatest key in segments not exceeding
   * the current simulation time, or valueBeforeFirstEntry if every key exceeds current simulation time.
   */
  public static <V> Resource<Discrete<V>> precomputed(
      final V valueBeforeFirstEntry, final NavigableMap<Instant, V> segments, final Instant simulationStartTime) {
    var segmentsUsingDurationKeys = new TreeMap<Duration, V>();
    for (var entry : segments.entrySet()) {
      segmentsUsingDurationKeys.put(
          Duration.of(ChronoUnit.MICROS.between(simulationStartTime, entry.getKey()), Duration.MICROSECONDS),
          entry.getValue());
    }
    return precomputed(valueBeforeFirstEntry, segmentsUsingDurationKeys);
  }

  /**
   * Add units to a discrete double resource.
   */
  public static UnitAware<Resource<Discrete<Double>>> unitAware(Resource<Discrete<Double>> resource, Unit unit) {
    return UnitAwareResources.unitAware(resource, unit, DiscreteResources::discreteScaling);
  }

  /**
   * Add units to a discrete double resource.
   */
  public static UnitAware<MutableResource<Discrete<Double>>> unitAware(MutableResource<Discrete<Double>> resource, Unit unit) {
    return UnitAwareResources.unitAware(resource, unit, DiscreteResources::discreteScaling);
  }

  private static Discrete<Double> discreteScaling(Discrete<Double> d, Double scale) {
    return DiscreteMonad.map(d, $ -> $ * scale);
  }

  // Generally applicable derivations

  public static <A> Resource<Discrete<Boolean>> equals(Resource<Discrete<A>> left, Resource<Discrete<A>> right) {
    var result = map(left, right, Objects::equals);
    name(result, "(%s) == (%s)", left, right);
    return result;
  }

  public static <A> Resource<Discrete<Boolean>> notEquals(Resource<Discrete<A>> left, Resource<Discrete<A>> right) {
    var result = not(equals(left, right));
    name(result, "(%s) != (%s)", left, right);
    return result;
  }

  // Boolean logic

  /**
   * Short-circuiting logical "and"
   */
  public static Resource<Discrete<Boolean>> and(Resource<Discrete<Boolean>> left, Resource<Discrete<Boolean>> right) {
    // Short-circuiting and: Only gets right if left is true
    var result = choose(left, right, constant(false));
    name(result, "(%s) and (%s)", left, right);
    return result;
  }

  /**
   * Reduce operands using short-circuiting logical "and"
   */
  @SafeVarargs
  public static Resource<Discrete<Boolean>> all(Resource<Discrete<Boolean>>... operands) {
    return all(stream(operands).toList());
  }

  /**
   * Reduce operands using short-circuiting logical "and"
   */
  public static Resource<Discrete<Boolean>> all(Collection<? extends Resource<Discrete<Boolean>>> operands) {
    // Reduce using the short-circuiting and to improve efficiency
    // Unlike most reductions, we explicitly want to add intermediate nodes by using Resources.reduce instead of ResourceMonad.reduce.
    // Those intermediate nodes allow us to short-circuit, truncating our dependencies.
    return Resources.reduce(operands, constant(true), DiscreteResources::and, "All");
  }

  /**
   * Short-circuiting logical "or"
   */
  public static Resource<Discrete<Boolean>> or(Resource<Discrete<Boolean>> left, Resource<Discrete<Boolean>> right) {
    // Short-circuiting or: Only gets right if left is false
    var result = choose(left, constant(true), right);
    name(result, "(%s) or (%s)", left, right);
    return result;
  }

  /**
   * Reduce operands using short-circuiting logical "or"
   */
  @SafeVarargs
  public static Resource<Discrete<Boolean>> any(Resource<Discrete<Boolean>>... operands) {
    return any(stream(operands).toList());
  }

  /**
   * Reduce operands using short-circuiting logical "or"
   */
  public static Resource<Discrete<Boolean>> any(Collection<? extends Resource<Discrete<Boolean>>> operands) {
    // Reduce using the short-circuiting or to improve efficiency
    // Unlike most reductions, we explicitly want to add intermediate nodes by using Resources.reduce instead of ResourceMonad.reduce.
    // Those intermediate nodes allow us to short-circuit, truncating our dependencies.
    return Resources.reduce(operands, constant(false), DiscreteResources::or, "Any");
  }

  /**
   * Logical "not"
   */
  public static Resource<Discrete<Boolean>> not(Resource<Discrete<Boolean>> operand) {
    var result = map(operand, $ -> !$);
    name(result, "not (%s)", operand);
    return result;
  }

  /**
   * Resource-level if-then-else logic.
   */
  public static <D> Resource<D> choose(Resource<Discrete<Boolean>> condition, Resource<D> thenCase, Resource<D> elseCase) {
    var result = bind(condition, c -> c.extract() ? thenCase : elseCase);
    // Manually add dependencies, since short-circuiting will break automatic dependency tracking.
    addDependency(result, thenCase);
    addDependency(result, elseCase);
    name(result, "(%s) ? (%s) : (%s)", condition, thenCase, elseCase);
    return result;
  }

  /**
   * Assert that this resource is always true.
   * Otherwise, this resource fails.
   * Register this resource to detect that failure.
   */
  public static Resource<Discrete<Boolean>> assertThat(String description, Resource<Discrete<Boolean>> assertion) {
    var result = map(assertion, a -> {
      if (a) return true;
      throw new AssertionError(description);
    });
    name(result, "Assertion: " + description);
    return result;
  }

  // Integer arithmetic

  /**
   * Add integer resources
   */
  @SafeVarargs
  public static Resource<Discrete<Integer>> addInt(Resource<Discrete<Integer>>... operands) {
    return sumInt(Arrays.stream(operands).toList());
  }

  /**
   * Add integer resources
   */
  public static Resource<Discrete<Integer>> sumInt(Collection<? extends Resource<Discrete<Integer>>> operands) {
    return reduce(operands, 0, Integer::sum, "Sum");
  }

  /**
   * Subtract integer resources
   */
  public static Resource<Discrete<Integer>> subtractInt(Resource<Discrete<Integer>> left, Resource<Discrete<Integer>> right) {
    var result = map(left, right, (l, r) -> l - r);
    name(result, "(%s) - (%s)", left, right);
    return result;
  }

  /**
   * Multiply integer resources
   */
  @SafeVarargs
  public static Resource<Discrete<Integer>> multiplyInt(Resource<Discrete<Integer>>... operands) {
    return productInt(Arrays.stream(operands).toList());
  }

  /**
   * Multiply integer resources
   */
  public static Resource<Discrete<Integer>> productInt(Collection<? extends Resource<Discrete<Integer>>> operands) {
    return reduce(operands, 1, (x, y) -> x * y, "Product");
  }

  /**
   * Divide integer resources
   */
  public static Resource<Discrete<Integer>> divideInt(Resource<Discrete<Integer>> left, Resource<Discrete<Integer>> right) {
    var result = map(left, right, (l, r) -> l / r);
    name(result, "(%s) / (%s)", left, right);
    return result;
  }

  // Double arithmetic

  /**
   * Add double resources
   */
  @SafeVarargs
  public static Resource<Discrete<Double>> add(Resource<Discrete<Double>>... operands) {
    return sum(Arrays.stream(operands).toList());
  }

  /**
   * Add double resources
   */
  public static Resource<Discrete<Double>> sum(Collection<? extends Resource<Discrete<Double>>> operands) {
    return reduce(operands, 0.0, Double::sum, "Sum");
  }

  /**
   * Subtract double resources
   */
  public static Resource<Discrete<Double>> subtract(Resource<Discrete<Double>> left, Resource<Discrete<Double>> right) {
    var result = map(left, right, (l, r) -> l - r);
    name(result, "(%s) - (%s)", left, right);
    return result;
  }

  /**
   * Multiply double resources
   */
  @SafeVarargs
  public static Resource<Discrete<Double>> multiply(Resource<Discrete<Double>>... operands) {
    return product(Arrays.stream(operands).toList());
  }

  /**
   * Multiply double resources
   */
  public static Resource<Discrete<Double>> product(Collection<? extends Resource<Discrete<Double>>> operands) {
    return reduce(operands, 1.0, (x, y) -> x * y, "Product");
  }

  /**
   * Divide double resources
   */
  public static Resource<Discrete<Double>> divide(Resource<Discrete<Double>> left, Resource<Discrete<Double>> right) {
    var result = map(left, right, (l, r) -> l / r);
    name(result, "(%s) / (%s)", left, right);
    return result;
  }

  // Collections

  /**
   * Returns a resource that's true when the argument is empty
   */
  public static <C extends Collection<?>> Resource<Discrete<Boolean>> isEmpty(Resource<Discrete<C>> resource) {
    var result = map(resource, Collection::isEmpty);
    name(result, "(%s) is empty", resource);
    return result;
  }

  /**
   * Returns a resource that's true when the argument is non-empty
   */
  public static <C extends Collection<?>> Resource<Discrete<Boolean>> isNonEmpty(Resource<Discrete<C>> resource) {
    var result = not(isEmpty(resource));
    name(result, "(%s) is not empty", resource);
    return result;
  }

  public static <V, C extends Collection<V>> Resource<Discrete<Boolean>> contains(Resource<Discrete<C>> collection, Resource<Discrete<V>> value) {
    var result = map(collection, value, Collection::contains);
    name(result, "(%s) contains (%s)", collection, value);
    return result;
  }
}
