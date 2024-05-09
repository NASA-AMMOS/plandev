package gov.nasa.jpl.aerie.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.milliseconds
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.*
import gov.nasa.jpl.aerie.timeline.Interval.Companion.at
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.Interval.Companion.betweenClosedOpen
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.jpl.aerie.timeline.collections.Intervals
import gov.nasa.jpl.aerie.timeline.collections.profiles.Constants
import gov.nasa.jpl.aerie.timeline.collections.profiles.Numbers
import gov.nasa.jpl.aerie.timeline.collections.profiles.Booleans
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.util.duration.div
import gov.nasa.jpl.aerie.timeline.util.duration.rangeTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GeneralOpsTest {

  @Test
  fun operate() {
    val result = Constants(Segment(seconds(0) .. seconds(1), "hello")).unsafeOperate {
      collect(it).map { s -> Segment(s.interval, s.value + " world") }
    }.collect()

    val expected = listOf(Segment(seconds(0) .. seconds(1), "hello world"))

    assertIterableEquals(expected, result)
  }

  @Test
  fun operateAutoCoalesce() {
    val result = Constants(
        Segment(seconds(0) .. seconds(1), "hello world"),
        Segment(seconds(1) .. seconds(2), "hello there")
    ).unsafeOperate {
      collect(it).map { s -> Segment(s.interval, s.value.substring(0..4)) }
    }.collect()

    val expected = listOf(Segment(seconds(0) .. seconds(2), "hello"))

    assertIterableEquals(expected, result)
  }

  @Test
  fun operateType() {
    // this is just a test to make sure the return type of unsafeOperate is correct.
    // we would get a compile error if it failed.
    @Suppress("UNUSED_VARIABLE")
    val result: Booleans = Booleans(true).unsafeOperate { collect(it) }
  }

  @Test
  fun inspect() {
    var count: Int? = null
    val tl = Intervals(
        at(seconds(1)),
        at(seconds(2))
    ).inspect {
      count = it.size
    }

    assertNull(count)

    tl.collect()

    assertEquals(2, count)
  }

  @Test
  fun unset() {
    val result = Intervals(
        seconds(0) .. seconds(2),
        seconds(2) .. seconds(3),
        seconds(3) .. seconds(5),
        seconds(10) .. seconds(11)
    ).unset(seconds(1) .. seconds(4)).collect()

    assertIterableEquals(
        listOf(
            betweenClosedOpen(seconds(0), seconds(1)),
            between(seconds(4), seconds(5), Exclusive, Inclusive),
            (seconds(10) .. seconds(11))
        ),
        result
    )
  }

  @Test
  fun filter() {
    val result = Numbers(
        Segment(at(seconds(1)), 4),
        Segment(at(seconds(2)), 5)
    ).filter { it.value.toInt() % 2 == 0 }.collect()

    assertIterableEquals(
        listOf(Segment(at(seconds(1)), 4)),
        result
    )
  }

  @Test
  fun filterPreserveMargin() {
    val intervals = Intervals(
        between(seconds(-1), seconds(1)),
        seconds(1) .. seconds(4),
        seconds(4) .. seconds(8),
    )

    // without preserve margin
    assertIterableEquals(
        listOf(seconds(1) .. seconds(4)),
        intervals.filter(false) { it.duration() >= seconds(2) }
            .collect(seconds(0) .. seconds(5))
    )

    // with preserve margin and truncate margin
    // notice that the marginal intervals are retained and then later truncated to within the bounds
    assertIterableEquals(
        listOf(
            seconds(0) .. seconds(1),
            seconds(1) .. seconds(4),
            seconds(4) .. seconds(5),
        ),
        intervals.filter(true) { it.duration() >= seconds(2) }
            .collect(seconds(0) .. seconds(5))
    )

    // with preserve margin, without truncate margin
    // notice that the marginal intervals are retained and NOT truncated later
    assertIterableEquals(
        intervals.collect(),
        intervals.filter(true) { it.duration() >= seconds(2) }
            .collect(CollectOptions(seconds(0) .. seconds(5), false))
    )
  }

  @Test
  fun map() {
    val result = Intervals(
        at(seconds(1)),
        seconds(2) .. seconds(3)
    ).unsafeMap(::Booleans, BoundsTransformer.IDENTITY, false) { Segment(it.interval, it.interval.isPoint()) }
        .collect()

    assertIterableEquals(
        listOf(
            Segment(at(seconds(1)), true),
            Segment(seconds(2) .. seconds(3), false),
        ),
        result
    )
  }

  @Test
  fun shiftBoundsTransform() {
    val intervals = Intervals(
        between(seconds(-1), seconds(0)),
        seconds(2) .. seconds(4),
    ).shift(Duration.SECOND)

    val expected = listOf(
        seconds(0) .. seconds(1),
        seconds(3) .. seconds(5)
    )

    assertIterableEquals(
        expected,
        intervals.collect()
    )

    assertIterableEquals(
        expected,
        intervals.collect(seconds(0) .. seconds(5))
    )
  }

  @Test
  fun shiftOutOfBounds() {
    val intervals = Intervals(at(seconds(3))).shift(seconds(3))

    assertIterableEquals(
        listOf<Interval>(),
        intervals.collect(seconds(0) .. seconds(5))
    )
  }

  @Test
  fun flatMapTest() {
    val result = Intervals(
        seconds(2) .. seconds(8),
        seconds(0) .. seconds(3)
    )
        // converts each interval to a windows object.
        // false for the first half of the interval, true for the second half
        .unsafeFlatMap(::Booleans, BoundsTransformer.IDENTITY, false) {
          val midpoint = it.interval.start.plus(it.interval.end) / 2
          Segment(
              it.interval.interval,
              Booleans(false).set(Booleans(Segment(between(midpoint, Duration.MAX_VALUE), true)))
          )
        }
        .collect()

    val expected = listOf(
        Segment(betweenClosedOpen(seconds(0), milliseconds(1500)), false),
        Segment(betweenClosedOpen(milliseconds(1500), seconds(2)), true),
        Segment(betweenClosedOpen(seconds(2), seconds(5)), false),
        Segment(seconds(5) .. seconds(8), true)
    )

    assertIterableEquals(expected, result)
  }
}
