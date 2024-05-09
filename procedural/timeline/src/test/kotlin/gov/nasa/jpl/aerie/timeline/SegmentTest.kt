package gov.nasa.jpl.aerie.timeline

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.aerie.timeline.Interval.Companion.at
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.payloads.transpose
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

class SegmentTest {

    @Test
    fun transpose() {
      assertEquals(Segment(at(seconds(2)), 5), Segment(at(seconds(2)), 5 as Int?).transpose())
      assertEquals(null, Segment(at(seconds(2)), null as Int?).transpose())
    }
}
