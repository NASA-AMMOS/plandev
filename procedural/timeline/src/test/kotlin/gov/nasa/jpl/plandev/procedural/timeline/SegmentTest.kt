package gov.nasa.jpl.plandev.procedural.timeline

import gov.nasa.jpl.plandev.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.plandev.procedural.timeline.Interval.Companion.at
import gov.nasa.jpl.plandev.procedural.timeline.payloads.Segment
import gov.nasa.jpl.plandev.procedural.timeline.payloads.transpose
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

class SegmentTest {

    @Test
    fun transpose() {
      assertEquals(Segment(at(seconds(2)), 5), Segment(at(seconds(2)), 5 as Int?).transpose())
      assertEquals(null, Segment(at(seconds(2)), null as Int?).transpose())
    }
}
