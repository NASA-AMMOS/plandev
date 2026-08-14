package gov.nasa.jpl.aerie.contrib.cells.linear;

import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;

import java.util.function.Function;

public final class LinearIntegrationCell {
  // We split `initialVolume` from `accumulatedVolume` to avoid loss of floating-point precision.
  // The rate is usually smaller than the volume by some orders of magnitude,
  // so accumulated deltas will usually be closer to each other in magnitude than to the current volume.
  private double initialVolume;
  private double accumulatedVolume;
  private double rate;

  public LinearIntegrationCell(final double initialVolume, final double rate, final double accumulatedVolume) {
    this.initialVolume = initialVolume;
    this.accumulatedVolume = accumulatedVolume;
    this.rate = rate;
  }

  public LinearIntegrationCell(final double initialVolume, final double rate) {
    this(initialVolume, rate, 0.0);
  }

  public static <Event> CellRef<Event, LinearIntegrationCell>
  allocate(final double initialVolume, final double rate, final Function<Event, LinearAccumulationEffect> interpreter) {
    return CellRef.allocate(
        new LinearIntegrationCell(initialVolume, rate),
        new LinearIntegrationCellType(),
        interpreter);
  }

  public RealDynamics getVolume() {
    return RealDynamics.linear(this.initialVolume + this.accumulatedVolume, this.rate);
  }

  public RealDynamics getRate() {
    return RealDynamics.constant(this.rate);
  }

  public static final class LinearIntegrationCellType
      implements CellType<LinearAccumulationEffect, LinearIntegrationCell>
  {
    @Override
    public EffectTrait<LinearAccumulationEffect> getEffectType() {
      return LinearAccumulationEffect.TRAIT;
    }

    @Override
    public LinearIntegrationCell duplicate(final LinearIntegrationCell cell) {
      return new LinearIntegrationCell(cell.initialVolume, cell.rate, cell.accumulatedVolume);
    }

    @Override
    public void apply(final LinearIntegrationCell cell, final LinearAccumulationEffect effect) {
      cell.accumulatedVolume += effect.deltaVolume;
      cell.rate += effect.deltaRate;
      if (effect.clearVolume) {
        cell.initialVolume = 0;
        cell.accumulatedVolume = 0;
      }
    }

    @Override
    public void step(final LinearIntegrationCell cell, final Duration elapsedTime) {
      // Law: The passage of time shall not alter a valid dynamics.
      cell.accumulatedVolume += cell.rate * elapsedTime.ratioOver(Duration.SECOND);
    }
  }
}
