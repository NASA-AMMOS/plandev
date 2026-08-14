package gov.nasa.jpl.aerie.contrib.streamline.modeling.linear;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Objects;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

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
