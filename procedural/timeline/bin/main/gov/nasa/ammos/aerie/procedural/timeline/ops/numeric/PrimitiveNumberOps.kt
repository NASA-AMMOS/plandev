package gov.nasa.ammos.aerie.procedural.timeline.ops.numeric

import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Numbers
import gov.nasa.ammos.aerie.procedural.timeline.ops.ConstantOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.LinearEquation
import kotlin.math.absoluteValue

/** Operations for timelines of primitive numbers. */
interface PrimitiveNumberOps<N: Number, THIS: PrimitiveNumberOps<N, THIS>>: NumericOps<N, THIS>, ConstantOps<N, THIS> {
  override fun N.toLinear() = LinearEquation(this.toDouble())

  /** [(DOC)][toDoubles] Converts all numbers in the profile to doubles. */
  fun toDoubles() = mapValues(::Numbers) { it.value.toDouble() }
  /** [(DOC)][toFloats] Converts all numbers in the profile to floats. */
  fun toFloats() = mapValues(::Numbers) { it.value.toFloat() }
  /** [(DOC)][toLongs] Converts all numbers in the profile to longs. */
  fun toLongs() = mapValues(::Numbers) { it.value.toLong() }
  /** [(DOC)][toInts] Converts all numbers in the profile to ints. */
  fun toInts() = mapValues(::Numbers) { it.value.toInt() }
  /** [(DOC)][toShorts] Converts all numbers in the profile to shorts. */
  fun toShorts() = mapValues(::Numbers) { it.value.toShort() }
  /** [(DOC)][toBytes] Converts all numbers in the profile to bytes. */
  fun toBytes() = mapValues(::Numbers) { it.value.toByte() }

  @Suppress("UNCHECKED_CAST")
  override fun negate() = mapValues {
    when(it.value) {
      is Double -> -it.value as N
      is Float -> -it.value as N
      is Long -> -it.value as N
      is Int -> -it.value as N
      is Short -> -it.value as N
      is Byte -> -it.value as N
      else -> throw UnreachablePrimitiveNumberException()
    }
  }

  @Suppress("UNCHECKED_CAST")
  override fun abs() = mapValues {
    when(it.value) {
      is Double -> it.value.absoluteValue as N
      is Float -> it.value.absoluteValue as N
      is Long -> it.value.absoluteValue as N
      is Int -> it.value.absoluteValue as N

      // .absoluteValue does not exist for shorts and bytes for some reason
      is Short -> (if (it.value < 0) -it.value else it.value) as N
      is Byte -> (if (it.value < 0) -it.value else it.value) as N
      else -> throw UnreachablePrimitiveNumberException()
    }
  }

  /** @suppress */
  class UnreachablePrimitiveNumberException: Exception("internal error. not all numeric types were accounted for")
}
