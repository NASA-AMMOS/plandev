package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.autoEffects;
import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.commutingEffects;
import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.noncommutingEffects;
import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteDynamicsMonad.effect;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawn;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static org.junit.jupiter.api.Assertions.*;

class MutableResourceTest {
  @Nested
  @ExtendWith(MerlinExtension.class)
  @TestInstance(Lifecycle.PER_CLASS)
  class NonCommutingEffects {
    public NonCommutingEffects(final Registrar registrar) {
      Resources.init();
    }

    private final MutableResource<Discrete<Integer>> cell = MutableResource.resource(discrete(42), noncommutingEffects());

    @Test
    void gets_initial_value_if_no_effects_are_emitted() {
      assertEquals(42, currentValue(cell));
    }

    @Test
    void applies_singleton_effect() {
      int initialValue = currentValue(cell);
      cell.emit(effect(n -> 3 * n));
      assertEquals(3 * initialValue, currentValue(cell));
    }

    @Test
    void applies_sequential_effects_in_order() {
      int initialValue = currentValue(cell);
      cell.emit(effect(n -> 3 * n));
      cell.emit(effect(n -> n + 1));
      assertEquals(3 * initialValue + 1, currentValue(cell));
    }

    @Test
    void throws_exception_when_concurrent_effects_are_applied() {
      spawn(() -> cell.emit(effect(n -> 3 * n)));
      spawn(() -> cell.emit(effect(n -> 3 * n)));
      delay(ZERO);
      assertInstanceOf(ErrorCatching.Failure.class, cell.getDynamics());
    }
  }

  @Nested
  @ExtendWith(MerlinExtension.class)
  @TestInstance(Lifecycle.PER_CLASS)
  class CommutingEffects {
    public CommutingEffects(final Registrar registrar) {
      Resources.init();
    }

    private final MutableResource<Discrete<Integer>> cell = MutableResource.resource(discrete(42), commutingEffects());

    @Test
    void gets_initial_value_if_no_effects_are_emitted() {
      assertEquals(42, currentValue(cell));
    }

    @Test
    void applies_singleton_effect() {
      int initialValue = currentValue(cell);
      cell.emit(effect(n -> 3 * n));
      assertEquals(3 * initialValue, currentValue(cell));
    }

    @Test
    void applies_sequential_effects_in_order() {
      int initialValue = currentValue(cell);
      cell.emit(effect(n -> 3 * n));
      cell.emit(effect(n -> n + 1));
      assertEquals(3 * initialValue + 1, currentValue(cell));
    }

    @Test
    void applies_concurrent_effects_in_any_order() {
      int initialValue = currentValue(cell);
      // These effects do not in fact commute,
      // but the point of the commutingEffects is that it *doesn't* check.
      spawn(() -> cell.emit(effect(n -> 3 * n)));
      spawn(() -> cell.emit(effect(n -> n + 1)));
      delay(ZERO);
      int result = currentValue(cell);
      assertTrue(result == 3*initialValue + 1 || result == 3 * (initialValue + 1));
    }
  }

  @Nested
  @ExtendWith(MerlinExtension.class)
  @TestInstance(Lifecycle.PER_CLASS)
  class AutoEffects {
    public AutoEffects(final Registrar registrar) {
      Resources.init();
    }

    private final MutableResource<Discrete<Integer>> cell = MutableResource.resource(discrete(42), autoEffects());

    @Test
    void gets_initial_value_if_no_effects_are_emitted() {
      assertEquals(42, currentValue(cell));
    }

    @Test
    void applies_singleton_effect() {
      int initialValue = currentValue(cell);
      cell.emit(effect(n -> 3 * n));
      assertEquals(3 * initialValue, currentValue(cell));
    }

    @Test
    void applies_sequential_effects_in_order() {
      int initialValue = currentValue(cell);
      cell.emit(effect(n -> 3 * n));
      cell.emit(effect(n -> n + 1));
      assertEquals(3 * initialValue + 1, currentValue(cell));
    }

    @Test
    void applies_commuting_concurrent_effects() {
      int initialValue = currentValue(cell);
      // These effects do not in fact commute,
      // but the point of the commutingEffects is that it *doesn't* check.
      spawn(() -> cell.emit(effect(n -> 3 * n)));
      spawn(() -> cell.emit(effect(n -> 4 * n)));
      delay(ZERO);
      int result = currentValue(cell);
      assertEquals(12 * initialValue, result);
    }

    @Test
    void throws_exception_when_non_commuting_concurrent_effects_are_applied() {
      spawn(() -> cell.emit(effect(n -> 3 * n)));
      spawn(() -> cell.emit(effect(n -> n + 1)));
      delay(ZERO);
      assertInstanceOf(ErrorCatching.Failure.class, cell.getDynamics());
    }
  }
}
