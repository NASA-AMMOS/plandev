package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * The time at which a value expires.
 */
public record Expiry(Optional<Duration> value) implements Comparable<Expiry> {
  public static Expiry NEVER = expiry(Optional.empty());

  public static Expiry at(Duration t) {
    return expiry(Optional.of(t));
  }

  public static Expiry expiry(Optional<Duration> value) {
    return new Expiry(value);
  }

  public Expiry or(Expiry other) {
    // If this has a value...
    //   If other has a value, compare and return the minimum
    //   Else other is NEVER, so return this
    // Else (this is NEVER), so return other
    return this.value.map(thisValue ->
            other.value.map(otherValue -> Expiry.at(Duration.min(thisValue, otherValue)))
                    .orElse(this))
            .orElse(other);
  }

  public Expiry minus(Duration t) {
    return expiry(value().map(v -> v.minus(t)));
  }

  public boolean isNever() {
    return value().isEmpty();
  }

  public int compareTo(Expiry other) {
    if (this.isNever()) {
      if (other.isNever()) {
        return 0;
      } else {
        return 1;
      }
    } else {
      if (other.isNever()) {
        return -1;
      } else {
        return this.value().get().compareTo(other.value().get());
      }
    }
  }

  public boolean shorterThan(Expiry other) {
    return this.compareTo(other) < 0;
  }

  public boolean noShorterThan(Expiry other) {
    return this.compareTo(other) >= 0;
  }

  public boolean longerThan(Expiry other) {
    return this.compareTo(other) > 0;
  }

  public boolean noLongerThan(Expiry other) {
    return this.compareTo(other) <= 0;
  }

  @Override
  public String toString() {
    return value.map(Duration::toString).orElse("NEVER");
  }
}
