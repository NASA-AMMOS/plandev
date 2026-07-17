package gov.nasa.ammos.aerie.procedural.timeline.plan

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyInstance
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective

/** An interface for querying plan information and simulation results. */
interface SimulationResults {
  /** Whether these results are up-to-date with all changes. */
  fun isStale(): Boolean

  /** Bounds on which the plan was most recently simulated. */
  fun simBounds(): Interval

  /**
   * Query a resource profile from this simulation dataset.
   *
   * @param deserializer constructor of the profile, converting [SerializedValue]
   * @param name string name of the resource
   */
  fun <V: Any, TL: SerialSegmentOps<V, TL>> resource(name: String, deserializer: (List<Segment<SerializedValue>>) -> TL): TL

  /**
   * The names of all resources available in this simulation dataset.
   *
   * Implementations that do not support enumerating resources should throw
   * [UnsupportedOperationException] (the default behavior).
   */
  fun resourceNames(): Set<String> =
    throw UnsupportedOperationException("This SimulationResults implementation does not support enumerating resource names.")

  /**
   * Query all resource profiles from this simulation dataset as raw serialized segments,
   * keyed by resource name.
   *
   * This is a type-agnostic accessor: each profile is returned as its raw
   * `List<Segment<SerializedValue>>`, leaving deserialization (and the choice of
   * timeline type — `Real`, `Discrete<T>`, etc.) up to the caller. Useful when
   * resources have heterogeneous value types and a single deserializer can't
   * handle them all.
   *
   * Implementations that do not support enumerating resources should throw
   * [UnsupportedOperationException] (the default behavior).
   *
   * @deprecated SimulationResults tend to be large, and in most implementations of this method require creating
   * a full copy of that data.
   */
  @Deprecated("Iterate over resourceNames and call the singular `rawResource` instead")
  fun rawResources(): Map<String, List<Segment<SerializedValue>>> =
    throw UnsupportedOperationException("This SimulationResults implementation does not support enumerating resources.")

  /**
   * Query a given resource profile from this simulation dataset as raw serialized segments.
   *
   * This is a type-agnostic accessor: the profile is returned as its raw
   * `List<Segment<SerializedValue>>`, leaving deserialization (and the choice of
   * timeline type — `Real`, `Discrete<T>`, etc.) up to the caller. Useful when
   * resources have heterogeneous value types and a single deserializer can't
   * handle them all.
   *
   * Implementations that do not support enumerating resources should throw
   * [UnsupportedOperationException] (the default behavior).
   */
  fun rawResource(name: String): List<Segment<SerializedValue>> =
    throw UnsupportedOperationException("This SimulationResults implementation does not support rawResource")

  /**
   * Query activity instances.
   *
   * @param type Activity type name to filter by; queries all activities if null.
   * @param deserializer a function from [SerializedValue] to an inner payload type
   */
  fun <A: Any> instances(type: String?, deserializer: (SerializedValue) -> A): Instances<A>
  /** Queries activity instances, filtered by type, deserializing them as [AnyInstance]. **/
  fun instances(type: String) = instances(type, AnyInstance.deserializer())
  /** Queries all activity instances, deserializing them as [AnyInstance]. **/
  fun instances() = instances(null, AnyInstance.deserializer())

  /** The input directives that were used for this simulation. */
  fun <A: Any> inputDirectives(deserializer: (SerializedValue) -> A): Directives<A>
  /** The input directives that were used for this simulation, deserialized as [AnyDirective]. */
  fun inputDirectives() = inputDirectives(AnyDirective.deserializer())
}
