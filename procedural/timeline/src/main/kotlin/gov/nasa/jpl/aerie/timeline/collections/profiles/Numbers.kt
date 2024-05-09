package gov.nasa.jpl.aerie.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.timeline.*
import gov.nasa.jpl.aerie.timeline.util.duration.unaryMinus
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.ops.SerialConstantOps
import gov.nasa.jpl.aerie.timeline.ops.numeric.PrimitiveNumberOps
import gov.nasa.jpl.aerie.timeline.ops.numeric.SerialNumericOps
import gov.nasa.jpl.aerie.timeline.payloads.LinearEquation
import gov.nasa.jpl.aerie.timeline.util.preprocessList
import java.lang.ArithmeticException
import kotlin.math.pow

/**
 * A profile of piece-wise constant numbers.
 *
 * Unlike [Real], this is not able to vary linearly. Instead,
 * it can contain either a homogeneous (and strictly-typed) collection of
 * any numeric type (i.e. `Numbers<Integer>` (Java) or `Numbers<Int>` (Kotlin)),
 * or a heterogeneous collection of all numeric types (i.e. `Numbers<Number>`).
 *
 * Unfortunately Kotlin/Java is not smart enough to keep the type information during binary operations.
 * So the sum of two `Numbers<Double>` objects will become `Numbers<Number>`, although no precision
 * will be lost.
 *
 * @see Number
 */
