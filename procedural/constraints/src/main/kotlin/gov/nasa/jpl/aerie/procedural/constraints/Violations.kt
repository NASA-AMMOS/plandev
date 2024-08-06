package gov.nasa.jpl.aerie.procedural.constraints

import gov.nasa.jpl.aerie.procedural.constraints.ActivityId.DirectiveId
import gov.nasa.jpl.aerie.procedural.constraints.ActivityId.InstanceId
import gov.nasa.ammos.aerie.procedural.timeline.BaseTimeline
import gov.nasa.ammos.aerie.procedural.timeline.BoundsTransformer
import gov.nasa.ammos.aerie.procedural.timeline.Timeline
import gov.nasa.ammos.aerie.procedural.timeline.collections.Intervals
import gov.nasa.ammos.aerie.procedural.timeline.collections.Windows
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real
import gov.nasa.ammos.aerie.procedural.timeline.ops.*
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceNoOp
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance
import gov.nasa.ammos.aerie.procedural.timeline.util.preprocessList

/** A timeline of [Violations][Violation]. */
data class Violations(private val timeline: Timeline<Violation, Violations>):
    Timeline<Violation, Violations> by timeline,
    ParallelOps<Violation, Violations>,
    NonZeroDurationOps<Violation, Violations>,
    CoalesceNoOp<Violation, Violations>
{
  constructor(vararg violation: Violation): this(violation.asList())
  constructor(violations: List<Violation>): this(BaseTimeline(::Violations, preprocessList(violations, null)))

  /**
   * Maps the list of associated activity ids on each violation.
   *
   * @param f a function which takes a [Violation] and returns a new list of ids.
   */
  fun mapIds(f: (Violation) -> List<ActivityId>) = unsafeMap(BoundsTransformer.IDENTITY, false) { it.withNewIds(f(it)) }

  /***/ companion object {
    /** Creates a [Violations] object that violates when this profile equals a given value. */
    @JvmStatic fun <V: Any> SerialConstantOps<V, *>.violateOn(v: V) = isolateEqualTo(v).violations()

    /** Creates a [Violations] object that violates when this profile equals a given value. */
    @JvmStatic fun Real.violateOn(n: Number) = equalTo(n).violateOn(true)

    /**
     * Creates a [Violations] object that violates on every object in the timeline.
     *
     * If the object is an activity, it will record the directive or instance id.
     */
    @JvmStatic fun <I: IntervalLike<I>> ParallelOps<I, *>.violations() =
        unsafeMap(::Violations, BoundsTransformer.IDENTITY, false) {
          Violation(
              it.interval,
              listOfNotNull(it.getActivityId())
          )
        }

    /** Creates a [Violations] object that violates inside each interval. */
    @JvmStatic fun Windows.violateInside() = unsafeCast(::Intervals).violations()
    /** Creates a [Violations] object that violates outside each interval. */
    @JvmStatic fun Windows.violateOutside() = complement().violateInside()

    /**
     * Creates a [Violations] object from two timelines, that violates whenever they have overlap.
     *
     * If either object is an activity, it will record the directive or instance id.
     */
    @JvmStatic infix fun <V: IntervalLike<V>, W: IntervalLike<W>> GeneralOps<V, *>.mutex(other: GeneralOps<W, *>) =
        unsafeMap2(::Violations, other) { l, r, i -> Violation(
            i,
            listOfNotNull(
                l.getActivityId(),
                r.getActivityId()
            )
        )}

    private fun <V: IntervalLike<V>> V.getActivityId() = when (this) {
      is Instance<*> -> InstanceId(id)
      is Directive<*> -> DirectiveId(id)
      else -> null
    }
  }
}


