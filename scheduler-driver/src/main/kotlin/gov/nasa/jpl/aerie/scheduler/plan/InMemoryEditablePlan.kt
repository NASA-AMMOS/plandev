package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan
import gov.nasa.ammos.aerie.procedural.scheduling.plan.NewDirective
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator
import gov.nasa.jpl.aerie.scheduler.model.*
import gov.nasa.jpl.aerie.types.ActivityDirectiveId
import java.lang.ref.WeakReference
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults as TimelineSimResults

/*
 * An implementation of [EditablePlan] that stores the plan in memory for use in the internal scheduler.
 *
 * ## Staleness checking
 *
 * The editable plan instance keeps track of sim results that it has produced using weak references, and can dynamically
 * update their staleness if the plan is changed after it was simulated. The process is this:
 *
 * 1. [InMemoryEditablePlan] has a set of weak references to simulation results objects that are currently up-to-date.
 *    I used weak references because if the user can't access it anymore, staleness doesn't matter and we might as well
 *    let it get gc'ed.
 * 2. When the user gets simulation results, either through simulation or by getting the latest, it always checks for
 *    plan equality between the returned results and the current plan, even if we just simulated. If it is up-to-date, a
 *    weak ref is added to the set.
 * 3. When an edit is made, the sim results in the current set are marked stale; then the set is reset to new reference
 *    to an empty set.
 * 4. When a commit is made, the commit object takes *shared ownership* of the set. If a new simulation is run (step 2)
 *    the plan can still add to the set while it is still jointly owned by the commit. Then when an edit is made (step 3)
 *    the commit will become the sole owner of the set.
 * 5. When changes are rolled back, any sim results currently in the plan's set are marked stale, the previous commit's
 *    sim results are marked not stale, then the plan will resume joint ownership of the previous commit's set.
 *
 * The joint ownership freaks me out a wee bit, but I think it's safe because the commits are only used to keep the
 * previous sets from getting gc'ed in the event of a rollback. Only the plan object actually mutates the set.
 */
data class InMemoryEditablePlan(
    private var idGenerator: DirectiveIdGenerator,
    private val plan: SchedulerToProcedurePlanAdapter,
    private val simulationFacade: SimulationFacade,
    private val lookupActivityType: (String) -> ActivityType
) : EditablePlan, Plan by plan {

  private data class Commit(
    val diff: List<Edit>,

    /**
     * A record of the simulation results objects that were up-to-date when the commit
     * was created.
     *
     * This has SHARED OWNERSHIP with [InMemoryEditablePlan]; the editable plan may add more to
     * this list AFTER the commit is created.
     */
    val upToDateSimResultsSet: MutableSet<WeakReference<MerlinToProcedureSimulationResultsAdapter>>
  )

  private var committedChanges = Commit(listOf(), mutableSetOf())
  var uncommittedChanges = mutableListOf<Edit>()
    private set

  val totalDiff: List<Edit>
    get() = committedChanges.diff

  // Jointly owned set of up-to-date simulation results. See class-level comment for algorithm explanation.
  private var upToDateSimResultsSet: MutableSet<WeakReference<MerlinToProcedureSimulationResultsAdapter>> = mutableSetOf()

  override fun latestResults(): SimulationResults? {
    val merlinResults = simulationFacade.latestSimulationData.getOrNull() ?: return null

    // kotlin checks structural equality by default, not referential equality.
    val isStale = merlinResults.plan.activities != plan.activities

    val results = MerlinToProcedureSimulationResultsAdapter(merlinResults.driverResults, isStale, plan)
    if (!isStale) upToDateSimResultsSet.add(WeakReference(results))
    return results
  }

  override fun create(directive: NewDirective): ActivityDirectiveId {
    class ParentSearchException(id: ActivityDirectiveId, size: Int): Exception("Expected one parent activity with id $id, found $size")
    val id = idGenerator.next()
    val parent = when (val s = directive.start) {
      is DirectiveStart.Anchor -> {
        val parentList = directives()
            .filter { it.id == s.parentId }
            .collect(totalBounds())
        if (parentList.size != 1) throw ParentSearchException(s.parentId, parentList.size)
        parentList.first()
      }
      is DirectiveStart.Absolute -> null
    }
    val resolved = directive.resolve(id, parent)
    uncommittedChanges.add(Edit.Create(resolved))
    resolved.validateArguments(lookupActivityType)
    plan.add(resolved.toSchedulingActivity(lookupActivityType, true))

    for (simResults in upToDateSimResultsSet) {
      simResults.get()?.stale = true
    }
    // create a new list instead of `.clear` because commit objects have the same reference
    upToDateSimResultsSet = mutableSetOf()

    return id
  }

  override fun commit() {
    // Early return if there are no changes. This prevents multiple commits from sharing ownership of the set,
    // because new sets are only created when edits are made.
    // Probably unnecessary, but shared ownership freaks me out enough already.
    if (uncommittedChanges.isEmpty()) return

    val newCommittedChanges = uncommittedChanges
    uncommittedChanges = mutableListOf()

    // Create a commit that shares ownership of the simResults set.
    committedChanges = Commit(committedChanges.diff + newCommittedChanges, upToDateSimResultsSet)
  }

  override fun rollback(): List<Edit> {
    // Early return if there are no changes, to keep staleness accuracy
    if (uncommittedChanges.isEmpty()) return emptyList()

    val result = uncommittedChanges
    uncommittedChanges = mutableListOf()
    for (edit in result) {
      when (edit) {
        is Edit.Create -> {
          plan.remove(edit.directive.toSchedulingActivity(lookupActivityType, true))
        }
      }
    }
    for (simResult in upToDateSimResultsSet) {
      simResult.get()?.stale = true
    }
    for (simResult in committedChanges.upToDateSimResultsSet) {
      simResult.get()?.stale = false
    }
    upToDateSimResultsSet = committedChanges.upToDateSimResultsSet
    return result
  }

  override fun simulate(options: SimulateOptions): TimelineSimResults {
    simulationFacade.simulateWithResults(plan, options.pause.resolve(this))
    return latestResults()!!
  }

  companion object {
    fun Directive<AnyDirective>.validateArguments(lookupActivityType: (String) -> ActivityType) {
      lookupActivityType(type).specType.inputType.validateArguments(inner.arguments)
    }

    @JvmStatic fun Directive<AnyDirective>.toSchedulingActivity(lookupActivityType: (String) -> ActivityType, isNew: Boolean) = SchedulingActivity(
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
      isNew,
      name
    )
  }
}
