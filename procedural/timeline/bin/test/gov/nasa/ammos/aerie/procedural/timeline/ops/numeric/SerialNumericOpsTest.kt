package gov.nasa.ammos.aerie.procedural.timeline.ops.numeric

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Numbers
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real
import gov.nasa.ammos.aerie.procedural.timeline.payloads.LinearEquation
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeUntil
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SerialNumericOpsTest {
  @Test
  fun toSerialLinear() {
    val real = Real(
        Segment(seconds(0)..seconds(1), LinearEquation(4.0)),
        Segment(seconds(3)..seconds(4), LinearEquation(5.0)),
    )
    val numbers = Numbers(
        Segment(seconds(0)..seconds(1), 4.0),
        Segment(seconds(3)..seconds(4), 5L),
    )

    assertIterableEquals(
        real.collect(),
        real.toReal().collect()
    )
    assertIterableEquals(
        real.collect(),
        numbers.toReal().collect()
    )
  }

  @Test
  fun toSerialPrimitiveNumbers() {
    val real = Real(
        Segment(seconds(0)..seconds(1), LinearEquation(4.0)),
        Segment(seconds(3)..seconds(4), LinearEquation(5.0)),
    )
    val numbers = Numbers(
        Segment(seconds(0)..seconds(1), 4.0),
        Segment(seconds(3)..seconds(4), 5L),
    )

    assertIterableEquals(
        numbers.collect(),
        numbers.toNumbers().collect()
    )
    assertIterableEquals(
        numbers.toDoubles().collect(),
        real.toNumbers().collect()
    )

    assertThrows<Real.RealOpException> {
      Real(
          Segment(seconds(0)..seconds(1), LinearEquation(seconds(0), 1.0, 1.0))
      ).toNumbers().collect()
    }
  }

  @Test
  fun integrate() {
    val numbers = Numbers(
        Segment(seconds(0)..<seconds(1), 1),
        Segment(seconds(1)..seconds(5), -2.0)
    ).assignGaps(0)

    assertIterableEquals(
        listOf(
            Segment(seconds(0)..<seconds(1), LinearEquation(seconds(0), 0.0, 1.0)),
            Segment(seconds(1)..seconds(5), LinearEquation(seconds(1), 1.0, -2.0)),
            Segment(between(seconds(5), seconds(10), Interval.Inclusivity.Exclusive, Interval.Inclusivity.Inclusive), LinearEquation(-7.0))
        ),
        numbers.integrate().collect(seconds(0)..seconds(10))
    )
  }

  @Test
  fun shiftedDifference() {
    val numbers = Numbers(
        Segment(seconds(0)..<seconds(2), 2),
        Segment(seconds(2)..seconds(4), 3),
    ).shiftedDifference(seconds(1)).collect()

    assertIterableEquals(
        listOf(
            Segment(seconds(0)..<seconds(1), 0),
            Segment(seconds(1)..<seconds(2), 1),
            Segment(seconds(2)..seconds(3), 0)
        ),
        numbers
    )
  }
}
