package gov.nasa.ammos.plandev.procedural.scheduling.plan

import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.Directive

/**
 * Edits that can be made to the plan.
 *
 * All edits are invertible.
 */
sealed interface Edit {
  /**
   * Returns the reverse operation.
   *
   * If both `E` and `E.inverse()` are applied, the plan is unchanged.
   */
  fun inverse(): Edit

  /** Create a new activity from a given directive. */
  data class Create(/***/ val directive: Directive<AnyDirective>): Edit {
    override fun inverse() = Delete(directive)
  }

  /** Delete an activity, specified by directive id. */
  data class Delete(/***/ val directive: Directive<AnyDirective>): Edit {
    override fun inverse() = Create(directive)
  }
}
