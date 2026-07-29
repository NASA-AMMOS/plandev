package gov.nasa.ammos.plandev.procedural.timeline.collections

import gov.nasa.ammos.plandev.procedural.timeline.BaseTimeline
import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.plandev.procedural.timeline.Timeline
import gov.nasa.ammos.plandev.procedural.timeline.ops.ActivityOps
import gov.nasa.ammos.plandev.procedural.timeline.util.preprocessList

/**
 * A timeline of activity directives.
 *
 * @param A the inner payload of the [Directive] type.
 */
data class Directives<A: Any>(private val timeline: Timeline<Directive<A>, Directives<A>>):
    Timeline<Directive<A>, Directives<A>> by timeline,
    ActivityOps<Directive<A>, Directives<A>>
{
  constructor(vararg directives: Directive<A>): this(directives.asList())
  constructor(directives: List<Directive<A>>): this(BaseTimeline(::Directives, preprocessList(directives, null)))
}
