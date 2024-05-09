package gov.nasa.jpl.aerie.timeline.ops.numeric

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.timeline.BoundsTransformer
import gov.nasa.jpl.aerie.timeline.collections.profiles.Numbers
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.payloads.LinearEquation

/**
 * Operations mixin for segment-valued timelines whose payloads
 * represent continuous, piecewise linear values.
 *
 * Currently only used for Real profiles, but in the future could be refactored for
 * duration profiles or parallel real profiles.
 */
interface LinearOps<THIS: LinearOps<THIS>>: NumericOps<LinearEquation, THIS> {
  override fun negate() = mapValues { LinearEquation(it.value.initialTime, -it.value.initialValue, -it.value.rate) }

  override fun abs() = flatMapValues { it.value.abs() }

  /**
   * [(DOC)][rate] Maps each segment into its derivative.
   *
   * The result is scaled according to the [unit] argument. If a segment increases at a rate
   * of `1` per second, and `unit` is [Duration.SECOND], the derivative will be `1`.
   * But if (for the same segment) `unit` is [Duration.MINUTE], the derivative will be `60`.
   *
   * In fancy math terms, `unit` is the length of the time basis vector, and the result is
   * covariant with it.
   *
   * @param unit length of the time basis vector
   */
  fun rate(unit: Duration = Duration.SECOND) =
      if (unit == Duration.SECOND) mapValues(::Numbers) { it.value.rate }
      else mapValues(::Numbers) { it.value.rate / (Duration.SECOND.ratioOver(unit)) }

  override fun shift(dur: Duration) = unsafeMap(BoundsTransformer.shift(dur), false) { v ->
    Segment(v.interval.shiftBy(dur), LinearEquation(v.value.initialTime.plus(dur), v.value.initialValue, v.value.rate))
  }
}
