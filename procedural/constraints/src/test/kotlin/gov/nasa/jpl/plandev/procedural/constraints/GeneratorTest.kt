package gov.nasa.jpl.plandev.procedural.constraints

import gov.nasa.jpl.plandev.procedural.timeline.Interval
import gov.nasa.jpl.plandev.procedural.timeline.collections.profiles.Numbers
import gov.nasa.jpl.plandev.procedural.timeline.ops.SerialSegmentOps
import gov.nasa.jpl.plandev.procedural.timeline.payloads.Segment
import gov.nasa.jpl.plandev.procedural.timeline.plan.Plan
import gov.nasa.jpl.plandev.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.plandev.procedural.timeline.util.duration.rangeTo
import gov.nasa.jpl.plandev.procedural.utils.StubSimulationResults
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration.seconds
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue
import org.junit.jupiter.api.Assertions.assertIterableEquals
import kotlin.test.Test

class GeneratorTest: GeneratorConstraint() {
  override fun generate(plan: Plan, simResults: SimulationResults) {
    violate(Interval.at(seconds(0)), message = "other message")
    simResults.resource("/plant", Numbers.deserializer())
      .greaterThan(0)
      .violateOn(false)
  }

  override fun defaultMessage() = "Plant must be greater than 0"

  @Test
  fun testGenerator() {
    val plan = gov.nasa.jpl.plandev.procedural.utils.StubPlan()
    val simResults = object : StubSimulationResults() {
      override fun <V : Any, TL: SerialSegmentOps<V, TL>> resource(
        name: String,
        deserializer: (List<Segment<SerializedValue>>) -> TL
      ): TL {
        if (name == "/plant") {
          val list = listOf(
            Segment(seconds(-4) .. seconds(-2), SerializedValue.of(-3)),
            Segment(seconds(0) .. seconds(1), SerializedValue.of(3)),
            Segment(seconds(1) .. seconds(2), SerializedValue.of(-1)),
          )
          return deserializer(list)
        } else {
          TODO("Not yet implemented")
        }
      }
    }

    val result = run(plan, simResults).collect()

    val defaultMessage = "Plant must be greater than 0";
    assertIterableEquals(
      listOf(
        Violation(seconds(-4) .. seconds(-2), defaultMessage),
        Violation(Interval.at(seconds(0)), "other message"),
        Violation(seconds(1) .. seconds(2), defaultMessage)
      ),
      result
    )
  }
}
