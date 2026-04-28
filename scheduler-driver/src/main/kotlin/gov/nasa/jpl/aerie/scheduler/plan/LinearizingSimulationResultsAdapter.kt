package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.ammos.aerie.procedural.timeline.Interval
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue

/**
 * A [MerlinToProcedureSimulationResultsAdapter] that, in addition to the resources actually
 * present in the underlying [gov.nasa.jpl.aerie.merlin.driver.SimulationResults], also exposes
 * a synthesized "real" profile for every discrete profile whose values are doubles.
 *
 * For each discrete double profile named `Foo`, a request for resource `Foo$suffix`
 * (default `"FooLinear"`) is served by re-interpreting `Foo`'s segments as linear dynamics
 * whose rate is chosen so each segment ends at the next segment's value (i.e. the slope of
 * the secant from one sample to the next). The last segment, and any zero-duration segment,
 * gets rate = 0. The synthesized segment list for each name is computed on first request and
 * cached for subsequent calls (the underlying [results] is assumed to be read-only after
 * construction).
 *
 * If a profile (real or discrete) already exists under the linearized name, the underlying
 * profile is returned unchanged via the superclass.
 */
class LinearizingSimulationResultsAdapter @JvmOverloads constructor(
    results: gov.nasa.jpl.aerie.merlin.driver.SimulationResults,
    plan: Plan,
    private val suffix: String = "Linear"
): MerlinToProcedureSimulationResultsAdapter(results, plan) {

  /** Wrap an existing [MerlinToProcedureSimulationResultsAdapter], reusing its results and plan. */
  @JvmOverloads
  constructor(other: MerlinToProcedureSimulationResultsAdapter, suffix: String = "Linear")
      : this(other.results, other.plan, suffix)

  /**
   * Cache of synthesized linear segment lists, keyed by the requested resource name
   * (i.e. the name *with* the suffix). Built on first request, reused thereafter.
   * Assumes the underlying [results] is read-only after construction.
   */
  private val linearizedCache: MutableMap<String, List<Segment<SerializedValue>>> = HashMap()

  override fun <V : Any, TL : SerialSegmentOps<V, TL>> resource(
      name: String,
      deserializer: (List<Segment<SerializedValue>>) -> TL
  ): TL {
    // Only synthesize if the name isn't already a real or discrete profile.
    if (!results.realProfiles.containsKey(name)
        && !results.discreteProfiles.containsKey(name)) {
      // Arrayed/keyed resources serialize as `<base>["KEY"]` (or similar bracketed
      // suffix). The "Linear" marker, if present, is on `<base>`, not at the end of
      // the full name. Split the name into a prefix and an optional trailing
      // bracketed segment so we can match the suffix on the prefix.
      val bracketStart = findTrailingBracketStart(name)
      val prefix = if (bracketStart >= 0) name.substring(0, bracketStart) else name
      val bracketTail = if (bracketStart >= 0) name.substring(bracketStart) else ""
      if (prefix.length > suffix.length && prefix.endsWith(suffix)) {
        val baseName = prefix.removeSuffix(suffix) + bracketTail
        val discrete = results.discreteProfiles[baseName]
        if (discrete != null) {
          val segments = discrete.segments
          if (!isNumericDiscreteProfile(segments)) {
            throw IllegalArgumentException(
                "Cannot reinterpret discrete profile '$baseName' as real/linear: values are not numeric")
          }
          val linearized = linearizedCache.getOrPut(name) { toLinearSegments(segments) }
          return deserializer.invoke(linearized)
        }
      }
    }
    return super.resource(name, deserializer)
  }

  companion object {
    private fun isNumericDiscreteProfile(segs: List<ProfileSegment<SerializedValue>>): Boolean =
        segs.isEmpty() || segs.first().dynamics.asReal().isPresent

    /**
     * If [name] ends with a balanced `[...]` bracketed segment (as produced for
     * arrayed/keyed resources, e.g. `Foo["EUROPA"]`), return the index of the
     * opening `[` of the *outermost* trailing bracket. Returns -1 if [name] does
     * not end with `]` or the brackets are unbalanced.
     */
    private fun findTrailingBracketStart(name: String): Int {
      if (!name.endsWith("]")) return -1
      var depth = 0
      for (i in name.indices.reversed()) {
        when (name[i]) {
          ']' -> depth++
          '[' -> {
            depth--
            if (depth == 0) return i
          }
        }
      }
      return -1
    }

    private fun toLinearSegments(
        old: List<ProfileSegment<SerializedValue>>
    ): List<Segment<SerializedValue>> {
      val result = ArrayList<Segment<SerializedValue>>(old.size)
      var elapsed = Duration.ZERO
      for (i in old.indices) {
        val s = old[i]
        val value = s.dynamics.asReal().orElseThrow {
          IllegalArgumentException(
              "Discrete profile contains non-numeric value; cannot reinterpret as Linear")
        }
        val extentSeconds = s.extent.ratioOver(Duration.SECOND)
        val rate = if (extentSeconds == 0.0 || i == old.size - 1) {
          0.0
        } else {
          val nextValue = old[i + 1].dynamics.asReal().orElseThrow {
            IllegalArgumentException(
                "Discrete profile contains non-numeric value; cannot reinterpret as Linear")
          }
          (nextValue - value) / extentSeconds
        }
        result.add(Segment(
            Interval.betweenClosedOpen(elapsed, elapsed + s.extent),
            SerializedValue.of(mapOf(
                "initial" to SerializedValue.of(value),
                "rate" to SerializedValue.of(rate)
            ))
        ))
        elapsed += s.extent
      }
      return result
    }
  }
}

