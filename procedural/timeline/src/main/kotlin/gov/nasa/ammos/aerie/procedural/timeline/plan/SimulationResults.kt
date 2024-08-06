package gov.nasa.ammos.aerie.procedural.timeline.plan

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyInstance
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceSegmentsOp
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances

/** An interface for querying plan information and simulation results. */
interface SimulationResults {
  /** Whether these results are up-to-date with all changes. */
  fun isStale(): Boolean

  /** Bounds on which the plan was most recently simulated. */
  fun simBounds(): Interval

  /**
   * Query a resource profile from the database
   *
   * @param deserializer constructor of the profile, converting [SerializedValue]
   * @param name string name of the resource
   */
  fun <V: Any, TL: CoalesceSegmentsOp<V, TL>> resource(name: String, deserializer: (List<Segment<SerializedValue>>) -> TL): TL

  /**
   * Query activity instances.
   *
   * @param type Activity type name to filter by; queries all activities if null.
   * @param deserializer a function from [SerializedValue] to an inner payload type
   */
  fun <A: Any> instances(type: String?, deserializer: (SerializedValue) -> A): Instances<A>
  /** Queries activity instances, filtered by type, deserializing them as [AnyInstance]. **/
  fun instances(type: String) = instances(type, AnyInstance::deserialize)
  /** Queries all activity instances, deserializing them as [AnyInstance]. **/
  fun instances() = instances(null, AnyInstance::deserialize)
}
