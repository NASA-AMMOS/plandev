package gov.nasa.jpl.plandev.contrib.streamline.core;

import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

import static gov.nasa.jpl.plandev.contrib.streamline.core.Expiry.NEVER;

/**
 * A value which may be valid for a limited time.
 */
public record Expiring<D>(D data, Expiry expiry) {
  public static <D> Expiring<D> expiring(D data, Expiry expiry) {
    return new Expiring<>(data, expiry);
  }

  public static <D> Expiring<D> neverExpiring(D data) {
    return expiring(data, NEVER);
  }

  public static <D> Expiring<D> expiring(D data, Duration expiry) {
    return expiring(data, Expiry.at(expiry));
  }
}
