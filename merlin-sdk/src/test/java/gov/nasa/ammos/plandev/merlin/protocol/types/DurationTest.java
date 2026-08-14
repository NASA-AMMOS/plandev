package gov.nasa.jpl.aerie.merlin.protocol.types;

import org.junit.jupiter.api.Test;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.EPSILON;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MILLISECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.duration;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.roundDownward;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.roundNearest;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.roundUpward;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class DurationTest {
  @Test
  public void testRoundNearest() {
    assertEquals(duration(2756, MILLISECONDS), roundNearest(2.756, SECONDS));
    assertEquals(duration(2756, MILLISECONDS), roundNearest(Math.nextUp(2.756), SECONDS));
    assertEquals(duration(2756, MILLISECONDS), roundNearest(Math.nextDown(2.756), SECONDS));
    assertEquals(duration(-2756, MILLISECONDS), roundNearest(-2.756, SECONDS));
    assertEquals(duration(-2756, MILLISECONDS), roundNearest(Math.nextUp(-2.756), SECONDS));
    assertEquals(duration(-2756, MILLISECONDS), roundNearest(Math.nextDown(-2.756), SECONDS));
  }

  @Test
  public void testRoundDown() {
    assertEquals(duration(2756, MILLISECONDS), roundDownward(Math.nextUp(2.756), SECONDS));
    assertEquals(duration(2756, MILLISECONDS).minus(EPSILON), roundDownward(Math.nextDown(2.756), SECONDS));
    assertEquals(duration(-2756, MILLISECONDS), roundDownward(Math.nextUp(-2.756), SECONDS));
    assertEquals(duration(-2756, MILLISECONDS).minus(EPSILON), roundDownward(Math.nextDown(-2.756), SECONDS));
  }

  @Test
  public void testRoundUp() {
    assertEquals(duration(2756, MILLISECONDS), roundUpward(Math.nextDown(2.756), SECONDS));
    assertEquals(duration(2756, MILLISECONDS).plus(EPSILON), roundUpward(Math.nextUp(2.756), SECONDS));
    assertEquals(duration(-2756, MILLISECONDS), roundUpward(Math.nextDown(-2.756), SECONDS));
    assertEquals(duration(-2756, MILLISECONDS).plus(EPSILON), roundUpward(Math.nextUp(-2.756), SECONDS));
  }

  @Test
  public void testSaturation() {
    assertEquals(Duration.MAX_VALUE.saturatingPlus(Duration.SECOND), Duration.MAX_VALUE);
    assertEquals(Duration.MAX_VALUE.minus(Duration.SECOND).saturatingPlus(Duration.of(2, SECONDS)), Duration.MAX_VALUE);
    assertEquals(Duration.MIN_VALUE.saturatingPlus(Duration.of(-1, SECONDS)), Duration.MIN_VALUE);
    assertEquals(Duration.MIN_VALUE.plus(Duration.SECOND).saturatingPlus(Duration.of(-2, SECONDS)), Duration.MIN_VALUE);
  }

  @Test
  public void parseDurationFromString(){
    // Test positive duration
    assertEquals(Duration.of(3, Duration.HOURS).plus(Duration.of(2, Duration.MINUTES).plus(Duration.of(3, Duration.SECONDS).plus(Duration.of(4, Duration.MICROSECONDS)))),
                 Duration.fromString("03:02:03.000004"));
    // Test negative duration
    assertEquals(Duration.of(-1, Duration.HOURS).plus(Duration.of(-2, Duration.MINUTES).plus(Duration.of(-3, Duration.SECONDS).plus(Duration.of(-4, Duration.MICROSECONDS)))),
                 Duration.fromString("-01:02:03.000004"));
    // Test duration with no subseconds
    assertEquals(Duration.of(1, Duration.HOURS).plus(Duration.of(2, Duration.MINUTES).plus(Duration.of(3, Duration.SECONDS))),
                 Duration.fromString("01:02:03"));
    // Test duration with trailing zeros in subseconds
    assertEquals(Duration.of(1, Duration.HOURS).plus(Duration.of(2, Duration.MINUTES).plus(Duration.of(3, Duration.SECONDS).plus(Duration.of(4, Duration.MICROSECONDS)))),
                 Duration.fromString("01:02:03.000004"));
    // Test duration with leading hours
    assertEquals(Duration.of(1234, Duration.HOURS).plus(Duration.of(12, Duration.MINUTES).plus(Duration.of(0, Duration.SECONDS).plus(Duration.of(123456, Duration.MICROSECONDS)))),
                 Duration.fromString("1234:12:00.123456"));
    // Test unbalanced duration
    assertEquals(Duration.of(1, Duration.HOURS).plus(Duration.of(0, Duration.MINUTES).plus(Duration.of(0, Duration.SECONDS))),
                 Duration.fromString("+00:60:00"));

    assertThrows(IllegalArgumentException.class, () -> Duration.fromString("+20:00"));
    // invalid input
    assertThrows(IllegalArgumentException.class,() -> Duration.fromString("3:2:03"));
    // Test positive duration
    assertThrows(IllegalArgumentException.class, () -> Duration.fromString("a1:023.1234567"));
    assertThrows(IllegalArgumentException.class, () -> Duration.fromString("-01:02:03.12345678901"));

  }
}
