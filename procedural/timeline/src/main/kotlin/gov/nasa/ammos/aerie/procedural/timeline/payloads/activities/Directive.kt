package gov.nasa.ammos.aerie.procedural.timeline.payloads.activities

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.payloads.reference.ActivitySource
import gov.nasa.jpl.aerie.types.ActivityDirectiveId

/** A wrapper of any type of activity directive containing common data. */
data class Directive<A: Any>(
  /** The inner payload, typically either [AnyDirective] or a mission model activity type. */
  @JvmField val inner: A,

  /** The name of this specific directive. */
  @JvmField val name: String?,

  /** The directive id. */
  @JvmField val id: ActivityDirectiveId,

  override val type: String,

  /** The start behavior for this directive. */
  val start: DirectiveStart,

  /** The activity's source(s), if scheduled. */
  var activitySources: List<ActivitySource<*>> = listOf()
): Activity<Directive<A>> {

  // because Java doesn't natively support default arguments
  constructor(inner: A, name: String, id: ActivityDirectiveId, type: String, start: DirectiveStart):
      this(inner, name, id, type, start, listOf())


  override val startTime: Duration
    get() = when (start) {
      is DirectiveStart.Absolute -> start.time
      is DirectiveStart.Anchor -> start.estimatedStart
    }

  override val interval: Interval
    get() = Interval.at(startTime)

  override fun withNewInterval(i: Interval): Directive<A> {
    if (i.isPoint()) return Directive(inner, name, id, type, start.atNewTime(i.start), listOf())
    else throw Exception("Cannot change directive time to a non-instantaneous interval.")
  }

  /** Transform the inner payload with a function, returning a new directive object. */
  fun <R: Any> mapInner(/***/ f: (A) -> R) = Directive(
    f(inner),
    name,
    id,
    type,
    start,
    listOf()
  )
}
