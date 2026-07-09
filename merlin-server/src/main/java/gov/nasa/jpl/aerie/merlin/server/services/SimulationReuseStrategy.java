package gov.nasa.jpl.aerie.merlin.server.services;

/**
 * describes how simulations are reused between simulation calls
 * <p>
 * simulation results are expensive to compute, so it is advantageous to recycle any still-relevant
 * parts of available prior simulations if possible. for example, for a plan that had only a small
 * change inserted at time T, the section of previously simulated results prior to T could serve as
 * a starting point for a modified simulation versus starting at t=0.
 * <p>
 * the caching of prior results might be persistent in the database or in volatile memory on an agent
 */
public enum SimulationReuseStrategy {

  //maybe an option for none to force resimulation (currently handled in MerlinBindings/CachedSimulationService)

  /**
   * stores the results from prior simulations so that exactly matching requests can be served back with the
   * same results immediately without any resimulation
   */
  CachedResults,

  /**
   * stores a chain/tree of previous simulation results tracking the causal structure of cell observation
   * and modification to allow resimulation of only those parts of a modified plan that could have changed
   */
  Incremental
}
