package gov.nasa.jpl.aerie.timeline.payloads.activities

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.timeline.Interval

/** A wrapper of any type of activity directive containing common data. */
data class Directive<A: Any>(
    /** The inner payload, typically either [AnyDirective] or a mission model activity type. */
    @JvmField val inner: A,

    /** The name of this specific directive. */
    @JvmField val name: String,

    /** The directive id. */
    @JvmField val id: Long,

    override val type: String,

    /** The start behavior for this directive. */
    val start: DirectiveStart,
): Activity<Directive<A>> {
  override val startTime: Duration
    get() = when (start) {
      is DirectiveStart.Absolute -> start.time
      is DirectiveStart.Anchor -> start.estimatedStart
    }

  override val interval: Interval
    get() = Interval.at(startTime)

  override fun withNewInterval(i: Interval): Directive<A> {
    if (i.isPoint()) return Directive(inner, name, id, type, start.atNewTime(i.start))
    else throw Exception("Cannot change directive time to a non-instantaneous interval.")
  }

  /** Transform the inner payload with a function, returning a new directive object. */
  fun <R: Any> mapInner(/***/ f: (A) -> R) = Directive(
      f(inner),
      name,
      id,
      type,
      start
  )
}
