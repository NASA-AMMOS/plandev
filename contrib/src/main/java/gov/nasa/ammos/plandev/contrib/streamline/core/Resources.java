package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock;
import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.framework.Scoped;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.neverExpiring;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.NEVER;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Reactions.wheneverDynamicsChange;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Dependencies.addDependency;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock.clock;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;

/**
 * Utility methods for {@link Resource}s.
 */
public final class Resources {
  private Resources() {}

  /**
   * Ensure that Resources are initialized.
   *
   * <p>
   *   This method needs to be called during simulation initialization.
   *   This method is idempotent; calling it multiple times is the same as calling it once.
   * </p>
   */
  public static void init() {
    currentTime();
  }

  // TODO if Aerie provides either a `getElapsedTime` method or dynamic allocation of Cells, we can avoid this mutable static variable
  private static Resource<Clock> CLOCK = resource(clock(ZERO));
  public static Duration currentTime() {
    try {
      return currentValue(CLOCK);
    } catch (Scoped.EmptyDynamicCellException | IllegalArgumentException e) {
      // If we're running unit tests, several simulations can happen without reloading the Resources class.
      // In that case, we'll have discarded the clock resource we were using, and get the above exception.
      // REVIEW: Is there a cleaner way to make sure this resource gets (re-)initialized?
      CLOCK = resource(clock(ZERO));
      return currentValue(CLOCK);
    }
  }

  public static <D> D currentData(Resource<D> resource) {
    return data(resource.getDynamics());
  }

  public static <D> D currentData(Resource<D> resource, D dynamicsIfError) {
    return data(resource.getDynamics(), dynamicsIfError);
  }

  public static <V, D extends Dynamics<V, D>> V currentValue(Resource<D> resource) {
    return value(resource.getDynamics());
  }

  public static <V, D extends Dynamics<V, D>> V currentValue(Resource<D> resource, V valueIfError) {
    return value(resource.getDynamics(), valueIfError);
  }

  public static <D> D data(ErrorCatching<Expiring<D>> dynamics) {
    return dynamics.getOrThrow().data();
  }

  public static <D> D data(ErrorCatching<Expiring<D>> dynamics, D dynamicsIfError) {
    return dynamics.match(Expiring::data, error -> dynamicsIfError);
  }

  public static <V, D extends Dynamics<V, D>> V value(ErrorCatching<Expiring<D>> dynamics) {
    return data(dynamics).extract();
  }

  public static <V, D extends Dynamics<V, D>> V value(ErrorCatching<Expiring<D>> dynamics, V valueIfError) {
    return dynamics.match(result -> result.data().extract(), error -> valueIfError);
  }

  /**
   * Condition that's triggered when the dynamics on resource change in a way
   * that's different from just evolving with time.
   * This can be due to effects on a cell used by this resource,
   * or by some part of the derivation expiring.
   */
  public static <D extends Dynamics<?, D>> Condition dynamicsChange(Resource<D> resource) {
    final var startingDynamics = resource.getDynamics();
    final Duration startTime = currentTime();
    Condition result = (positive, atEarliest, atLatest) -> {
      var currentDynamics = resource.getDynamics();
      var elapsedTime = currentTime().minus(startTime);
      boolean haveChanged = startingDynamics.match(
          start -> currentDynamics.match(
              current -> !current.data().equals(start.data().step(elapsedTime)) ||
                         !current.expiry().equals(start.expiry().minus(elapsedTime)),
              ignored -> true),
          startException -> currentDynamics.match(
              ignored -> true,
              // Use semantic comparison for exceptions, since derivation can generate the exception each invocation.
              currentException -> !equivalentExceptions(startException, currentException)));

        if (positive == haveChanged) return Optional.of(atEarliest);
        if (!positive) return Optional.empty(); // Dynamics have changed, so they will never be unchanged

        // Dynamics haven't changed, but they may expire before atLatest. Treat expiry as a change
        return currentDynamics.match(
                expiring -> expiring.expiry().value().filter(atLatest::noShorterThan),
                exception -> Optional.empty());
    };
    name(result, "Dynamics Change (%s)", resource);
    return result;
  }

