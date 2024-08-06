# Module Timeline

This is a Kotlin library for manipulating collections of time-distributed objects on a timeline. "Time-distributed" means that
each object occupies an instant or interval of time. For example, an activity instance occupies the interval over which it
executed, and so a `Instances` collection can be used to reason about and manipulate a set of activities in aggregate.

## Architecture

This package is built around the `BaseTimeline` data class, which every timeline type contains and delegates to. All a `BaseTimeline` object does
is represent a "collector" function, which, when given bounds of evaluation, will produce a list of `IntervalLike` objects. All timeline operations in
this package are composed lazily, because the collection bounds can change based on the operations applied, and only the final evaluation bounds are known.

The collections are created primarily by combining mixin interfaces from the `ops` package. All mixin interfaces are named `...Ops` or `...Op`
(i.e. `ActivityOps` or `CoalesceOp`). When implementing a new operation for a timeline type, first consider whether it is general enough to
apply to more than one type. If so, either add it to the appropriate mixin if it exists, or create a new one if it doesn't. Operations should
only be added as a class method if there's no potential for it to apply to any other type (such as `Booleans.shiftEdges`, which is highly specific to boolean profiles).

### Time Representation

Instants of time are represented using the `Duration` class, defined as the duration from the plan start. This
means that `Duration.ZERO` always refers to the start of the plan. You can convert these to and from `java.time.Instant` objects using utility functions on a `Plan` object, and use that to reason about time of day.

`Interval`s represent contiguous ranges of time, which may include or exclude their end points. They may be single points or even empty. `IntervalLike` is the interface that all timeline payload objects must satisfy.

### Timeline Types

All timelines implement the `Timeline` interface, by delegating to a `BaseTimeline` object. The `BaseTimeline`s purpose is to hold a collector function (which evaluates the timeline and returns a list) and a constructor function (which wraps the `BaseTimeline` in a more specialized `Timeline` implementor). Users should never
need to use `BaseTimeline`s directly, except when creating new timeline types.

Timelines can be conceptually separated into "profiles" and "everything else".

- Profiles are timelines of *time-ordered, non-overlapping* `Segment` objects. Segments are just a general-purpose container
  that associates a payload with an interval to satisfy the `IntervalLike` interface. They are also *coalesced*,
  meaning that adjacent segments with equal values are combined into a single segment. Profiles are most often used to track the
  evolution of a simulation resource over time. Profiles can have "gaps", or intervals where there is no segment. Conceptually,
  gaps are intervals when the evolving value represented by the profile is undefined.
- All other timelines are collections of *unordered, potentially overlapping* objects, like activity instances, activity directives, or plain intervals (a timeline of intervals with no payload).

### Collecting

All operations are performed lazily, and only happen when the `.collect(CollectOptions)` method is called. `CollectOptions` allows you to specify:
- the bounds of evaluation (an `Interval`), outside which no data should be calculated or returned
- whether "marginal" objects (objects that are only partially contained in the bounds) should be truncated
  or returned whole.

Some operations may need to change the collect options on the timelines they are applied to. For example, in the following code:

```kotlin
val original: Booleans = /* ... */

// shift the profile one hour into the future.
val shifted = original.shift(Duration.HOUR)

val segments = shifted.collect(CollectOptions(
    bounds = Interval.between(Duration.ZERO, Duration.DAY),
    truncateMarginal = true
))
```

When `shifted`'s collector is invoked, it will in turn invoke `original'`s collector - but not on the same bounds.
Instead, the bounds will be `Interval.between(-HOUR, DAY - HOUR)`. It is one hour earlier so that objects in `original` just before the intended bounds of `[ZERO, DAY]` are properly calculated and shifted into the bounds.

Similarly, some operations can change whether marginal objects are truncated. `GeneralOps.filterByDuration` and `NonZeroDurationOps.split` will always perform their call the previous timeline's collector with `truncateMarginal = false`, because they need to know the full duration of all objects. (They will then truncate the marginal objects in the result if it was requested in `CollectOptions`.) Unfortunately, this is not totally fool-proof; if an
operation is applied to a profile that would have caused a segment fully outside the bounds to be coalesced (see below)
with a marginal segment, the full extent of the segment will be lost.

### Coalescing

Profiles are "coalesced", meaning that adjacent or overlapping segments with equal values are merged into a single segment over the union of their intervals. This is performed automatically after every operation. This can have implications for operations like `GeneralOps.filterByDuration` and `NonZeroDurationOps.split`, which care about the duration of each interval.

Temporarily avoiding coalescing for a profile operation is possible, but not recommended or ergonomic. You can
call `myProfile.convert(::Intervals)`. The `Intervals` timeline type is the most general, and has no special mathematical properties and no specialized knowledge of the data it contains. You'd then perform the desired operation (which will likely be less ergonomic due to `Intervals`' lack of specialization) and convert it back with `.convert(::/* original type */)`.

### Numerics

There are two options for representing numeric profiles: `Real` and `Numbers`. `Numbers` is piece-wise constant and can contain any primitive numeric type, while `Real` is piece-wise linear and can only contain doubles. `Real` is unique because it is the only profile type so far that represents values that vary within the segment, not just between segments.

# Package gov.nasa.ammos.aerie.procedural.timeline.collections
The officially supported timeline types.

# Package gov.nasa.ammos.aerie.procedural.timeline.collections.profiles
Timeline types for resource profiles.

# Package gov.nasa.ammos.aerie.procedural.timeline.ops
Operations mixins to be applied to timeline types.

# Package gov.nasa.ammos.aerie.procedural.timeline.ops.coalesce
Operations mixins for specifying whether a timeline should be coalesced.

# Package gov.nasa.ammos.aerie.procedural.timeline.ops.numeric
Operations mixins just for numeric types (`Real` and `Numbers`).

# Package gov.nasa.ammos.aerie.procedural.timeline.payloads
Payload types (`IntervalLike` implementors) that can be contained in timelines.

# Package gov.nasa.ammos.aerie.procedural.timeline.payloads.activities
Containers for representing activity directives and instants.

Currently, there is no specialization for activity
types, and all directives and instants use `AnyDirective` and `AnyInstance`, respectively. These classes represent
arguments and computed attributes using `SerializedValue`.

# Package gov.nasa.ammos.aerie.procedural.timeline.plan
Tools for querying simulation results, activity directives, and general information from a plan.

# Package gov.nasa.ammos.aerie.procedural.timeline.util
Common tools used by operations and timeline constructors to sanitize and process lists.

