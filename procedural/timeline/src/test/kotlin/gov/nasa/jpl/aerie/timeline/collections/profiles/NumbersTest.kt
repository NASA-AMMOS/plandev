package gov.nasa.jpl.aerie.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.Interval
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

class NumbersTest {

  @Test
  fun plus() {
    val five = Numbers(Segment(between(Duration.ZERO, seconds(1)), 5)).assignGaps(Numbers(0))

    assertIterableEquals(
        listOf(
            Segment(between(Duration.ZERO, seconds(1)), 9),
            Segment(between(seconds(1), seconds(2), Interval.Inclusivity.Exclusive, Interval.Inclusivity.Inclusive), 4)
        ),
        (five + 4).collect(between(Duration.ZERO, seconds(2)))
    )
  }
}
