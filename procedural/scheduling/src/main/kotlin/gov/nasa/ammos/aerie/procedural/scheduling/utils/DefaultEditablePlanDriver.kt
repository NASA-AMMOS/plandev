package gov.nasa.ammos.aerie.procedural.scheduling.utils

import gov.nasa.ammos.aerie.procedural.scheduling.plan.*
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart.Anchor
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.aerie.types.ActivityDirectiveId
import java.lang.ref.WeakReference

/**
 * A default (but optional) driver for [EditablePlan] implementations that handles
 * commits/rollbacks, staleness checking, and anchor deletion automatically.
 *
 * The [EditablePlan] interface requires the implementor to perform some fairly complex
 * stateful operations, with a tangle of interdependent algorithmic guarantees.
 * Most of those operations are standard among all implementations though, so this driver
 * captures most of it in a reusable form. Just implement the must simpler [PlanEditAdapter]
 * from this class to make a valid [EditablePlan].
 *
 * The implementor is still responsible for simulation and the basic context-free creation
 * and deletion operations. See the *Contracts* section of the interface methods' doc comments.
 */
/*
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
class DefaultEditablePlanDriver(
  val adapter: PlanEditAdapter
): EditablePlan, Plan by adapter {

  interface PlanEditAdapter: Plan {
    /**
     * Create a unique directive ID.
     *
     * *Contract:*
     * - the implementor must return an ID that is distinct from any activity ID that was in the initial plan
     *   or that has been returned from this method before during the implementor's lifetime.
     */
    fun generateDirectiveId(): ActivityDirectiveId

    /**
     * Create a directive in the plan.
     *
     * *Contracts*:
     * - the driver will guarantee that the directive ID does not collide with any other directive currently in the plan.
     * - the implementor must return the new directive in future calls to [Plan.directives], unless it is later deleted.
     * - the implementor must include the directive in future input plans for simulation, unless it is later deleted.
     */
    fun create(directive: Directive<AnyDirective>)

    /**
     * Remove a directive from the plan, specified by ID.
     */
    fun delete(id: ActivityDirectiveId)

    /**
     * Get the latest simulation results.
     *
     * *Contract:*
     * - the implementor must return equivalent results objects if this method is called multiple times without
     *   updates.
     *
     * The implementor doesn't have to return the exact same *instance* each time if no updates are made (i.e. referential
     * equality isn't required, only structural equality).
     */
    fun latestResults(): PerishableSimulationResults?

    /**
     * Simulate the current plan.
     *
     * *Contracts:*
     * - all prior creations and deletions must be reflected in the simulation run.
     * - the results corresponding to this run must be returned from future calls to [latestResultsInternal]
     *   until the next time [simulateInternal] is called.
     */
    @Throws(Exception::class)
    fun simulate(options: SimulateOptions)

    /**
     * Optional validation hook for new activities.
     *
     * The default implementation checks if the activity is within the bounds of the plan. The implementor can
     * add additional checks by overriding this method and calling `super.validate(directive)`. Implementor
     * should throw if the directive is invalid.
     */
    fun validate(directive: Directive<AnyDirective>) {
      if (directive.startTime > duration()) {
        throw Exception("New activity with id ${directive.id.id()} would start after the end of the plan")
      }
      if (directive.start is DirectiveStart.Absolute && directive.startTime.isNegative) {
        throw Exception("New activity with id ${directive.id.id()} would start before the beginning of the plan")
      }
    }
  }

  private data class Commit(
    val diff: Set<Edit>,

    /**
     * A record of the simulation results objects that were up-to-date when the commit
     * was created.
     *
     * This has SHARED OWNERSHIP with [DefaultEditablePlanDriver]; the editable plan may add more to
     * this list AFTER the commit is created.
     */
    val upToDateSimResultsSet: MutableSet<WeakReference<PerishableSimulationResults>>
  )

  private var committedChanges = Commit(setOf(), mutableSetOf())
  private var uncommittedChanges = mutableListOf<Edit>()

  /** Whether there are uncommitted changes. */
  val isDirty
    get() = uncommittedChanges.isNotEmpty()

  /** The total reduced set of changes made to the plan. */
  val totalDiff: Set<Edit>
    get() = committedChanges.diff

  // Jointly owned set of up-to-date simulation results. See class-level comment for algorithm explanation.
  private var upToDateSimResultsSet: MutableSet<WeakReference<PerishableSimulationResults>> = mutableSetOf()

  override fun latestResults(): SimulationResults? {
    val internalResults = adapter.latestResults()

    // kotlin checks structural equality by default, not referential equality.
    val isStale = internalResults?.inputDirectives()?.toSet() != directives().toSet()

    internalResults?.setStale(isStale)

    if (!isStale) upToDateSimResultsSet.add(WeakReference(internalResults))
    return internalResults
  }

  override fun create(directive: NewDirective): ActivityDirectiveId {
    class ParentSearchException(id: ActivityDirectiveId, size: Int): Exception("Expected one parent activity with id $id, found $size")
    val id = adapter.generateDirectiveId()
    val parent = when (val s = directive.start) {
      is Anchor -> {
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

    adapter.validate(resolved)

    adapter.create(resolved)

    for (simResults in upToDateSimResultsSet) {
      simResults.get()?.setStale(true)
    }
    // create a new list instead of `.clear` because commit objects have the same reference
    upToDateSimResultsSet = mutableSetOf()

    return id
  }

  override fun delete(directive: Directive<AnyDirective>, strategy: DeletedAnchorStrategy) {
    val directives = directives().cache()

    val directivesToDelete: Set<Directive<AnyDirective>>
    val directivesToCreate: Set<Directive<AnyDirective>>

    if (strategy == DeletedAnchorStrategy.Cascade) {
      directivesToDelete = deleteCascadeRecursive(directive, directives).toSet()
      directivesToCreate = mutableSetOf()
    } else {
      directivesToDelete = mutableSetOf(directive)
      directivesToCreate = mutableSetOf()
      val childActivities = directives.filter {
        it.start is Anchor
            && (it.start as Anchor).parentId == directive.id
      }
      for (c in childActivities) {
        when (strategy) {
          DeletedAnchorStrategy.Error -> throw Exception("Cannot delete an activity that has anchors pointing to it without a ${DeletedAnchorStrategy::class.java.simpleName}")
          DeletedAnchorStrategy.PreserveTree -> {
            directivesToDelete.add(c)
            val cStart = c.start as Anchor // Cannot smart cast
            val start = when (val parentStart = directive.start) {
              is DirectiveStart.Absolute -> DirectiveStart.Absolute(parentStart.time + cStart.offset)
              is Anchor -> Anchor(
                parentStart.parentId,
                parentStart.offset + cStart.offset,
                parentStart.anchorPoint,
                cStart.estimatedStart
              )
            }
            directivesToCreate.add(c.copy(start = start))
          }
          else -> throw Error("internal error; unreachable")
        }
      }
    }

    for (d in directivesToDelete) {
      uncommittedChanges.add(Edit.Delete(d))
      adapter.delete(d.id)
    }
    for (d in directivesToCreate) {
      uncommittedChanges.add(Edit.Create(d))
      adapter.create(d)
    }

    for (simResults in upToDateSimResultsSet) {
      simResults.get()?.setStale(true)
    }

    upToDateSimResultsSet = mutableSetOf()
  }

  private fun deleteCascadeRecursive(directive: Directive<AnyDirective>, allDirectives: Directives<AnyDirective>): List<Directive<AnyDirective>> {
    val recurse = allDirectives.collect().flatMap { d ->
      when (val s = d.start) {
        is Anchor -> {
          if (s.parentId == directive.id) deleteCascadeRecursive(d, allDirectives)
          else listOf()
        }
        else -> listOf()
      }
    }
    return recurse + listOf(directive)
  }

  override fun delete(id: ActivityDirectiveId, strategy: DeletedAnchorStrategy) {
    val matchingDirectives = directives().filter { it.id == id }.collect()
    if (matchingDirectives.isEmpty()) throw Exception("attempted to delete activity by ID that does not exist: $id")
    if (matchingDirectives.size > 1) throw Exception("multiple activities with ID found: $id")

    delete(matchingDirectives.first(), strategy)
  }

  override fun commit() {
    // Early return if there are no changes. This prevents multiple commits from sharing ownership of the set,
    // because new sets are only created when edits are made.
    // Probably unnecessary, but shared ownership freaks me out enough already.
    if (uncommittedChanges.isEmpty()) return

    val newCommittedChanges = uncommittedChanges
    val newTotalDiff = committedChanges.diff.toMutableSet()

    for (newChange in newCommittedChanges) {
      val inverse = newChange.inverse()
      if (newTotalDiff.contains(inverse)) {
        newTotalDiff.remove(inverse)
      } else {
        newTotalDiff.add(newChange)
      }
    }

    uncommittedChanges = mutableListOf()

    // Create a commit that shares ownership of the simResults set.
    committedChanges = Commit(newTotalDiff, upToDateSimResultsSet)
  }

  override fun rollback(): List<Edit> {
    // Early return if there are no changes, to keep staleness accuracy
    if (uncommittedChanges.isEmpty()) return emptyList()

    val result = uncommittedChanges
    uncommittedChanges = mutableListOf()
    for (edit in result.reversed()) {
      when (edit) {
        is Edit.Create -> adapter.delete(edit.directive.id)
        is Edit.Delete -> adapter.create(edit.directive)
      }
    }
    for (simResult in upToDateSimResultsSet) {
      simResult.get()?.setStale(true)
    }
    for (simResult in committedChanges.upToDateSimResultsSet) {
      simResult.get()?.setStale(false)
    }
    upToDateSimResultsSet = committedChanges.upToDateSimResultsSet
    return result
  }

  override fun simulate(options: SimulateOptions): SimulationResults {
    adapter.simulate(options)
    return latestResults()!!
  }

}
