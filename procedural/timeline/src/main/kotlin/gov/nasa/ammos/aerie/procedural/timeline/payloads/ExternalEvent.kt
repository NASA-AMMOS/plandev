package gov.nasa.ammos.aerie.procedural.timeline.payloads

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue

/** An external event instance. */
data class ExternalEvent(
  /** The string name of this event. */
  @JvmField
  val key: String,
  /** The type of the event. */
  @JvmField
  val type: String,
  /** The source this event comes from. */
  @JvmField
  val source: ExternalSource,
  /** The attributes of the event. */
  @JvmField
  val attributes: Map<String, SerializedValue>,
  override val interval: Interval,
): IntervalLike<ExternalEvent> {
  override fun withNewInterval(i: Interval) = ExternalEvent(key, type, source, attributes, i)
}
