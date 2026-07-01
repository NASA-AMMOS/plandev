package gov.nasa.ammos.aerie.procedural.timeline.collections

import gov.nasa.ammos.aerie.procedural.timeline.CollectOptions
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.milliseconds
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeUntil
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.unaryMinus
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class WindowsTest {
  @Test
  fun constructorCoalesce() {
    val result = Windows(
        seconds(2)..seconds(3),
        seconds(0)..<seconds(2),
        at(seconds(5)),
        seconds(4)..seconds(6)
    ).collect()

    assertIterableEquals(
        listOf(
            seconds(0) .. seconds(3),
            seconds(4) .. seconds(6)
        ),
        result
    )
  }

  @Test
  fun complement() {
    val windows = Windows(
        seconds(1)..seconds(3),
        seconds(5)..<seconds(8)
    ).complement()

    // testing how complement works on different bounds

    assertIterableEquals(
        listOf(
            Duration.MIN_VALUE ..< seconds(1),
            between(seconds(3), seconds(5), Exclusive),
            seconds(8) .. Duration.MAX_VALUE
        ),
        windows.collect()
    )

    assertIterableEquals(
        listOf(
            seconds(0) ..< seconds(1),
            between(seconds(3), seconds(5), Exclusive),
            seconds(8) .. seconds(10)
        ),
        windows.collect(seconds(0) .. seconds(10))
    )

    assertIterableEquals(
        listOf(between(seconds(3), seconds(5), Exclusive)),
        windows.collect(seconds(2) .. seconds(7))
    )
    assertIterableEquals(
        listOf(between(seconds(3), seconds(5), Exclusive)),
        windows.collect(CollectOptions(seconds(2) .. seconds(7), false))
    )

    assertIterableEquals(
        listOf(
            seconds(4) ..< seconds(5),
            seconds(8) .. seconds(10)
        ),
        windows.collect(seconds(4) .. seconds(10))
    )
  }

  @Test
  fun union() {
    val w1 = Windows(
        seconds(0)..<seconds(1),
        at(milliseconds(3500)),
        seconds(5)..seconds(7),
        seconds(8)..seconds(10)
    )

    val w2 = Windows(
        seconds(1)..seconds(2),
        seconds(3)..seconds(4),
        seconds(6)..seconds(9)
    )

    val result = (w1 union w2).collect()

    assertIterableEquals(
        listOf(
            seconds(0) .. seconds(2),
            seconds(3) .. seconds(4),
            seconds(5) .. seconds(10)
        ),
        result
    )
  }

  @Test
  fun intersection() {
    val w1 = Windows(
        seconds(1)..seconds(2),
        seconds(5)..<seconds(7)
    )

    val w2 = Windows(
        seconds(0)..<seconds(1),
        seconds(2)..seconds(3),
        at(seconds(5)),
        seconds(6)..seconds(8)
    )

    val result = (w1 intersection w2).collect()

    assertIterableEquals(
        listOf(
            at(seconds(2)),
            at(seconds(5)),
            seconds(6) ..< seconds(7)
        ),
        result
    )
  }

  @Test
  fun starts() {
    val w = Windows(
      at(seconds(1)),
      seconds(2)..seconds(3),
      seconds(4)..<seconds(5),
      between(seconds(6), seconds(7), Exclusive, Inclusive)
    )

    val result = w.starts()

    assertIterableEquals(
      listOf(
        at(seconds(1)),
        at(seconds(2)),
        at(seconds(4)),
        at(seconds(6))
      ),
      result
    )
  }

  @Test
  fun ends() {
    val w = Windows(
      at(seconds(1)),
      seconds(2)..seconds(3),
      seconds(4)..<seconds(5),
      between(seconds(6), seconds(7), Exclusive, Inclusive)
    )

    val result = w.ends()

    assertIterableEquals(
      listOf(
        at(seconds(1)),
        at(seconds(3)),
        at(seconds(5)),
        at(seconds(7))
      ),
      result
    )
  }

  @Test
  fun shiftEndpointsShrink() {
    val w = Windows(
      at(seconds(1)),
      seconds(2)..seconds(3),
      seconds(4)..seconds(6),
      between(seconds(7), seconds(9), Exclusive, Inclusive),
      seconds(10)..<seconds(12),
      seconds(13)..seconds(17)
    )

    val result1 = w.shiftEndpoints(seconds(2), Duration.ZERO)

    assertIterableEquals(
      listOf(
        at(seconds(6)),
        seconds(15)..seconds(17)
      ),
      result1
    )

    val result2 = w.shiftEndpoints(Duration.ZERO, -seconds(2))

    assertIterableEquals(
      listOf(
        at(seconds(4)),
        seconds(13)..seconds(15)
      ),
      result2
    )
  }

  @Test
  fun shiftEndpointsGrow() {
    val w = Windows(
      at(seconds(0)),
      seconds(2)..<seconds(3),
      seconds(4)..seconds(5),
    )

    val result = w.extend(seconds(1))

    assertIterableEquals(
      listOf(
        seconds(0)..seconds(1),
        seconds(2)..seconds(6),
      ),
      result
    )
  }
}
