package gov.nasa.ammos.aerie.procedural.timeline.payloads

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Booleans
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.div
import java.util.function.BiFunction
import kotlin.math.abs
import kotlin.math.absoluteValue

/** A linear equation in point-slope form. */
data class LinearEquation(
    /** The time of the start point. */
    @JvmField val initialTime: Duration,
    /** The value of the start point. */
    @JvmField val initialValue: Double,
    /** The rate of change, in units per second. */
    @JvmField val rate: Double
) {
  /** Creates a constant linear equation at a given value. */
  constructor(constant: Number): this(Duration.ZERO, constant.toDouble(), 0.0)

  /** Calculates the value at a given time. */
  fun valueAt(time: Duration): Double {
    val change = rate * time.ratioOver(Duration.SECOND) - rate * initialTime.ratioOver(Duration.SECOND)
    return initialValue + change
  }

  /** Returns an equivalent equation that is represented at a different start time. */
  fun shiftInitialTime(newInitialTime: Duration) = LinearEquation(
      newInitialTime,
      initialValue + newInitialTime.minus(initialTime).ratioOver(Duration.SECOND) * rate,
      rate
  )

  /***/
  fun isConstant() = rate == 0.0

  private fun intersectionPointWith(other: LinearEquation): Duration? {
    if (rate == other.rate) return null

    /*
    Floating point noise can cause rates to be extremely near zero, when they should
    have been exactly zero. For example: `0.1 + 0.2 - 0.1 - 0.2 != 0`.

    This can cause the denominator below to be tiny, leading to long overflow later.
     */

    // If the following causes an exception, something really has gone wrong, and we don't want to catch it.
    val numSeconds = (other.valueAt(initialTime) - initialValue) / (rate - other.rate)

    // Check if numSeconds is too big before putting it in a long.
    return if (abs(numSeconds) > Long.MAX_VALUE.toDouble() / (Duration.SECOND / Duration.MICROSECOND)) null
    else initialTime.plus(Duration.roundNearest(numSeconds, Duration.SECOND))
  }

  /** Calculates when this is less than another linear equation, as a [Booleans] object. */
  infix fun intervalsLessThan(other: LinearEquation): Booleans {
    return getInequalityIntervals(other) { l: Double, r: Double -> l < r }
  }

  /** Calculates when this is less than or equal to another linear equation, as a [Booleans] object. */
  infix fun intervalsLessThanOrEqualTo(other: LinearEquation): Booleans {
    return getInequalityIntervals(other) { l: Double, r: Double -> l <= r }
  }

  /** Calculates when this is greater than another linear equation, as a [Booleans] object. */
  infix fun intervalsGreaterThan(other: LinearEquation): Booleans {
    return getInequalityIntervals(other) { l: Double, r: Double -> l > r }
  }

  /** Calculates when this is greater than or equal to another linear equation, as a [Booleans] object. */
  infix fun intervalsGreaterThanOrEqualTo(other: LinearEquation): Booleans {
    return getInequalityIntervals(other) { l: Double, r: Double -> l >= r }
  }

  private fun getInequalityIntervals(
      other: LinearEquation,
      op: BiFunction<Double, Double, Boolean>
  ): Booleans {
    val intersection = intersectionPointWith(other)
    return if (intersection === null) {
      val resultEverywhere = op.apply(initialValue, other.valueAt(initialTime))
      Booleans(resultEverywhere)
    } else {
      val oneSecondBefore = intersection.minus(Duration.SECOND)
      val oneSecondAfter = intersection.plus(Duration.SECOND)
      Booleans(
          Segment(
              Interval.betweenClosedOpen(Duration.MIN_VALUE, intersection),
              op.apply(this.valueAt(oneSecondBefore), other.valueAt(oneSecondBefore))
          ),
          Segment(
              Interval.at(intersection),
              op.apply(this.valueAt(intersection), other.valueAt(intersection))
          ),
          Segment(
              Interval.between(intersection, Duration.MAX_VALUE, Exclusive, Inclusive),
              op.apply(this.valueAt(oneSecondAfter), other.valueAt(oneSecondAfter))
          )
      )
    }
  }

  /** Calculates when this is equal to another linear equation, as a [Booleans] object. */
  fun intervalsEqualTo(other: LinearEquation): Booleans {
    val intersection = intersectionPointWith(other)
    return if (intersection === null) {
      Booleans(initialValue == other.valueAt(initialTime))
    } else {
      Booleans(
          Segment(Interval.betweenClosedOpen(Duration.MIN_VALUE, intersection), false),
          Segment(Interval.at(intersection), true),
          Segment(Interval.between(intersection, Duration.MAX_VALUE, Exclusive, Inclusive), false)
      )
    }
  }

  /** Calculates when this is not equal to another linear equation, as a [Booleans] object. */
  fun intervalsNotEqualTo(other: LinearEquation): Booleans {
    return !intervalsEqualTo(other)
  }

  /** Finds the time that this equation is zero, or `null` if it does not cross the axis. */
  fun findRoot() = if (rate == 0.0) null else initialTime - Duration.roundNearest(initialValue / rate, Duration.SECOND)

  /** Calculates the absolute value of this equation, as a real profile. */
  fun abs() =
      if (isConstant()) Real(LinearEquation(initialTime, initialValue.absoluteValue, 0.0))
      else {
        val root = findRoot()!!
        Real(
            Segment(Interval.betweenClosedOpen(Duration.MIN_VALUE, root), LinearEquation(root, 0.0, -rate.absoluteValue)),
            Segment(Interval.between(root, Duration.MAX_VALUE), LinearEquation(root, 0.0, rate.absoluteValue))
        )
      }

  /***/
  override fun toString(): String {
    return String.format(
        """{
  Initial Time: %s
  Initial Value: %s
  Rate: %s
}""",
        initialTime,
        initialValue,
        rate
    )
  }

  /***/
  override fun equals(other: Any?) =
      if (other !is LinearEquation) false
      else initialValue == other.valueAt(initialTime) && rate == other.rate

  /***/
  override fun hashCode(): Int {
    var result = initialTime.hashCode()
    result = 31 * result + initialValue.hashCode()
    result = 31 * result + rate.hashCode()
    return result
  }
}