  /**
   * A weaker form of {@link Resources#dynamicsChange},
   * which doesn't attempt to compare dynamics.
   * <p>
   * This Condition is less robust, and may trigger spuriously.
   * When used in a reaction loop like {@link Reactions#wheneverUpdates(Resource, Consumer)},
   * there is a known 1-tick "blindspot" after updates fires; if two updates happen to resource
   * in back-to-back simulation ticks at the same time, only the first triggers the reaction loop.
   * One way to handle this blindspot is with spawn and delay, like this:
   * <pre>
   * wheneverUpdates(resource, () -> {
   *   // Handle the immediate update, if desired
   *   spawn(replaying(() -> {
   *     delay(ZERO);
   *     // Handle an update 1 tick after the update that triggered this loop, if any happened.
   *   }));
   * });
   * </pre>
   * </p>
   * <p>
   * However, this condition doesn't depend on dynamics having a well-behaved equals method.
   * </p>
   */
  public static Condition updates(Resource<?> resource) {
    var result = new Condition() {
      private boolean first = true;

      @Override
      public Optional<Duration> nextSatisfied(
          final boolean positive,
          final Duration atEarliest,
          final Duration atLatest)
      {
        // Get resource to subscribe this condition to resource's cells
        var dynamics = resource.getDynamics();
        if (first) {
          first = false;
          return dynamics.match(Expiring::expiry, e -> NEVER).value().filter(atLatest::noShorterThan);
        } else {
          return Optional.of(atEarliest);
        }
      }
    };
    name(result, "Updates (%s)", resource);
    return result;
  }

  public static Condition expires(Resource<?> resource) {
    Condition result = (positive, atEarliest, atLatest) -> resource.getDynamics().match(
        expiring -> expiring.expiry().value().filter(atLatest::noShorterThan).map(t -> Duration.max(t, atEarliest)),
        error -> Optional.empty());
    name(result, "Expires (%s)", resource);
    return result;
  }

  /**
   * Use a reaction loop to synchronize destination with source.
   * This is used primarily for building feedback loops in a resource derivation.
   */
  public static <D extends Dynamics<?, D>> void forward(Resource<D> source, MutableResource<D> destination) {
    wheneverDynamicsChange(source, s -> destination.emit(
            name($ -> s, "Forward %s dynamics to %s", source, destination)));
    addDependency(destination, source);
  }

  /**
   * Tests if two exceptions are equivalent from the point of view of resource values.
   * Two exceptions are equivalent if they have the same type and message.
   */
  public static boolean equivalentExceptions(Throwable startException, Throwable currentException) {
    return Objects.equals(startException.getClass(), currentException.getClass())
           && Objects.equals(startException.getMessage(), currentException.getMessage());
  }

  /**
   * Cache this resource in a resource.
   *
   * <p>
   *   Updates the resource when resource changes dynamics.
   *   This can be used to isolate a resource from effects
   *   which don't change the dynamics, so Aerie samples that
   *   resource only when strictly necessary.
   * </p>
   * <p>
   *   This introduces a small delay in deriving values.
   *   Specifically, the cached version of a resource changes two
   *   simulation engine cycles after its uncached version.
   *   It will show up as the same instant in the results,
   *   but beware that it could be momentarily out-of-sync
   *   with its sources during simulation.
   * </p>
   */
  public static <D extends Dynamics<?, D>> Resource<D> cache(Resource<D> resource) {
    final var cell = resource(resource.getDynamics());
    forward(resource, cell);
    name(cell, "Cache (%s)", resource);
    return cell;
  }

