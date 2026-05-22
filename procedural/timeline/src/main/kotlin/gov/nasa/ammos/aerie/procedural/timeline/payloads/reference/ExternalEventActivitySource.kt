package gov.nasa.ammos.aerie.procedural.timeline.payloads.reference

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive

data class ExternalEventActivitySource (
  override val v: ExternalEvent
): ActivitySource<ExternalEvent>(v)
