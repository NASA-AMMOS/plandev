package gov.nasa.jpl.aerie.timeline.ops.numeric

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.timeline.collections.profiles.Numbers
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.collections.profiles.Real
import gov.nasa.jpl.aerie.timeline.ops.SerialSegmentOps
import gov.nasa.jpl.aerie.timeline.payloads.LinearEquation


/**
 * Operations for profiles that represent numbers.
 */
interface SerialNumericOps<V: Any, THIS: SerialNumericOps<V, THIS>>: SerialSegmentOps<V, THIS>, NumericOps<V, THIS> {
  /** [(DOC)][toReal] Converts the profile to a linear profile, a.k.a. [Real] (no-op if it already was linear). */
  fun toReal(): Real
  /** [(DOC)][toNumbers] Converts the profile to a constant numbers profile, a.k.a. [Numbers] (no-op if it already was [Numbers]). */
  fun toNumbers(message: String? = null): Numbers<*>

  /**
   * [(DOC)][integrate] Calculates the integral of this profile, starting from zero.
   *
   * The result is scaled according to the [unit] argument. If a segment has a value of `1`,
   * and `unit` is [Duration.SECOND], the integral will increase at `1` per second.
   * But if (for the same segment) `unit` is [Duration.MINUTE], the integral will increase at `1` per minute
   * (`1/60` per second).
   *
   * In fancy math terms, `unit` is the length of the time basis vector, and the result is
   * contravariant with it.
   *
   * @param unit length of the time basis vector
   */
  fun integrate(unit: Duration = Duration.SECOND) =
      toNumbers("Cannot integrate a non-piecewise-constant linear profile.").unsafeOperate(::Real) { opts ->
        val segments = collect(opts)
        val result = mutableListOf<Segment<LinearEquation>>()
        val baseRate = Duration.SECOND.ratioOver(unit)
        var previousTime = opts.bounds.start
        var acc = 0.0
        for (segment in segments) {
          if (previousTime < segment.interval.start)
            throw Real.RealOpException("Cannot integrate a linear profile that has gaps (time $previousTime")
          val rate = segment.value.toDouble() * baseRate
          val nextAcc = acc + rate * segment.interval.duration().ratioOver(Duration.SECOND)
          result.add(Segment(segment.interval, LinearEquation(previousTime, acc, rate)))
          previousTime = segment.interval.end
          acc = nextAcc
        }
        result
      }

  /**
   * [(DOC)][shiftedDifference] Calculates the difference between this profile's value at [range] time in the future,
   * and this profile at the present.
   *
   * If this is a function `f(t)`, the result is `f(t+range) - f(t)`.
   */
  fun shiftedDifference(range: Duration): THIS
}
