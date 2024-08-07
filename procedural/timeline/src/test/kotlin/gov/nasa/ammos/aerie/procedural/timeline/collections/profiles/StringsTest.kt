package gov.nasa.ammos.aerie.procedural.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.seconds
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class StringsTest {
  @Test
  fun caseInsensitiveEqualTo() {
    val result = Strings(
        Segment(seconds(1)..seconds(2), "HELLO world"),
        Segment(seconds(3)..seconds(4), "hello WORLD"),
        Segment(seconds(5)..seconds(6), "hello there")
    ).caseInsensitiveEqualTo("hello world")

    assertIterableEquals(
      listOf(
        Segment(seconds(1) .. seconds(2), true),
        Segment(seconds(3) .. seconds(4), true),
        Segment(seconds(5) .. seconds(6), false)
      ),
      result.collect()
    )
  }
}
