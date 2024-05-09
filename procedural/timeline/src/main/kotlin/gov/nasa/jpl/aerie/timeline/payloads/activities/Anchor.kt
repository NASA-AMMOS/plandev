package gov.nasa.jpl.aerie.timeline.payloads.activities

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

sealed interface DirectiveStart {
  fun atNewTime(time: Duration): DirectiveStart

  data class Anchor(
      val parentId: Long,
      val offset: Duration,
      val anchorPoint: AnchorPoint,
      var estimatedStart: Duration
  ): DirectiveStart {
    enum class AnchorPoint {
      Start, End;

      companion object {
        fun anchorToStart(b: Boolean) = if (b) Start else End
      }
    }

    constructor(parentId: Long, offset: Duration, anchorPoint: AnchorPoint): this(parentId, offset, anchorPoint, Duration.ZERO)

    fun updateEstimate(d: Duration) {
      estimatedStart = d
    }
    override fun atNewTime(time: Duration) = Anchor(parentId, offset + time - estimatedStart, anchorPoint, time)
  }

  data class Absolute(val time: Duration): DirectiveStart {
    override fun atNewTime(time: Duration) = Absolute(time)
  }
}
