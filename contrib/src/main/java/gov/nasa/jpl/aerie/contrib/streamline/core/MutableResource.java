package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ErrorCatchingMonad;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Context;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling;
import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.Cell;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers.duration;
import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.allocate;
import static gov.nasa.jpl.aerie.contrib.streamline.core.CellRefV2.autoEffects;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.expiry;
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

  static <D extends Dynamics<?, D>> MutableResource<D> resource(InconBehavior<ErrorCatching<Expiring<D>>> inconBehavior) {
    // Use autoEffects for a generic CellResource, on the theory that most resources
    // have relatively few effects, and even fewer concurrent effects, so this is performant enough.
    // If that doesn't hold, a more specialized solution can be constructed directly.
    return resource(inconBehavior, autoEffects());
  }

  static <D extends Dynamics<?, D>> MutableResource<D> resource(
          InconBehavior<ErrorCatching<Expiring<D>>> inconBehavior,
          EffectTrait<DynamicsEffect<D>> effectTrait) {
    MutableResource<D> result = new MutableResource<>() {
      private final CellRef<DynamicsEffect<D>, Cell<D>> cell = allocate(inconBehavior, effectTrait);

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

  static <D> InconBehavior<ErrorCatching<Expiring<D>>> notSaving(D initialValue) {
    return notSaving(pure(initialValue));
  }

  static <D> InconBehavior<ErrorCatching<Expiring<D>>> notSaving(ErrorCatching<Expiring<D>> initialValue) {
    return InconBehavior.of($ -> initialValue, (s, f) -> {});
  }

  // TODO - It would be nice if the name we set here could somehow auto-populate the name of the resource,
  //  and also be the name we register the resource as. Same for the value mapper, it would be nice to just use that for registration too.
  // Alternatively, we could demand a name for every MutableResource, and combine that with the other info here later...?
  // On reflection, I think the discrete resource and linear resource constructors are the place to combine all this info.
  // Those would know which registrar method to call, and what value mapper to use.
  static <D> InconBehavior<ErrorCatching<Expiring<D>>> serializing(String key, D defaultValue, ValueMapper<D> mapper) {
    return serializing(key, pure(defaultValue), standardDynamicsMapper(mapper));
  }

  static <D> ValueMapper<ErrorCatching<Expiring<D>>> standardDynamicsMapper(ValueMapper<D> baseMapper) {
    return new ValueMapper<>() {
      @Override
      public ValueSchema getValueSchema() {
        // Note: Both errorMessage and expiry are nullable.
        return ValueSchema.ofStruct(Map.of(
                "error", ValueSchema.STRING,
                "expiry", ValueSchema.DURATION,
                "dynamics", baseMapper.getValueSchema()));
      }

      @Override
      public SerializedValue serializeValue(ErrorCatching<Expiring<D>> value) {
        return value.match(
                success -> SerializedValue.of(Map.of(
                        "expiry", success.expiry().value().map(duration()::serializeValue).orElse(SerializedValue.NULL),
                        "dynamics", baseMapper.serializeValue(success.data())
                )),
                error -> SerializedValue.of(Map.of(
                        "error", SerializedValue.of(error.getMessage())
                ))
        );
      }

      @Override
      public Result<ErrorCatching<Expiring<D>>, String> deserializeValue(SerializedValue serializedValue) {
        try {
          var map = serializedValue.asMap().orElseThrow();
          if (map.containsKey("error")) {
            return Result.success(ErrorCatching.failure(new Exception(map.get("error").asString().orElseThrow())));
          } else {
            var expiry = expiry(Optional.ofNullable(map.get("expiry"))
                    .map($ -> duration().deserializeValue($).getSuccessOrThrow()));
            var dynamics = baseMapper.deserializeValue(map.get("dynamics")).getSuccessOrThrow();
            return Result.success(ErrorCatching.success(Expiring.expiring(dynamics, expiry)));
          }
        } catch (Throwable e) {
          // TODO - we need *way* better error reporting here, but I just can't be bothered tonight.
          return Result.failure("Failed to deserialize value as a standard wrapped dynamics object.");
        }
      }
    };
  }

  static <D> InconBehavior<ErrorCatching<Expiring<D>>> serializing(String key, ErrorCatching<Expiring<D>> defaultValue, ValueMapper<ErrorCatching<Expiring<D>>> mapper) {
    return InconBehavior.of(
            incons -> incons.get(key).map($ -> mapper.deserializeValue($).getSuccessOrThrow()).orElse(defaultValue),
            (state, fincons) -> fincons.put(key, mapper.serializeValue(state)));
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
