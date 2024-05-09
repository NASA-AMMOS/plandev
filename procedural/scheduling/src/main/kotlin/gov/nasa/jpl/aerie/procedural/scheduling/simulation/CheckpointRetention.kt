package gov.nasa.jpl.aerie.procedural.scheduling.simulation

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

/**
 * Behavior for retaining generated checkpoints in memory.
 *
 * Checkpoints can be very large; for large models maybe only a few can fit
 * in memory at a time.
 */
sealed interface CheckpointRetention {
  /** Retain all checkpoints. */
  data object All: CheckpointRetention
  /** Retain only the latest checkpoint. Replace it with each new checkpoint. */
  data object Latest: CheckpointRetention
  /** Retain all checkpoints in within a range from the current simulation time. */
  data class DurationFromPresent(/***/ val dur: Duration): CheckpointRetention
}
