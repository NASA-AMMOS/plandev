package gov.nasa.jpl.aerie.timeline.plan

import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.timeline.Duration
import gov.nasa.jpl.aerie.timeline.Interval.Companion.between
import gov.nasa.jpl.aerie.timeline.Interval.Inclusivity.*
import gov.nasa.jpl.aerie.timeline.payloads.Segment
import gov.nasa.jpl.aerie.timeline.payloads.activities.Instance
import gov.nasa.jpl.aerie.timeline.collections.Instances
import gov.nasa.jpl.aerie.timeline.ops.coalesce.CoalesceSegmentsOp
import java.io.StringReader
import java.sql.PreparedStatement
import javax.json.Json
import kotlin.jvm.optionals.getOrNull

/** A connection to Aerie's database for a particular simulation result. */
data class SimulatedPlan(
    private val plan: Plan,
    private val simResults: SimulationResults
): Plan by plan, SimulationResults by simResults
