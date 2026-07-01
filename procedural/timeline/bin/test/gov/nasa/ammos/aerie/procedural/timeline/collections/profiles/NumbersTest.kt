package gov.nasa.ammos.aerie.procedural.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import org.junit.jupiter.api.Test
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeUntil
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo

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

  @Test
  fun increases() {
    val result = Numbers(
        Segment(seconds(0)..<seconds(1), 0),
        Segment(seconds(1)..<seconds(2), 2),
        Segment(seconds(2)..seconds(3), 1)
    ).increases()

    assertIterableEquals(
      listOf(
        Segment(between(seconds(0), seconds(1), Interval.Inclusivity.Exclusive), false),
        Segment(at(seconds(1)), true),
        Segment(between(seconds(1), seconds(3), Interval.Inclusivity.Exclusive), false)
      ),
      result.collect()
    )
  }
}
