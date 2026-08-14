package gov.nasa.jpl.aerie.foomissionmodel.models;

import gov.nasa.jpl.aerie.contrib.models.Accumulator;
import gov.nasa.jpl.aerie.contrib.models.Register;
import gov.nasa.jpl.aerie.merlin.framework.resources.real.RealResource;
import org.apache.commons.lang3.tuple.Pair;

public final class Imager {
  private final double bitsPerPixel;

  private final Register<ImagerMode> mode;
  private final Register<Double> frameRate;
  private final Accumulator imagedBits;

  public final RealResource volume;
  public final RealResource rate;

  public Imager(final double bitsPerPixel, final ImagerMode mode, final double frameRate) {
    this.bitsPerPixel = bitsPerPixel;

    this.mode = Register.forImmutable(mode);
    this.frameRate = Register.forImmutable(frameRate);
    this.imagedBits = new Accumulator();

    this.volume = this.imagedBits;
    this.rate = this.imagedBits.rate;
  }

  public void beginImaging(final ImagerMode mode, final double frameRate) {
    final var newRate = mode.getTotalPixels() * this.bitsPerPixel * frameRate;

    this.mode.set(mode);
    this.frameRate.set(frameRate);
    this.imagedBits.rate.add(newRate - this.imagedBits.rate.get());
  }

  public void endImaging() {
    this.mode.set(ImagerMode.OFF);
    this.frameRate.set(0.0);
    this.imagedBits.rate.add(-this.imagedBits.rate.get());
  }

  public ImagerMode getMode() {
    return this.mode.get();
  }

  public Pair<Integer, Integer> getResolution() {
    return this.mode.get().getResolution();
  }

  public boolean isImaging() {
    return this.mode.get() != ImagerMode.OFF;
  }

  public boolean isConflicted() {
    return this.mode.isConflicted();
  }
}
