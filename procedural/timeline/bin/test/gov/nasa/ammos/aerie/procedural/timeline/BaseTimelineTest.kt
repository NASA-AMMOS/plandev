package gov.nasa.ammos.aerie.procedural.timeline

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Constants
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo

class BaseTimelineTest {
  @Test
  fun cache() {
    var collectCounter = 0

    val profile = BaseTimeline(::Constants) {
        collectCounter += 1
        listOf<Segment<Int>>()
    }

    assertEquals(collectCounter, 0)

    // collect once
    profile.collect()
    assertEquals(collectCounter, 1)

    // cache on small bounds, triggering a eval
    profile.cache(seconds(0) .. seconds(5))
    assertEquals(collectCounter, 2)

    // collect on cached bounds, no-op
    profile.collect(Interval.Companion.at(seconds(3)))
    assertEquals(collectCounter, 2)

    // collect on wider bounds, triggering an eval
    profile.collect()
    assertEquals(collectCounter, 3)

    // cache on wider bounds, triggering an eval
    profile.cache()
    assertEquals(collectCounter, 4)

    // collect on cached bounds, no-op
    profile.collect()
    assertEquals(collectCounter, 4)

    // cache on smaller bounds, no-op
    profile.cache(seconds(2)..seconds(10))
    assertEquals(collectCounter, 4)
  }
}
