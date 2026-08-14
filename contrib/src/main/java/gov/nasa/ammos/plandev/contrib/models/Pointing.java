package gov.nasa.jpl.aerie.contrib.models;

import gov.nasa.jpl.aerie.merlin.framework.resources.real.RealResource;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;

/**
 * Model for a three-dimensional vector.
 * Each component contains a value and rate <code>Accumulator</code>, which exposes an underlying resource and
 * convenience methods.
 */
public final class Pointing {
  public final Component x, y, z;

  public Pointing(final Vector3D initialVec) {
    this(initialVec, new Vector3D(0, 0, 0));
  }

  public Pointing(final Vector3D initialVec, final Vector3D initialRate) {
    this.x = new Component(initialVec.getX(), initialRate.getX());
    this.y = new Component(initialVec.getY(), initialRate.getY());
    this.z = new Component(initialVec.getZ(), initialRate.getZ());
  }

  public Vector3D getVector() {
    return new Vector3D(this.x.get(), this.y.get(), this.z.get());
  }

  public Vector3D getRate() {
    return new Vector3D(this.x.rate.get(), this.y.rate.get(), this.z.rate.get());
  }

  public void addRate(final Vector3D delta) {
    this.x.rate.add(delta.getX());
    this.y.rate.add(delta.getY());
    this.z.rate.add(delta.getZ());
  }

  /** Slew to a target vector over a given duration. */
  public void slew(final Vector3D target, final Duration duration) {
    final var previousRate = getRate();
    addRate(previousRate.negate()); // Negate the previous rate to bring rate to <0, 0, 0>

    // rate = (v2 - v1) / duration
    final var desiredRate = target.subtract(getVector()).scalarMultiply(1/duration.ratioOver(Duration.SECOND));
    addRate(desiredRate);
    delay(duration);

    // Reset the rate post-slew
    addRate(desiredRate.negate()); // Negate the slew rate to bring rate back to <0, 0, 0>
    addRate(previousRate);         // Reset rate to previous rate
  }

  public static final class Component implements RealResource {
    private final Accumulator acc;
    public final Accumulator.Rate rate;

    public Component(final double value, final double rate) {
      this.acc = new Accumulator(value, rate);
      this.rate = this.acc.rate;
    }

    @Override
    public RealDynamics getDynamics() {
      return this.acc.getDynamics();
    }
  }
}
