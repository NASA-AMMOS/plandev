package gov.nasa.jpl.aerie.foomissionmodel.models;

import org.apache.commons.lang3.tuple.Pair;

public enum ImagerMode {
  OFF,
  LOW_RES,
  MED_RES,
  HI_RES;

  public Pair<Integer, Integer> getResolution() {
    switch (this) {
      case OFF:
        return Pair.of(0, 0);
      case LOW_RES:
        return Pair.of(640, 480);
      case MED_RES:
        return Pair.of(960, 720);
      case HI_RES:
        return Pair.of(1280, 960);
      default:
        throw new IllegalArgumentException("Unexpected imager resolution mode: " + this.name());
    }
  }

  public int getTotalPixels() {
    final var resolution = this.getResolution();
    return resolution.getLeft() * resolution.getRight();
  }
}
