package gov.nasa.ammos.aerie.procedural.utils

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue

open class StubSimulationResults: SimulationResults {
  override fun isStale(): Boolean = TODO()
  override fun simBounds(): Interval = TODO()
  override fun <V : Any, TL: SerialSegmentOps<V, TL>> resource(
    name: String,
    deserializer: (List<Segment<SerializedValue>>) -> TL
  ): TL = TODO()
  override fun <A : Any> instances(type: String?, deserializer: (SerializedValue) -> A): Instances<A> = TODO()
  override fun <A : Any> inputDirectives(deserializer: (SerializedValue) -> A): Directives<A> = TODO()
}
