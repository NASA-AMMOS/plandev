package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.ammos.aerie.procedural.scheduling.utils.DefaultEditablePlanDriver
import gov.nasa.ammos.aerie.procedural.scheduling.utils.PerishableSimulationResults
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.jpl.aerie.merlin.driver.MissionModel
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator
import gov.nasa.jpl.aerie.scheduler.model.*
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade
import gov.nasa.jpl.aerie.types.*
import kotlin.jvm.optionals.getOrNull

/*
 * An implementation of [EditablePlan] that stores the plan in memory for use in the internal scheduler.
 *
 */
data class SchedulerPlanEditAdapter(
  private val missionModel: MissionModel<*>,
  private var idGenerator: DirectiveIdGenerator,
  val plan: SchedulerToProcedurePlanAdapter,
  private val simulationFacade: SimulationFacade,
  private val lookupActivityType: (String) -> ActivityType
): Plan by plan, DefaultEditablePlanDriver.PlanEditAdapter {

  override fun generateDirectiveId(): ActivityDirectiveId = idGenerator.next()
  override fun latestResults(): PerishableSimulationResults? {
    val merlinResults = simulationFacade.latestSimulationData.getOrNull() ?: return null
    return MerlinToProcedureSimulationResultsAdapter(merlinResults.driverResults, plan.copy(schedulerPlan = plan.duplicate()))
  }

  override fun create(directive: Directive<AnyDirective>) {
    plan.add(directive.toSchedulingActivity(lookupActivityType))
  }

  override fun delete(id: ActivityDirectiveId) {
    plan.remove(plan.activitiesById[id])
  }

  override fun simulate(options: SimulateOptions) {
    simulationFacade.simulateWithResults(plan, options.pause.resolve(this))
  }

  override fun validate(directive: Directive<AnyDirective>) {
    super.validate(directive)
    lookupActivityType(directive.type).specType.inputType.validateArguments(directive.inner.arguments)
  }

  companion object {
    fun convertActivitySources(activitySources: List<gov.nasa.ammos.aerie.procedural.timeline.payloads.reference.ActivitySource<*>>): List<ActivitySource<out Any>> {
      if (activitySources.size > 0) {
        return activitySources.map {
          when (it) {
            is gov.nasa.ammos.aerie.procedural.timeline.payloads.reference.ResourceActivitySource ->
              ResourceActivitySource(it.v)

            is gov.nasa.ammos.aerie.procedural.timeline.payloads.reference.DirectiveActivitySource ->
              DirectiveActivitySource(
                ActivityDirective(
                  it.v.startTime,
                  it.v.name,
                  it.v.inner.arguments,
                  it.v.id,
                  true, // TODO
                  it.v.activitySources?.let { it1 -> convertActivitySources(it1) },
                )
              )

            is gov.nasa.ammos.aerie.procedural.timeline.payloads.reference.ExternalEventActivitySource ->
              ExternalEventActivitySource(
                ExternalEvent(
                  it.v.key,
                  it.v.type,
                  it.v.source.derivationGroup,
                  it.v.source.key,
                  it.v.source.createdAt
                )
              )

            else -> ResourceActivitySource("get rid of this, not possible")
          }
        }
      }
      return emptyList()
    }

    @JvmStatic fun Directive<AnyDirective>.toSchedulingActivity(lookupActivityType: (String) -> ActivityType) = SchedulingActivity(
        id,
        lookupActivityType(type),
        when (val s = start) {
          is DirectiveStart.Absolute -> s.time
          is DirectiveStart.Anchor -> s.offset
        },
        when (val d = lookupActivityType(type).durationType) {
          is DurationType.Controllable -> {
            inner.arguments[d.parameterName]?.asInt()?.let { Duration(it.get()) }
          }
          is DurationType.Parametric -> {
            d.durationFunction.apply(inner.arguments)
          }
          is DurationType.Fixed -> {
            d.duration
          }
          else -> Duration.ZERO
        },
        inner.arguments,
        null,
        when (val s = start) {
          is DirectiveStart.Absolute -> null
          is DirectiveStart.Anchor -> s.parentId
        },
      when (val s = start) {
        is DirectiveStart.Absolute -> true
        is DirectiveStart.Anchor -> s.anchorPoint == DirectiveStart.Anchor.AnchorPoint.Start
      },
      name,
      activitySources?.let { convertActivitySources(it) }
    )
  }
}
