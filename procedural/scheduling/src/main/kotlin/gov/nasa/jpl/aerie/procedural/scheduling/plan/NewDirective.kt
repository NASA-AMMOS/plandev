package gov.nasa.jpl.aerie.procedural.scheduling.plan

import gov.nasa.jpl.aerie.timeline.payloads.activities.AnyDirective
import gov.nasa.jpl.aerie.timeline.payloads.activities.Directive
import gov.nasa.jpl.aerie.timeline.payloads.activities.DirectiveStart

data class NewDirective(
    val inner: AnyDirective,
    val name: String,
    val type: String,
    val start: DirectiveStart
) {
  fun resolve(id: Long, parent: Directive<*>?): Directive<AnyDirective> {
    if (start is DirectiveStart.Anchor) {
      if (parent == null) throw IllegalArgumentException("Parent must provided when anchor is not null")
      start.updateEstimate(parent.startTime + start.offset)
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
