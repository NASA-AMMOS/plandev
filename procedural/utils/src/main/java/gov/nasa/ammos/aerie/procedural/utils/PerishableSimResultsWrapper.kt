package gov.nasa.ammos.aerie.procedural.utils

import gov.nasa.ammos.aerie.procedural.scheduling.utils.PerishableSimulationResults
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.*
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue

/**
 * A wrapper around [SimulationResults] objects to make them implement
 * [PerishableSimResultsWrapper]. Used by [TypeUtilsEditablePlanAdapter] internally.
 */
class PerishableSimResultsWrapper(
  private val simulationResults: SimulationResults,
  private var stale: Boolean = false
): PerishableSimulationResults {
  override fun setStale(stale: Boolean) {
    this.stale = stale
  }

  override fun isStale() = this.stale


  // Delegation for SimulationResults
  // Cannot use the `by` keyword due to multiple inheritance of the default overloaded resource methods

  override fun simBounds() = simulationResults.simBounds()

  override fun <V: Any, TL: SerialSegmentOps<V, TL>> resource(name: String, deserializer: (List<Segment<SerializedValue>>) -> TL)
    = simulationResults.resource(name, deserializer)

  override fun <A : Any> instances(type: String?, deserializer: (SerializedValue) -> A) = simulationResults.instances(type, deserializer)

  override fun <A : Any> inputDirectives(deserializer: (SerializedValue) -> A) = simulationResults.inputDirectives(deserializer)

}
