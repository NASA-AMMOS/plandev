package gov.nasa.ammos.aerie.procedural.constraints

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.jpl.aerie.types.ActivityId

/** A single violation of a constraint. */
data class Violation @JvmOverloads constructor(
    /** Interval on which the violation occurs. */
    override val interval: Interval,

    /** Violation message to be displayed to user. */
    val message: String? = null,

    /** List of associated activities (directives or instances) that are related to the violation. */
    val ids: List<ActivityId> = listOf()
) : IntervalLike<Violation> {

  override fun withNewInterval(i: Interval) = Violation(i, message, ids)

  /** Constructs a violation on the same interval with a different list of ids. */
  fun withNewIds(vararg id: ActivityId) = Violation(interval, message, id.asList())

  /** Constructs a violation on the same interval with a different list of ids. */
  fun withNewIds(ids: List<ActivityId>) = Violation(interval, message, ids)
}
