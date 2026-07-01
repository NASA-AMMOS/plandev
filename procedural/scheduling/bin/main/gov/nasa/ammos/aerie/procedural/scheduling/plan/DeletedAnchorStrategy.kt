package gov.nasa.ammos.aerie.procedural.scheduling.plan

/**
 * How to handle directives anchored to a deleted activity.
 *
 * If you intend to delete an activity that you believe has nothing anchored to it,
 * using [Error] is recommended. This is the default.
 */
enum class DeletedAnchorStrategy {
  /** Throw an error. */ Error,
  /** Recursively delete everything in the anchor chain. */ Cascade,

  /**
   * Attempt to delete the activity in-place without changing the start times
   * of any activities anchored to it.
   *
   * Consider the anchor chain `A <- B <- C`, where `A` starts at an absolute time and
   * `B` and `C` are anchored.
   * - If `A` is deleted with [PreserveTree], `B` will be set to start at the absolute time `A.startTime + B.offset`.
   *   `C` will be unchanged.
   * - If `B` is deleted with [PreserveTree], `C` will be anchored to `A` with a new offset equal to `B.offset + C.offset`.
   *
   * If an activity is anchored to the end of the deleted activity, the delete activity's duration is assumed to be 0,
   * which may change the ultimate start time of the anchored activity.
   */
  PreserveTree,
}
