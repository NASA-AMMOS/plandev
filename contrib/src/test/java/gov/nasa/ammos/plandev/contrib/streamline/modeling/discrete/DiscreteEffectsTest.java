package gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.ErrorCatching;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resources;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock;
import gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAware;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentTime;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.unitAware;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Quantities.add;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Quantities.quantity;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Quantities.subtract;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.StandardUnits.*;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.StandardUnits.BIT;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.StandardUnits.METER;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.UnitAwareResources.currentValue;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawn;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTE;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MerlinExtension.class)
@TestInstance(Lifecycle.PER_CLASS)
class DiscreteEffectsTest {
  public DiscreteEffectsTest(final Registrar registrar) {
    Resources.init();
  }

  private final MutableResource<Discrete<Integer>> settable = resource(discrete(42));

  @Test
  void set_effect_changes_to_new_value() {
    set(settable, 123);
    assertEquals(123, currentValue(settable));
  }

  @Test
  void conflicting_concurrent_set_effects_throw_exception() {
    spawn(() -> set(settable, 123));
    spawn(() -> set(settable, 456));
    delay(ZERO);
    assertInstanceOf(ErrorCatching.Failure.class, settable.getDynamics());
  }

  @Test
  void agreeing_concurrent_set_effects_set_new_value() {
    spawn(() -> set(settable, 789));
    spawn(() -> set(settable, 789));
    delay(ZERO);
    assertEquals(789, currentValue(settable));
  }

  private final MutableResource<Discrete<Boolean>> flag = resource(discrete(false));

  @Test
  void flag_set_makes_value_true() {
    turnOn(flag);
    assertTrue(currentValue(flag));
  }

  @Test
  void flag_unset_makes_value_false() {
    turnOff(flag);
    assertFalse(currentValue(flag));
  }

  @Test
  void flag_toggle_changes_value() {
    turnOn(flag);
    toggle(flag);
    assertFalse(currentValue(flag));

    toggle(flag);
    assertTrue(currentValue(flag));
  }

  private final MutableResource<Discrete<Integer>> counter = resource(discrete(0));

  @Test
  void increment_increases_value_by_1() {
    int initialValue = currentValue(counter);
    increment(counter);
    assertEquals(initialValue + 1, currentValue(counter));
  }

  @Test
  void increment_by_n_increases_value_by_n() {
    int initialValue = currentValue(counter);
    increment(counter, 3);
    assertEquals(initialValue + 3, currentValue(counter));
  }

  @Test
  void decrement_decreases_value_by_1() {
    int initialValue = currentValue(counter);
    decrement(counter);
    assertEquals(initialValue - 1, currentValue(counter));
  }

  @Test
  void decrement_by_n_decreases_value_by_n() {
    int initialValue = currentValue(counter);
    decrement(counter, 3);
    assertEquals(initialValue - 3, currentValue(counter));
  }

  private final MutableResource<Discrete<Double>> consumable = resource(discrete(10.0));

  @Test
  void consume_decreases_value_by_amount() {
    double initialValue = currentValue(consumable);
    consume(consumable, 3.14);
    assertEquals(initialValue - 3.14, currentValue(consumable));
  }

  @Test
  void restore_increases_value_by_amount() {
    double initialValue = currentValue(consumable);
    restore(consumable, 3.14);
    assertEquals(initialValue + 3.14, currentValue(consumable));
  }

  @Test
  void consume_and_restore_effects_commute() {
    double initialValue = currentValue(consumable);
    spawn(() -> consume(consumable, 2.7));
    spawn(() -> restore(consumable, 5.6));
    delay(ZERO);
    assertEquals(initialValue - 2.7 + 5.6, currentValue(consumable));
  }

  private final MutableResource<Discrete<Double>> nonconsumable = resource(discrete(10.0));

  @Test
  void using_decreases_value_while_action_is_running() {
    double initialValue = currentValue(nonconsumable);
    using(nonconsumable, 3.14, () -> {
      assertEquals(initialValue - 3.14, currentValue(nonconsumable));
    });
    assertEquals(initialValue, currentValue(nonconsumable));
  }

  MutableResource<Clock> DEBUG_clock = resource(new Clock(ZERO));

  @Test
  void using_runs_synchronously() {
    Duration start = currentTime();
    using(nonconsumable, 3.14, () -> {
      assertEquals(start, currentTime());
      delay(MINUTE);
    });
    assertEquals(start.plus(MINUTE), currentTime());
  }

  @Test
  void tasks_in_parallel_with_using_observe_decreased_value() {
    double initialValue = currentValue(nonconsumable);
    spawn(() -> using(nonconsumable, 3.14, () -> {
      delay(MINUTE);
    }));
    // Allow one tick for effects to be observable from child task
    delay(ZERO);
    assertEquals(initialValue - 3.14, currentValue(nonconsumable));
    delay(30, SECONDS);
    assertEquals(initialValue - 3.14, currentValue(nonconsumable));
    delay(30, SECONDS);
    // Allow one tick for effects to be observable from child task
    delay(ZERO);
    assertEquals(initialValue, currentValue(nonconsumable));
  }

  UnitAware<MutableResource<Discrete<Double>>> settableDataVolume = unitAware(resource(discrete(10.0)), BIT);

  @Test
  void unit_aware_set_converts_to_resource_unit() {
    set(settableDataVolume, quantity(2, BYTE));
    assertEquals(quantity(16.0, BIT), currentValue(settableDataVolume));
  }

  @Test
  void unit_aware_set_throws_exception_if_wrong_dimension_is_used() {
    assertThrows(IllegalArgumentException.class, () -> set(settableDataVolume, quantity(2, METER)));
  }

  UnitAware<MutableResource<Discrete<Double>>> consumableDataVolume = unitAware(resource(discrete(10.0)), BIT);

  @Test
  void unit_aware_consume_converts_to_resource_unit() {
    var initialDataVolume = currentValue(consumableDataVolume);
    var oneByte = quantity(1, BYTE);
    consume(consumableDataVolume, oneByte);
    assertEquals(subtract(initialDataVolume, oneByte), currentValue(consumableDataVolume));
  }

  @Test
  void unit_aware_consume_throws_exception_if_wrong_dimension_is_used() {
    assertThrows(IllegalArgumentException.class, () -> consume(consumableDataVolume, quantity(1, METER)));
  }

  @Test
  void unit_aware_restore_converts_to_resource_unit() {
    var initialDataVolume = currentValue(consumableDataVolume);
    var oneByte = quantity(1, BYTE);
    restore(consumableDataVolume, oneByte);
    assertEquals(add(initialDataVolume, oneByte), currentValue(consumableDataVolume));
  }

  @Test
  void unit_aware_restore_throws_exception_if_wrong_dimension_is_used() {
    assertThrows(IllegalArgumentException.class, () -> restore(consumableDataVolume, quantity(1, METER)));
  }

  UnitAware<MutableResource<Discrete<Double>>> nonconsumableDataVolume = unitAware(resource(discrete(10.0)), BIT);

  @Test
  void unit_aware_using_converts_to_resource_unit() {
    var initialDataVolume = currentValue(nonconsumableDataVolume);
    var oneByte = quantity(1, BYTE);
    using(nonconsumableDataVolume, oneByte, () -> {
      assertEquals(subtract(initialDataVolume, oneByte), currentValue(nonconsumableDataVolume));
    });
    assertEquals(initialDataVolume, currentValue(nonconsumableDataVolume));
  }
}
