package gov.nasa.jpl.aerie.procedural.constraints

import gov.nasa.jpl.aerie.timeline.CollectOptions
import gov.nasa.jpl.aerie.timeline.plan.SimulatedPlan

/** The interface that all constraints must satisfy. */
interface Constraint {
  /**
   * Run the constraint on a plan.
   *
   * The provided collect options are the options that the [Violations] result will be collected on after
   * the constraint is run. The constraint does not need to use the options unless it collects a timeline prematurely.
   *
   * @param plan the plan to check the constraint on
   * @param options the [CollectOptions] that the result will be collected with
   */
  fun run(plan: SimulatedPlan, options: CollectOptions): Violations
}
