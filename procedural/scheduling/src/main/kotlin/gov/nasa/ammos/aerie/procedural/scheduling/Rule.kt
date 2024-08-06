package gov.nasa.ammos.aerie.procedural.scheduling

import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan

/** The interface that all scheduling rules must satisfy. */
interface Rule {
  /**
   * Run the rule.
   *
   * @param plan A plan representation that can be edited and simulated.
   */
  fun run(plan: EditablePlan)
}
