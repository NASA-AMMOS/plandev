package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.driver.timeline.TemporalEventSource;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class CombinedSimulationResults implements SimulationResultsInterface {

  private static boolean debug = false;

  final SimulationResultsInterface nr;
  final SimulationResultsInterface or;
  final TemporalEventSource timeline;


  public CombinedSimulationResults(SimulationResultsInterface newSimulationResults,
                                   SimulationResultsInterface oldSimulationResults,
                                   TemporalEventSource timeline) {
    this.nr = newSimulationResults;
    this.or = oldSimulationResults;
    this.timeline = timeline;
  }



  @Override
  public Instant getStartTime() {
    if (_startTime == null) {
      _startTime = ObjectUtils.min(nr.getStartTime(), or.getStartTime());
    }
    return _startTime;
  }
  private Instant _startTime = null;

  @Override
  public Duration getDuration() {
    if (_duration == null) {
      _duration = Duration.minus(ObjectUtils.max(Duration.addToInstant(nr.getStartTime(), nr.getDuration()),
                                                 Duration.addToInstant(or.getStartTime(), or.getDuration())),
                                 getStartTime());
    }
    return _duration;
  }
  private Duration _duration = null;

  @Override
  public Map<String, ResourceProfile<RealDynamics>> getRealProfiles() {
    String[] resourceName = new String[] {null};
    if (_realProfiles == null) {
      _realProfiles = Stream.of(or.getRealProfiles(), nr.getRealProfiles()).flatMap(m -> m.entrySet().stream())
                            .collect(Collectors.toMap(e -> {resourceName[0] = e.getKey(); if (debug) System.out.println("mergeProfiles for " + e.getKey());
                                                            return e.getKey();}, Map.Entry::getValue,
                                                      (pOld, pNew) -> mergeProfiles(or.getStartTime(), nr.getStartTime(),
                                                                                    resourceName[0], pOld, pNew, timeline)));
    }
    return _realProfiles;
  }
  private Map<String, ResourceProfile<RealDynamics>> _realProfiles = null;

  // We need to pass startTimes for both to know from where they are offset?  We don't want to assume that the two
  // simulations had the same timeframe.
  static <D> ResourceProfile<D> mergeProfiles(
      Instant tOld, Instant tNew, String resourceName,
      ResourceProfile<D> pOld, ResourceProfile<D> pNew,
      TemporalEventSource timeline) {
    // We assume that the two ValueSchemas are the same and don't check for the sake of minimizing computation.
    return ResourceProfile.of(
        pOld.schema(),
        mergeSegmentLists(tOld, tNew, resourceName,  pOld.segments(), pNew.segments(), timeline));
  }

  static private int ctr = 0;
  /**
   * Merge {@link ProfileSegment}s from an old simulation into those of a new one, replacing the old with the new.
   *
   * @param tOld start time of the old plan/simulation to correlate offsets
   * @param tNew start time of the new plan/simulation to correlate offsets
   * @param listOld old list of {@link ProfileSegment}s
   * @param listNew new list of {@link ProfileSegment}s
   * @param timeline the {@link TemporalEventSource} from the new simulation to determine where segments should be removed when the segment information isn't enough
   * @return the combined list of {@link ProfileSegment}s
   * @param <D>
   */
  private static <D> List<ProfileSegment<D>> mergeSegmentLists(Instant tOld, Instant tNew, String resourceName,
                                                               List<ProfileSegment<D>> listOld,
                                                               List<ProfileSegment<D>> listNew,
                                                               TemporalEventSource timeline) {
    int i = ctr++;
    // Find difference in simulation start times in the case that the simulations started at different times
    Duration offset = Duration.minus(tNew, tOld);
    // The time elapsed in each of the simulations
    final Duration[] elapsed = {Duration.ZERO, Duration.ZERO};  // need a final variable to satisfy lambda syntax but that allows us to reassign inside.
    // Initialize the times elapsed based on the difference in simulation start times
    if (offset.isNegative()) {
      elapsed[0] = elapsed[0].minus(offset);
    } else {
      elapsed[1] = elapsed[1].plus(offset);
    }

    if (debug) System.out.println("mergeSegmentLists() -- old segments: " + listOld);
    if (debug) System.out.println("mergeSegmentLists() -- new segments: " + listNew);

    var sOld = listOld.stream();
    var sNew = listNew.stream();

    // translate the segment extents into time elapsed.
    Stream<Triple<Duration, Integer, ProfileSegment<D>>> ssOld = sOld.map(p -> {
      var r =  Triple.of(elapsed[0], 1, p);  // This middle index distinguishes old vs new and orders new before old when at the same time.
      elapsed[0] = elapsed[0].plus(p.extent());
      return r;
    });
    Stream<Triple<Duration, Integer, ProfileSegment<D>>> ssNew = sNew.map(p -> {
      var r =  Triple.of(elapsed[1], 0, p);
      elapsed[1] = elapsed[1].plus(p.extent());
      return r;
    });

    // Place a dummy triple at the end of the sorted triples since we need to look at two triples to handle ties in triples with the same time.
    final Triple<Duration, Integer, ProfileSegment<D>> tripleNull = Triple.of(null, null, null);
    final Stream<Triple<Duration, Integer, ProfileSegment<D>>> sorted =
        Stream.concat(Stream.of(ssOld, ssNew).flatMap(s -> s).sorted(), Stream.of(tripleNull));

    // Need a final to satisfy lambda syntax below, but we need to reassign so we enclose in an array.
    final Triple<Duration, Integer, ProfileSegment<D>>[] last = new Triple[] {null};
    var sss = sorted.map(t -> {
      final var lastTriple = last[0];
      last[0] = t; // for the next iteration
      Duration extent = null;

      // We need to look at two triples at a time, so we skip the first iteration.  Nulls will be stripped out later.
      if (lastTriple == null) {
        if (debug) System.out.println("" + i + " skip first iteration");
        return null;
      }

      // This is the last pair of triples, the last being (null, null, null).  Just return the segment in the
      // last non-null triple, lastTriple.
      if (t == tripleNull) {
        if (debug) System.out.println("" + i + " keeping last " + lastTriple);
        return lastTriple.getRight();
      }

        // Compute the duration between triples, translating elapsed time back into segment durations/extents
        extent = t.getLeft().minus(lastTriple.getLeft());

        // If the times are the same (extent == 0), and the new/vs old indices are different, then the new
        // segment replaces the old.  lastTriple is the new triple because of the middle index ordering.
        // We do the replacement by remembering the new (lastTriple) instead of the old (t) for the next
        // iteration and return nothing in this iteration, thus, skipping the old.
        if (extent.isEqualTo(Duration.ZERO) && !lastTriple.getMiddle().equals(t.getMiddle())) {
          if (debug) System.out.println("" + i + " skipping " + t);
          last[0] = lastTriple;
          return null;
        }

      // We need to remove old segments where there are new events and no corresponding new segment.
      // We do this by remembering lastTriple instead of the old segment, t.
      if (timeline != null && t.getMiddle() == 1) {
        var resourcesWithRemovedSegments = timeline.removedResourceSegments.get(t.getLeft());
        if (resourcesWithRemovedSegments != null && resourcesWithRemovedSegments.contains(resourceName)) {
          if (debug) System.out.println("" + i + " skipping removed " + t);
          last[0] = lastTriple;
          return null;
        }
      }

      // Return a profile segment based on oldTriple and the time difference with t
      if (debug) System.out.println("" + i + " keeping " + lastTriple);
      var p = new ProfileSegment<D>(extent, lastTriple.getRight().dynamics());
      return p;
    });

    // remove the nulls, representing skipped, replaced, removed, and non-existent segments, and convert Stream to List
    var mergedSegments = sss.filter(Objects::nonNull).toList();
    if (debug) System.out.println("" + i + " combined segments = " + mergedSegments);

    return mergedSegments;
  }

  private static void testMergeSegmentLists() {
    ProfileSegment<Integer> p1 = new ProfileSegment<>(Duration.of(2, Duration.MINUTES), 0);
    ProfileSegment<Integer> p2 = new ProfileSegment<>(Duration.of(5, Duration.MINUTES), 1);
    ProfileSegment<Integer> p3 = new ProfileSegment<>(Duration.of(5, Duration.MINUTES), 2);

    ProfileSegment<Integer> p0 = new ProfileSegment<>(Duration.of(15, Duration.MINUTES), 0);
    Instant t = Instant.ofEpochSecond(366L * 24 * 3600 * 60);
    var list1 = List.of(p1, p2, p3);
    System.out.println(list1);
    var list2 = List.of(p0);
    System.out.println(list2);
    var list3 = mergeSegmentLists(t, t, null, list2, list1, null);
    System.out.println("merged list3");
    System.out.println(list3);
  }
  public static void main(final String[] args) {
    testMergeSegmentLists();
  }

  // TODO: Looking to modify interleave into a mergeSorted() to merge ProfileSegment Lists, but also need to combine elements.
  //       This wouldn't really avoid any of the messy stuff above, but there's a chance for an efficient Stream.
  public static <T extends Comparable<T>> Stream<T> interleave(Stream<? extends T> a, Stream<? extends T> b) {
    Spliterator<? extends T> spA = a.spliterator(), spB = b.spliterator();
    long s = spA.estimateSize() + spB.estimateSize();
    if(s < 0) s = Long.MAX_VALUE;  // s is negative if there's overflow from addition above
    int ch = spA.characteristics() & spB.characteristics()
             & (Spliterator.NONNULL|Spliterator.SIZED); //|Spliterator.SORTED  // if merging in order instead of interleaving
    ch |= Spliterator.ORDERED;

    return StreamSupport.stream(new Spliterators.AbstractSpliterator<T>(s, ch) {
      Spliterator<? extends T> sp1 = spA, sp2 = spB;

      @Override
      public boolean tryAdvance(final Consumer<? super T> action) {
        Spliterator<? extends T> sp = sp1;
        if(sp.tryAdvance(action)) {
          sp1 = sp2;
          sp2 = sp;
          return true;
        }
        return sp2.tryAdvance(action);
      }
    }, false);
  }

  @Override
  public Map<String, ResourceProfile<SerializedValue>> getDiscreteProfiles() {
    final String[] resourceName = new String[] {null};
    if (_discreteProfiles == null)
      _discreteProfiles = Stream.of(or.getDiscreteProfiles(), nr.getDiscreteProfiles()).flatMap(m -> m.entrySet().stream())
                                .collect(Collectors.toMap(e -> {
                                  resourceName[0] = e.getKey();
                                  if (debug) System.out.println("mergeProfiles for " + e.getKey());
                                  return e.getKey();
                                }, Map.Entry::getValue, (p1, p2) -> mergeProfiles(or.getStartTime(), nr.getStartTime(),
                                                                                  resourceName[0], p1, p2, timeline)));
    return _discreteProfiles;
  }
  private Map<String, ResourceProfile<SerializedValue>> _discreteProfiles = null;

  @Override
  public Map<ActivityInstanceId, ActivityInstance> getSimulatedActivities() {
    var combined = new HashMap<>(or.getSimulatedActivities());
    nr.getRemovedActivities().forEach(simActId -> combined.remove(simActId));
    combined.putAll(nr.getSimulatedActivities());
    return combined;
  }

  /**
   * @return
   */
  @Override
  public Set<ActivityInstanceId> getRemovedActivities() {
    var combined = new HashSet<>(or.getRemovedActivities());
    combined.addAll(nr.getRemovedActivities());
    return combined;
  }

  @Override
  public Map<ActivityInstanceId, UnfinishedActivity> getUnfinishedActivities() {
    var combined = new HashMap<>(or.getUnfinishedActivities());
    combined.putAll(nr.getUnfinishedActivities());
    return combined;
  }

  @Override
  public List<Triple<Integer, String, ValueSchema>> getTopics() {
    // WARNING: Assuming the same topics in old and new!!!
    return nr.getTopics();
  }

  @Override
  public Map<Duration, List<EventGraph<EventRecord>>> getEvents() {
    if (_events != null) return _events;
    // TODO: REVIEW -- Is this right?  Is it the best way to do it?  What about SimulationEngine.getCommitsByTime(),
    //       which already combined them?  Notice the adjustment for sim start time differences!
    var ors = or.getEvents().entrySet().stream().map(e -> Pair.of(e.getKey().plus(Duration.minus(or.getStartTime(),getStartTime())), e.getValue()));
    var nrs = nr.getEvents().entrySet().stream().map(e -> Pair.of(e.getKey().plus(Duration.minus(nr.getStartTime(),getStartTime())), e.getValue()));
    // overwrite old with new where at the same time
    _events = Stream.of(ors, nrs).flatMap(s -> s)
                 .collect(Collectors.toMap(Pair::getKey, Pair::getValue, (list1, list2) -> list2));
    return _events;
  }

  @Override
  public SimulationResultsInterface replaceIds(final Map<ActivityDirectiveId, ActivityDirectiveId> map) {
    return new CombinedSimulationResults(nr.replaceIds(map), or.replaceIds(map), timeline);
  }

  private Map<Duration, List<EventGraph<EventRecord>>> _events = null;

  @Override
  public String toString() {
    return makeString();
  }
}
