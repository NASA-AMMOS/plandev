package gov.nasa.ammos.aerie.procedural.constraints

import gov.nasa.ammos.aerie.procedural.timeline.BaseTimeline
import gov.nasa.ammos.aerie.procedural.timeline.BoundsTransformer
import gov.nasa.ammos.aerie.procedural.timeline.Timeline
import gov.nasa.ammos.aerie.procedural.timeline.collections.Universal
import gov.nasa.ammos.aerie.procedural.timeline.collections.Windows
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real
import gov.nasa.ammos.aerie.procedural.timeline.ops.*
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceNoOp
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance
import gov.nasa.ammos.aerie.procedural.timeline.util.preprocessList
import gov.nasa.jpl.aerie.types.ActivityId

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

  /**
   * Sets a default violation message for violations that don't already have one.
   *
   * @param message the default message to give to the user
   */
  fun withDefaultMessage(message: String) = unsafeMap(BoundsTransformer.IDENTITY, false) {
    if (it.message == null) Violation(it.interval, message, it.ids)
    else it
  }

  /***/ companion object {
    /** Creates a [Violations] object that violates when the profile equals a given value. */
    @JvmStatic fun <V: Any> on(tl: SerialConstantOps<V, *>, v: V) = onAll(tl.isolateEqualTo(v))

    /** Creates a [Violations] object that violates when this profile equals a given value. */
    @JvmStatic fun on(tl: Real, n: Number) = on(tl.equalTo(n), true)

    /**
     * Creates a [Violations] object that violates on every object in the timeline.
     *
     * If the object is an activity, it will record the directive or instance id.
     */
    @JvmStatic fun <I: IntervalLike<I>> onAll(tl: ParallelOps<I, *>) =
        tl.unsafeMap(::Violations, BoundsTransformer.IDENTITY, false) {
          Violation(
              it.interval,
              null,
              listOfNotNull(it.getActivityId())
          )
        }

    /** Creates a [Violations] object that violates inside each interval. */
    @JvmStatic fun inside(tl: Windows) = onAll(tl.unsafeCast(::Universal))
    /** Creates a [Violations] object that violates outside each interval. */
    @JvmStatic fun outside(tl: Windows) = inside(tl.complement())

    /**
     * Creates a [Violations] object from two timelines, that violates whenever they have overlap.
     *
     * If either object is an activity, it will record the directive or instance id.
     */
    @JvmStatic fun <V: IntervalLike<V>, W: IntervalLike<W>> whenSimultaneous(left: GeneralOps<V, *>, right: GeneralOps<W, *>) =
        left.unsafeMap2(::Violations, right) { l, r, i -> Violation(
            i,
            null,
            listOfNotNull(
                l.getActivityId(),
                r.getActivityId()
            )
        ) }

    private fun <V: IntervalLike<V>> V.getActivityId(): ActivityId? = when (this) {
      is Instance<*> -> if (directiveId != null) directiveId else id
      is Directive<*> -> id
      else -> null
    }
  }
}


