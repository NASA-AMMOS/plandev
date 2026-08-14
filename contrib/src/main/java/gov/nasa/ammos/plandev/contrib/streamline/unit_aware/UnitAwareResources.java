package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.ErrorCatching;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.DynamicsEffect;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resources;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad;

import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.*;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Quantities.quantity;

public final class UnitAwareResources {
  private UnitAwareResources() {}

  public static <D> UnitAware<Resource<D>> unitAware(Resource<D> resource, Unit unit, BiFunction<D, Double, D> scaling) {
    return UnitAware.unitAware(resource, unit, extend(scaling, ResourceMonad::map));
  }

  public static <D extends Dynamics<?, D>> UnitAware<MutableResource<D>> unitAware(MutableResource<D> resource, Unit unit, BiFunction<D, Double, D> scaling) {
    final BiFunction<ErrorCatching<Expiring<D>>, Double, ErrorCatching<Expiring<D>>> extendedScaling = extend(scaling, DynamicsMonad::map);
    return UnitAware.unitAware(resource, unit, (cellResource, scale) -> new MutableResource<D>() {
      @Override
      public void emit(final DynamicsEffect<D> effect) {
        // Use an effect in the scaled domain by first scaling the dynamics,
        // then applying the effect, then de-scaling the result back
        DynamicsEffect<D> scaledEffect = unscaledDynamics ->
            extendedScaling.apply(effect.apply(extendedScaling.apply(unscaledDynamics, scale)), 1 / scale);
        name(effect, "%s", scaledEffect);
        cellResource.emit(scaledEffect);
      }

      @Override
      public ErrorCatching<Expiring<D>> getDynamics() {
        return extendedScaling.apply(cellResource.getDynamics(), scale);
      }
    });
  }

  public static <A, MA> BiFunction<MA, Double, MA> extend(BiFunction<A, Double, A> scaling, BiFunction<MA, Function<A, A>, MA> map) {
    return (ma, s) -> map.apply(ma, a -> scaling.apply(a, s));
  }

  public static <A> BiFunction<Resource<A>, Double, Resource<A>> extend(BiFunction<A, Double, A> scaling) {
    return extend(scaling, ResourceMonad::map);
  }

  public static <D extends Dynamics<Double, D>> UnitAware<Double> currentValue(UnitAware<? extends Resource<D>> resource, UnitAware<Double> valueIfError) {
    return quantity(Resources.currentValue(resource.value(), valueIfError.value(resource.unit())), resource.unit());
  }

  public static <D extends Dynamics<Double, D>> UnitAware<Double> currentValue(UnitAware<? extends Resource<D>> resource) {
    return quantity(Resources.currentValue(resource.value()), resource.unit());
  }

  public static <D extends Dynamics<?, D>> UnitAware<Resource<D>> cache(UnitAware<Resource<D>> resource) {
    return resource.map(Resources::cache);
  }
}
