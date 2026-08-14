package gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAware;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteDynamicsMonad.effect;

public final class DiscreteEffects {
  private DiscreteEffects() {}

  // More convenient overload of "set" when using discrete dynamics

  /**
   * Set the resource to the given value.
   */
  public static <A> void set(MutableResource<Discrete<A>> resource, A newValue) {
    resource.emit(name(effect(x -> newValue), "Set %s", newValue));
  }

  // Flag/Switch style operations

  /**
   * Set the resource to true.
   */
  public static void turnOn(MutableResource<Discrete<Boolean>> resource) {
    set(resource, true);
  }

  /**
   * Set the resource to false.
   */
  public static void turnOff(MutableResource<Discrete<Boolean>> resource) {
    set(resource, false);
  }

  /**
   * Toggle the resource value.
   */
  public static void toggle(MutableResource<Discrete<Boolean>> resource) {
    resource.emit("Toggle", effect(x -> !x));
  }

  // Counter style operations

  /**
   * Add one to the resource's value.
   */
  public static void increment(MutableResource<Discrete<Integer>> resource) {
    increment(resource, 1);
  }

  /**
   * Add the given amount to the resource's value.
   */
  public static void increment(MutableResource<Discrete<Integer>> resource, int amount) {
    resource.emit(name(effect(x -> x + amount), "Increment by %s", amount));
  }

  /**
   * Subtract one from the resource's value.
   */
  public static void decrement(MutableResource<Discrete<Integer>> resource) {
    decrement(resource, 1);
  }

  /**
   * Subtract the given amount from the resource's value.
   */
  public static void decrement(MutableResource<Discrete<Integer>> resource, int amount) {
    resource.emit(name(effect(x -> x - amount), "Decrement by %s", amount));
  }

  // General numeric resources

  /**
   * Add amount to resource's value
   */
  public static void increase(MutableResource<Discrete<Double>> resource, double amount) {
    resource.emit(name(effect(x -> x + amount), "Increase by %s", amount));
  }

  /**
   * Subtract amount from resource's value
   */
  public static void decrease(MutableResource<Discrete<Double>> resource, double amount) {
    resource.emit(name(effect(x -> x - amount), "Decrease by %s", amount));
  }

  // Queue style operations, mirroring the Queue interface

  /**
   * Add element to the end of the queue resource
   */
  public static <T> void add(MutableResource<Discrete<List<T>>> resource, T element) {
    resource.emit(name(effect(q -> {
      var q$ = new LinkedList<>(q);
      q$.add(element);
      return q$;
    }), "Add %s to queue", element));
  }

  /**
   * Remove an element from the front of the queue resource.
   * <p>
   *   Returns that element, or empty if the queue was already empty.
   * </p>
   */
  public static <T> Optional<T> remove(MutableResource<Discrete<List<T>>> resource) {
    final var currentQueue = currentValue(resource);
    if (currentQueue.isEmpty()) return Optional.empty();

    final T result = currentQueue.get(0);
    resource.emit(name(effect(q -> {
      var q$ = new LinkedList<>(q);
      T purportedResult = q$.removeFirst();
      if (!result.equals(purportedResult)) {
        throw new IllegalStateException("Detected effect conflicting with queue remove operation");
      }
      return q$;
    }), "Remove %s from queue", result));
    return Optional.of(result);
  }

  // Consumable style operations

  /**
   * Subtract the given amount from resource.
   */
  public static void consume(MutableResource<Discrete<Double>> resource, double amount) {
    resource.emit(name(effect(x -> x - amount), "Consume %s", amount));
  }

  /**
   * Add the given amount to resource.
   */
  public static void restore(MutableResource<Discrete<Double>> resource, double amount) {
    resource.emit(name(effect(x -> x + amount), "Restore %s", amount));
  }

  // Non-consumable style operations

  /**
   * Decrease resource by amount while action is running.
   */
  public static void using(MutableResource<Discrete<Double>> resource, double amount, Runnable action) {
    consume(resource, amount);
    action.run();
    restore(resource, amount);
  }

  // Atomic style operations

  /**
   * Decrease resource by one while action is running.
   */
  public static void using(MutableResource<Discrete<Integer>> resource, Runnable action) {
    decrement(resource);
    action.run();
    increment(resource);
  }

  // Unit-aware effects:

  // More convenient overload of "set" when using discrete dynamics

  /**
   * Set the resource to the given value.
   */
  public static <A> void set(UnitAware<MutableResource<Discrete<A>>> resource, UnitAware<A> newValue) {
    set(resource.value(), newValue.value(resource.unit()));
  }

  // Consumable style operations

  /**
   * Subtract the given amount from resource.
   */
  public static void consume(UnitAware<MutableResource<Discrete<Double>>> resource, UnitAware<Double> amount) {
    consume(resource.value(), amount.value(resource.unit()));
  }

  /**
   * Add the given amount to resource.
   */
  public static void restore(UnitAware<MutableResource<Discrete<Double>>> resource, UnitAware<Double> amount) {
    restore(resource.value(), amount.value(resource.unit()));
  }

  // Non-consumable style operations

  /**
   * Decrease resource by amount while action is running.
   */
  public static void using(UnitAware<MutableResource<Discrete<Double>>> resource, UnitAware<Double> amount, Runnable action) {
    consume(resource, amount);
    action.run();
    restore(resource, amount);
  }
}
