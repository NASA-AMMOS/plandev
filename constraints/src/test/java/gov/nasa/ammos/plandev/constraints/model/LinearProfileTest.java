package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static gov.nasa.jpl.aerie.constraints.Assertions.assertEquivalent;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.interval;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

public class LinearProfileTest {

  @Test
  public void testBasicAddition() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between(0, Inclusive,10, Exclusive, SECONDS), new LinearEquation(Duration.of(0, SECONDS), 0, 1)),
        Segment.of(Interval.between(10, Inclusive,20, Inclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 10, -2))
    );

    final var other = new LinearProfile(
      Segment.of(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), new LinearEquation(Duration.of(0, SECONDS), 1, 2)),
      Segment.of(Interval.between(5, Inclusive, 10, Inclusive, SECONDS), new LinearEquation(Duration.of(5, SECONDS), 11, -1)),
      Segment.of(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 6, 3)),
      Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(15, SECONDS), 21, -2))
    );

    final var result = profile.plus(other);

    final var expected = new LinearProfile(
        Segment.of(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), new LinearEquation(Duration.of(0, SECONDS), 1, 3)),
        Segment.of(Interval.between(5, Inclusive, 10, Exclusive, SECONDS), new LinearEquation(Duration.of(5, SECONDS), 16, 0)),
        Segment.of(Interval.between(10, Inclusive, 10, Inclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 16, -3)),
        Segment.of(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 16, 1)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(15, SECONDS), 21, -4))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testAdditionWithDifferingBounds() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 5, Exclusive,10, Inclusive, SECONDS), new LinearEquation(Duration.of( 5, SECONDS), 2, 2)),
        Segment.of(Interval.between(10, Exclusive,20, Inclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 12, -1))
    );

    final var other = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  8, Inclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS), 2, 3)),
        Segment.of(Interval.between( 8, Exclusive, 15, Inclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS), 26, -3))
    );

    final var result = profile.plus(other);

    final var expected = new LinearProfile(
        Segment.of(Interval.between( 5, Exclusive,  8, Inclusive, SECONDS), new LinearEquation(Duration.of( 5, SECONDS), 19, 5)),
        Segment.of(Interval.between( 8, Exclusive, 10, Inclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS), 34, -1)),
        Segment.of(Interval.between(10, Exclusive, 15, Inclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 32, -4))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testMultiplication() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive, 10, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS), 2, 3)),
        Segment.of(Interval.between(10, Inclusive, 20, Exclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 3, -5)),
        Segment.of(Interval.between(20, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(20, SECONDS), -2, 0))
    );

    final var result = (LinearProfile)(profile.times(2));

    final var expected = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive, 10, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS), 4, 6)),
        Segment.of(Interval.between(10, Inclusive, 20, Exclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 6, -10)),
        Segment.of(Interval.between(20, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(20, SECONDS), -4, 0))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testRate() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive, 10, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS), 2, 3)),
        Segment.of(Interval.between(10, Inclusive, 20, Exclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 3, -5)),
        Segment.of(Interval.between(20, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(20, SECONDS), -2, 0))
    );

    final var result = (LinearProfile)(profile.rate());

    final var expected = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive, 10, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS), 3, 0)),
        Segment.of(Interval.between(10, Inclusive, 20, Exclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), -5, 0)),
        Segment.of(Interval.between(20, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(20, SECONDS), 0, 0))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testChangePoints() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS), 2, 0)),
        Segment.of(Interval.between( 5, Inclusive,  8, Exclusive, SECONDS), new LinearEquation(Duration.of( 5, SECONDS), 2, 0)),
        Segment.of(Interval.between( 8, Inclusive, 10, Inclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS), 3, 0)),
        Segment.of(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), new LinearEquation(Duration.of(10, SECONDS), 3, 1)),
        Segment.of(Interval.between(15, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(15, SECONDS), -2, 0))
    );

    final var result = profile.changePoints();

    final var expected = new Windows(interval(0, Exclusive, 20, Inclusive, SECONDS), false)
    		.set(Interval.at(8, SECONDS), true)
    		.set(Interval.between(10, Exclusive, 15, Inclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testLessThan() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 4, Inclusive,  8, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  4,  0)),
        Segment.of(Interval.between( 8, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS),  4, -1)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  0,  1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var other = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  2, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 2, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 2, SECONDS),  2,  0)),
        Segment.of(Interval.between( 4, Inclusive,  6, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  2,  1)),
        Segment.of(Interval.between( 6, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 6, SECONDS),  4,  0)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  4, -1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  -20,  1e-20))
    );

    final var result = profile.lessThan(other);

    final var expected = new Windows(interval(0, 20, SECONDS), false)
    		.set(Interval.between(8, Exclusive, 14, Exclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testLessThanOrEqualTo() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 4, Inclusive,  8, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  4,  0)),
        Segment.of(Interval.between( 8, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS),  4, -1)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  0,  1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var other = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  2, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 2, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 2, SECONDS),  2,  0)),
        Segment.of(Interval.between( 4, Inclusive,  6, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  2,  1)),
        Segment.of(Interval.between( 6, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 6, SECONDS),  4,  0)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  4, -1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var result = profile.lessThanOrEqualTo(other);

    final var expected = new Windows(interval(0, 20, SECONDS), false)
    		.set(Interval.between( 0, Inclusive,  2, Inclusive, SECONDS), true)
    		.set(Interval.between( 6, Inclusive, 14, Inclusive, SECONDS), true)
    		.set(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testGreaterThan() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 4, Inclusive,  8, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  4,  0)),
        Segment.of(Interval.between( 8, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS),  4, -1)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  0,  1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var other = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  2, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 2, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 2, SECONDS),  2,  0)),
        Segment.of(Interval.between( 4, Inclusive,  6, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  2,  1)),
        Segment.of(Interval.between( 6, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 6, SECONDS),  4,  0)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  4, -1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var result = profile.greaterThan(other);

    final var expected = new Windows(interval(0, 20, SECONDS), false)
    		.set(Interval.between( 2, Exclusive,  6, Exclusive, SECONDS), true)
    		.set(Interval.between(14, Exclusive, 16, Exclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testGreaterThanOrEqualTo() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 4, Inclusive,  8, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  4,  0)),
        Segment.of(Interval.between( 8, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS),  4, -1)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  0,  1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var other = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  2, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 2, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 2, SECONDS),  2,  0)),
        Segment.of(Interval.between( 4, Inclusive,  6, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  2,  1)),
        Segment.of(Interval.between( 6, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 6, SECONDS),  4,  0)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  4, -1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var result = profile.greaterThanOrEqualTo(other);

    final var expected = new Windows(interval(0, 20, SECONDS), false)
    		.set(Interval.between( 0, Inclusive,  8, Inclusive, SECONDS), true)
    		.set(Interval.between(14, Inclusive, 20, Inclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testEqualTo() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 4, Inclusive,  8, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  4,  0)),
        Segment.of(Interval.between( 8, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS),  4, -1)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  0,  1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var other = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  2, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 2, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 2, SECONDS),  2,  0)),
        Segment.of(Interval.between( 4, Inclusive,  6, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  2,  1)),
        Segment.of(Interval.between( 6, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 6, SECONDS),  4,  0)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  4, -1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var result = profile.equalTo(other);

    final var expected = new Windows(interval(0, 20, SECONDS), false)
    		.set(Interval.between( 0, Inclusive,  2, Inclusive, SECONDS), true)
    		.set(Interval.between( 6, Inclusive,  8, Inclusive, SECONDS), true)
    		.set(Interval.at(14, SECONDS), true)
    		.set(Interval.between( 16, Inclusive,  20, Inclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testNotEqualTo() {
    final var profile = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 4, Inclusive,  8, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  4,  0)),
        Segment.of(Interval.between( 8, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 8, SECONDS),  4, -1)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  0,  1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var other = new LinearProfile(
        Segment.of(Interval.between( 0, Inclusive,  2, Exclusive, SECONDS), new LinearEquation(Duration.of( 0, SECONDS),  0,  1)),
        Segment.of(Interval.between( 2, Inclusive,  4, Exclusive, SECONDS), new LinearEquation(Duration.of( 2, SECONDS),  2,  0)),
        Segment.of(Interval.between( 4, Inclusive,  6, Exclusive, SECONDS), new LinearEquation(Duration.of( 4, SECONDS),  2,  1)),
        Segment.of(Interval.between( 6, Inclusive, 12, Exclusive, SECONDS), new LinearEquation(Duration.of( 6, SECONDS),  4,  0)),
        Segment.of(Interval.between(12, Inclusive, 16, Exclusive, SECONDS), new LinearEquation(Duration.of(12, SECONDS),  4, -1)),
        Segment.of(Interval.between(16, Inclusive, 20, Inclusive, SECONDS), new LinearEquation(Duration.of(16, SECONDS),  0,  0))
    );

    final var result = profile.notEqualTo(other);

    final var expected = new Windows(interval(0, 20, SECONDS), false)
        .set(Interval.between(2, Exclusive, 6, Exclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 14, Exclusive, SECONDS), true)
        .set(Interval.between(14, Exclusive, 16, Exclusive, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testSegmentPointOutsideInterval() {
    final var left = new LinearProfile(
        Segment.of(Interval.between(0, 2, SECONDS), new LinearEquation(Duration.of(4, SECONDS), 2, 1))
    );

    assertIterableEquals(
        new LinearProfile(Segment.of(Interval.between(0, 2, SECONDS), new LinearEquation(Duration.ZERO, -2, 1))),
        left
    );

    final var right = new LinearProfile(
        Segment.of(Interval.between(0, 2, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 2, 1))
    );

    final var sum = left.plus(right);
    assertIterableEquals(
        new LinearProfile(Segment.of(Interval.between(0, 2, SECONDS), new LinearEquation(Duration.of(4, SECONDS), 6, 2))),
        sum
    );
  }

  @Test
  public void testConvertFromExternalFormat() {
    final var externalProfile = List.of(
        new ProfileSegment<>(Duration.of(1, SECOND), Optional.of(RealDynamics.linear(1, 1))),
        new ProfileSegment<>(Duration.of(1, SECOND), Optional.<RealDynamics>empty()),
        new ProfileSegment<>(Duration.of(1, SECOND), Optional.of(RealDynamics.linear(5, -1)))
    );

    final var profile = LinearProfile.fromExternalProfile(Duration.of(1, SECOND), externalProfile);

    final var expected = new LinearProfile(
        Segment.of(Interval.between(1, Inclusive, 2, Exclusive, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 1, 1)),
        Segment.of(Interval.between(3, Inclusive, 4, Exclusive, SECONDS), new LinearEquation(Duration.of(3, SECONDS), 5, -1))
    );

    assertIterableEquals(
        expected.profilePieces, profile.profilePieces
    );
  }
}
