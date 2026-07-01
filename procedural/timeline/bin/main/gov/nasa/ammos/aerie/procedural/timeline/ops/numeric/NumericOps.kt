package gov.nasa.ammos.aerie.procedural.timeline.ops.numeric

import gov.nasa.ammos.aerie.procedural.timeline.ops.SegmentOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.LinearEquation

/** Operations for all timelines of segments that represent numbers. */
interface NumericOps<V: Any, THIS: NumericOps<V, THIS>>: SegmentOps<V, THIS> {
  /**
   * Used to convert individual segments of numeric profiles to linear equations.
   * @suppress
   */
  fun V.toLinear(): LinearEquation

  /** [(DOC)][abs] Calculates the absolute value of this profile. */
  fun abs(): THIS

  /** [(DOC)][negate] Negates this profile. */
  fun negate(): THIS

  /** @see negate */
  operator fun unaryMinus() = negate()
}
