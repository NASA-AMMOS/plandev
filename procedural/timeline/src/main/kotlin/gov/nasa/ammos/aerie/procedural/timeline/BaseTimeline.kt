package gov.nasa.ammos.aerie.procedural.timeline

import gov.nasa.ammos.aerie.procedural.timeline.payloads.IntervalLike
import gov.nasa.ammos.aerie.procedural.timeline.util.listCollector

/**
 * The basic timeline container that all higher-level timeline collections ultimately delegate to.
 *
 * Only the most extreme power-users should ever need to construct this manually.
 */
data class BaseTimeline<V: IntervalLike<V>, TL: Timeline<V, TL>>(
    override val ctor: (Timeline<V, TL>) -> TL,
    private val collector: (CollectOptions) -> List<V>
): Timeline<V, TL> {
  private var cached: List<V>? = null
  private var cachedOptions: CollectOptions? = null

  override fun cache(opts: CollectOptions) {
    if (cachedOptions == null || !cachedOptions!!.contains(opts)) {
      cached = collect(opts)
      cachedOptions = opts
    }
  }

  override fun collect(opts: CollectOptions) =
    if (cached == null || !cachedOptions!!.contains(opts)) collector(opts)
    else ctor(BaseTimeline(ctor, listCollector(cached!!))).collect(opts)

  override fun <RESULT : Timeline<V, RESULT>> unsafeCast(ctor: (Timeline<V, RESULT>) -> RESULT) =
      BaseTimeline(ctor, collector).specialize()
}
