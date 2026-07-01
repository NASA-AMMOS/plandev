package gov.nasa.ammos.aerie.procedural.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.*
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Booleans
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Constants

/**
 * Operations mixin for segment-valued timelines whose payloads
 * represent constant values.
 */
interface SerialConstantOps<V: Any, THIS: SerialConstantOps<V, THIS>>: SerialSegmentOps<V, THIS>, ConstantOps<V, THIS> {

  /** [(DOC)][equalTo] Returns a [Booleans] that is `true` when this and another profile are equal. */
  infix fun equalTo(other: SerialConstantOps<V, *>) =
      map2Values(::Booleans, other) { l, r, _ -> l == r }

  /** [(DOC)][equalTo] Returns a [Booleans] that is `true` when this equals a constant value. */
  infix fun equalTo(v: V) = equalTo(Constants(v))

  /** [(DOC)][notEqualTo] Returns a [Booleans] that is `true` when this and another profile are not equal. */
  infix fun notEqualTo(other: SerialConstantOps<V, *>) =
      map2Values(::Booleans, other) { l, r, _ -> l != r }

  /** [(DOC)][notEqualTo] Returns a [Booleans] that is `true` when this is not equal to a constant value. */
  infix fun notEqualTo(v: V) = notEqualTo(Constants(v))

  override fun changes() = detectEdges(NullBinaryOperation.combineOrNull { l, r, _-> l != r })

  /**
   * [(DOC)][transitions] Returns a [Booleans] that is `true` when this profile's value changes between
   * two specific values.
   */
  fun transitions(from: V, to: V) = detectEdges(NullBinaryOperation.cases(
      { l, _ -> if (l == from) null else false },
      { r, _ -> if (r == to) null else false },
      { l, r, _ -> l == from && r == to }
  ))

  private class UnreachableValueAtException: Exception("internal error. a serial profile had multiple values at the same time.")

  /** [(DOC)][sample] Calculates the value of the profile at the given time. */
  fun sample(time: Duration): V? {
    val list = collect(CollectOptions(Interval.at(time), true))
    if (list.isEmpty()) return null
    if (list.size > 1) throw UnreachableValueAtException()
    return list[0].value
  }
}
