package gov.nasa.jpl.aerie.procedural.scheduling.simulation

import gov.nasa.jpl.aerie.timeline.Duration

sealed interface PauseBehavior {
  data class AfterDuration(val dur: Duration): PauseBehavior
  data class AfterActivity(val directive: Long): PauseBehavior
  data object AfterNewEdits: PauseBehavior
  data object AtEnd: PauseBehavior

  data class EarliestOf(val pausePoints: List<PauseBehavior>): PauseBehavior
  data class LatestOf(val pausePoints: List<PauseBehavior>): PauseBehavior

  // very hard!
  // data class OnCondition(val condition: ???): PauseBehavior
}
