package gov.nasa.jpl.aerie.merlin.protocol.types;

import java.util.Objects;
import java.util.Optional;

/**
 * A description of a time-dependent behavior for real-valued resources that may vary continuously.
 *
 * <p>
 *   This class currently only supports constant and linear dynamics, but we hope to add more in the future.
 * </p>
 */
public final class RealDynamics {
  public final double initial;
  public final double rate;

  private RealDynamics(final double initial, final double rate) {
    this.initial = initial;
    this.rate = rate;
  }

  public static RealDynamics constant(final double initial) {
    return new RealDynamics(initial, 0.0);
  }

  public static RealDynamics linear(final double initial, final double rate) {
    return new RealDynamics(initial, rate);
  }


  public RealDynamics scaledBy(final double scalar) {
    return linear(this.initial * scalar, this.rate * scalar);
  }

  public RealDynamics plus(final RealDynamics other) {
    return linear(this.initial + other.initial, this.rate + other.rate);
  }

  public RealDynamics minus(final RealDynamics other) {
    return this.plus(other.scaledBy(-1.0));
  }


  public Optional<Duration>
  whenBetween(final double min, final double max, final Duration atEarliest, final Duration atLatest) {
    if (this.rate == 0) {
      if (this.initial < min || max < this.initial) {
        return Optional.empty();
      } else {
        return Optional.of(atEarliest);
      }
    }

    final double valueAtEarliest = this.initial + this.rate * atEarliest.in(Duration.SECONDS);
    final double valueAtLatest = this.initial + this.rate * atLatest.in(Duration.SECONDS);
    final Duration entry;
    if (min <= valueAtEarliest && valueAtEarliest <= max) {
      entry = atEarliest;
    } else if (this.rate > 0) {
      if (valueAtLatest < min || max < valueAtEarliest) return Optional.empty();
      entry = Duration.roundUpward((min - this.initial) / this.rate, Duration.SECONDS);
    } else /* this.rate < 0 */ {
      if (valueAtEarliest < min || max < valueAtLatest) return Optional.empty();
      entry = Duration.roundUpward((max - this.initial) / this.rate, Duration.SECONDS);
    }

    return Optional.of(entry);
  }

  public Optional<Duration>
  whenNotBetween(final double min, final double max, final Duration atEarliest, final Duration atLatest) {
    if (this.rate == 0) {
      if (min <= this.initial && this.initial <= max) {
        return Optional.empty();
      } else {
        return Optional.of(atEarliest);
      }
    }

    final double valueAtEarliest = this.initial + this.rate * atEarliest.in(Duration.SECONDS);
    final double valueAtLatest = this.initial + this.rate * atLatest.in(Duration.SECONDS);
    final Duration entry;
    if (valueAtEarliest < min || max < valueAtEarliest) {
      entry = atEarliest;
    } else if (this.rate > 0) {
      if (valueAtLatest <= max) return Optional.empty();
      entry = Duration.roundUpward((max - this.initial) / this.rate, Duration.SECONDS);
    } else /* this.rate < 0 */ {
      if (valueAtLatest >= min) return Optional.empty();
      entry = Duration.roundUpward((min - this.initial) / this.rate, Duration.SECONDS);
    }

    return Optional.of(entry);
  }


  @Override
  public String toString() {
    return "λt. " + this.initial + " + t * " + this.rate;
  }

  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof RealDynamics)) return false;
    final var other = (RealDynamics) o;

    return (this.initial == other.initial) && (this.rate == other.rate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(initial, rate);
  }
}
