package gov.nasa.jpl.aerie.procedural.scheduling.simulation

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

sealed interface CheckpointGeneration {
  data class FixedPeriod(val period: Duration): CheckpointGeneration
  data class AtTimes(val times: List<Duration>): CheckpointGeneration {
    constructor(vararg times: Duration): this(times.asList())
  }
  data object AtEnd: CheckpointGeneration
  data object None: CheckpointGeneration
}
