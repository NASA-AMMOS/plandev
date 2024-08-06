package gov.nasa.ammos.aerie.procedural.timeline.payloads

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.util.coalesceList

/**
 * A generic container that associates a value with an interval on a timeline.
 */
data class Segment<V>(/***/ override val interval: Interval, /***/ @JvmField val value: V): IntervalLike<Segment<V>> {
  /**
   * Create a new segment on the same interval, with a new value derived from this segment.
   *
   * In most cases using [withNewValue] will result in simpler-looking code.
   *
   * @param f a function that takes `this` as argument and produces a new value.
   */
  fun <W> mapValue(f: (Segment<V>) -> W) = Segment(interval, f(this))

  /**
   * Create a new segment with the same value, on a new interval derived from this segment.
   *
   * In most cases using [withNewInterval] will result in simpler-looking code.
   *
   * @param f a function that takes `this` as argument and produces a new interval.
   */
  fun mapInterval(f: (Segment<V>) -> Interval) = Segment(f(this), value)

  /**
   * Creates a new segment with the same value on a new interval.
   *
   * @param i the new interval
   */
  override fun withNewInterval(i: Interval) = Segment(i, value)

  /**
   * Creates a new segment on the same interval with a new value.
   *
   * @param w the new value
   */
  fun <W> withNewValue(w: W): Segment<W> = Segment(interval, w)

  /**
   * Checks whether this segment's value equals another, using the basic equality operator.
   *
   * Used mostly for the [coalesceList] operation.
   */
  fun valueEquals(other: Segment<V>) = value == other.value

  /***/
  companion object {
    /**
     * Delegates to the constructor.
     *
     * Can be more convenient in Java by avoiding the `new` keyword.
     */
    @JvmStatic fun <V> of(interval: Interval, value: V) = Segment(interval, value)
  }
}

/**
 * Converts a non-null segment of a maybe-null value into a maybe-null segment of a non-null value.
 */
fun <V> Segment<V?>.transpose() = if (value == null) null else Segment(interval, value)
