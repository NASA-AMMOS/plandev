package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ErrorCatchingMonad;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Context;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling;
import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.Cell;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.allocate;
import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.autoEffects;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad.pure;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.*;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling.profile;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling.profileEffects;
import static java.util.stream.Collectors.joining;

/**
 * A resource to which effects can be applied.
 */
public interface MutableResource<D extends Dynamics<?, D>> extends Resource<D> {
  void emit(DynamicsEffect<D> effect);
  default void emit(String effectName, DynamicsEffect<D> effect) {
    emit(name(effect, effectName));
  }

  static <D extends Dynamics<?, D>> MutableResource<D> resource(D initial) {
    return resource(pure(initial));
  }

  static <D extends Dynamics<?, D>> MutableResource<D> resource(D initial, EffectTrait<DynamicsEffect<D>> effectTrait) {
    return resource(pure(initial), effectTrait);
  }

  static <D extends Dynamics<?, D>> MutableResource<D> resource(ErrorCatching<Expiring<D>> initial) {
    // Use autoEffects for a generic CellResource, on the theory that most resources
    // have relatively few effects, and even fewer concurrent effects, so this is performant enough.
    // If that doesn't hold, a more specialized solution can be constructed directly.
    return resource(initial, autoEffects());
  }

  static <D extends Dynamics<?, D>> MutableResource<D> resource(ErrorCatching<Expiring<D>> initial, EffectTrait<DynamicsEffect<D>> effectTrait) {
    MutableResource<D> result = new MutableResource<>() {
      private final CellRef<DynamicsEffect<D>, Cell<D>> cell = allocate(initial, effectTrait);

      @Override
      public void emit(final DynamicsEffect<D> effect) {
        // NOTE: The strange pattern of naming effect::apply is to create a new object, identical in behavior to effect,
        //   which we can assign a more informative name without actually getting the name of effect.
        // Replacing effect::apply with effect would create a self-loop in the naming graph on effect, which isn't allowed.
        // Using Naming.getName to get effect's current name and use that when elaborating is correct but potentially slow,
        //   depending on how deep the naming graph is.
        cell.emit(name(effect::apply, "%s on %s" + Context.get().stream().map(c -> " during " + c).collect(joining()), effect, this));
      }

      @Override
      public ErrorCatching<Expiring<D>> getDynamics() {
        return cell.get().dynamics;
      }
    };
    if (MutableResourceFlags.DETECT_BUSY_CELLS) {
      result = profileEffects(result);
    }
    if (MutableResourceFlags.PROFILE_GET_DYNAMICS) {
      result = profile(result);
    }
    return result;
  }

  static <D extends Dynamics<?, D>> void set(MutableResource<D> resource, D newDynamics) {
    resource.emit(name(DynamicsMonad.effect(x -> newDynamics), "Set %s", newDynamics));
  }

  static <D extends Dynamics<?, D>> void set(MutableResource<D> resource, Expiring<D> newDynamics) {
    resource.emit(name(ErrorCatchingMonad.<Expiring<D>, Expiring<D>>map($ -> newDynamics)::apply, "Set %s", newDynamics));
  }

  /**
   * Turn on busy cell detection.
   *
   * <p>
   *     Calling this method once before constructing your model will profile effects on every resource.
   *     Profiling effects may be compute and/or memory intensive, and should not be used in production.
   * </p>
   * <p>
   *     If only a few resources are suspect, you can also call {@link Profiling#profileEffects}
   *     directly on just those resource, rather than profiling every resource.
   * </p>
   * <p>
   *     Call {@link Profiling#dump()} to see results.
   * </p>
   */
  static void detectBusyCells() {
    MutableResourceFlags.DETECT_BUSY_CELLS = true;
  }

  /**
   * Turn on profiling for all {@link MutableResource}s created by {@link MutableResource#resource}.
   * Also implies {@link MutableResource#detectBusyCells()}.
   *
   * <p>
   *     Calling this method once before constructing your model will profile virtually every {@link MutableResource}.
   *     Profiling may be compute and/or memory intensive, and should not be used in production.
   * </p>
   * <p>
   *     If only a few resources are suspect, you can also call {@link Profiling#profile}
   *     directly on just those resource, rather than profiling every resource.
   * </p>
   * <p>
   *     Call {@link Profiling#dump()} to see results.
   * </p>
   */
  static void profileAllResources() {
    MutableResourceFlags.PROFILE_GET_DYNAMICS = true;
    detectBusyCells();
  }
}

/**
 * Private global flags for configuring cell resources for debugging.
 * Flags here are meant to be set once before constructing the model,
 * and to apply to every cell that gets built.
 */
final class MutableResourceFlags {
  public static boolean DETECT_BUSY_CELLS = false;
  public static boolean PROFILE_GET_DYNAMICS = false;
}
