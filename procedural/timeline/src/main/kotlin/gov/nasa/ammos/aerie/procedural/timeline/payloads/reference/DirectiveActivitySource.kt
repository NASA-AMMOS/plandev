package gov.nasa.ammos.aerie.procedural.timeline.payloads.reference

import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive

data class DirectiveActivitySource (
  override val v: Pair<Directive<AnyDirective>, Long>
): ActivitySource<Pair<Directive<AnyDirective>, Long>>(v)