data class Numbers<N: Number>(private val timeline: Timeline<Segment<N>, Numbers<N>>):
    Timeline<Segment<N>, Numbers<N>> by timeline,
    SerialNumericOps<N, Numbers<N>>,
    PrimitiveNumberOps<N, Numbers<N>>,
    SerialConstantOps<N, Numbers<N>>
{
  constructor(v: N): this(Segment(Interval.MIN_MAX, v))
  constructor(vararg segments: Segment<N>): this(segments.asList())
  constructor(segments: List<Segment<N>>): this(BaseTimeline(::Numbers, preprocessList(segments, Segment<N>::valueEquals)))

  override fun toReal() = mapValues(::Real) { LinearEquation(it.value.toDouble()) }
  override fun toNumbers(message: String?) = this

  /*
  Due to the fact there is no superinterface for numbers that includes any arithmetic
  or comparison operators, AFAICT this giant if-else statement needs to be copy-pasted
  for each operation. If you can find a better way, please do.
   */

  /** Adds this and another primitive numeric profile. */
  operator fun plus(other: Numbers<*>) =
      map2Values(::Numbers, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() + r.toDouble()
        else if (l is Float || r is Float) l.toFloat() + r.toFloat()
        else if (l is Long || r is Long) l.toLong() + r.toLong()
        else if (l is Int || r is Int) l.toInt() + r.toInt()
        else if (l is Short || r is Short) l.toShort() + r.toShort()
        else if (l is Byte || r is Byte) l.toByte() + r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Adds this a constant number. */
  operator fun plus(n: Number) = plus(Numbers(n))
  /** Adds this and a linear profile. */
  operator fun plus(other: Real) = other + this

  /** Subtracts another primitive numeric profile from this. */
  operator fun minus(other: Numbers<*>) =
      map2Values(::Numbers, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() - r.toDouble()
        else if (l is Float || r is Float) l.toFloat() - r.toFloat()
        else if (l is Long || r is Long) l.toLong() - r.toLong()
        else if (l is Int || r is Int) l.toInt() - r.toInt()
        else if (l is Short || r is Short) l.toShort() - r.toShort()
        else if (l is Byte || r is Byte) l.toByte() - r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Subtracts a constant number from this. */
  operator fun minus(n: Number) = minus(Numbers(n))
  /** Subtracts a linear profile from this. */
  operator fun minus(other: Real) = -other + this

  /** Multiplies this and another primitive numeric profile. */
  operator fun times(other: Numbers<*>) =
      map2Values(::Numbers, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() * r.toDouble()
        else if (l is Float || r is Float) l.toFloat() * r.toFloat()
        else if (l is Long || r is Long) l.toLong() * r.toLong()
        else if (l is Int || r is Int) l.toInt() * r.toInt()
        else if (l is Short || r is Short) l.toShort() * r.toShort()
        else if (l is Byte || r is Byte) l.toByte() * r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Multiplies this by a constant number. */
  operator fun times(n: Number) = times(Numbers(n))
  /** Multiplies this by a linear profile. */
  operator fun times(other: Real) = other * this

  /** Calculates this divided by another primitive numeric profile. */
  operator fun div(other: Numbers<*>) =
      map2Values(::Numbers, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() / r.toDouble()
        else if (l is Float || r is Float) l.toFloat() / r.toFloat()
        else if (l is Long || r is Long) l.toLong() / r.toLong()
        else if (l is Int || r is Int) l.toInt() / r.toInt()
        else if (l is Short || r is Short) l.toShort() / r.toShort()
        else if (l is Byte || r is Byte) l.toByte() / r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Divides this by a constant number. */
  operator fun div(n: Number) = div(Numbers(n))
  /** Divides this by a linear profile. */
  operator fun div(other: Real) = this / other.toNumbers("Cannot divide by a non-piecewise-constant divisor.")

  /**
   * Calculates this raised to the power of another primitive numeric profile.
   *
   * Both profiles are converted to doubles first.
   */
  infix fun pow(exp: Numbers<*>) =
      map2Values(::Numbers, exp) { l, r, _ ->
        l.toDouble().pow(r.toDouble())
      }

  /** Raises this to the power of a constant number. */
  infix fun pow(n: Number) = pow(Numbers(n))
  /** Raises this to the power of a linear profile. */
  infix fun pow(other: Real) = this pow other.toNumbers("Cannot apply a non-piecewise-constant exponent.")

  /** Returns a [Booleans] that is true when this is less than another primitive numeric profile. */
  infix fun lessThan(other: Numbers<*>) =
      map2Values(::Booleans, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() < r.toDouble()
        else if (l is Float || r is Float) l.toFloat() < r.toFloat()
        else if (l is Long || r is Long) l.toLong() < r.toLong()
        else if (l is Int || r is Int) l.toInt() < r.toInt()
        else if (l is Short || r is Short) l.toShort() < r.toShort()
        else if (l is Byte || r is Byte) l.toByte() < r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Returns a [Booleans] that is true when this is less than a constant number. */
  infix fun lessThan(n: Number) = lessThan(Numbers(n))
  /** Returns a [Booleans] that is true when this is less than a linear profile. */
  infix fun lessThan(other: Real) = other greaterThan this

  /** Returns a [Booleans] that is true when this is less than or equal to another primitive numeric profile. */
  infix fun lessThanOrEqualTo(other: Numbers<*>) =
      map2Values(::Booleans, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() <= r.toDouble()
        else if (l is Float || r is Float) l.toFloat() <= r.toFloat()
        else if (l is Long || r is Long) l.toLong() <= r.toLong()
        else if (l is Int || r is Int) l.toInt() <= r.toInt()
        else if (l is Short || r is Short) l.toShort() <= r.toShort()
        else if (l is Byte || r is Byte) l.toByte() <= r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Returns a [Booleans] that is true when this is less than or equal to a constant number. */
  infix fun lessThanOrEqual(n: Number) = lessThanOrEqualTo(Numbers(n))
  /** Returns a [Booleans] that is true when this is less than or equal to a linear profile. */
  infix fun lessThanOrEqualTo(other: Real) = other greaterThanOrEqualTo this

  /** Returns a [Booleans] that is true when this is greater than another primitive numeric profile. */
  infix fun greaterThan(other: Numbers<*>) =
      map2Values(::Booleans, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() > r.toDouble()
        else if (l is Float || r is Float) l.toFloat() > r.toFloat()
        else if (l is Long || r is Long) l.toLong() > r.toLong()
        else if (l is Int || r is Int) l.toInt() > r.toInt()
        else if (l is Short || r is Short) l.toShort() > r.toShort()
        else if (l is Byte || r is Byte) l.toByte() > r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Returns a [Booleans] that is true when this is greater than a constant number. */
  infix fun greaterThan(n: Number) = greaterThan(Numbers(n))
  /** Returns a [Booleans] that is true when this is greater than a linear profile. */
  infix fun greaterThan(other: Real) = other lessThan this

  /** Returns a [Booleans] that is true when this is greater than or equal to another primitive numeric profile. */
  infix fun greaterThanOrEqualTo(other: Numbers<*>) =
      map2Values(::Booleans, other) { l, r, _ ->
        if (l is Double || r is Double) l.toDouble() >= r.toDouble()
        else if (l is Float || r is Float) l.toFloat() >= r.toFloat()
        else if (l is Long || r is Long) l.toLong() >= r.toLong()
        else if (l is Int || r is Int) l.toInt() >= r.toInt()
        else if (l is Short || r is Short) l.toShort() >= r.toShort()
        else if (l is Byte || r is Byte) l.toByte() >= r.toByte()
        else throw PrimitiveNumberOps.UnreachablePrimitiveNumberException()
      }

  /** Returns a [Booleans] that is true when this is greater than or equal to a constant number. */
  infix fun greaterThanOrEqual(n: Number) = greaterThanOrEqualTo(Numbers(n))
  /** Returns a [Booleans] that is true when this is greater than or equal to a linear profile. */
  infix fun greaterThanOrEqualTo(other: Real) = other lessThanOrEqualTo this

  // This unchecked cast is OK because the difference between two primitives of different type
  // will never be a third type.
  @Suppress("UNCHECKED_CAST")
  override fun shiftedDifference(range: Duration) = (shift(-range) - this) as Numbers<N>

  /***/ companion object {
    /**
     * Converts a list of serialized value segments into a [Numbers] profile;
     * for use with [gov.nasa.jpl.aerie.timeline.plan.Plan.resource].
     *
     * Prefers converting to longs if possible, and falls back to doubles if not.
     */
    @JvmStatic fun deserialize(list: List<Segment<SerializedValue>>) = Numbers(list.map { seg ->
      val bigDecimal = seg.value.asNumeric().orElseThrow { Exception("value was not numeric: $seg") }
      val number: Number = try {
        bigDecimal.longValueExact()
      } catch (e: ArithmeticException) {
        bigDecimal.toDouble()
      }
      seg.withNewValue(number)
    })
  }
}
