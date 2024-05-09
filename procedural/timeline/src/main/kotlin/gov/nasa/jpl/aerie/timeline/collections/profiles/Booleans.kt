package gov.nasa.jpl.aerie.timeline.collections.profiles

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.timeline.*
import gov.nasa.jpl.aerie.timeline.util.duration.unaryMinus
import gov.nasa.jpl.aerie.timeline.ops.BooleanOps
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.ops.SerialConstantOps
import gov.nasa.jpl.aerie.timeline.payloads.LinearEquation
import gov.nasa.jpl.aerie.timeline.util.preprocessList

/** A profile of booleans. */
data class Booleans(private val timeline: Timeline<Segment<Boolean>, Booleans>):
    Timeline<Segment<Boolean>, Booleans> by timeline,
    SerialConstantOps<Boolean, Booleans>,
    BooleanOps<Booleans>
{
  constructor(v: Boolean): this(Segment(Interval.MIN_MAX, v))
  constructor(vararg segments: Segment<Boolean>): this(segments.asList())
  constructor(segments: List<Segment<Boolean>>): this(BaseTimeline(::Booleans, preprocessList(segments, Segment<Boolean>::valueEquals)))

  /** Computes the AND operation between two boolean profiles. */
  infix fun and(other: Booleans) = map2OptionalValues(other, NullBinaryOperation.cases(
      { l, _ -> if (l) null else false },
      { r, _ -> if (r) null else false },
      { l, r, _ -> l && r }
  ))

  /** Computes the OR operation between two boolean profiles. */
  infix fun or(other: Booleans) = map2OptionalValues(other, NullBinaryOperation.cases(
      { l, _ -> if (l) true else null },
      { r, _ -> if (r) true else null },
      { l, r, _ -> l || r }
  ))

  /** Computes the XOR operation between two boolean profiles. */
  infix fun xor(other: Booleans) = map2Values(other) { l, r, _ -> l xor r }

  /** Computes the NOR operation between two boolean profiles. */
  infix fun nor(other: Booleans) = map2OptionalValues(other, NullBinaryOperation.cases(
      { l, _ -> if (l) false else null },
      { r, _ -> if (r) false else null },
      { l, r, _ -> !(l || r) }
  ))

  /** Computes the NAND operation between two boolean profiles. */
  infix fun nand(other: Booleans) = map2OptionalValues(other, NullBinaryOperation.cases(
      { l, _ -> if (l) null else true },
      { r, _ -> if (r) null else true },
      { l, r, _ -> !(l && r) }
  ))

  /**
   * Shifts the rising and falling edges of a boolean profile independently of each other.
   *
   * This allows for segments to not just be shifted around, but stretched or squished, or even
   * deleted.
   *
   * A rising edge is defined as the time just after a `false` segment ends - whether it meets a `true`
   * segment or a gap. Similarly, a falling edge is just after a `true` segment ends.
   *
   * @param shiftRising duration to shift the rising edges by
   * @param shiftFalling duration to shift the rising edges by
   */
  fun shiftEdges(shiftRising: Duration, shiftFalling: Duration) =
      unsafeMapIntervals(
          { i ->
            Interval.between(
                Duration.min(Duration.saturatingAdd(i.start, -shiftRising), Duration.saturatingAdd(i.start, -shiftFalling)),
                Duration.max(Duration.saturatingAdd(i.end, -shiftRising), Duration.saturatingAdd(i.end, -shiftFalling)),
                i.startInclusivity,
                i.endInclusivity
            )
          },
          true
      ) { t ->
        if (t.value) t.interval.shiftBy(shiftRising, shiftFalling)
        else t.interval.shiftBy(shiftFalling, shiftRising)
      }

  /**
   * Creates a Real profile corresponding to the running total of time
   * that this profile has spent `true`.
   *
   * @param unit base unit of time to count. As in, the resulting real profile will increase by
   *             `1` for each `unit` duration spent in the `true` state.
   *
   * @see gov.nasa.jpl.aerie.timeline.ops.numeric.SerialNumericOps.integrate for further explanation of [unit].
   */
  fun accumulatedTrueDuration(unit: Duration) =
      mapValues(::Real) { LinearEquation(if (it.value) 1.0 else 0.0) }.integrate(unit)

  /**
   * Calculates the sum of durations of true segments in a range leading the current time.
   *
   * This returns a real profile that equals, at each time `t`, the duration of true segments in the interval `[t, t+range]`.
   *
   * Real profiles can't actually represent durations, only unitless numbers, so the result is actually calculated
   * as a multiple of the provided [unit].
   *
   * Because this is a serial profile, the duration of true segments in the look-ahead range can't exceed [range] itself.
   * So the result is bounded by `[0, range/unit]`
   *
   * @param range how far into the future to look
   * @param unit the time basis vector of the result; the unit of time that the result counts.
   */
  fun rollingTrueDuration(range: Duration, unit: Duration) =
      accumulatedTrueDuration(unit).shiftedDifference(range)

  /** Detects when this transitions from false to true. */
  fun risingEdges() = transitions(from = false, to = true)

  /** Detects when this transitions from false to true. */
  fun fallingEdges() = transitions(from = true, to = false)

  /***/ companion object {
    /**
     * Converts a list of serialized value segments into a [Booleans] profile;
     * for use with [gov.nasa.jpl.aerie.timeline.plan.Plan.resource].
     */
    @JvmStatic fun deserialize(list: List<Segment<SerializedValue>>) = Booleans(list.map { it.withNewValue(it.value.asBoolean().get()) })
  }
}
