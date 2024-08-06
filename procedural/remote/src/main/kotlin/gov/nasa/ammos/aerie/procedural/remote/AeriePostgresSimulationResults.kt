package gov.nasa.ammos.aerie.procedural.remote

import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Companion.between
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Exclusive
import gov.nasa.ammos.aerie.procedural.timeline.Interval.Inclusivity.Inclusive
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances
import gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce.CoalesceSegmentsOp
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import java.io.StringReader
import java.sql.Connection
import javax.json.Json
import kotlin.jvm.optionals.getOrNull

/**
 * A connection to Aerie's database for a particular simulation result.
 *
 * @param c A connection to Aerie's database
 * @param simDatasetId The simulation dataset id to query for (this is different from the dataset id)
 * @param plan a plan object representing the plan associated with this simulation dataset
 * @param stale whether these results are not up-to-date
 */
data class AeriePostgresSimulationResults(
    /**  */
    private val c: Connection,
    /** The particular simulation dataset to query. */
    private val simDatasetId: Int,
    private val plan: Plan,
    private val stale: Boolean
): SimulationResults {

  private val datasetId by lazy {
    val statement = c.prepareStatement("select dataset_id from merlin.simulation_dataset where id = ?;")
    statement.setInt(1, simDatasetId)
    getSingleLongQueryResult(statement)
  }

  private val simulationId by lazy {
    val statement = c.prepareStatement("select simulation_id from merlin.simulation_dataset where id = ?;")
    statement.setInt(1, simDatasetId)
    getSingleLongQueryResult(statement)
  }

  private val simulationInfo by lazy {
    val statement = c.prepareStatement("select simulation_start_time, simulation_end_time from merlin.simulation where id = ?;")
    statement.setLong(1, simulationId)
    val response = statement.executeQuery()
    if (!response.next()) throw DatabaseError("Expected exactly one result for query, found none: $statement")
    val result = object {
      val startTime = response.getTimestamp(1).toInstant()
      val endTime = response.getTimestamp(2).toInstant()
    }
    if (response.next()) throw DatabaseError("Expected exactly one result for query, found more than one: $statement")
    result
  }

  override fun simBounds() = between(
      plan.toRelative(simulationInfo.startTime),
      plan.toRelative(simulationInfo.endTime),
  )

  private val profileInfoStatement = c.prepareStatement(
      "select id, duration from merlin.profile where dataset_id = ? and name = ?;"
  )
  private data class ProfileInfo(val id: Int, val duration: Duration)

  private val segmentsStatement = c.prepareStatement(
      "select start_offset, dynamics, is_gap from merlin.profile_segment where profile_id = ? and dataset_id = ? order by start_offset asc;"
  )

  /***/ class DatabaseError(message: String): Error(message)

  override fun <V : Any, TL : CoalesceSegmentsOp<V, TL>> resource(name: String, deserializer: (List<Segment<SerializedValue>>) -> TL): TL {
    val profileInfo = getProfileInfo(name)

    segmentsStatement.clearParameters()
    segmentsStatement.setInt(1, profileInfo.id)
    segmentsStatement.setLong(2, datasetId)
    intervalStyleStatement(c).execute()
    val response = segmentsStatement.executeQuery()

    val result = mutableListOf<Segment<SerializedValue>>()

    var previousValue: SerializedValue? = null
    var previousStart: Duration? = null

    while (response.next()) {
      val thisStart = Duration.parseISO8601(response.getString(1))
      if (previousStart !== null) {
        val interval = between(previousStart, thisStart, Inclusive, Exclusive)
        val newSegment = Segment(
            interval,
            previousValue!!
        )
        result.add(newSegment)
      }
      if (!response.getBoolean(3)) { // if not gap
        val serializedValue = parseJson(response.getString(2))
        previousValue = serializedValue
        previousStart = thisStart
      } else {
        previousValue = null
        previousStart = null
      }
    }
    if (previousStart !== null) {
      val interval = between(previousStart, profileInfo.duration, Inclusive, Exclusive)
      result.add(
          Segment(
              interval,
              previousValue!!
          )
      )
    }
    return deserializer(result)
  }

  private fun getProfileInfo(name: String): ProfileInfo {
    profileInfoStatement.clearParameters()
    profileInfoStatement.setLong(1, datasetId)
    profileInfoStatement.setString(2, name)
    intervalStyleStatement(c).execute()
    val profileResult = profileInfoStatement.executeQuery()
    if (!profileResult.next()) throw DatabaseError("Profile $name not found in database")
    val id = profileResult.getInt(1)
    val duration = Duration.parseISO8601(profileResult.getString(2))
    if (profileResult.next()) throw DatabaseError("Multiple profiles named $name found in one simulation dataset")
    return ProfileInfo(id, duration)
  }

  private fun parseJson(jsonStr: String): SerializedValue = Json.createReader(StringReader(jsonStr)).use { reader ->
      val requestJson = reader.readValue()
      val result = serializedValueP.parse(requestJson)
      return result.getSuccessOrThrow { DatabaseError(it.toString()) }
  }

  private val allInstancesStatement = c.prepareStatement(
      "select start_offset, duration, attributes, activity_type_name, id from merlin.simulated_activity" +
          " where simulation_dataset_id = ?;"
  )
  private val filteredInstancesStatement = c.prepareStatement(
      "select start_offset, duration, attributes, activity_type_name, id from merlin.simulated_activity" +
          " where simulation_dataset_id = ? and activity_type_name = ?;"
  )
  override fun <A: Any> instances(type: String?, deserializer: (SerializedValue) -> A): Instances<A> {
    val statement = if (type == null) allInstancesStatement else filteredInstancesStatement
    statement.clearParameters()
    statement.setInt(1, simDatasetId)
    if (type != null) statement.setString(2, type);
    intervalStyleStatement(c).execute()
    val response = statement.executeQuery()
    val result = mutableListOf<Instance<A>>()
    while (response.next()) {
      val start = Duration.parseISO8601(response.getString(1))
      val id = response.getLong(5)
      val attributesString = response.getString(3)
      val attributes = parseJson(attributesString)
      val directiveId = attributes.asMap().getOrNull()?.get("directiveId")?.asInt()?.getOrNull()
      result.add(Instance(
          deserializer(attributes),
          response.getString(4),
          id,
          directiveId,
          between(start, start.plus(Duration.parseISO8601(response.getString(2))))
      ))
    }
    return Instances(result)
  }

  override fun isStale() = stale
}
