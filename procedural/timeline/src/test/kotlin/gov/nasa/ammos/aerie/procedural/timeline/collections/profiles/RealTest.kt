package gov.nasa.ammos.aerie.procedural.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.payloads.LinearEquation
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import org.junit.jupiter.api.Assertions.assertIterableEquals
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RealTest {
  @Test
  fun plusShiftsInitialTime() {
    val result = (Real(
        Segment(seconds(0)..seconds(2), LinearEquation(seconds(0), 1.0, 1.0))
    ) + Real(
        Segment(seconds(1)..seconds(3), LinearEquation(seconds(-2), -1.0, 3.0))
    )).collect()
    assertIterableEquals(
        listOf(Segment(seconds(1)..seconds(2), LinearEquation(seconds(1), 10.0, 4.0))),
        result
    )
  }

  @Test
  fun increases() {
    val result = Real(
        Segment(seconds(0)..<seconds(1), LinearEquation(0)),
        Segment(seconds(1)..<seconds(2), LinearEquation(seconds(1), 2.0, -1.0)),
        Segment(seconds(2)..seconds(3), LinearEquation(seconds(2), 1.0, 1.0))
    ).increases()

    assertIterableEquals(
      listOf(
        Segment(between(seconds(0), seconds(1), Interval.Inclusivity.Exclusive), false),
        Segment(at(seconds(1)), true),
        Segment(between(seconds(1), seconds(2), Interval.Inclusivity.Exclusive, Interval.Inclusivity.Inclusive), false),
        Segment(between(seconds(2), seconds(3), Interval.Inclusivity.Exclusive), true)
      ),
      result.collect()
    )
  }

  @Test
  fun sample() {
    val profile = Real(
        Segment(seconds(0)..seconds(2), LinearEquation(0)),
        Segment(seconds(2)..seconds(5), LinearEquation(seconds(0), 1.0, 1.0)),
        Segment(seconds(20)..seconds(25), LinearEquation(seconds(20), 5.0, -2.0)),
    )

    assertEquals(0.0, profile.sample(seconds(1)))
    assertEquals(5.0, profile.sample(seconds(4)))
    assertEquals(null, profile.sample(seconds(10)))
    assertEquals(3.0, profile.sample(seconds(21)))
  }
}
