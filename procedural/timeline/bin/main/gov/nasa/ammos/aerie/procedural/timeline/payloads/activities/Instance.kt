package gov.nasa.ammos.aerie.procedural.timeline.payloads.activities

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.types.ActivityDirectiveId
import gov.nasa.jpl.aerie.types.ActivityInstanceId

/** A wrapper of any type of activity instance containing common data. */
data class Instance<A: Any>(
  /** The inner payload, typically either [AnyInstance] or a mission model activity type. */
  @JvmField val inner: A,
  override val type: String,

  /** The instance id. */
  @JvmField val id: ActivityInstanceId,

  /**
   * The maybe-null id of the directive associated with this instance.
   *
   * Will be `null` if this is a child activity.
   */
  @JvmField val directiveId: ActivityDirectiveId?,

  /**
   * The maybe-null instance id of the instance that spawned this instance.
   *
   * Will be `null` if this is not a child activity.
   */
  @JvmField val parentId: ActivityInstanceId?,
  override val interval: Interval,
): Activity<Instance<A>> {
  override val startTime: Duration
    get() = interval.start

  override fun withNewInterval(i: Interval) = Instance(inner, type, id, directiveId, parentId, i)
}
