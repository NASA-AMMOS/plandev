package gov.nasa.jpl.aerie.contrib.cells.linear;

import gov.nasa.jpl.aerie.contrib.traits.CommutativeMonoid;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

/** Simple data class for storing an effect's rate delta and flag to clear volume. */
public final class LinearAccumulationEffect {
  public final double deltaVolume;
  public final double deltaRate;
  public final boolean clearVolume;

  public LinearAccumulationEffect(final double deltaRate, final boolean clearVolume) {
    this(0, deltaRate, clearVolume);
  }

  public LinearAccumulationEffect(final double deltaVolume, final double deltaRate, final boolean clearVolume) {
    this.deltaVolume = deltaVolume;
    this.deltaRate = deltaRate;
    this.clearVolume = clearVolume;
  }

  public static LinearAccumulationEffect of(final LinearAccumulationEvent event) {
    if (event instanceof LinearAccumulationEvent.AddVolume e) {
      return new LinearAccumulationEffect(e.deltaVolume(), 0, false);
    }
    if (event instanceof LinearAccumulationEvent.AddRate e) {
      return new LinearAccumulationEffect(0, e.deltaRate(), false);
    }
    throw new Error("Unhandled subtype of " + LinearAccumulationEvent.class + ": " + event);
  }

  public LinearAccumulationEffect plus(final LinearAccumulationEffect other) {
    return new LinearAccumulationEffect(
        this.deltaVolume + other.deltaVolume,
        this.deltaRate + other.deltaRate,
        this.clearVolume || other.clearVolume);
  }

  public static LinearAccumulationEffect addRate(final double deltaRate) {
    return new LinearAccumulationEffect(0, deltaRate, false);
  }

  public static LinearAccumulationEffect clearVolume() {
    return new LinearAccumulationEffect(0, 0, true);
  }

  public static LinearAccumulationEffect empty() {
    return new LinearAccumulationEffect(0,0,  false);
  }

  @Override
  public String toString() {
    if (this.deltaRate == 0) {
      return (this.clearVolume) ? "clear" : "noop";
    } else {
      return ((this.clearVolume) ? "clear," : "") + this.deltaRate;
    }
  }

  public static final EffectTrait<LinearAccumulationEffect> TRAIT =
      new CommutativeMonoid<>(LinearAccumulationEffect.empty(), LinearAccumulationEffect::plus);
}
