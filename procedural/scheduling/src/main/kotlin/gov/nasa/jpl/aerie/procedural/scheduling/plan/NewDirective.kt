package gov.nasa.jpl.aerie.procedural.scheduling.plan

import gov.nasa.jpl.aerie.timeline.payloads.activities.AnyDirective
import gov.nasa.jpl.aerie.timeline.payloads.activities.Directive
import gov.nasa.jpl.aerie.timeline.payloads.activities.DirectiveStart

/** A new directive to be created, which doesn't have an id yet. */
data class NewDirective(
    /** The activity's arguments. */
    val inner: AnyDirective,

    /** The name of the activity. */
    val name: String,

    /** The activity type. */
    val type: String,

    /** The activity's start behavior. */
    val start: DirectiveStart
) {
  /**
   * Resolves this activity into a proper [Directive] object.
   *
   * Users likely don't need to use this function; it is more for implementations of
   * [EditablePlan].
   *
   * @param id The id for the new directive.
   * @param parent The activity this activity is anchored to, if applicable.
   */
  fun resolve(id: Long, parent: Directive<*>?): Directive<AnyDirective> {
    if (start is DirectiveStart.Anchor) {
      if (parent == null) throw IllegalArgumentException("Parent must provided when anchor is not null")
      start.estimatedStart = parent.startTime + start.offset
    }
    return Directive(
        inner,
        name,
        id,
        type,
        start
    )
  }
}
