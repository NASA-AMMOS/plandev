package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives
import gov.nasa.ammos.aerie.procedural.timeline.collections.ExternalEvents
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart.Anchor.AnchorPoint.Companion.anchorToStart
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.minus
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.plus
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile
import gov.nasa.jpl.aerie.constraints.model.LinearProfile
import gov.nasa.jpl.aerie.constraints.time.Interval as ConstraintsInterval
import gov.nasa.jpl.aerie.constraints.time.Segment as ConstraintsSegment
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon
import java.time.Instant
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan as TimelinePlan
import gov.nasa.jpl.aerie.scheduler.model.Plan as SchedulerPlan

data class SchedulerToProcedurePlanAdapter(
    private val schedulerPlan: SchedulerPlan,
    private val planningHorizon: PlanningHorizon,
    private val eventsByDerivationGroup: Map<String, List<ExternalEvent>>,
    private val discreteExternalResources: Map<String, DiscreteProfile>,
    private val realExternalResources: Map<String, LinearProfile>
): TimelinePlan, SchedulerPlan by schedulerPlan {
  override fun totalBounds() = Interval.between(Duration.ZERO, planningHorizon.aerieHorizonDuration)

  override fun toRelative(abs: Instant) = abs - planningHorizon.startInstant

  override fun toAbsolute(rel: Duration) = planningHorizon.startInstant + rel

  override fun <A : Any> directives(type: String?, deserializer: (SerializedValue) -> A): Directives<A> {
    val schedulerActivities = if (type == null) {
      this.schedulerPlan.activities
    } else {
      this.schedulerPlan.activities.filter { it.type.name == type }
    }

    val result = ArrayList<Directive<A>>(schedulerActivities.size)
    for (activity in schedulerActivities) {
      result.add(
          Directive(
              deserializer(SerializedValue.of(activity.arguments)),
              activity.name,
              activity.id,
              activity.type.name,
              if (activity.anchorId == null) DirectiveStart.Absolute(activity.startOffset)
              else DirectiveStart.Anchor(activity.anchorId, activity.startOffset, anchorToStart(activity.anchoredToStart))
          )
      )
    }
    return Directives(result)
  }

  override fun events(query: EventQuery): ExternalEvents {
    var result = if (query.derivationGroups != null) query.derivationGroups!!.flatMap {
      eventsByDerivationGroup[it]
        ?: throw Error("derivation group either doesn't exist or isn't associated with plan: $it")
    }
    else eventsByDerivationGroup.values.flatten()
    if (query.eventTypes != null) result = result.filter { it.type in query.eventTypes!! }
    if (query.sources != null) result = result.filter { EventQuery.SourceQuery(it.source) in query.sources!! }
    return ExternalEvents(result)
  }
  override fun <V : Any, TL : SerialSegmentOps<V, TL>> resource(
    name: String,
    deserializer: (List<Segment<SerializedValue>>) -> TL
  ): TL {
    fun <V> constraintsToProceduralSegment(seg: ConstraintsSegment<V>) = Segment(
      seg.interval.toProceduralInterval(),
      seg.value
    )
    val segments = when (name) {
      in discreteExternalResources -> discreteExternalResources[name]!!
        .asIterable().map(::constraintsToProceduralSegment)
        .toList()
      in realExternalResources -> realExternalResources[name]!!
        .asIterable()
        .map(::constraintsToProceduralSegment)
        .map { it.withNewValue(SerializedValue.of(
          mapOf(
            "initial" to SerializedValue.of(it.value.valueAt(it.interval.start)),
            "rate" to SerializedValue.of(it.value.rate)
          )
        ))}
        .toList()
      else -> throw IllegalArgumentException("External profile not found: $name")
    }

    return deserializer(segments)
  }

}
