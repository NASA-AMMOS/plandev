package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.scheduler.model.ActivityType
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon
import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.minus
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.plus
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart.Anchor.AnchorPoint.Companion.anchorToStart
import java.time.Instant
import gov.nasa.jpl.aerie.scheduler.model.Plan as SchedulerPlan
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan as TimelinePlan

data class SchedulerToProcedurePlanAdapter(
    private val schedulerPlan: SchedulerPlan,
    private val planningHorizon: PlanningHorizon,
): TimelinePlan, SchedulerPlan by schedulerPlan {
  override fun totalBounds() = Interval.between(Duration.ZERO, planningHorizon.aerieHorizonDuration)

  override fun toRelative(abs: Instant) = abs - planningHorizon.startInstant

  override fun toAbsolute(rel: Duration) = planningHorizon.startInstant + rel

  override fun <A : Any> directives(type: String?, deserializer: (SerializedValue) -> A): Directives<A> {
    val schedulerActivities = (if (type == null) schedulerPlan.activities else schedulerPlan.activitiesByType[ActivityType(type)])
        ?: throw Exception("could not find activities by type $type")

    val result = ArrayList<Directive<A>>(schedulerActivities.size)
    for (activity in schedulerActivities) {
      result.add(
          Directive(
              deserializer(SerializedValue.of(activity.arguments)),
              "Name unavailable",
              activity.id,
              activity.type.name,
              if (activity.anchorId == null) DirectiveStart.Absolute(activity.startOffset)
              else DirectiveStart.Anchor(activity.anchorId, activity.startOffset, anchorToStart(activity.anchoredToStart))
          )
      )
    }
    return Directives(result)
  }

}
