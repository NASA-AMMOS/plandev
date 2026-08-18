package gov.nasa.ammos.plandev.contrib.streamline.modeling.linear;

import gov.nasa.ammos.plandev.contrib.streamline.core.Dynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.protocol.types.Duration.SECOND;

// TODO: Implement better support for going to/from Linear
public record Linear(Double extract, Double rate) implements Dynamics<Double, Linear> {
  @Override
  public Linear step(Duration t) {
    return linear(extract() + t.ratioOver(SECOND) * rate(), rate());
  }

  public static Linear linear(double value, double rate) {
    return new Linear(value, rate);
  }
}
