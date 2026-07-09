package gov.nasa.jpl.aerie.scheduler.simulation;

/**
 * describes how simulations are reused between simulation calls made by the scheduler
 * <p>
 * simulation results are expensive to compute, so it is advantageous to recycle any still-relevant
 * parts of available prior simulations if possible. for example, for a plan that had only a small
 * change inserted at time T, the section of previously simulated results prior to T could serve as
 * a starting point for a modified simulation versus starting at t=0.
 * <p>
 * the caching of prior results might be persistent in the database or in volatile memory on an agent
 */
public enum SchedulerSimulationReuseStrategy {

  /**
   * stores temporal prefix simulation results at several time points in the plan that can then be reused
   * as starting points for subsequent requests for varying suffix simulations
   */
  Checkpoint,

  /**
   * stores a chain/tree of previous simulation results tracking the causal structure of cell observation
   * and modification to allow resimulation of only those parts of a modified plan that could have changed
   */
  Incremental
}
