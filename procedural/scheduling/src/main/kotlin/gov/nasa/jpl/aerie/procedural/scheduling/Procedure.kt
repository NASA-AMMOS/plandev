package gov.nasa.jpl.aerie.procedural.scheduling

import gov.nasa.jpl.aerie.procedural.scheduling.plan.EditablePlan
import gov.nasa.jpl.aerie.timeline.CollectOptions

/** The interface that all scheduling procedures must satisfy. */
interface Procedure {
  /**
   * Run the procedure.
   *
   * @param plan A plan representation that can be edited and simulated.
   */
  fun run(plan: EditablePlan)
}
