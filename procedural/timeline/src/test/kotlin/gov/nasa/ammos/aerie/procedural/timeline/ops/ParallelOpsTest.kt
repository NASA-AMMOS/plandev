package gov.nasa.ammos.aerie.procedural.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.NullBinaryOperation
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.betweenClosedOpen
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.collections.Intervals
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Numbers
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class ParallelOpsTest {
  @Test
  fun flattenIntoProfile() {
    val result = Intervals(
        Segment(seconds(1) .. seconds(3), 1),
        Segment(seconds(0) .. seconds(2), 2),
        Segment(seconds(4) .. seconds(6), 5)
    ).flattenIntoProfile(::Numbers) { it.value + 1 }.collect()

    assertIterableEquals(
        listOf(
            Segment(betweenClosedOpen(seconds(0), seconds(1)), 3),
            Segment(seconds(1) .. seconds(3), 2),
            Segment(seconds(4) .. seconds(6), 6)
        ),
        result
    )
  }

  @Test
  fun reduceIntoProfile() {
    val result = Intervals(
        Segment(seconds(1) .. seconds(4), 1),
        Segment(seconds(0) .. seconds(3), 2),
        Segment(seconds(4) .. seconds(6), 5),
        Segment(seconds(2) .. seconds(5), 3)
    ).reduceIntoProfile(::Numbers, NullBinaryOperation.reduce(
        { seg, _ -> seg.value },
        { seg, acc, _ -> seg.value + acc }
    )).collect()

    assertIterableEquals(
        listOf(
            Segment(betweenClosedOpen(seconds(0), seconds(1)), 2),
            Segment(betweenClosedOpen(seconds(1), seconds(2)), 3),
            Segment(seconds(2) .. seconds(3), 6),
            Segment(between(seconds(3), seconds(4), Exclusive), 4),
            Segment(at(seconds(4)), 9),
            Segment(between(seconds(4), seconds(5), Exclusive, Inclusive), 8),
            Segment(between(seconds(5), seconds(6), Exclusive, Inclusive), 5)
        ),
        result
    )
  }
}
