package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

public class DiscreteProfileTest {

  @Test
  public void testEqualTo() {
    final var profile = new DiscreteProfile(
        Segment.of(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between( 5, Inclusive, 10, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(10, Inclusive, 15, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), SerializedValue.of(false))
    );

    final var other = new DiscreteProfile(
        Segment.of(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between( 5, Inclusive, 10, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(10, Inclusive, 15, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), SerializedValue.of(false))
    );

    final var result = profile.equalTo(other);

    final var expected = new Windows(Interval.between(0, 20, SECONDS), false)
        .set(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), true)
        .set(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testNotEqualTo() {
    final var profile = new DiscreteProfile(
        Segment.of(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between( 5, Inclusive, 10, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(10, Inclusive, 15, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), SerializedValue.of(false))
    );

    final var other = new DiscreteProfile(
        Segment.of(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between( 5, Inclusive, 10, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(10, Inclusive, 15, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), SerializedValue.of(false))
    );

    final var result = profile.notEqualTo(other);

    final var expected = new Windows(Interval.between(0, 20, SECONDS), false)
        .set(Interval.between( 5, Inclusive, 15, Exclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testChangePoints() {
    final var profile = new DiscreteProfile(
        Segment.of(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between( 5, Inclusive, 10, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(10, Inclusive, 15, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), SerializedValue.of(false))
    );

    final var result = profile.changePoints();

    final var expected = new Windows(Interval.between(0, Exclusive, 20, Inclusive, SECONDS), false)
        .set(Interval.at( 5, SECONDS), true)
    		.set(Interval.at(10, SECONDS), true)
    		.set(Interval.at(15, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testTransitions() {
    final var profile = new DiscreteProfile(
        Segment.of(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between( 5, Inclusive, 10, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(10, Inclusive, 15, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), SerializedValue.of(false))
    );

    final var result = profile.transitions(SerializedValue.of(true), SerializedValue.of(false));

    final var expected = new Windows(Interval.between(0, 20, SECONDS), false)
    		.set(Interval.at( 5, SECONDS), true)
    		.set(Interval.at(15, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testConvertFromExternalFormat() {
    final var externalProfile = List.of(
        new ProfileSegment<>(Duration.of(1, SECOND), Optional.of(SerializedValue.of(true))),
        new ProfileSegment<>(Duration.of(1, SECOND), Optional.<SerializedValue>empty()),
        new ProfileSegment<>(Duration.of(1, SECOND), Optional.of(SerializedValue.of(false)))
    );

    final var profile = DiscreteProfile.fromExternalProfile(Duration.of(1, SECOND), externalProfile);

    final var expected = new DiscreteProfile(
        Segment.of(Interval.between(1, Inclusive, 2, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(3, Inclusive, 4, Exclusive, SECONDS), SerializedValue.of(false))
    );

    assertIterableEquals(
        expected.profilePieces, profile.profilePieces
    );
  }
}
