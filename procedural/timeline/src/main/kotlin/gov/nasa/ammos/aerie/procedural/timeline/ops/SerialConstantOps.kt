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

  /**
   * Compares two values of type [V], requiring that [V] be [Comparable] at runtime.
   *
   * @throws UnsupportedOperationException if [V] does not implement [Comparable]
   */
  private fun compare(l: V, r: V): Int {
    @Suppress("UNCHECKED_CAST")
    val comparable = l as? Comparable<Any>
        ?: throw UnsupportedOperationException(
            "Comparison operations (lessThan, greaterThan, etc.) are not supported for non-Comparable type ${l::class.java.name}"
        )
    return comparable.compareTo(r)
  }

  /** [(DOC)][lessThan] Returns a [Booleans] that is `true` when this is less than another profile. */
  infix fun lessThan(other: SerialConstantOps<V, *>) =
      map2Values(::Booleans, other) { l, r, _ -> compare(l, r) < 0 }

  /** [(DOC)][lessThan] Returns a [Booleans] that is `true` when this is less than a constant value. */
  infix fun lessThan(v: V) = lessThan(Constants(v))

  /** [(DOC)][lessThanOrEqualTo] Returns a [Booleans] that is `true` when this is less than or equal to another profile. */
  infix fun lessThanOrEqualTo(other: SerialConstantOps<V, *>) =
      map2Values(::Booleans, other) { l, r, _ -> compare(l, r) <= 0 }

  /** [(DOC)][lessThanOrEqualTo] Returns a [Booleans] that is `true` when this is less than or equal to a constant value. */
  infix fun lessThanOrEqualTo(v: V) = lessThanOrEqualTo(Constants(v))

  /** Alias for [lessThanOrEqualTo]. */
  infix fun lessThanOrEqual(v: V) = lessThanOrEqualTo(v)
  /** Alias for [lessThanOrEqualTo]. */
  infix fun lessThanOrEqual(other: SerialConstantOps<V, *>) = lessThanOrEqualTo(other)

  /** Alias for [lessThanOrEqualTo]. */
  infix fun noLongerThan(v: V) = lessThanOrEqualTo(v)
  /** Alias for [lessThanOrEqualTo]. */
  infix fun noLongerThan(other: SerialConstantOps<V, *>) = lessThanOrEqualTo(other)

  /** [(DOC)][greaterThan] Returns a [Booleans] that is `true` when this is greater than another profile. */
  infix fun greaterThan(other: SerialConstantOps<V, *>) =
      map2Values(::Booleans, other) { l, r, _ -> compare(l, r) > 0 }

  /** [(DOC)][greaterThan] Returns a [Booleans] that is `true` when this is greater than a constant value. */
  infix fun greaterThan(v: V) = greaterThan(Constants(v))

  /** [(DOC)][greaterThanOrEqualTo] Returns a [Booleans] that is `true` when this is greater than or equal to another profile. */
  infix fun greaterThanOrEqualTo(other: SerialConstantOps<V, *>) =
      map2Values(::Booleans, other) { l, r, _ -> compare(l, r) >= 0 }

  /** [(DOC)][greaterThanOrEqualTo] Returns a [Booleans] that is `true` when this is greater than or equal to a constant value. */
  infix fun greaterThanOrEqualTo(v: V) = greaterThanOrEqualTo(Constants(v))

  /** Alias for [greaterThanOrEqualTo]. */
  infix fun greaterThanOrEqual(v: V) = greaterThanOrEqualTo(v)
  /** Alias for [greaterThanOrEqualTo]. */
  infix fun greaterThanOrEqual(other: SerialConstantOps<V, *>) = greaterThanOrEqualTo(other)

  /** Alias for [greaterThanOrEqualTo]. */
  infix fun noShorterThan(v: V) = greaterThanOrEqualTo(v)
  /** Alias for [greaterThanOrEqualTo]. */
  infix fun noShorterThan(other: SerialConstantOps<V, *>) = greaterThanOrEqualTo(other)

  /**
   * [(DOC)][contains] Returns a [Booleans] that is `true` when this profile's value contains [v], where
   * "contains" is interpreted according to the runtime type of [V] and [v]:
   * - if [V] is a [CharSequence] (e.g. [String]) and [v] is a [CharSequence], this checks for substring containment.
   * - if [V] is a [CharSequence] and [v] is a [Char], this checks whether that character occurs in the string.
   * - if [V] is an [Iterable] or [Array] and [v] is also an [Iterable] or [Array], this checks whether every
   *   element of [v] is present somewhere in [V] (not necessarily contiguous or in the same order).
   * - if [V] is an [Iterable] or [Array] and [v] is anything else, this checks whether [v] itself is one of the
   *   elements of [V].
   *
   * @throws UnsupportedOperationException if [V] and [v] don't match any of the above at runtime.
   */
  infix fun contains(v: Any): Booleans = mapValues(::Booleans) { seg ->
    val l = seg.value
    when {
      l is CharSequence && v is CharSequence -> l.contains(v)
      l is CharSequence && v is Char -> l.contains(v)
      l is Collection<*> && v is Collection<*> -> l.containsAll(v)
      l is Collection<*> -> l.contains(v)
      else -> throw UnsupportedOperationException(
          "contains is not supported for type ${l::class.java.name}"
      )
    }
  }

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
