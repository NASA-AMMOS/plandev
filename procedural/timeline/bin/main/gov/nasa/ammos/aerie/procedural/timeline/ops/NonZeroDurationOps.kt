package gov.nasa.ammos.aerie.procedural.timeline.ops

import gov.nasa.ammos.aerie.procedural.timeline.CollectOptions
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.div
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.util.truncateList

/**
 * Operations mixin for timelines of activity directives.
 *
 * Used for both generic directives, and specific directive types from the mission model.
 */
interface NonZeroDurationOps<T: IntervalLike<T>, THIS: NonZeroDurationOps<T, THIS>>: GeneralOps<T, THIS> {
  /**
   * [(DOC)][split] Splits payload objects into a variable number of equally sized pieces.
   *
   * The caller provides a function which, for each object, decides how many pieces it should be split into.
   * If it returns `1`, the object will be unchanged.
   *
   * @param f a function that decides how many pieces each object should be split into
   *
   * @throws SplitException if [f] returns a number less than `1`,
   *                        or a number greater than the number of microseconds contained in the object's interval
   */
  fun split(f: (T) -> Int) = unsafeOperate { opts ->
    val result = collect(CollectOptions(opts.bounds, false)).flatMap {
      val numPieces = f(it)
      val interval = it.interval
      if (numPieces == 1) listOf(it)
      else if (numPieces < 1) throw SplitException("Cannot split an interval into less than 1 piece (time ${interval.start}")
      else if (interval.isPoint()) throw SplitException("Cannot split an interval with no duration (time ${interval.start}")
      else {
        val integerWidth = interval.duration() / Duration.EPSILON
        val width = interval.duration() / numPieces.toLong()

        if (integerWidth < numPieces)
          throw SplitException("Cannot split an interval only $integerWidth microseconds long into $numPieces pieces (time ${interval.start}")

        var currentTime = interval.start.plus(width)
        val result = mutableListOf<T>()

        result.add(it.withNewInterval(Interval.between(interval.start, currentTime, interval.startInclusivity, Exclusive)))
        for (i in 1 ..< numPieces - 1) {
          val nextTime = currentTime.plus(width)
          result.add(it.withNewInterval(Interval.between(currentTime, nextTime, Exclusive)))
          currentTime = nextTime
        }
        result.add(it.withNewInterval(Interval.between(currentTime, interval.end, Exclusive, interval.endInclusivity)))

        result
      }
    }
    truncateList(result, opts, false, false)
  }

  /** @see [split] */
  class SplitException(message: String): Exception(message)
}
