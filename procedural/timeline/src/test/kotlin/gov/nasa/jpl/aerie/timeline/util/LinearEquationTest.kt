package gov.nasa.jpl.aerie.timeline.util

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.Interval.Companion.betweenClosedOpen
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.collections.profiles.Real
import gov.nasa.jpl.aerie.timeline.collections.profiles.Booleans
import gov.nasa.jpl.aerie.timeline.payloads.LinearEquation
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

class LinearEquationTest {

  @Test
  fun shiftInitialTime() {
    val original = LinearEquation(Duration.ZERO, 1.0, 1.0)

    val shifted = original.shiftInitialTime(Duration.SECOND)

    assertEquals(
        LinearEquation(Duration.SECOND, 2.0, 1.0),
        shifted
    )
  }

  @Test
  fun intervalsLessThan() {
    assertIterableEquals(
        Booleans(true).collect(),
        LinearEquation(1.0).intervalsLessThan(LinearEquation(2.0)).collect()
    )

    assertIterableEquals(
        Booleans(false).collect(),
        LinearEquation(2.0).intervalsLessThan(LinearEquation(1.0)).collect()
    )

    assertIterableEquals(
        Booleans(Segment(between(Duration.ZERO, Duration.MAX_VALUE), false)).assignGaps(Booleans(true)).collect(),
        LinearEquation(Duration.ZERO, 0.0, 1.0).intervalsLessThan(LinearEquation(0.0)).collect()
    )
  }

  @Test
  fun findRoot() {
    assertNull(LinearEquation(1.0).findRoot())

    assertEquals(
        seconds(2),
        LinearEquation(Duration.ZERO, 2.0, -1.0).findRoot()
    )
  }

  @Test
  fun abs() {
    assertIterableEquals(
        Real(5.0).collect(),
        LinearEquation(5.0).abs().collect()
    )

    assertIterableEquals(
        Real(5.0).collect(),
        LinearEquation(-5.0).abs().collect()
    )

    assertIterableEquals(
        Real(
            Segment(betweenClosedOpen(Duration.MIN_VALUE, Duration.SECOND), LinearEquation(Duration.SECOND, 0.0, -2.0)),
            Segment(between(Duration.SECOND, Duration.MAX_VALUE), LinearEquation(Duration.SECOND, 0.0, 2.0))
        ).collect(),
        LinearEquation(Duration.ZERO, 2.0, -2.0).abs().collect()
    )
  }

  @Test
  fun equals() {
    assertTrue(
        LinearEquation(5.0) == LinearEquation(Duration.SECOND, 5.0, 0.0)
    )

    assertTrue(
        LinearEquation(Duration.ZERO, 0.0, 2.0) == LinearEquation(Duration.SECOND, 2.0, 2.0)
    )

    assertFalse(
        LinearEquation(Duration.ZERO, 0.5, 2.0) == LinearEquation(Duration.SECOND, 2.0, 2.0)
    )
  }
}
