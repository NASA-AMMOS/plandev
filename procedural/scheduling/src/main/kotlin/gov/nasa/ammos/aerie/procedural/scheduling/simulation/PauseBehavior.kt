package gov.nasa.ammos.aerie.procedural.scheduling.simulation

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan

/** Behavior for when the simulation should pause. */
sealed interface PauseBehavior {
  /**
   * @suppress
   *
   * Currently this function is to convert the pause behavior into a concrete time,
   * but this will need to be refactored to a different way of pausing to support
   * pausing after activities.
   */
  fun resolve(plan: Plan): Duration

  /** Pause after a given amount of time has elapsed. */
  data class AfterDuration(/***/ val dur: Duration): PauseBehavior {
    override fun resolve(plan: Plan) = dur
  }

//  /** Pause after a specific activity directive has finished. */
//  data class AfterActivity(/** Id of the directive to wait for. */ val directive: Long): PauseBehavior

//  /** Pause after all edits that were not included in the previous sim are simulated. */
//  data object AfterNewEdits: PauseBehavior

  /** Do not pause; continue to the end of the plan. */
  data object AtEnd: PauseBehavior {
    override fun resolve(plan: Plan) = plan.totalBounds().end
  }

  /** Pause at the earliest of a list of possible pause points. */
  data class EarliestOf(/***/ val pausePoints: List<PauseBehavior>): PauseBehavior {
    init {
      assert(pausePoints.isNotEmpty())
    }

    /***/ constructor(vararg pausePoints: PauseBehavior): this(pausePoints.asList())
    override fun resolve(plan: Plan) = pausePoints.minOf { it.resolve(plan) }
  }

  /** Pause at the latest of a list of possible pause points. */
  data class LatestOf(/***/ val pausePoints: List<PauseBehavior>): PauseBehavior {
    init {
      assert(pausePoints.isNotEmpty())
    }

    /***/ constructor(vararg pausePoints: PauseBehavior): this(pausePoints.asList())
    override fun resolve(plan: Plan) = pausePoints.maxOf { it.resolve(plan) }
  }

  // very hard!
  // data class OnCondition(val condition: ???): PauseBehavior
}
