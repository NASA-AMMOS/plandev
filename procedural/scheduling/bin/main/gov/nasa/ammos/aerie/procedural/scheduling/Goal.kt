package gov.nasa.ammos.aerie.procedural.scheduling

import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults

/** The interface that all scheduling rules must satisfy. */
interface Goal {
  /**
   * Whether the scheduler should delete this goal's past created activities.
   *
   * Default implementation returns [ActivityAutoDelete.No]. Override this method
   * to specify otherwise and choose a strategy for deleted anchors.
   *
   * This method may be called multiple times during the scheduling run, and must return the
   * same result every time. All calls to this method and [run] during a scheduling run
   * will be performed on the same object instance.
   */
  fun shouldDeletePastCreations(plan: Plan, simResults: SimulationResults?): ActivityAutoDelete = ActivityAutoDelete.No

  /**
   * Run the rule.
   *
   * @param plan A plan representation that can be edited and simulated.
   */
  fun run(plan: EditablePlan)
}
