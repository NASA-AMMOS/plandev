package gov.nasa.ammos.aerie.procedural.timeline.ops

/**
 * Operations mixin for segment-valued timelines whose payloads
 * represent constant values.
 */
interface ConstantOps<V: Any, THIS: ConstantOps<V, THIS>>: SegmentOps<V, THIS> {
  /**
   * [(DOC)][isolateEqualTo] Isolates intervals where the value is equal to a specific value.
   * @see [GeneralOps.isolate]
   */
  fun isolateEqualTo(value: V) = isolate { it.value == value }

  /**
   * [(DOC)][highlightEqualTo] Highlights intervals where the value is equal to a specific value.
   * @see [GeneralOps.highlight]
   */
  fun highlightEqualTo(value: V) = highlight { it.value == value }

  /**
   * [(DOC)][splitEqualTo] Splits segments where the value is equal to a specific value.
   *
   * @see [split]
   *
   * @param value the value of the segments to split
   * @param numPieces the number of pieces to split the segments into
   */
  fun splitEqualTo(value: V, numPieces: Int) = split { if (it.value == value) numPieces else 1 }
}
