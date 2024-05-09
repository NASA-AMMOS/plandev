package gov.nasa.jpl.aerie.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.Interval.Companion.at
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.collections.profiles.Numbers
import gov.nasa.jpl.aerie.timeline.util.duration.rangeTo
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class SerialConstantTest {
  @Test
  fun transitions() {
    val result = Numbers(
        Segment(seconds(0) .. seconds(1), 0),
        Segment(seconds(2) .. seconds(3), 1),
        Segment(seconds(4) .. seconds(5), 0),
        Segment(seconds(5) .. seconds(6), 5),
        Segment(seconds(6) .. seconds(7), 1),
        Segment(seconds(7) .. seconds(8), 0),
        Segment(seconds(8) .. seconds(9), 1)
    ).transitions(0, 1).collect()

    assertIterableEquals(
        listOf(
            Segment(between(seconds(0), seconds(1), Inclusive, Exclusive), false),
            Segment(between(seconds(2), seconds(3), Exclusive, Inclusive), false),
            Segment(between(seconds(4), seconds(8), Inclusive, Exclusive), false),
            Segment(at(seconds(8)), true),
            Segment(between(seconds(8), seconds(9), Exclusive, Inclusive), false)
        ),
        result
    )
  }
}
