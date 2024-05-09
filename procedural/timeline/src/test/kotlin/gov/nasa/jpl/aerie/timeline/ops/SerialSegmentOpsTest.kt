package gov.nasa.jpl.aerie.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.milliseconds
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.NullBinaryOperation
import gov.nasa.jpl.aerie.timeline.Interval.Companion.at
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.Interval.Companion.betweenClosedOpen
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.collections.profiles.Constants
import gov.nasa.jpl.aerie.timeline.util.duration.rangeTo
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class SerialSegmentOpsTest {

  // set, assignGaps, and map2Values are not tested here because they are trivial delegations to map2Serial.
  // see Map2SerialTest.kt

  @Test
  fun detectEdges() {
    val result = Constants(
        Segment(betweenClosedOpen(seconds(0), seconds(1)), "hello"),
        Segment(seconds(1) .. seconds(2), "oooo"),
        Segment(between(seconds(2), seconds(3), Exclusive), "aaaa"),
        Segment(seconds(5) .. seconds(6), "ao")
    ).detectEdges(NullBinaryOperation.cases(
        { l, _ -> l.endsWith('o') },
        { r, _ -> r.startsWith('o') },
        { l, r, _ -> l.endsWith(r.first()) }
    ))

    assertIterableEquals(
        listOf(
            Segment(betweenClosedOpen(seconds(0), seconds(1)), false),
            Segment(at(seconds(1)), true),
            Segment(between(seconds(1), seconds(3), Exclusive, Inclusive), false),
            Segment(betweenClosedOpen(seconds(5), seconds(6)), false),
            Segment(at(seconds(6)), true)
        ),
        result.collect()
    )

    // collecting on smaller bounds that start in the middle of "oooo" to make sure it does not get truncated
    // and count the bounds start as the segment start.
    assertIterableEquals(
        listOf(Segment(between(milliseconds(1500), seconds(3)), false)),
        result.collect(between(milliseconds(1500), seconds(4)))
    )
  }
}
