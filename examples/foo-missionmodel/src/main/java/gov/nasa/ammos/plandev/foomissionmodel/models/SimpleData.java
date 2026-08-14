package gov.nasa.jpl.aerie.foomissionmodel.models;

import gov.nasa.jpl.aerie.contrib.cells.linear.LinearAccumulationEffect;
import gov.nasa.jpl.aerie.contrib.cells.linear.LinearIntegrationCell;
import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.merlin.framework.resources.real.RealResource;

import java.util.List;
import java.util.function.Function;

/** Simple data model inspired by Clipper's example data system model diagrams. */
public final class SimpleData {
  public final InstrumentData a, b;
  public final RealResource totalVolume;

  public SimpleData() {
    this.a = new InstrumentData(10.0);
    this.b = new InstrumentData(5.0);
    this.totalVolume = RealResource.add(this.a.volume, this.b.volume);
  }

  public void downlinkData() {
    List.of(this.a, this.b)
        .forEach(InstrumentData::clearVolume);
  }

  public static final class InstrumentData {
    private final CellRef<LinearAccumulationEffect, LinearIntegrationCell> ref;
    private final double activeRate;

    public final RealResource volume, rate;

    private InstrumentData(final double activeRate) {
      this.ref = LinearIntegrationCell.allocate(0, 0, Function.identity());
      this.activeRate = activeRate;
      this.volume = () -> this.ref.get().getVolume();
      this.rate = () -> this.ref.get().getRate();
    }

    private void setRate(final double newRate) {
      final var currRate = this.ref.get().getRate().rate;
      this.ref.emit(LinearAccumulationEffect.addRate(newRate - currRate));
    }

    public void clearVolume() {
      this.ref.emit(LinearAccumulationEffect.clearVolume());
    }

    public void activate() {
      this.setRate(this.activeRate);
    }

    public void deactivate() {
      this.setRate(0.0);
    }

    public void setMode(final boolean isActive) {
      if (isActive) {
        this.activate();
      } else {
        this.deactivate();
      }
    }
  }
}
