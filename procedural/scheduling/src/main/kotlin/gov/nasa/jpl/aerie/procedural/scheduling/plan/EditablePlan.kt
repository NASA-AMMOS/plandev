package gov.nasa.jpl.aerie.procedural.scheduling.plan

import gov.nasa.jpl.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.jpl.aerie.timeline.plan.Plan
import gov.nasa.jpl.aerie.timeline.plan.SimulationResults

interface EditablePlan: Plan {
  fun latestResults(): SimulationResults?

  fun create(directive: NewDirective): Long
  fun commit()
  fun rollback(): List<Edit>

  fun simulate(options: SimulateOptions): SimulationResults
  fun simulate() = simulate(SimulateOptions())
}
