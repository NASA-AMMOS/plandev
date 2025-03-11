package gov.nasa.ammos.aerie.procedural.utils

import gov.nasa.ammos.aerie.procedural.scheduling.utils.PerishableSimulationResults
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults

/**
 * A wrapper around [SimulationResults] objects to make them implement
 * [PerishableSimResultsWrapper]. Used by [TypeUtilsEditablePlanAdapter] internally.
 */
class PerishableSimResultsWrapper(
  private val simulationResults: SimulationResults,
  private var stale: Boolean = false
): PerishableSimulationResults, SimulationResults by simulationResults {
  override fun setStale(stale: Boolean) {
    this.stale = stale
  }

  override fun isStale() = this.stale
}
