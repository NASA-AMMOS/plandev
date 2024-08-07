package gov.nasa.ammos.aerie.procedural.scheduling.simulation

/** Configuration for simulation. */
/* data */ class SimulateOptions(
    // The following two options will be uncommented when checkpoint simulation is released.
//    val checkPointGeneration: CheckpointGeneration = CheckpointGeneration.None,
//    val checkpointRetention: CheckpointRetention = CheckpointRetention.All,
    val pause: PauseBehavior = PauseBehavior.AtEnd,
)
