package gov.nasa.jpl.aerie.timeline.plan

/** A connection to Aerie's database for a particular simulation result. */
data class SimulatedPlan(
    private val plan: Plan,
    private val simResults: SimulationResults
): Plan by plan, SimulationResults by simResults
