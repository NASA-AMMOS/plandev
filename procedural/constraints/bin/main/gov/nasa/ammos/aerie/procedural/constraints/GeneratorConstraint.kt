package gov.nasa.ammos.aerie.procedural.constraints

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.collections.Windows
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real
import gov.nasa.ammos.aerie.procedural.timeline.ops.GeneralOps
import gov.nasa.ammos.aerie.procedural.timeline.ops.ParallelOps
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialConstantOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults

/**
 * A generator-style implementation of [Constraint].
 *
 * The subclass must implement [generate], and within it call [violate] to produce violations.
 * Or if you are using Kotlin, you can use the timeline extension functions such as [windows.violateInside()][violateInside]
 * to more easily submit violations.
 */
abstract class GeneratorConstraint: Constraint {
  private var violations = mutableListOf<Violation>()

  /** Finalizes one or more intervals as violations. */
  @JvmOverloads protected fun violate(vararg i: Interval, message: String? = null) {
    violate(i.map { Violation(it) }, message)
  }

  /** Finalizes one or more violations. */
  @JvmOverloads protected fun violate(vararg v: Violation, message: String? = null) {
    violate(v.toList(), message)
  }

  /** Finalizes a list of violations. */
  @JvmOverloads protected fun violate(l: List<Violation>, message: String? = null) {
    violations.addAll(l.map {
      if (it.message == null) Violation(
        it.interval,
        message ?: defaultMessage(),
        it.ids
      ) else it
    })
  }

  /** Collects a [Violations] timeline and finalizes the result. */
  @JvmOverloads protected fun violate(tl: Violations, message: String? = null) {
    violate(tl.collect(), message)
  }

  /** Creates a [Violations] object that violates when this profile equals a given value. */
  @JvmOverloads protected fun <V: Any> SerialConstantOps<V, *>.violateOn(v: V, message: String? = null) = violate(Violations.on(this, v), message)

  /** Creates a [Violations] object that violates when this profile equals a given value. */
  @JvmOverloads protected fun Real.violateOn(n: Number, message: String? = null) = violate(Violations.on(this, n), message)

  /**
   * Creates a [Violations] object that violates on every object in the timeline.
   *
   * If the object is an activity, it will record the directive or instance id.
   */
  @JvmOverloads protected fun <I: IntervalLike<I>> ParallelOps<I, *>.violateOnAll(message: String? = null) {
    violate(Violations.onAll(this), message)
  }

  /** Creates a [Violations] object that violates inside each interval. */
  @JvmOverloads protected fun Windows.violateInside(message: String? = null) = violate(Violations.inside(this), message)
  /** Creates a [Violations] object that violates outside each interval. */
  @JvmOverloads protected fun Windows.violateOutside(message: String? = null) = violate(Violations.outside(this), message)

  /**
   * Creates a [Violations] object from two timelines, that violates whenever they have overlap.
   *
   * If either object is an activity, it will record the directive or instance id.
   */
  @JvmOverloads protected fun <V: IntervalLike<V>, W: IntervalLike<W>> GeneralOps<V, *>.violateWhenSimultaneous(other: GeneralOps<W, *>, message: String? = null) {
    violate(Violations.whenSimultaneous(this, other), message)
  }

  /**
   * A generator function that calls [violate] to produce violations.
   */
  abstract fun generate(plan: Plan, simResults: SimulationResults)

  /**
   * Default violation message to be displayed to user.
   *
   * Can be overridden on a violation-by-violation basis by manually specifying
   * it in the [Violation] object.
   */
  open fun defaultMessage(): String? = null

  final override fun run(plan: Plan, simResults: SimulationResults): Violations {
    violations = mutableListOf()
    generate(plan, simResults)
    val message = defaultMessage()
    return if (message == null) Violations(violations)
    else Violations(violations).withDefaultMessage(message)
  }
}
