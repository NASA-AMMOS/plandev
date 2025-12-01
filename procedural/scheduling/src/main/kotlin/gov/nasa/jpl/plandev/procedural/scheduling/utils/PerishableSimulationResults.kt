package gov.nasa.jpl.plandev.procedural.scheduling.utils

import gov.nasa.jpl.plandev.procedural.timeline.plan.SimulationResults

/** Simulation results whose staleness can be changed after creation. */
interface PerishableSimulationResults: SimulationResults {
  /***/ fun setStale(stale: Boolean)
}
