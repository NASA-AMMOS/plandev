package gov.nasa.jpl.aerie.timeline.payloads.activities

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

/** Start behavior for an activity directive. */
sealed interface DirectiveStart {
  /** Shift the activity so that it starts at the new time (may be approximate for anchored activities). */
  fun atNewTime(time: Duration): DirectiveStart

  /** For activities that start at a known, grounded time. */
  data class Absolute(/***/ val time: Duration): DirectiveStart {
    override fun atNewTime(time: Duration) = Absolute(time)
  }

  /** For activities that are anchored to another activity. */
  data class Anchor @JvmOverloads constructor(
      /** Id of the parent it is anchored to. */
      val parentId: Long,

      /** Duration to offset from the parent (negative durations mean before the parent). */
      val offset: Duration,

      /** Which end of the parent to anchor to. */
      val anchorPoint: AnchorPoint,

      /**
       * When the activity is estimated to start (approximate, and automatically set by [EditablePlan] implementations).
       *
       * Defaults to [Duration.ZERO] (plan start).
       */
      var estimatedStart: Duration = Duration.ZERO
  ): DirectiveStart {

    /** Which end of the parent to anchor to. */
    enum class AnchorPoint {
      /***/ Start,
      /***/ End;

      /***/ companion object {
        /** Helper function; returns [Start] if `true`, [End] if `false`. */
        fun anchorToStart(b: Boolean) = if (b) Start else End
      }
    }

    override fun atNewTime(time: Duration) = Anchor(parentId, offset + time - estimatedStart, anchorPoint, time)
  }
}
