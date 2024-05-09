package gov.nasa.jpl.aerie.procedural.scheduling.simulation

/** Configuration for simulation. */
/* data */ class SimulateOptions(
    // The following two options will be uncommented when checkpoint simulation is released.
//    val checkPointGeneration: CheckpointGeneration = CheckpointGeneration.None,
//    val checkpointRetention: CheckpointRetention = CheckpointRetention.All,

    // TODO This option will be uncommented before MVP release. (see `feat/procedural-scheduling` branch)
//    val pause: PauseBehavior = PauseBehavior.AtEnd,
)
