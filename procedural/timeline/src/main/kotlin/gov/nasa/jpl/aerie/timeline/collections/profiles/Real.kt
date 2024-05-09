package gov.nasa.jpl.aerie.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.timeline.*
import gov.nasa.jpl.aerie.timeline.ops.numeric.LinearOps
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.ops.numeric.SerialNumericOps
import gov.nasa.jpl.aerie.timeline.payloads.LinearEquation
import gov.nasa.jpl.aerie.timeline.payloads.transpose
import gov.nasa.jpl.aerie.timeline.util.preprocessList
import gov.nasa.jpl.aerie.timeline.util.truncateList
import gov.nasa.jpl.aerie.timeline.util.duration.unaryMinus
import kotlin.jvm.optionals.getOrNull
import kotlin.math.pow

/** A profile of [LinearEquations][LinearEquation]; a piece-wise linear real-number profile. */
data class Real(private val timeline: Timeline<Segment<LinearEquation>, Real>):
    Timeline<Segment<LinearEquation>, Real> by timeline,
    SerialNumericOps<LinearEquation, Real>,
    LinearOps<Real>
{
  constructor(v: Int): this(v.toDouble())
  constructor(v: Long): this(v.toDouble())
  constructor(v: Double): this(LinearEquation(v))
  constructor(eq: LinearEquation): this(Segment(Interval.MIN_MAX, eq))
  constructor(vararg segments: Segment<LinearEquation>): this(segments.asList())
  constructor(segments: List<Segment<LinearEquation>>): this(BaseTimeline(::Real, preprocessList(segments, Segment<LinearEquation>::valueEquals)))

  override fun toReal() = this
  override fun LinearEquation.toLinear() = this

  /**
   * Converts this to a primitive number profile (i.e. [Numbers]), throwing an error if this is not piece-wise constant.
   *
   * @param message error message to throw if this is not piece-wise constant.
   */
  override fun toNumbers(message: String?) = mapValues(::Numbers) {
    if (it.value.isConstant()) it.value.initialValue
    else if (message == null) throw RealOpException("Cannot convert a non-piecewise-constant linear equation to a constant number. (at time ${it.interval.start})")
    else throw RealOpException("$message (at time ${it.interval.start})")
  }

  /** Adds this and another numeric profile. */
  operator fun plus(other: SerialNumericOps<*, *>) = map2Values(other.toReal()) { l, r, _ ->
    val shiftedRight = r.shiftInitialTime(l.initialTime)
    LinearEquation(l.initialTime, l.initialValue + shiftedRight.initialValue, l.rate + r.rate)
  }

  /** Adds a constant number to this. */
  operator fun plus(n: Number) = plus(Numbers(n))

  /** Subtracts another numeric profile from this. */
  operator fun minus(other: SerialNumericOps<*, *>) = map2Values(other.toReal()) { l, r, _ ->
    val shiftedRight = r.shiftInitialTime(l.initialTime)
    LinearEquation(l.initialTime, l.initialValue - shiftedRight.initialValue, l.rate - r.rate)
  }

  /** Subtracts a constant number from this. */
  operator fun minus(n: Number) = minus(Numbers(n))

  /**
   * Multiplies this and another numeric profile.
   *
   * @throws RealOpException if both profiles have non-zero rate at the same time.
   */
  operator fun times(other: SerialNumericOps<*, *>) = map2Values(other.toReal()) { l, r, i ->
    if (!l.isConstant() && !r.isConstant()) throw RealOpException("Cannot multiply two linear equations that are non-constant at the same time (at time ${i.start})")
    val shiftedRight = r.shiftInitialTime(l.initialTime)
    val newRate = l.rate * shiftedRight.initialValue + r.rate * l.initialValue
    LinearEquation(l.initialTime, l.initialValue * shiftedRight.initialValue, newRate)
  }

  /** Multiplies this by a constant number. */
  operator fun times(n: Number) = times(Numbers(n))

  /**
   * Calculates this divided by another numeric profile.
   *
   * @throws RealOpException if the divisor has a non-zero rate at any time that the dividend is defined.
   */
  operator fun div(other: SerialNumericOps<*, *>) = map2Values(other.toReal()) { l, r, i ->
    if (!r.isConstant()) throw RealOpException("Cannot divide by a non-piecewise-constant linear equation (at time ${i.start})")
    LinearEquation(l.initialTime, l.initialValue / r.initialValue, l.rate / r.initialValue)
  }

  /** Calculates this divided by a constant number. */
  operator fun div(n: Number) = div(Numbers(n))

  /**
   * Calculates this raised to the power of another numeric profile.
   *
   * @throws RealOpException if the exponent has a non-zero rate at any time that the base is defined,
   *                                 or if the base has a non-zero rate at any time that the exponent is defined and not
   *                                 either 0 or 1.
   */
  infix fun pow(exp: SerialNumericOps<*, *>) = map2Values(exp.toReal()) { l, r, i ->
    if (!r.isConstant()) throw RealOpException("Cannot apply a non-piecewise-constant exponent (at time ${i.start}")
    if (r.initialValue == 0.0) LinearEquation(1.0)
    else if (r.initialValue == 1.0) l
    else if (!l.isConstant()) throw RealOpException("Cannot apply an exponent to a non-piecewise-constant profile")
    else LinearEquation(l.initialValue.pow(r.initialValue))
  }

  /** Calculates this raised to the power of a constant number. */
  infix fun pow(n: Number) = pow(Numbers(n))

  /** Returns a [Booleans] that is true when this and another numeric profile are equal. */
  infix fun equalTo(other: SerialNumericOps<*, *>) = inequalityHelper(other, LinearEquation::intervalsEqualTo)
  /** Returns a [Booleans] that is true when this equals a constant number. */
  infix fun equalTo(n: Number) = equalTo(Numbers(n))

  /** Returns a [Booleans] that is true when this and another numeric profile are not equal. */
  infix fun notEqualTo(other: SerialNumericOps<*, *>) = inequalityHelper(other, LinearEquation::intervalsNotEqualTo)
  /** Returns a [Booleans] that is true when this does not equal a constant number. */
  infix fun notEqualTo(n: Number) = notEqualTo(Numbers(n))

  /** Returns a [Booleans] that is true when this is less than another numeric profile. */
  infix fun lessThan(other: SerialNumericOps<*, *>) = inequalityHelper(other, LinearEquation::intervalsLessThan)
  /** Returns a [Booleans] that is true when this is less than a constant number. */
  infix fun lessThan(n: Number) = lessThan(Numbers(n))

  /** Returns a [Booleans] that is true when this is less than or equal to another numeric profile. */
  infix fun lessThanOrEqualTo(other: SerialNumericOps<*, *>) = inequalityHelper(other, LinearEquation::intervalsLessThanOrEqualTo)
  /** Returns a [Booleans] that is true when this is less than or equal to a constant number. */
  infix fun lessThanOrEqualTo(n: Number) = lessThanOrEqualTo(Numbers(n))

  /** Returns a [Booleans] that is true when this is greater than another numeric profile. */
  infix fun greaterThan(other: SerialNumericOps<*, *>) = inequalityHelper(other, LinearEquation::intervalsGreaterThan)
  /** Returns a [Booleans] that is true when this is greater than a constant number. */
  infix fun greaterThan(n: Number) = greaterThan(Numbers(n))

  /** Returns a [Booleans] that is true when this is greater than or equal to another numeric profile. */
  infix fun greaterThanOrEqualTo(other: SerialNumericOps<*, *>) = inequalityHelper(other, LinearEquation::intervalsGreaterThanOrEqualTo)
  /** Returns a [Booleans] that is true when this is greater than or equal to a constant number. */
  infix fun greaterThanOrEqualTo(n: Number) = greaterThanOrEqualTo(Numbers(n))

  private fun inequalityHelper(other: SerialNumericOps<*, *>, f: LinearEquation.(LinearEquation) -> Booleans) =
      flatMap2Values(::Booleans, other.toReal()) { l, r, _ -> l.f(r) }


  override fun changes() =
      unsafeOperate(::Booleans) { opts ->
        val bounds = opts.bounds
        var previous: Segment<LinearEquation>? = null
        val result = collect(CollectOptions(bounds, false)).flatMap { currentSegment: Segment<LinearEquation> ->
          val currentInterval = currentSegment.interval
          val leftEdge = if (
              previous !== null &&
              previous!!.interval.compareEndToStart(currentInterval) == 0 &&
              currentInterval.includesStart()
          ) {
            previous!!.value.valueAt(currentInterval.start) == currentSegment.value.valueAt(currentInterval.start)
          } else if (currentInterval.compareStarts(bounds) == 0) {
            currentSegment.value.rate != 0.0
          } else {
            null
          }
          previous = currentSegment
          listOfNotNull(
              Segment(Interval.at(currentInterval.start), leftEdge).transpose(),
              Segment(Interval.between(currentInterval.start, currentInterval.end, Interval.Inclusivity.Exclusive), currentSegment.value.rate != 0.0)
          )
        }
        truncateList(result, opts)
      }

  /**
   * Returns a [Booleans] that is true whenever this discontinuously transitions between
   * a specific pair of values, and false or gap everywhere else.
   */
  fun transitions(from: Double, to: Double) = detectEdges(NullBinaryOperation.cases(
      { l, i -> if (l.valueAt(i.start) == from) null else false },
      { r, i -> if (r.valueAt(i.start) == to) null else false },
      { l, r, i -> l.valueAt(i.start) == from && r.valueAt(i.start) == to }
  ))

  override fun shiftedDifference(range: Duration) = shift(-range).minus(this)

  /**
   * An exception for linear profile operations; usually thrown in contexts that
   * require one or more of the operands to be piecewise constant.
   */
  class RealOpException(message: String): Exception(message)

  /***/ companion object {
    /**
     * Converts a list of serialized value segments into a real profile; for use with [gov.nasa.jpl.aerie.timeline.plan.Plan.resource].
     *
     * Accepts either a map with the form `{initial: number, rate: number}`, or just a plain number for piecewise constant profiles.
     * While plain numbers are acceptable and will be converted to a [LinearEquation] without warning, consider using [Numbers]
     * to keep the precision.
     */
    @JvmStatic fun deserialize(list: List<Segment<SerializedValue>>): Real {
      val converted: List<Segment<LinearEquation>> = list.map { s ->
        s.value.asReal().getOrNull()?.let { return@map s.withNewValue(LinearEquation(it)) }
        val map = s.value.asMap().orElseThrow { RealDeserializeException("value was not a map or plain number: $s") }
        val initialValue = (map["initial"]
            ?: throw RealDeserializeException("initial value not found in map"))
            .asReal().orElseThrow { RealDeserializeException("initial value was not a double") }
        val rate = (map["rate"] ?: throw RealDeserializeException("rate not found in map"))
            .asReal().orElseThrow { RealDeserializeException("rate was not a double") }
        s.withNewValue(LinearEquation(s.interval.start, initialValue, rate))
      }
      return Real(converted)
    }

    /***/ class RealDeserializeException(message: String): Exception(message)
  }
}
