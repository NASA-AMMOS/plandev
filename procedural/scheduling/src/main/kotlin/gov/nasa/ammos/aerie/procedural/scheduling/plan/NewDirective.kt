package gov.nasa.ammos.aerie.procedural.scheduling.plan

import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.jpl.aerie.merlin.driver.ActivityDirectiveId

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
  fun resolve(id: ActivityDirectiveId, parent: Directive<*>?) = Directive(
      inner,
      name,
      id,
      type,
      when (start) {
        is DirectiveStart.Absolute -> start
        is DirectiveStart.Anchor -> {
          if (parent == null) throw IllegalArgumentException("Parent must provided when anchor is not null")
          DirectiveStart.Anchor(
              parent.id,
              start.offset,
              start.anchorPoint,
              parent.startTime + start.offset
          )
        }
      }
  )
}
