package gov.nasa.jpl.aerie.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.CollectOptions
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.collections.profiles.Numbers
import gov.nasa.jpl.aerie.timeline.util.duration.rangeTo
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class NonZeroDurationOpsTest {
  @Test
  fun split() {
    val result = Numbers(
        Segment(seconds(0) .. seconds(1), 1),
        Segment(seconds(2) .. seconds(4), 2),
        Segment(between(seconds(4), seconds(10), Exclusive), 3)
    ).split { it.value }.collect()

    assertIterableEquals(
        listOf(
            Segment(seconds(0) .. seconds(1), 1),
            Segment(between(seconds(2), seconds(3), Inclusive, Exclusive), 2),
            Segment(between(seconds(3), seconds(4), Exclusive, Inclusive), 2),
            Segment(between(seconds(4), seconds(6), Exclusive), 3),
            Segment(between(seconds(6), seconds(8), Exclusive), 3),
            Segment(between(seconds(8), seconds(10), Exclusive), 3),
        ),
        result
    )
  }

  @Test
  fun splitMarginal() {
    val profile = Numbers(
        Segment(between(seconds(-5), seconds(3)), 2),
        Segment(seconds(7) .. seconds(13), 3),
    ).split { it.value }

    assertIterableEquals(
        listOf(
            Segment(seconds(0) .. seconds(3), 2),
            Segment(between(seconds(7), seconds(9), Inclusive, Exclusive), 3),
            Segment(between(seconds(9), seconds(10), Exclusive, Inclusive), 3),
        ),
        profile.collect(seconds(0) .. seconds(10))
    )

    assertIterableEquals(
        listOf(
            Segment(between(seconds(-1), seconds(3), Exclusive, Inclusive), 2),
            Segment(between(seconds(7), seconds(9), Inclusive, Exclusive), 3),
            Segment(between(seconds(9), seconds(11), Exclusive), 3),
        ),
        profile.collect(CollectOptions(seconds(0) .. seconds(10), false))
    )
  }
}
