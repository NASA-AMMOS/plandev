package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.ammos.aerie.procedural.scheduling.utils.PerishableSimulationResults
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.rangeTo
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.types.ActivityDirectiveId
import gov.nasa.jpl.aerie.types.ActivityInstanceId
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

class MerlinToProcedureSimulationResultsAdapter(
    private val results: gov.nasa.jpl.aerie.merlin.driver.SimulationResults,

    /** A copy of the plan that will not be mutated after creation. */
    private val plan: Plan
): PerishableSimulationResults {

  private var stale = false;
  override fun setStale(stale: Boolean) {
    this.stale = stale;
  }

  override fun isStale() = stale

  override fun simBounds(): Interval {
    val start = plan.toRelative(results.startTime)
    val end = start + results.duration
    return start .. end
  }

  companion object {
    private inline fun <D: Any> convertProfileWithoutGaps(old: List<ProfileSegment<D>>, converter: (D) -> SerializedValue): List<Segment<SerializedValue>> {
      val result: MutableList<Segment<SerializedValue>> = ArrayList(old.size)
      var elapsedTime = Duration.ZERO
      for (segment in old) {
        result.add(Segment(
            Interval.betweenClosedOpen(elapsedTime, elapsedTime + segment.extent),
            converter(segment.dynamics)
        ))
        elapsedTime += segment.extent
      }
      return result
    }
  }

  override fun <V: Any, TL: SerialSegmentOps<V, TL>> resource(name: String, deserializer: (List<Segment<SerializedValue>>) -> TL): TL {
    val profile =
        if (results.discreteProfiles.containsKey(name)) convertProfileWithoutGaps(results.discreteProfiles[name]!!.segments) { it }
        else if (results.realProfiles.containsKey(name)) convertProfileWithoutGaps(results.realProfiles[name]!!.segments) {
          SerializedValue.of(mapOf(
              "initial" to SerializedValue.of(it.initial),
              "rate" to SerializedValue.of(it.rate)
          ))
        }
        else throw IllegalArgumentException("No such resource $name")
    return deserializer.invoke(profile)
  }

  private data class FinishedActivityAttributes(val duration: Duration, val computedAttributes: SerializedValue)
  private data class CommonActivity(
      val arguments: Map<String, SerializedValue>,
      val type: String,
      val directiveId: ActivityDirectiveId?,
      val instanceId: ActivityInstanceId,
      val startTime: Instant,
      val parentId: ActivityInstanceId?,
      val finishedActivityAttributes: FinishedActivityAttributes?
  )

  private val commonActivities by lazy {
    val result = mutableListOf<CommonActivity>()
    for ((key, a) in results.simulatedActivities) {
      result.add(CommonActivity(
        a.arguments,
        a.type,
        a.directiveId.getOrNull(),
        ActivityInstanceId(key.id),
        a.start,
        a.parentId,
        FinishedActivityAttributes(a.duration, a.computedAttributes)
      ))
    }
    for ((key, a) in results.unfinishedActivities) {
      result.add(CommonActivity(
        a.arguments,
        a.type,
        a.directiveId.getOrNull(),
        ActivityInstanceId(key.id),
        a.start,
        a.parentId,
        null
      ))
    }
    result
  }

  override fun <A: Any> instances(type: String?, deserializer: (SerializedValue) -> A): Instances<A> {
    val instances = mutableListOf<Instance<A>>()
    for (a in commonActivities) {
      if (type != null && a.type != type) continue
      val startTime = plan.toRelative(a.startTime)
      val endTime = a.finishedActivityAttributes?.let { it.duration + startTime }
        ?: simBounds().end
      val computedAttributes = a.finishedActivityAttributes?.computedAttributes ?: SerializedValue.of(mapOf())
      val serializedActivity = SerializedValue.of(mapOf(
        "arguments" to SerializedValue.of(a.arguments),
        "computedAttributes" to computedAttributes
      ))
      instances.add(Instance(
        deserializer(serializedActivity),
        a.type,
        a.instanceId,
        a.directiveId,
        a.parentId,
        Interval(startTime, endTime)
      ))
    }
    return Instances(instances)
  }

  override fun <A : Any> inputDirectives(deserializer: (SerializedValue) -> A) = plan.directives(null, deserializer)
}
