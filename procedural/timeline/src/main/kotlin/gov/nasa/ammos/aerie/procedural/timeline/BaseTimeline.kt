package gov.nasa.ammos.aerie.procedural.timeline

import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike

/**
 * The basic timeline container that all higher-level timeline collections ultimately delegate to.
 *
 * Only the most extreme power-users should ever need to construct this manually.
 */
data class BaseTimeline<V: IntervalLike<V>, TL: Timeline<V, TL>>(
    override val ctor: (Timeline<V, TL>) -> TL,
    private val collector: (CollectOptions) -> List<V>
): Timeline<V, TL> {
  override fun collect(opts: CollectOptions) = collector(opts)
  override fun <RESULT : Timeline<V, RESULT>> unsafeCast(ctor: (Timeline<V, RESULT>) -> RESULT) =
      BaseTimeline(ctor, collector).specialize()
}
