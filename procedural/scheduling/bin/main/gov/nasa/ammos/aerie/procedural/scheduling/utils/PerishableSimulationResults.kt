package gov.nasa.ammos.aerie.procedural.scheduling.utils

import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults

/** Simulation results whose staleness can be changed after creation. */
interface PerishableSimulationResults: SimulationResults {
  /***/ fun setStale(stale: Boolean)
}
