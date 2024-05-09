package gov.nasa.jpl.aerie.timeline

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration

/** A functional interface for transforming bounds for operations that transform intervals. */
fun interface BoundsTransformer {
  /**
   * Convert the given bounds into the bounds that the child timeline should be collected with.
   *
   * This is typically the inverse of the motion caused by the operation itself. For example,
   * if the operation shifts all intervals to the future by `Duration.SECOND`, this method should
   * shift the bounds to the past by `Duration.SECOND`.
   */
  operator fun invoke(bounds: Interval): Interval

  /** Helper functions for constructing bounds transformers. */
  companion object {
    /** Does nothing. Used for operations that don't need to change the bounds. */
    @JvmField
    val IDENTITY: BoundsTransformer = BoundsTransformer { i -> i }

    /**
     * Creates a bounds transformer for the simple case of uniformly shifting the bounds.
     *
     * @param dur the duration that the INTERVALS are being shifted by; it is negated for you.
     */
    @JvmStatic
    fun shift(dur: Duration) = BoundsTransformer { i -> i.shiftBy(dur.negate()) }
  }
}
