package gov.nasa.jpl.aerie.procedural.scheduling.plan

import gov.nasa.jpl.aerie.timeline.payloads.activities.AnyDirective
import gov.nasa.jpl.aerie.timeline.payloads.activities.Directive

sealed interface Edit {
  data class Create(val directive: Directive<AnyDirective>): Edit
}
