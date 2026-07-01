package gov.nasa.ammos.aerie.procedural.timeline.util

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.NullBinaryOperation
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.*
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeUntil
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

class Map2Test {

  @Nested
  inner class Map2SegmentLists {

    @Test
    fun basicCombineOrIdentity() {
      val left = listOf(Segment(seconds(0)..seconds(2), 2))
      val right = listOf(Segment(seconds(1)..seconds(3), 3))

      val result = map2SegmentLists(
          left, right,
          NullBinaryOperation.combineOrIdentity { l, r, _ -> l + r }
      )

      val expected = listOf(
          Segment(seconds(0)..<seconds(1), 2),
          Segment(seconds(1)..seconds(2), 5),
          Segment(between(seconds(2), seconds(3), Exclusive, Inclusive), 3),
      )

      assertIterableEquals(expected, result)
    }

    @Test
    fun basicCombineOrUndefined() {
      val left = listOf(Segment(seconds(0)..seconds(2), 2))
      val right = listOf(Segment(seconds(1)..seconds(3), 3))

      val result = map2SegmentLists(
          left, right,
          NullBinaryOperation.combineOrNull { l, r, _ -> l + r }
      )

      val expected = listOf(
          Segment(seconds(1)..seconds(2), 5)
      )

      assertIterableEquals(expected, result)
    }
  }

  @Nested
  inner class SegmentAlignment {

    private fun makeLeft(s: Long, e: Long, si: Interval.Inclusivity = Inclusive, ei: Interval.Inclusivity = Inclusive) =
        listOf(Segment(between(seconds(s), seconds(e), si, ei), -1))

    private fun makeRight(s: Long, e: Long, si: Interval.Inclusivity = Inclusive, ei: Interval.Inclusivity = Inclusive) =
        listOf(Segment(between(seconds(s), seconds(e), si, ei), 1))

    private fun testBothMap2Routines(
        left: List<Segment<Int>>,
        right: List<Segment<Int>>,
        resultWithIdentity: List<Segment<Int>>,
    ) {
      val resultWithGaps = resultWithIdentity.filter { it.value == 0 }
      assertIterableEquals(
          resultWithIdentity,
          map2SegmentLists(left, right, NullBinaryOperation.combineOrIdentity { l, r, _ -> l + r })
      )
      assertIterableEquals(
          resultWithGaps,
          map2SegmentLists(left, right, NullBinaryOperation.combineOrNull { l, r, _ -> l + r })
      )
      assertIterableEquals(
          resultWithGaps,
          map2ParallelLists(left, right, false, false) { l, r, i -> Segment(i, l.value + r.value) }
      )
    }

    @Test
    fun identical() {
      testBothMap2Routines(
          makeLeft(1, 2), makeRight(1, 2),
          listOf(Segment(seconds(1)..seconds(2), 0))
      )
    }

    @Test
    fun identicalExclusive() {
      testBothMap2Routines(
          makeLeft(1, 2, Exclusive, Exclusive), makeRight(1, 2, Exclusive, Exclusive),
          listOf(Segment(between(seconds(1), seconds(2), Exclusive, Exclusive), 0)),
      )
    }

    @Test
    fun entireLeftSegmentFirst() {
      testBothMap2Routines(
          makeLeft(1, 2), makeRight(3, 4),
          listOf(
              Segment(seconds(1)..seconds(2), -1),
              Segment(seconds(3)..seconds(4), 1)
          ),
      )
    }

    @Test
    fun entireRightSegmentFirst() {
      testBothMap2Routines(
          makeLeft(3, 4), makeRight(1, 2),
          listOf(
              Segment(seconds(1)..seconds(2), 1),
              Segment(seconds(3)..seconds(4), -1)
          ),
      )
    }

    @Test
    fun leftFirstMomentOfOverlap() {
      testBothMap2Routines(
          makeLeft(1, 2), makeRight(2, 3),
          listOf(
              Segment(between(seconds(1), seconds(2), endInclusivity = Exclusive), -1),
              Segment(Interval.at(seconds(2)), 0),
              Segment(between(seconds(2), seconds(3), Exclusive, Inclusive), 1)
          ),
      )
    }

    @Test
    fun rightFirstMomentOfOverlap() {
      testBothMap2Routines(
          makeLeft(2, 3), makeRight(1, 2),
          listOf(
              Segment(between(seconds(1), seconds(2), endInclusivity = Exclusive), 1),
              Segment(Interval.at(seconds(2)), 0),
              Segment(between(seconds(2), seconds(3), Exclusive, Inclusive), -1)
          ),
      )
    }

    @Test
    fun leftFirstMomentOfNonOverlap() {
      testBothMap2Routines(
          makeLeft(1, 2), makeRight(1, 2, Exclusive, Inclusive),
          listOf(
              Segment(Interval.at(seconds(1)), -1),
              Segment(between(seconds(1), seconds(2), Exclusive, Inclusive), 0)
          ),
      )
    }

    @Test
    fun rightFirstMomentOfNonOverlap() {
      testBothMap2Routines(
          makeLeft(1, 2, Exclusive, Inclusive), makeRight(1, 2),
          listOf(
              Segment(Interval.at(seconds(1)), 1),
              Segment(between(seconds(1), seconds(2), Exclusive, Inclusive), 0)
          ),
      )
    }

    @Test
    fun leftFirstHalfNonOverlap() {
      testBothMap2Routines(
          makeLeft(1, 3), makeRight(2, 4),
          listOf(
              Segment(seconds(1)..<seconds(2), -1),
              Segment(seconds(2)..seconds(3), 0),
              Segment(between(seconds(3), seconds(4), Exclusive, Inclusive), 1),
          ),
      )
    }

    @Test
    fun rightFirstHalfNonOverlap() {
      testBothMap2Routines(
          makeLeft(2, 4), makeRight(1, 3),
          listOf(
              Segment(seconds(1)..<seconds(2), 1),
              Segment(seconds(2)..seconds(3), 0),
              Segment(between(seconds(3), seconds(4), Exclusive, Inclusive), -1),
          ),
      )
    }

    @Test
    fun leftContainsRight() {
      testBothMap2Routines(
          makeLeft(1, 4), makeRight(2, 3),
          listOf(
              Segment(seconds(1)..<seconds(2), -1),
              Segment(seconds(2)..seconds(3), 0),
              Segment(between(seconds(3), seconds(4), Exclusive, Inclusive), -1),
          ),
      )
    }

    @Test
    fun rightContainsLeft() {
      testBothMap2Routines(
          makeLeft(2, 3), makeRight(1, 4),
          listOf(
              Segment(seconds(1)..<seconds(2), 1),
              Segment(seconds(2)..seconds(3), 0),
              Segment(between(seconds(3), seconds(4), Exclusive, Inclusive), 1),
          ),
      )
    }
  }
}
