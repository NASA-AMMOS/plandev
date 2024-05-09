package gov.nasa.jpl.aerie.procedural.scheduling.simulation

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

/** Behavior for when the simulation should pause. */
sealed interface PauseBehavior {
  /** Pause after a given amount of time has elapsed. */
  data class AfterDuration(/***/ val dur: Duration): PauseBehavior

  /** Pause after a specific activity directive has finished. */
  data class AfterActivity(/** Id of the directive to wait for. */ val directive: Long): PauseBehavior

  /** Pause after all edits that were not included in the previous sim are simulated. */
  data object AfterNewEdits: PauseBehavior

  /** Do not pause; continue to the end of the plan. */
  data object AtEnd: PauseBehavior

  /** Pause at the earliest of a list of possible pause points. */
  data class EarliestOf(/***/ val pausePoints: List<PauseBehavior>): PauseBehavior

  /** Pause at the latest of a list of possible pause points. */
  data class LatestOf(/***/ val pausePoints: List<PauseBehavior>): PauseBehavior

  // very hard!
  // data class OnCondition(val condition: ???): PauseBehavior
}
