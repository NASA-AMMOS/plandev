package gov.nasa.jpl.aerie.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.CollectOptions
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.util.duration.rangeTo
import gov.nasa.jpl.aerie.timeline.util.duration.rangeUntil
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class BooleansTest {
  @Test
  fun shiftEdgesBasic() {
    val result = Booleans(
        Segment(seconds(0) .. seconds(4), false),
        Segment(seconds(4) .. seconds(8), true),
        Segment(seconds(8) .. seconds(12), false)
    ).shiftEdges(seconds(1), seconds(-1)).collect()

    assertIterableEquals(
        listOf(
            Segment(seconds(-1) ..< seconds(5), false),
            Segment(seconds(5) ..< seconds(7), true),
            Segment(seconds(7) .. seconds(13), false)
        ),
        result
    )
  }

  @Test
  fun shiftEdgesBoundsShift() {
    val shiftRightResult = Booleans(
        Segment(between(seconds(-2), seconds(0)), true),
    ).shiftEdges(seconds(0), seconds(2)).collect(seconds(1) .. seconds(3))

    assertIterableEquals(
        listOf(Segment(seconds(1) .. seconds(2), true)),
        shiftRightResult
    )

    val shiftLeftResult = Booleans(
        Segment(seconds(2) .. seconds(4), true),
    ).shiftEdges(seconds(-2), seconds(0)).collect(between(seconds(-2), seconds(1)))

    assertIterableEquals(
        listOf(Segment(seconds(0) .. seconds(1), true)),
        shiftLeftResult
    )
  }

  @Test
  fun shiftEdgesBoundsShiftNoTruncate() {
    val shiftRightResult = Booleans(
        Segment(between(seconds(-2), seconds(0)), true),
    ).shiftEdges(seconds(0), seconds(2)).collect(CollectOptions(seconds(1) .. seconds(3), false))

    assertIterableEquals(
        listOf(Segment(between(seconds(-2), seconds(2)), true)),
        shiftRightResult
    )

    val shiftLeftResult = Booleans(
        Segment(seconds(2) .. seconds(4), true),
    ).shiftEdges(seconds(-2), seconds(0)).collect(CollectOptions(between(seconds(-2), seconds(1)), false))

    assertIterableEquals(
        listOf(Segment(seconds(0) .. seconds(4), true)),
        shiftLeftResult
    )
  }
}
