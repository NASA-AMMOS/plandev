package gov.nasa.ammos.aerie.procedural.scheduling

import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy

/**
 * Whether (and how) a scheduling goal should delete its previous outputs
 * before running.
 */
sealed interface ActivityAutoDelete {
  /**
   * Delete the previous outputs at the beginning of scheduling, so no prior goals can interact with them.
   */
  data class AtBeginning(
    /** How to deal with activities anchored to the previous outputs. */
    val anchorStrategy: DeletedAnchorStrategy,
    /** Whether the scheduler should simulate after deleting the activities, and before the first goal runs. */
    val simulateAfter: Boolean
  ): ActivityAutoDelete

  /**
   * Delete the previous outputs in the middle of scheduling, just before this goal runs, so prior goals can
   * interact with those past outputs.
   *
   * This option doesn't have `simulateAfter` like [AtBeginning]. If you want that behavior, just simulate
   * at the beginning of the goal with `plan.simulate()`.
   */
  data class JustBefore(
    /** How to deal with activities anchored to the previous outputs. */
    val anchorStrategy: DeletedAnchorStrategy
  ): ActivityAutoDelete

  /**
   * Don't delete previous outputs. If you want your goal to not keep producing
   * extra activities on each run (i.e. idempotence), you have to do it yourself.
   */
  data object No: ActivityAutoDelete
}
