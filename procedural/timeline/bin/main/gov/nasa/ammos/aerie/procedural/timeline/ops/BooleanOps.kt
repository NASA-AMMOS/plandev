package gov.nasa.ammos.aerie.procedural.timeline.ops

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Universal
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo

/**
 * Operations mixin for timelines of booleans.
 *
 * Currently only used by [gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Booleans], but could be used for
 * parallel boolean profiles in the future.
 */
interface BooleanOps<THIS: BooleanOps<THIS>>: ConstantOps<Boolean, THIS> {
  /** [(DOC)][not] Applies the unary NOT operation. */
  operator fun not() = mapValues { !it.value }

  /** [(DOC)][falsifyByDuration] Falsifies any `true` segments with durations outside the given interval. */
  fun falsifyByDuration(validInterval: Interval) =
      mapValues { it.value && it.interval.duration() in validInterval }

  /** [(DOC)][falsifyShorterThan] Falsifies any `true` segments with durations shorter than the given duration. */
  fun falsifyShorterThan(dur: Duration) = falsifyByDuration(dur .. Duration.MAX_VALUE)
  /** [(DOC)][falsifyLongerThan] Falsifies any `true` segments with durations longer than the given duration. */
  fun falsifyLongerThan(dur: Duration) = falsifyByDuration(Duration.MIN_VALUE .. dur)

  /** [(DOC)][isolateTrue] Creates an [Universal] object with intervals whenever this profile is `true`. */
  fun isolateTrue() = isolateEqualTo(true)

  /** [(DOC)][isolateFalse] Creates an [Universal] object with intervals whenever this profile is `false`. */
  fun isolateFalse() = isolateEqualTo(false)

  /** [(DOC)][highlightTrue] Creates an [Windows] object that highlights whenever this profile is `true`. */
  fun highlightTrue() = highlightEqualTo(true)

  /** [(DOC)][highlightFalse] Creates an [Windows] object that highlights whenever this profile is `false`. */
  fun highlightFalse() = highlightEqualTo(false)

  /** [(DOC)][splitTrue] Splits `true` segments into the given number of pieces (leaving `false` unchanged). */
  fun splitTrue(numPieces: Int) = splitEqualTo(true, numPieces)

  /** [(DOC)][splitFalse] Splits `false` segments into the given number of pieces (leaving `true` unchanged). */
  fun splitFalse(numPieces: Int) = splitEqualTo(false, numPieces)
}
