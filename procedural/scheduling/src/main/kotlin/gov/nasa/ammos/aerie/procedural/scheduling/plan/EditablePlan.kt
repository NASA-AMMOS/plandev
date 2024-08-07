package gov.nasa.ammos.aerie.procedural.scheduling.plan

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.aerie.merlin.driver.ActivityDirectiveId

/** A plan representation that can be edited and simulated. */
interface EditablePlan: Plan {
  /** Get the latest simulation results. */
  fun latestResults(): SimulationResults?

  /**
   * Create a new activity.
   *
   * @param directive a directive without a directive id.
   * @return a long, the directive id this activity will have.
   */
  fun create(directive: NewDirective): ActivityDirectiveId

  /** A simplified version of [create] with minimal arguments. */
  fun create(
      type: String,
      start: DirectiveStart,
      arguments: Map<String, SerializedValue>
  ) = create(NewDirective(
      AnyDirective(arguments),
      "Unnamed Activity",
      type,
      start
  ))

  /** Commit plan edits, making them final. */
  fun commit()

  /**
   * Roll back uncommitted edits.
   *
   * @return the list of rolled back edits.
   */
  fun rollback(): List<Edit>

  /**
   * Simulate the current plan, including committed and uncommitted changes.
   *
   * @param options configurations for the simulation.
   */
  fun simulate(options: SimulateOptions): SimulationResults

  /** A simplified version of [simulate] which uses the default configuration. */
  fun simulate() = simulate(SimulateOptions())
}