  /**
   * Signal discrete changes in this resource's dynamics.
   *
   * <p>
   *   For Aerie's resource sampling to work correctly,
   *   there must be an effect every time a resource changes dynamics.
   *   For most derived resources, this happens automatically.
   *   For some derivations, though, continuous changes in the source state
   *   can cause discrete changes in the result.
   *   For example, imagine a continuous numeric resource R,
   *   and a derived resource S := "R > 0".
   *   If R changes continuously from positive to negative,
   *   then S changes discretely from true to false, *without* an effect.
   *   If used directly, Aerie would not re-sample S at this time.
   *   This method emits a trivial effect when this happens so that S
   *   *would* be resampled correctly.
   * </p>
   * <p>
   *   Unlike {@link Resources#cache}, this method does *not* introduce
   *   a delay between the source and derived resources.
   *   Signalling resources use a resource "in parallel" rather than "in series"
   *   with the derivation process, thereby avoiding the delay.
   *   Like regular derived resources, signalling resources calculate their value
   *   through the derivation every time they are sampled.
   * </p>
   */
  // REVIEW: Suggestion from Jonathan Castello to remove this method
  // in favor of allowing resources to report expiry information directly.
  // This would be cleaner and potentially more performant.
  public static <D extends Dynamics<?, D>> Resource<D> signalling(Resource<D> resource) {
    var cell = resource(discrete(Unit.UNIT));
    name(cell, "Signal for (%s)", resource);
    wheneverDynamicsChange(resource, ignored -> cell.emit($ -> $));
    Resource<D> result = () -> {
      cell.getDynamics();
      return resource.getDynamics();
    };
    name(result, "Signalling (%s)", resource);
    addDependency(result, resource);
    addDependency(result, cell);
    return result;
  }

  public static <D extends Dynamics<?, D>> Resource<D> shift(Resource<D> resource, Duration interval, D initialDynamics) {
    if (interval.shorterThan(ZERO)) {
      throw new IllegalArgumentException("Cannot shift resource by negative interval: " + interval);
    }
    if (interval.equals(ZERO)) {
      return resource;
    }
    var cell = resource(initialDynamics);
    delayedSet(cell, resource.getDynamics(), interval);
    wheneverDynamicsChange(resource, newDynamics ->
        delayedSet(cell, newDynamics, interval));
    name(cell, "Shifted (%s)", resource);
    addDependency(cell, resource);
    return cell;
  }

  private static <D extends Dynamics<?, D>> void delayedSet(
          MutableResource<D> cell, ErrorCatching<Expiring<D>> newDynamics, Duration interval)
  {
    spawn(replaying(() -> {
      delay(interval);
      cell.emit($ -> newDynamics);
    }));
  }

  /**
   * Erase expiry information from a resource.
   *
   * <p>
   *   This is useful when a resource is defined through a feedback loop,
   *   to not propagate the expiry across iterations of that loop
   * </p>
   */
  public static <D> Resource<D> eraseExpiry(Resource<D> p) {
    Resource<D> result = () -> p.getDynamics().map(e -> neverExpiring(e.data()));
    name(result, "Erase expiry (%s)", p);
    addDependency(result, p);
    return result;
  }

  /**
   * Reduce a collection of resources.
   * <p>
   *     Note on efficiency: For n operands, this method will create (n-2) intermediate resources,
   *     plus the terminal result resource.
   *     This overhead is often unnecessary. Consider using the methods linked below
   *     for alternative implementations of reduce that may be more efficient.
   *     There may also be efficient implementations of reduce on the relevant resource monad, like
   *     {@link gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad#reduce}.
   * </p>
   *
   * @see gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad#reduce(Collection, Object, BiFunction)
   * @see gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad#reduce(Collection, Object, BiFunction, String)
   */
  public static <D> Resource<D> reduce(Stream<? extends Resource<D>> operands, Resource<D> identity, BiFunction<Resource<D>, Resource<D>, Resource<D>> operation, String operationName) {
    return reduce(operands.toList(), identity, operation, operationName);
  }

  /**
   * Reduce a collection of resources.
   * <p>
   *     Note on efficiency: For n operands, this method will create O(n) intermediate resources.
   *     This overhead is often unnecessary. Consider using the methods linked below
   *     for alternative implementations of reduce that may be more efficient.
   *     There may also be efficient implementations of reduce on the relevant resource monad, like
   *     {@link gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad#reduce}.
   * </p>
   *
   * @see gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad#reduce(Collection, Object, BiFunction)
   * @see gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad#reduce(Collection, Object, BiFunction, String)
   */
  public static <D> Resource<D> reduce(Collection<? extends Resource<D>> operands, Resource<D> identity, BiFunction<Resource<D>, Resource<D>, Resource<D>> operation, String operationName) {
    var result = operands.stream().reduce(identity, operation, operation::apply);
    name(result, operationName + argsFormat(operands), operands.toArray());
    return result;
  }
}
