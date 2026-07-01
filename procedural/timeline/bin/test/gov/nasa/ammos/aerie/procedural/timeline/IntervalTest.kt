package gov.nasa.ammos.aerie.procedural.timeline

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.EMPTY
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.at
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.betweenClosedOpen
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.*
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeUntil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class IntervalTest {
  @Nested
  inner class InclusivityTest {
    @Test
    fun opposite() {
      assertEquals(Inclusive, Exclusive.opposite())
    }

    @Test
    fun moreRestrictiveThan() {
      assertTrue(Exclusive.moreRestrictiveThan(Inclusive))
      assertFalse(Exclusive.moreRestrictiveThan(Exclusive))
    }
  }

  @Test
  fun at() {
    val t = seconds(45)

    val i = at(t)
    assertEquals(t, i.start)
    assertEquals(t, i.end)
    assertEquals(Inclusive, i.startInclusivity)
    assertEquals(Inclusive, i.endInclusivity)
  }

  @Test
  fun between() {
    val t1 = seconds(1)
    val t2 = seconds(5)

    var i = between(t1, t2)
    assertEquals(t1, i.start)
    assertEquals(t2, i.end)
    assertEquals(Inclusive, i.startInclusivity)
    assertEquals(Inclusive, i.endInclusivity)

    i = between(t1, t2, Exclusive)
    assertEquals(Exclusive, i.startInclusivity)
    assertEquals(Exclusive, i.endInclusivity)

    i = between(t1, t2, Inclusive, Exclusive)
    assertEquals(Inclusive, i.startInclusivity)
    assertEquals(Exclusive, i.endInclusivity)

    val i2 = betweenClosedOpen(t1, t2)
    assertEquals(i, i2)
  }

  @Test
  fun isEmpty() {
    assertFalse(at(seconds(5)).isEmpty())
    assertFalse((seconds(1) .. seconds(2)).isEmpty())
    assertTrue((seconds(2) .. seconds(1)).isEmpty())
    assertTrue(between(seconds(1), seconds(1), Inclusive, Exclusive).isEmpty())
    assertTrue(EMPTY.isEmpty())
  }

  @Test
  fun isSingleton() {
    assertTrue(at(seconds(1)).isPoint())
    assertFalse((seconds(1) .. seconds(2)).isPoint())
    assertFalse((seconds(2) .. seconds(1)).isPoint())
  }

  @Test
  fun duration() {
    assertEquals(seconds(1), (seconds(1) .. seconds(2)).duration())
    assertEquals(Duration.ZERO, EMPTY.duration())
    assertEquals(Duration.ZERO, (seconds(2) .. seconds(1)).duration())
  }

  @Test
  fun intersect() {
    assertEquals(
        (seconds(1) .. seconds(2)),
        seconds(1) .. seconds(2) intersection seconds(0) .. seconds(4)
    )
    assertEquals(
        (seconds(1) .. seconds(2)),
        (seconds(0) .. seconds(2)).intersection(seconds(1) .. seconds(3))
    )
    assertEquals(
        betweenClosedOpen(seconds(1), seconds(2)),
        between(seconds(0), seconds(2), Exclusive).intersection(seconds(1) .. seconds(3))
    )
    assertTrue((seconds(0) .. seconds(1)).intersection(seconds(2) .. seconds(3)).isEmpty())
    assertTrue((seconds(0) .. seconds(1)).intersection(EMPTY).isEmpty())
  }

  @Test
  fun union() {
    assertIterableEquals(
        listOf(seconds(0) .. seconds(2)),
        seconds(0) .. seconds(2) union at(seconds(1))
    )
    assertIterableEquals(
        listOf(seconds(0) .. seconds(2)),
        (seconds(0) .. seconds(2)).union(EMPTY)
    )
    assertIterableEquals(
        listOf(seconds(0) .. seconds(2)),
        (seconds(0) .. seconds(1)).union(seconds(1) .. seconds(2))
    )
    assertIterableEquals(
        listOf(seconds(0) .. seconds(2)),
        betweenClosedOpen(seconds(0), seconds(1)).union(seconds(1) .. seconds(2))
    )
    assertIterableEquals(
        listOf((seconds(0) .. seconds(1)), at(seconds(4))),
        (seconds(0) .. seconds(1)).union(at(seconds(4)))
    )
    assertIterableEquals(
        listOf(seconds(0) .. seconds(1)),
        (seconds(0) .. seconds(1)).union(EMPTY)
    )
  }

  @Test
  fun subtract() {
    assertIterableEquals(
        listOf(betweenClosedOpen(seconds(0), seconds(1)), between(seconds(1), seconds(2), Exclusive, Inclusive)),
        (seconds(0) .. seconds(2)).minus(at(seconds(1)))
    )
    assertIterableEquals(
        listOf(seconds(0) .. seconds(1)),
        (seconds(0) .. seconds(1)).minus(seconds(3) .. seconds(4))
    )
    assertIterableEquals(
        listOf(betweenClosedOpen(seconds(0), seconds(1))),
        (seconds(0) .. seconds(2)).minus(seconds(1) .. seconds(4))
    )
    assertIterableEquals(
        listOf(seconds(3) .. seconds(4)),
        (seconds(3) .. seconds(4)).minus(seconds(1) .. seconds(2))
    )
    assertIterableEquals(
        listOf(between(seconds(3), seconds(4), Exclusive, Inclusive)),
        (seconds(2) .. seconds(4)).minus(seconds(1) .. seconds(3))
    )
  }

  @Test
  fun compareStarts() {
    assertEquals(-1, (seconds(0) .. seconds(1)).compareStarts(at(seconds(1))))
    assertEquals(1, between(seconds(0), seconds(1), Exclusive).compareStarts(at(seconds(0))))
    assertEquals(0, (seconds(0) .. seconds(1)).compareStarts(at(seconds(0))))
  }

  @Test
  fun compareEnds() {
    assertEquals(1, (seconds(0) .. seconds(1)).compareEnds(at(seconds(0))))
    assertEquals(-1, between(seconds(0), seconds(1), Exclusive).compareEnds(at(seconds(1))))
    assertEquals(0, (seconds(0) .. seconds(1)).compareEnds(at(seconds(1))))
    assertEquals(-1, (seconds(0) ..< seconds(1)).compareEnds(at(seconds(1))))
  }

  @Test
  fun compareEndToStart() {
    assertEquals(-1, at(seconds(0)).compareEndToStart(at(seconds(1))))
    assertEquals(0, at(seconds(0)).compareEndToStart(between(seconds(0), seconds(1), Exclusive)))
    assertEquals(1, (seconds(0) .. seconds(2)).compareEndToStart(seconds(1) .. seconds(4)))
  }

  @Test
  fun contains() {
    assertTrue((seconds(0) .. seconds(2)).contains(seconds(1)))
    assertFalse((seconds(0) .. seconds(2)).contains(seconds(3)))
    assertTrue((seconds(0) .. seconds(2)).contains(at(seconds(1))))
    assertFalse((seconds(0) .. seconds(2)).contains(at(seconds(3))))
    assertTrue((seconds(0) .. seconds(2)).contains(seconds(1) .. seconds(2)))
    assertFalse((seconds(0) .. seconds(2)).contains(seconds(1) .. seconds(3)))
  }

  @Test
  fun shiftBy() {
    assertEquals((seconds(1) .. seconds(2)), (seconds(0) .. seconds(1)).shiftBy(seconds(1)))
    assertEquals(at(seconds(1)), at(seconds(2)).shiftBy(seconds(-1)))
    assertEquals((seconds(1) .. seconds(3)), (seconds(0) .. seconds(4)).shiftBy(seconds(1), seconds(-1)))
    assertEquals(at(seconds(1)), (seconds(0) .. seconds(2)).shiftBy(seconds(1), seconds(-1)))
    assertTrue((seconds(0) .. seconds(1)).shiftBy(seconds(2), seconds(0)).isEmpty())
  }
}
