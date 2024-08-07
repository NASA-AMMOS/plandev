package gov.nasa.ammos.aerie.procedural.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Constants
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Numbers
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeUntil
import org.junit.jupiter.api.Assertions.assertEquals
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

  @Test
  fun sample() {
    val profile = Constants(
      Segment(seconds(0) .. seconds(2), "a"),
      Segment(seconds(2) .. seconds(5), "b"),
      Segment(seconds(20) .. seconds(25), "c"),

      Segment(seconds(100) ..< seconds(101), "x"),
      Segment(at(seconds(101)), "y"),
      Segment(between(seconds(101), seconds(102), Exclusive), "x")
    )

    assertEquals("a", profile.sample(seconds(1)))
    assertEquals("b", profile.sample(seconds(4)))
    assertEquals(null, profile.sample(seconds(10)))
    assertEquals("c", profile.sample(seconds(21)))
    assertEquals("y", profile.sample(seconds(101)))
  }
}
