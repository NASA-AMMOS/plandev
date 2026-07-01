package gov.nasa.ammos.aerie.procedural.timeline.ops.numeric

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Numbers
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class PrimitiveNumberOpsTest {
  @Test
  fun negatePreservesType() {
    val justInts: List<Segment<Int>> = (-Numbers(
        Segment(at(seconds(0)), 5),
        Segment(at(seconds(1)), -2),
    )).collect()

    assertIterableEquals(
        listOf(
            Segment(at(seconds(0)), -5),
            Segment(at(seconds(1)), 2),
        ),
        justInts
    )

    val justDoubles: List<Segment<Double>> = Numbers(
        Segment(at(seconds(0)), 5.0),
        Segment(at(seconds(1)), -2.0),
    ).negate().collect()

    assertIterableEquals(
        listOf(
            Segment(at(seconds(0)), -5.0),
            Segment(at(seconds(1)), 2.0),
        ),
        justDoubles
    )

    val both: List<Segment<out Number>> = Numbers(
        Segment(at(seconds(0)), 5),
        Segment(at(seconds(1)), -2.0),
    ).negate().collect()

    assertIterableEquals(
        listOf(
            Segment(at(seconds(0)), -5),
            Segment(at(seconds(1)), 2.0),
        ),
        both
    )
  }
}
