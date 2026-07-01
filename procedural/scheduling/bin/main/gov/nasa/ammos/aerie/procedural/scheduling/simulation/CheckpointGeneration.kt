package gov.nasa.ammos.aerie.procedural.scheduling.simulation

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

/** Behavior for generating new checkpoints in simulation. */
sealed interface CheckpointGeneration {
  /** Generate a checkpoint at multiples of a regular period. */
  data class Periodic(/***/ val period: Duration): CheckpointGeneration

  /** Generate a checkpoint at a list of specific times. */
  data class AtTimes(/***/ val times: List<Duration>): CheckpointGeneration {
    /***/ constructor(/***/ vararg times: Duration): this(times.asList())
  }

  /** Generate a checkpoint at the end of the simulation. */
  data object AtEnd: CheckpointGeneration

  /** Do not generate any checkpoints. */
  data object None: CheckpointGeneration
}
