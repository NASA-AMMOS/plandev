package gov.nasa.jpl.aerie.contrib.models;

import gov.nasa.jpl.aerie.contrib.cells.linear.LinearAccumulationEffect;
import gov.nasa.jpl.aerie.contrib.cells.linear.LinearAccumulationEvent;
import gov.nasa.jpl.aerie.contrib.cells.linear.LinearIntegrationCell;
import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.merlin.framework.resources.real.RealResource;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;

public final class Accumulator implements RealResource {
  private final CellRef<LinearAccumulationEvent, LinearIntegrationCell> ref;

  public final Rate rate = new Rate();

  public Accumulator() {
    this(0.0, 0.0);
  }

  public Accumulator(final double initialVolume, final double initialRate) {
    this.ref = LinearIntegrationCell.allocate(initialVolume, initialRate, LinearAccumulationEffect::of);
  }

  public void add(final double deltaVolume) {
    this.ref.emit(new LinearAccumulationEvent.AddVolume(deltaVolume));
  }

  public void subtract(final double deltaVolume) {
    this.ref.emit(new LinearAccumulationEvent.AddVolume(-deltaVolume));
  }

  @Override
  public RealDynamics getDynamics() {
    return this.ref.get().getVolume();
  }

  @Deprecated
  @Override
  public boolean equals(final Object obj) {
    return super.equals(obj);
  }

  @Override
  public String toString() {
    return "Accumulator{" +
           "ref=" + ref.get() +
           ", rate=" + rate.get() +
           '}';
  }

  public final class Rate implements RealResource {
    @Override
    public RealDynamics getDynamics() {
      return Accumulator.this.ref.get().getRate();
    }

    public void add(final double delta) {
      Accumulator.this.ref.emit(new LinearAccumulationEvent.AddRate(delta));
    }

    @Deprecated
    @Override
    public boolean equals(final Object obj) {
      return super.equals(obj);
    }
  }
}
