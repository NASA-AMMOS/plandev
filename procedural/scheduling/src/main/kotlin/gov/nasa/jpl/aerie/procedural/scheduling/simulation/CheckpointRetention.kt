package gov.nasa.jpl.aerie.procedural.scheduling.simulation

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

sealed interface CheckpointRetention {
  data object All: CheckpointRetention
  data object Latest: CheckpointRetention
  data class DurationFromPresent(val dur: Duration): CheckpointRetention
}
