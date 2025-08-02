package gov.nasa.ammos.aerie.procedural.timeline.collections

import gov.nasa.ammos.aerie.procedural.timeline.Timeline
import gov.nasa.ammos.aerie.procedural.timeline.BaseTimeline
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance
import gov.nasa.ammos.aerie.procedural.timeline.ops.*
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceNoOp
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource
import gov.nasa.ammos.aerie.procedural.timeline.util.preprocessList

/**
 * A timeline of external events.
 */
data class ExternalEvents(private val timeline: Timeline<ExternalEvent, ExternalEvents>):
    Timeline<ExternalEvent, ExternalEvents> by timeline,
    NonZeroDurationOps<ExternalEvent, ExternalEvents>,
    ParallelOps<ExternalEvent, ExternalEvents>
{
  constructor(vararg events: ExternalEvent): this(events.asList())
  constructor(events: List<ExternalEvent>): this(BaseTimeline(::ExternalEvents, preprocessList(events, null)))

  /** Filter by one or more types. */
  fun filterByType(vararg types: String) = filter { it.type in types }

  /** Filter by one or more event sources. */
  fun filterBySource(vararg sources: ExternalSource) = filter { it.source in sources }

  /** Filter by one or more derivation groups. */
  fun filterByDerivationGroup(vararg groups: String) = filter { it.source.derivationGroup in groups }
}
