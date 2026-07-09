package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.engine.TaskId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SubInstantDuration;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TemporalEventSource implements EventSource, Iterable<TemporalEventSource.TimePoint> {
  public static boolean alwaysfreezable = false; // HACK -- thread unfriendly
  public static boolean neverfreezable = false; // HACK -- thread unfriendly
  public static boolean freezable = alwaysfreezable; // HACK -- thread unfriendly
  public static boolean debug = false;
  private boolean frozen = false;
  private SubInstantDuration timeFroze = null;
  public LiveCells liveCells;
  private MissionModel<?> missionModel;
  public HashMap<EventGraph<Event>, EventGraph<Event>> noReadEvents = new HashMap<>();
  public TreeMap<Duration, List<TimePoint.Commit>> commitsByTime = new TreeMap<>();
  public Map<Topic<?>, TreeMap<Duration, List<EventGraph<Event>>>> eventsByTopic = new HashMap<>();
  public Map<TaskId, TreeMap<Duration, List<EventGraph<Event>>>> eventsByTask = new HashMap<>();
  public Map<EventGraph<Event>, Set<Topic<?>>> topicsForEventGraph = new HashMap<>();
  public Map<EventGraph<Event>, Set<TaskId>> tasksForEventGraph = new HashMap<>();
  public Map<EventGraph<Event>, Duration> timeForEventGraph = new HashMap<>();
  HashMap<Cell<?>, SubInstantDuration> cellTimes = new HashMap<>();

  public HashMap<Duration, Set<Topic<?>>> topicsOfRemovedEvents = new HashMap<>();
  /** Times when a resource profile segment should be removed from the simulation results. */
  public HashMap<Duration, Set<String>> removedResourceSegments = new HashMap<>();
  public TemporalEventSource oldTemporalEventSource;
  protected SubInstantDuration curTime = new SubInstantDuration(Duration.MIN_VALUE, 0);

  public SubInstantDuration curTime() {
    return curTime;
  }

  public void setCurTime(SubInstantDuration time) {
    curTime = time;
  }

  public void setCurTime(Duration time) {
    if (curTime.duration().isEqualTo(time)) return;
    setCurTime(new SubInstantDuration(time, 0));
  }

  private static int ctr = 0;
  private final int i = ctr++;  // An instance id to differentiate old and new TemporalEventSources

  /**
   * cellCache keeps duplicates and old cells that can be reused to more quickly get a past cell value.
   * For example, if a task needs to re-run but starts in the past, we can re-run it from a past point,
   * and successive reads a cell can use a duplicate cached cell stepped up from its initial state.
   */
  private final HashMap<Topic<?>, TreeMap<SubInstantDuration, Cell<?>>> cellCache = new HashMap<>();

  /** When topics/cells become stale */
  public final Map<Topic<?>, TreeMap<SubInstantDuration, Boolean>> staleTopics = new HashMap<>();


  public TemporalEventSource() {
    this(null, null, null);
  }

  public TemporalEventSource(
      final LiveCells liveCells,
      final MissionModel<?> missionModel,
      final TemporalEventSource oldTemporalEventSource)
  {
    this.liveCells = liveCells;
    this.missionModel = missionModel;
    this.oldTemporalEventSource = oldTemporalEventSource;
    // Assumes the current time is zero, and the cells have not yet been stepped.
    if (liveCells != null) {
      for (LiveCell<?> liveCell : liveCells.getCells()) {
        final Cell<?> cell = liveCell.get();
        putCellTime(cell, Duration.ZERO, 0);
      }
    }
  }

  public TemporalEventSource(LiveCells liveCells) {
    this(liveCells, null, null);
  }

  // When adding a new commit to the timeline, we need to combine it with pre-existing commits.
  // If the commit is an empty graph, we only want to use it to fill the array element at stepIndexAtTime
  // when there is nothing in the old or new graph filling that spot.  Otherwise, we can ignore it.
  public void add(final EventGraph<Event> graph, Duration time, final int stepIndexAtTime,
                  final Topic<Topic<?>> queryTopic) {
    if (this.frozen) {
      throw new IllegalStateException("Cannot add to frozen TemporalEventSource");
    }
    if (debug) System.out.println("TemporalEventSource:add(" + graph + ", " + time + ", " + stepIndexAtTime + ")");
    List<TimePoint.Commit> commits = commitsByTime.get(time);
    if (debug) System.out.println("TemporalEventSource:add(): commits = " + commits);

    // copy old commits to new timeline if haven't already
    boolean copyingCommits = oldTemporalEventSource != null && (commits == null || commits.isEmpty());
    if (copyingCommits) {
      commits = oldTemporalEventSource.getCombinedCommitsByTime().get(time);
      if (commits != null && !commits.isEmpty()) {
        commits = new ArrayList<>(commits);// Make a copy of list to avoid modifying the old timeline
        commitsByTime.put(time, commits);
      }
    }

    // combine the newEventGraph concurrently with the existing one at the corresponding time step index
    var newEventGraph = graph;
    boolean combineGraphs = oldTemporalEventSource != null && commits != null && commits.size() > stepIndexAtTime;
    if (combineGraphs) {  // commits in new graph already replacing old
      newEventGraph = EventGraph.concurrently(graph, commits.get(stepIndexAtTime).events());
    }

    // update the commit and its topics
    var topics = extractTopics(newEventGraph);
    var commit = new TimePoint.Commit(newEventGraph, topics);

    // Remove topics from the "removed" set if they're being re-added by this new event graph.
    // This handles the case where an activity is deleted and a new activity is added at the same time.
    var removedAtTime = this.topicsOfRemovedEvents.get(time);
    if (removedAtTime != null && !removedAtTime.isEmpty() && !topics.isEmpty()) {
      var intersection = new HashSet<>(removedAtTime);
      intersection.retainAll(topics);
      if (!intersection.isEmpty()) {
        System.err.println("[FIX] Time=" + time + " removing " + intersection + " from removedEvents");
        removedAtTime.removeAll(topics);
      }
    }

    // put the commit into the list of commits at for the time/offset
    if (combineGraphs) {
      commits.set(stepIndexAtTime, commit);
      commitsByTime.put(time, commits);
    } else {
      // If not combining with an existing graphs, just add to the end of the list.
      commitsByTime.computeIfAbsent(time, $ -> new ArrayList<>()).add(commit);
      commits = commitsByTime.get(time);
    }

    // Add indices for the new and copied commits
    // NOTE: since this is additive, we don't need to worry about replacing the old pre-combined graph's indices
    if (copyingCommits && commits != null) {
      commits.forEach(c -> addIndices(c, time, oldTemporalEventSource.getTopicsForEventGraph(c.events)));
    } else {
      addIndices(commit, time, topics);
    }
    if (debug) System.out.println("TemporalEventSource:add(): " + (copyingCommits ? "copyingCommits, " : "") +
                                  (combineGraphs? "combineGraphs, " : "") + "commits = " + commits);
  }

  /**
   * Strip out the read events from the EventGraph and store in a cache if haven't already
   * @param graph the graph with read events
   * @return the graph without read events
   */
  public EventGraph<Event> withoutReadEvents(EventGraph<Event> graph) {
    EventGraph<Event> g = noReadEvents.get(graph);
    if (g == null) {
      g = graph.filter(e -> e.topic() != MissionModel.queryTopic);
      noReadEvents.put(graph, g);
    }
    return g;
  }

  /**
   * Index the commit and graph by time, topic, and task.
   * For multiple commits at the same time, we assume addIndices() is called for each commit in the sequential order
   * that they are to be applied.
   *
   * @param commit the commit of Events to add
   * @param time the time as a Duration when the events occur
   */
  protected void addIndices(final TimePoint.Commit commit, Duration time, Set<Topic<?>> topics) {
    if (this.frozen) {
      throw new IllegalStateException("Cannot add to frozen TemporalEventSource");
    }
    final var finalTopics = topics == null ? extractTopics(commit.events) : topics;
    final var tasks = extractTasks(commit.events);
    timeForEventGraph.put(commit.events, time);
    var eventList = commitsByTime.get(time).stream().map(c -> c.events).toList();
    if (finalTopics != null)
      finalTopics.forEach(t -> this.eventsByTopic.computeIfAbsent(t, $ -> new TreeMap<>()).put(time, eventList));
    tasks.forEach(t -> this.eventsByTask.computeIfAbsent(t, $ -> new TreeMap<>()).put(time, eventList));
    // TODO: REVIEW -- do we really need all these maps?
    if (finalTopics != null)
      topicsForEventGraph.computeIfAbsent(commit.events, $ -> HashSet.newHashSet(finalTopics.size())).addAll(finalTopics);
    tasksForEventGraph.computeIfAbsent(commit.events, $ -> HashSet.newHashSet(tasks.size())).addAll(tasks);
  }

  public Map<? extends Topic<?>, TreeMap<Duration, List<EventGraph<Event>>>> getCombinedEventsByTopic() {
    if (oldTemporalEventSource == null) return eventsByTopic;
    if (_eventsByTopic != null && eventsByTopic.size() == _numEventsByTopic) {
      return _eventsByTopic;
    }
    _numEventsByTopic = eventsByTopic.size();
    if (_oldEventsByTopic == null) {
      _oldEventsByTopic = oldTemporalEventSource.getCombinedEventsByTopic();
      oldTemporalEventSource._oldEventsByTopic = null;
    }
    _eventsByTopic = Stream.of(eventsByTopic, _oldEventsByTopic).flatMap(m -> m.entrySet().stream())
        .collect(Collectors.toMap(t -> t.getKey(), t -> t.getValue(), (m1, m2) -> mergeMapsFirstWins(m1, m2)));
    return _eventsByTopic;
  }
  private Map<? extends Topic<?>, TreeMap<Duration, List<EventGraph<Event>>>> _oldEventsByTopic = null;
  private Map<? extends Topic<?>, TreeMap<Duration, List<EventGraph<Event>>>> _eventsByTopic = null;
  private long _numEventsByTopic = 0;

  public static <K, V> HashMap<K, V> mergeHashMapsFirstWins(HashMap<K, V> m1, HashMap<K, V> m2) {
    if (m1 == null) return m2;
    if (m2 == null || m2.isEmpty()) return m1;
    if (m1.isEmpty()) return m2;
    return Stream.of(m1, m2).flatMap(m -> m.entrySet().stream()).collect(Collectors.toMap(t -> t.getKey(),
                                                                                          t -> t.getValue(),
                                                                                          (v1, v2) -> v1,
                                                                                          HashMap::new));
  }
  public static <K, V> TreeMap<K, V> mergeMapsFirstWins(TreeMap<K, V> m1, TreeMap<K, V> m2) {
    if (m1 == null) return m2;
    if (m2 == null || m2.isEmpty()) return m1;
    if (m1.isEmpty()) return m2;
    return Stream.of(m1, m2).flatMap(m -> m.entrySet().stream()).collect(Collectors.toMap(t -> t.getKey(),
                                                                                          t -> t.getValue(),
                                                                                          (v1, v2) -> v1,
                                                                                          TreeMap::new));
  }
  public static <K, V> TreeMap<K, V> deepMergeMapsFirstWins(TreeMap<K, V> m1, TreeMap<K, V> m2) {
    if (m1 == null) return m2;
    if (m2 == null || m2.isEmpty()) return m1;
    if (m1.isEmpty()) return m2;
    return Stream.of(m1, m2).flatMap(m -> m.entrySet().stream()).collect(Collectors.toMap(t -> t.getKey(),
                                                                                          t -> t.getValue(),
                                                                                          (v1, v2) -> (v1 instanceof TreeMap mm1 && v2 instanceof TreeMap mm2) ? (V)deepMergeMapsFirstWins(mm1, mm2) : v1,
                                                                                          TreeMap::new));
  }

  public Duration getTimeForEventGraph(EventGraph<Event> g) {
    var time = timeForEventGraph.get(g);
    if (time == null && oldTemporalEventSource != null) {
      time = oldTemporalEventSource.getTimeForEventGraph(g);
    }
    return time;
  }

  public Set<TaskId> getTasksForEventGraph(EventGraph<Event> g) {
    var tasks = tasksForEventGraph.get(g);
    if (tasks == null && oldTemporalEventSource != null) {
      tasks = oldTemporalEventSource.getTasksForEventGraph(g);
    }
    return tasks;
  }

  public Set<Topic<?>> getTopicsForEventGraph(EventGraph<Event> g) {
    var topics = topicsForEventGraph.get(g);
    if (topics == null && oldTemporalEventSource != null) {
      topics = oldTemporalEventSource.getTopicsForEventGraph(g);
    }
    return topics;
  }

  /**
   * Replace an {@link EventGraph} with another in the various lookup data structures.  {@link EventGraph}s are
   * unique per instance; i.e., {@code equals()} is {@code ==}.  Thus, a graph only occurs at one point in time.
   * This simplifies the implementation.  If the graph to be replaced only exists in the old timeline,
   * {@link TemporalEventSource#oldTemporalEventSource}, then the new graph must be inserted in {@code this}
   * {@link TemporalEventSource} along with any other graphs at the same time in the old timeline.
   *
   * @param oldG the {@link EventGraph} to be replaced
   * @param newG the {@link EventGraph} replacing {@code oldG}
   */
  public void replaceEventGraph(EventGraph<Event> oldG, EventGraph<Event> newG) {
    // Need to replace in this.{timeForEventGraph, commitsByTime, tasksForEventGraph, eventsByTask, topicsForEventGraph,
    // eventsByTopic, points}
    // TODO: points can't be updated, so we should try to remove this.points
    final var newTopics = extractTopics(newG);

    // time - timeForEventGraph
    Duration timeNew = timeForEventGraph.remove(oldG);
    Duration timeOld = oldTemporalEventSource.getTimeForEventGraph(oldG);
    Duration time = timeNew == null ? timeOld : timeNew;
    if (time == null) {
      throw new RuntimeException("Can't find EventGraph to replace!");
    }
    timeForEventGraph.put(newG, time);
    // time - commitsByTime
    var newCommit = new TimePoint.Commit(newG, newTopics);
    var commitList = commitsByTime.get(time);
    if (commitList == null) {
      // copy from old timeline
      commitList = oldTemporalEventSource.getCombinedCommitsByTime().get(time);
      if (commitList != null) {
        commitList = new ArrayList<>(commitList);
      }
    }
    commitList.replaceAll(c -> c.events.equals(oldG) ? newCommit : c);
    commitsByTime.put(time, commitList);

    var eventList = commitsByTime.get(time).stream().map(c -> c.events).toList();

    // task - tasksForEventGraph
    var oldTasks = tasksForEventGraph.remove(oldG);
    final var newTasks = extractTasks(newG);
    tasksForEventGraph.put(newG, newTasks);
    // task - eventsByTask

    // eventsByTask is a Map<TaskId, TreeMap<Duration, List<EventGraph<Event>>>>
    // The list of EventGraphs per Duration includes the list of all EventGraphs in commitsByTime (eventList)
    // whether or not each have the task.
    //
    // There could be a task t in oldG in the old timeline that is not in newG.  this.eventsByTask.get(t).get(time)
    // should be empty if no other EventGraphs at this time include task t, but it's not a problem if the graphs remain,
    // as long as the graphs were replaced.
    if (oldTasks == null) {
      oldTasks = oldTemporalEventSource.getTasksForEventGraph(oldG);
    }
    var allTasks = new HashSet<TaskId>();
    if (oldTasks != null) allTasks.addAll(oldTasks);
    allTasks.addAll(newTasks);
    allTasks.forEach(t -> {
        eventsByTask.computeIfAbsent(t, $ -> new TreeMap<>()).put(time, eventList);
    });

    // topic - topicsForEventGraph
    var oldTopics = topicsForEventGraph.remove(oldG);
    if (oldTopics == null) {
      oldTopics = oldTemporalEventSource.getTopicsForEventGraph(oldG);
    }
    topicsForEventGraph.put(newG, newTopics);
    var allTopics = new HashSet<Topic<?>>();
    if (oldTopics != null) allTopics.addAll(oldTopics);
    Set<Topic<?>> lostTopics = (oldTopics == null) ? Set.of() : oldTopics.stream().filter(t -> !newTopics.contains(t)).collect(Collectors.toSet());
    this.topicsOfRemovedEvents.computeIfAbsent(time, $ -> new HashSet<>()).addAll(lostTopics);
    allTopics.addAll(newTopics);
    allTopics.forEach(t -> {
        eventsByTopic.computeIfAbsent(t, $ -> new TreeMap<>()).put(time, eventList);
    });
  }

  /**
   * An Iterator for a TreeMap that allows it to grow by appending new entries (i.e. put(k, v) where k is greater than
   * all keys in keySet()).
   *
   * @param <K>
   * @param <V>
   */
  private class TreeMapIterator<K, V> implements Iterator<Map.Entry<K, V>> {

    private TreeMap<K, V> treeMap;
    /** The key of the last entry returned by next() */
    private K lastKey = null;
    private Iterator<Map.Entry<K, V>> iterator = null;
    /** The size of the map when we last checked. If this has changed, then the iterator must be reset based on lastKey */
    private long size;

    private static int ctr = 0;
    private final int i = ctr++;


    public TreeMapIterator(TreeMap<K, V> treeMap) {
      this.treeMap = treeMap;
      size = treeMap.size();
      iterator = treeMap.entrySet().iterator();
      if (debug) System.out.println("" + i + " TreeMapIterator(): " + treeMap);
    }

    /**
     * Returns {@code true} if the iteration has more elements.
     * (In other words, returns {@code true} if {@link #next} would
     * return an element rather than throwing an exception.)
     *
     * @return {@code true} if the iteration has more elements
     */
    @Override
    public boolean hasNext() {
      if (size != treeMap.size()) {  // treeMap has grown, reset iterator
        if (debug) System.out.println("" + i + " TreeMapIterator.hasNext(): size " + size + " <- " + treeMap.size());
        if (debug) System.out.println("" + i + " TreeMapIterator.hasNext(): treeMap = " + treeMap);
        size = treeMap.size();
        if (lastKey == null) {
          iterator = treeMap.entrySet().iterator();
          if (debug) System.out.println("" + i + " TreeMapIterator.hasNext(): iterator <- " + treeMap);
        } else {
          var submap = treeMap.tailMap(lastKey, false);
          if (debug) System.out.println("" + i + " TreeMapIterator.hasNext(): tailMap(lastKey=" + lastKey + ") = " + submap);
          if (submap != null) {
            iterator = submap.entrySet().iterator();
            if (debug) System.out.println("" + i + " TreeMapIterator.hasNext(): iterator <- " + submap);
          } else {
            throw new RuntimeException("no submap!");
          }
        }
      }
      if (iterator != null && iterator.hasNext()) return true;
      return false;
    }

    /**
     * Returns the next element in the iteration.
     *
     * @return the next element in the iteration
     * @throws NoSuchElementException if the iteration has no more elements
     */
    @Override
    public Map.Entry<K, V> next() {
      if (!hasNext()) throw new NoSuchElementException();
      if (iterator == null) throw new NoSuchElementException();
      var e = iterator.next();
      if (debug) System.out.println("" + i + " TreeMapIterator.next(): lastKey changed from " + lastKey + " to " + e.getKey());
      lastKey = e.getKey();
      if (debug) System.out.println("" + i + " TreeMapIterator.next(): returning " + e);
      return e;
    }
  }

  public class CombinedTreeMapIterator<V, K extends Comparable<K>> implements Iterator<Map.Entry<K, V>> {

    Iterator<Map.Entry<K, V>> i1, i2;
    BiFunction<Entry<K, V>, Entry<K, V>, Entry<K, V>> combiner;
    Map.Entry<K, V> last1 = null;
    Map.Entry<K, V> last2 = null;

    public CombinedTreeMapIterator(final Iterator<Entry<K, V>> i1, final Iterator<Entry<K, V>> i2,
                                   BiFunction<Entry<K, V>, Entry<K, V>, Entry<K, V>> combiner) {
      this.i1 = i1;
      this.i2 = i2;
      this.combiner = combiner;
    }

    @Override
    public boolean hasNext() {
      return last1 != null || last2 != null || i1.hasNext() || i2.hasNext();
    }

    @Override
    public Entry<K, V> next() {
      if (last1 == null && i1.hasNext()) {
        last1 = i1.next();
      }
      if (last2 == null && i2.hasNext()) {
        last2 = i2.next();
      }
      if (last1 == null && last2 == null) {
        throw new NoSuchElementException();
      }
      if (last1 == null) {
        var tmp = last2;
        last2 = null;
        return tmp;
      }
      if (last2 == null) {
        var tmp = last1;
        last1 = null;
        return tmp;
      }
      int c = last1.getKey().compareTo(last2.getKey());
      if (c < 0) {
        var tmp = last1;
        last1 = null;
        return tmp;
      }
      if (c > 0) {
        var tmp = last2;
        last2 = null;
        return tmp;
      }
      var result = combiner.apply(last1, last2);
      last1 = null;
      last2 = null;
      return result;
    }
  }

  /**
   * @return a {@link TreeMap} of {@link TimePoint.Commit}s by time ({@link Duration}) combining
   * the {@link TemporalEventSource#commitsByTime} those of the {@link TemporalEventSource#oldTemporalEventSource}
   * and nested {@link TemporalEventSource#oldTemporalEventSource}s.
   * <p>
   *   The caller should be careful not to modify the returned TreeMap since it might be an actual
   *   {@link TemporalEventSource#commitsByTime}.
   * </p>
   */
  public TreeMap<Duration, List<TimePoint.Commit>> getCombinedCommitsByTime() {
    final var mNew = commitsByTime;
    if (oldTemporalEventSource == null) return mNew;
    final TreeMap<Duration, List<TimePoint.Commit>> mOld;
    if (_combinedCommitsByTime != null && mNew.size() == _numberCommitsByTime) {
      return _combinedCommitsByTime;
    }
    _numberCommitsByTime = mNew.size();
    if (_oldCombinedCommitsByTime != null) {
      mOld = _oldCombinedCommitsByTime;
    } else {
      mOld = oldTemporalEventSource.getCombinedCommitsByTime();
      _oldCombinedCommitsByTime = mOld;
      oldTemporalEventSource._oldCombinedCommitsByTime = null;
    }
    _combinedCommitsByTime = mergeMapsFirstWins(mNew, mOld);
    return _combinedCommitsByTime;
  }
  private TreeMap<Duration, List<TimePoint.Commit>> _oldCombinedCommitsByTime = null;
  private TreeMap<Duration, List<TimePoint.Commit>> _combinedCommitsByTime = null;
  private long _numberCommitsByTime = 0;


  private class TimePointIteratorFromCommitMap implements Iterator<TimePoint> {

    private Iterator<Map.Entry<Duration, List<TimePoint.Commit>>> i;
    private Duration time = Duration.ZERO;
    private Map.Entry<Duration, List<TimePoint.Commit>> lastEntry = null;
    private Iterator<TimePoint.Commit> commitIter = null;

    public TimePointIteratorFromCommitMap(Iterator<Map.Entry<Duration, List<TimePoint.Commit>>> i) {
      this.i = i;
    }

    @Override
    public boolean hasNext() {
      if (commitIter != null && commitIter.hasNext()) return true;
      if (i.hasNext()) return true;
      if (lastEntry != null) {
        if (lastEntry.getKey().longerThan(time)) return true;
        if (!lastEntry.getValue().isEmpty()) return true;
      }
      return false;
    }

    public TimePoint peek() {  // why do I always want this??!!
      return null;
    }

    @Override
    public TimePoint next() {
      if (commitIter != null) {
        if (commitIter.hasNext()) {
          return commitIter.next();
        } else {
          commitIter = null;
        }
      }
      if (lastEntry == null) lastEntry = i.next();
      if (lastEntry.getKey().longerThan(time)) {
        var delta = new TimePoint.Delta(lastEntry.getKey().minus(time));
        time = lastEntry.getKey();
        commitIter = lastEntry.getValue().iterator();
        lastEntry = null;
        return delta;
      }
      commitIter = lastEntry.getValue().iterator();
      while (!commitIter.hasNext()) {
        if (!i.hasNext()) {
          throw new NoSuchElementException();
        }
        lastEntry = i.next();
        commitIter = lastEntry.getValue().iterator();
      }
      if (commitIter.hasNext()) {
        lastEntry = null;
        return commitIter.next();
      }
      throw new NoSuchElementException();
    }
  }

  @Override
  public Iterator<TimePoint> iterator() {
    // Create an iterator that combines the old and new EventGraph timelines
    // This TemporalEventSource only keeps modifications of EventGraphs from the oldTemporalEventSource.

    // The idea is to get a combined commitsByTime map rolling up the nested commitsByTime members of
    // TemporalEventSource.  Then, convert that into sequence of TimePoints.  However, this iterator
    // may be constructed (and possibly used) before commitsByTime has been filled by the simulation.
    // This allows us to use this iterator to stream information during simulation to pipeline computation.
    // Thus, we provide an iterator (TreeMapIterator) that works for a growing map of commitsByTime.
    // So, instead of combining maps, we need to combine iterators.  But, we can simplify this by
    // assuming that the simulation is complete in the oldTemporalEventSource, and can combine those
    // old nested commitsByTime with oldTemporalEventSource.getCombinedCommitsByTime().  Then we
    // can combine the iterators of the old and new commitsByTime, and convert that iterator into one
    // that generates TimePoints instead of map entries.
    Iterator<Map.Entry<Duration, List <TimePoint.Commit>>> treeMapIter;
    var i1 = new TreeMapIterator<>(commitsByTime);
    if (oldTemporalEventSource == null) {
      treeMapIter = i1;
    } else {
      var m = oldTemporalEventSource.getCombinedCommitsByTime();
      var i2 = m.entrySet().iterator();
      treeMapIter = new CombinedTreeMapIterator<>(i1, i2, (list1, list2) -> list1);
    }
    var i3 = new TimePointIteratorFromCommitMap(treeMapIter);
    return i3;
  }

  public void setTopicStale(Topic<?> topic, SubInstantDuration offsetTime) {
    if (debug) System.out.println("setTopicStale(" + topic + ", " + offsetTime + ")");
    staleTopics.computeIfAbsent(topic, $ -> new TreeMap<>()).put(offsetTime, true);
  }

  public void setTopicUnstale(Topic<?> topic, SubInstantDuration offsetTime) {
    if (debug) System.out.println("setTopicUnStale(" + topic + ", " + offsetTime + ")");
    staleTopics.computeIfAbsent(topic, $ -> new TreeMap<>()).put(offsetTime, false);
  }

  /**
   * Determine whether a topic been marked stale at a specified time.
   * @param topic topic to check for staleness
   * @param timeOffset the staleness time
   * @return true if the topic is marked stale at timeOffset
   */
  public boolean isTopicStale(Topic<?> topic, SubInstantDuration timeOffset) {
    if (oldTemporalEventSource == null) return true;
    var map = this.staleTopics.get(topic);
    if (map == null) return false;
    final var staleTime = map.floorKey(timeOffset);
    return staleTime != null && map.get(staleTime);
  }

  public Optional<SubInstantDuration> whenIsTopicStale(Topic<?> topic, SubInstantDuration earliestTimeOffset, SubInstantDuration latestTimeOffset) {
    if (oldTemporalEventSource == null) return Optional.of(earliestTimeOffset);
    var map = this.staleTopics.get(topic);
    if (map == null) return Optional.empty();
    final SubInstantDuration staleTime = map.floorKey(earliestTimeOffset);
    if (staleTime != null && map.get(staleTime)) {
      return Optional.of(earliestTimeOffset);
    }
    if (earliestTimeOffset.noLongerThan(latestTimeOffset)) {
      var submap = map.subMap(earliestTimeOffset, true, latestTimeOffset, true);
      for (Map.Entry<SubInstantDuration, Boolean> e : submap.entrySet()) {
        if (e.getValue()) return Optional.of(e.getKey());
      }
    }
    return Optional.empty();
  }



  /**
   * Step up the Cell for one set of Events (an EventGraph) up to a specified last Event.  Stepping up means to
   * apply Effects from Events up to some point in time.  The EventGraph represents partially time-ordered events.
   * Thus, the Cell may be stepped up to an Event within that partial order.
   * <p>
   * Staleness is not checked here and must be handled by the caller.
   *
   * @param cell the Cell to step up
   * @param events the Events that may affect the Cell
   * @param lastEvent a boundary within the graph of Events beyond which Events are not applied
   * @param includeLast whether to apply the Effect of the last Event
   */
  public void stepUp(final Cell<?> cell, EventGraph<Event> events, final Event lastEvent, final boolean includeLast) {
    if (debug) System.out.println("" + i + " stepUp to event BEGIN (cell=" + cell + ", events=" + events + ", lastEvent=" + lastEvent + ", includeLast=" + includeLast + ")");
    cell.apply(events, lastEvent, includeLast);
    if (debug) System.out.println("" + i + " stepUp to event END, cell=" + cell);
  }

  /**
   * Step up a cell ignoring the oldTemporalEventSource.  See {@link #stepUp(Cell, SubInstantDuration)}.
   * @param cell the Cell to step up
   * @param endTime the time to which the cell is stepped
   * @param beforeEvent a boundary within the graph of Events beyond which the cell is not stepped
   *
   * Note: Since cell times do not specify partial application of an EventGraph, if passing beforeEvent,
   *       calls to putCellTime() may not accurately reflect the state of the cell since an
   *       EventGraph may only be partially applied.  Thus, the caller should pass in a duplicated cell, whose cell time
   *       has been recorded with putCellTime(), and after calling, the duplicated cell's time should be removed.
   */
  public boolean stepUpSimple(final Cell<?> cell, SubInstantDuration endTime, Event beforeEvent) {
    if (debug) System.out.println("" + i + " stepUpSimple(cell=" + cell + "[" + getCellTime(cell) + "] topics=" + cell.getTopics() + ", endTime=" + endTime + ") -- BEGIN");
    if (debug && TemporalEventSource.freezable &&
        cell.doneStepping) {
      System.out.println("" + i + " WARNING! stepUpSimple(cell=" + cell + ") called when cell is already done stepping!");
    }
    final NavigableMap<Duration, List<EventGraph<Event>>> subTimeline;
    var cellTime = getCellTime(cell);
    if (debug) System.out.println("" + i + " cell time: " + cellTime);
    if (cellTime.longerThan(endTime)) {
      throw new UnsupportedOperationException("" + i + " Trying to step cell from the past");
    }
    boolean foundBeforeEvent = false;
    SubInstantDuration timeAfterLastEvent = null;
    try {
      final TreeMap<Duration, List<EventGraph<Event>>> eventsByTimeForTopic = eventsByTopic.get(cell.getTopic());
      // If there are no events to apply, we can exit; if we're frozen, then we're done stepping.
      // Before exiting, we can step up to the end time or time froze, whichever is first.
      if (eventsByTimeForTopic == null || eventsByTimeForTopic.isEmpty()) {
        var endTimeForFinalTimeStep = isFrozen() ? SubInstantDuration.min(endTime, timeFroze()) : endTime;
        if (endTimeForFinalTimeStep.longerThan(cellTime) && endTimeForFinalTimeStep.shorterThan(Duration.MAX_VALUE) && !foundBeforeEvent) {
          var prevCellTime = cellTime;
          Duration timeDelta = endTimeForFinalTimeStep.duration().minus(cellTime.duration());
          if (timeDelta.isPositive()) {
            if (debug) System.out.println("" + i + " cell.step(" + timeDelta + ")");
            cell.step(timeDelta);
            cellTime = new SubInstantDuration(endTimeForFinalTimeStep.duration(), 0);
          } else {
            cellTime = endTimeForFinalTimeStep;
          }
          putCellTime(cell, prevCellTime, cellTime);
        }
        if (debug) System.out.println("" + i + " stepUpSimple(cell=" + cell + "[" + getCellTime(cell) + "], endTime=" + endTime + ") no events -- END");
        cell.doneStepping = cell.doneStepping || isFrozen();
        return false;
      }
      // get the events to apply in the time window
      // This ignores the time froze, but if timeFroze is before an event, that could be a problem and deserves a warning, at least.
      subTimeline = eventsByTimeForTopic.subMap(cellTime.duration(), true, endTime.duration(), true);//endTime.index() > 0);
      timeAfterLastEvent = getTimeAfterLastEvent(subTimeline);
      if (isFrozen() && !subTimeline.isEmpty()) {
        if (timeAfterLastEvent != null && timeAfterLastEvent.longerThan(timeFroze())) {
          System.out.println("" + i + " WARNING! TemporalEventSource time froze (" + timeFroze() +
                             ") is shorter than the time of the last applicable event (" + timeAfterLastEvent + ") for cell topic, " + cell.getTopic());
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    // Apply the events
    for (Entry<Duration, List<EventGraph<Event>>> e : subTimeline.entrySet()) {
      if (foundBeforeEvent) break;
      final List<EventGraph<Event>> eventGraphList = e.getValue();
      // step up in time if necessary to the event
      var delta = e.getKey().minus(cellTime.duration());
      if (delta.isPositive()) {
        if (debug) System.out.println("" + i + " cell.step(" + delta + ")");
        cell.step(delta);
        var prevCellTime = cellTime;
        cellTime = new SubInstantDuration(e.getKey(), 0);
        putCellTime(cell, prevCellTime, cellTime);
      } else if (delta.isNegative()) {
        throw new UnsupportedOperationException("" + i + " Trying to step cell from the past");
      }
      // Not using endOfGraphs for now to work around an inconsistency with how SimulationEngine.stepIndexAtTime is incremented
//        var endOfGraphs = new SubInstantDuration(e.getKey(), eventGraphList.size());
      /*
      if (cellTime.longerThan(endOfGraphs) && cellTime.duration().isEqualTo(endOfGraphs.duration()) &&
          cellTime.index() == Integer.MAX_VALUE) {
        var prevCellTime = cellTime;
        cellTime = endOfGraphs;
        putCellTime(cell, prevCellTime, cellTime);
      }
      if (cellTime.longerThan(endOfGraphs)) {
        throw new UnsupportedOperationException("" + i + " Trying to step cell from the past");
      }
       */
//      if (cellTime.noShorterThan(endOfGraphs)) {
//        // We've already applied all graphs; not doing it twice!
//      } else {
        int maxStepIndex = Math.min(eventGraphList.size(),
                                    cellTime.duration().isEqualTo(endTime.duration()) ? endTime.index() : Integer.MAX_VALUE);
        var cellSteppedAtTime = cellTime.index();
        for (; cellSteppedAtTime < maxStepIndex; ++cellSteppedAtTime) {
          var eventGraph = eventGraphList.get(cellSteppedAtTime);
          if (beforeEvent == null) eventGraph = withoutReadEvents(eventGraph);
          if (debug) System.out.println("" + i + " cell.apply(" + eventGraph + ")");
          foundBeforeEvent = cell.apply(eventGraph, beforeEvent, false);
          if (foundBeforeEvent) break;
        }
        var prevCellTime = cellTime;
        cellTime = new SubInstantDuration(e.getKey(), cellSteppedAtTime);
        putCellTime(cell, prevCellTime, cellTime);
//      }
    }
    // Now, step up after applying events.  If there are more events to apply, we must have stopped because of the
    // endTime or beforeEvent.  If timeFroze is before the last event, then we should at least log a warning.
    var endTimeForFinalTimeStep = isFrozen() ? SubInstantDuration.min(endTime, timeFroze()) : endTime;
    cell.doneStepping = cell.doneStepping ||
                        (isFrozen() && (timeAfterLastEvent == null ||
                                        timeAfterLastEvent.noLongerThan(endTimeForFinalTimeStep)));
    if (endTimeForFinalTimeStep.longerThan(cellTime) && endTimeForFinalTimeStep.shorterThan(Duration.MAX_VALUE) && !foundBeforeEvent) {
      var prevCellTime = cellTime;
      Duration timeDelta = endTimeForFinalTimeStep.duration().minus(cellTime.duration());
      if (timeDelta.isPositive()) {
        if (debug) System.out.println("" + i + " cell.step(" + timeDelta + ")");
        cell.step(timeDelta);
        cellTime = new SubInstantDuration(endTimeForFinalTimeStep.duration(), 0);
      } else {
        cellTime = endTimeForFinalTimeStep;
      }
      putCellTime(cell, prevCellTime, cellTime);
    }
    if (debug) System.out.println("" + i + " stepUpSimple(" + cell + "[" + getCellTime(cell) + "], endTime=" + endTime + ") --> found beforeEvent=" + foundBeforeEvent + " -- END");
    return foundBeforeEvent;
  }

  /**
   * Step up the Cell through the timeline of EventGraphs.  Stepping up means to
   * apply Effects from Events up to some point in time.
   *
   * @param cell the Cell to step up
   * @param endTime the time up to which the cell is stepped
   */
  public <D> void stepUp(final Cell<D> cell, final SubInstantDuration endTime) {
    stepUp(cell, endTime, null);
  }

  /**
   * Step up the Cell through the timeline of EventGraphs.  Stepping up means to
   * apply Effects from Events up to some point in time.
   *
   * @param cell the Cell to step up
   * @param endTime the time up to which the cell is stepped
   * @param beforeEvent if not null, the event at which stepping stops (without applying the event)
   */
  public <D> void stepUp(final Cell<D> cell, final SubInstantDuration endTime, final Event beforeEvent) {
    // Separate out the simpler case of no past simulation for readability
    if (oldTemporalEventSource == null) {
      stepUpSimple(cell, endTime, beforeEvent);
      return;
    }

    // Get the relevant submap of EventGraphs for both the old and new timelines.
    final NavigableMap<Duration, List<EventGraph<Event>>> subTimeline;
    NavigableMap<Duration, List<EventGraph<Event>>> oldSubTimeline;
    var cellTime = getCellTime(cell);
    final var originalCellTime = cellTime;
    if (cellTime.longerThan(endTime)) {
      throw new UnsupportedOperationException("Trying to step cell from the past.");
    }
    final TreeMap<Duration, List<EventGraph<Event>>> mo;
    try {
      var t = cell.getTopic();
      var m = eventsByTopic.get(t);
      if (debug) System.out.println("eventsByTopic(" + t + ") = " + eventsByTopic.get(t));
      subTimeline = m == null ? null : m.subMap(cellTime.duration(), true, endTime.duration(), endTime.index() > 0);
      mo = oldTemporalEventSource.getCombinedEventsByTopic().get(t);
      oldSubTimeline = mo == null ? null : mo.subMap(cellTime.duration(), true, endTime.duration(), endTime.index() > 0);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    // Initialize submap entries and iterators
    var iter = subTimeline == null ? null : subTimeline.entrySet().iterator();
    var entry = iter != null && iter.hasNext() ? iter.next() : null;
    var entryTime = entry == null ? Duration.MAX_VALUE : entry.getKey();
    var oldCell = getOldCell(cell).orElseThrow();
    var oldCellTime = oldTemporalEventSource.getCellTime(oldCell);
    if (oldCellTime.longerThan(cellTime)) {
      oldCell = oldTemporalEventSource.<D>getOrCreateCellInCache(cell.getTopic(), cellTime);
      oldCellTime = oldTemporalEventSource.getCellTime(oldCell);
    } else {
      oldTemporalEventSource.stepUp(oldCell, cellTime, null);
      oldCellTime = oldTemporalEventSource.getCellTime(oldCell);
    }
    final var originalOldCellTime = oldCellTime;
    var oldIter = oldSubTimeline == null ? null : oldSubTimeline.entrySet().iterator();
    var oldEntry = oldIter != null && oldIter.hasNext() ? oldIter.next() : null;
    var oldEntryTime = oldEntry == null ? Duration.MAX_VALUE : oldEntry.getKey();
    var stale = TemporalEventSource.this.isTopicStale(cell.getTopic(), cellTime);
    if (debug) System.out.println("" + i + " BEGIN stepUp(" + cell.getTopic() + ", " + endTime + "): cellState = " + cell.toString() + ", stale = " + stale + ", cellTime = " + cellTime + ", oldCellState = " + oldCell.getState().toString() + ", oldCellTime = " + oldCellTime);
    if (debug) System.out.println("" + i + " stepUp(): entry = " + entry + ", entryTime = " + entryTime);
    if (debug) System.out.println("" + i + " stepUp(): oldEntry = " + oldEntry + ", oldEntryTime = " + oldEntryTime);
    // Each iteration of this loop processes a time delta and a list of EventGraphs; else just steps up to endTime.
    // The cell applies both the old and new EventGraphs except only the new when at the same timepoint.
    // An old cell is created and/or stepped just within the old TemporalEventSource to determine if the
    // new cell becomes stale or unstale.  The old cell is abandoned when not stale and when there are no
    // new EventGraphs, which are just changes (additions and replacements) on top of the old.
    boolean foundBeforeEventInNew = false;
    boolean foundBeforeEventInOld = false;
    int done = 0;
    while (done < 2) {
      boolean stepped = false;

      // step(timeDelta) for oldCell if necessary
      if (stale && !foundBeforeEventInOld) {  // Only step if the topic is stale
        var minWrtOld = Duration.min(entryTime, oldEntryTime, endTime.duration());
        if (oldCellTime.shorterThan(minWrtOld) && minWrtOld.shorterThan(Duration.MAX_VALUE)) {
          stepped = true;
          var prevOldCellTime = oldCellTime;
          oldCell.step(minWrtOld.minus(oldCellTime.duration()));
          if (debug) System.out.println("" + i + " stepUp(): oldCell.step(minWrtOld=" + minWrtOld + " - oldCellTime=" + oldCellTime + " = " + minWrtOld.minus(oldCellTime.duration()) + "), oldCellState = " + oldCell.getState().toString());
          oldCellTime = new SubInstantDuration(minWrtOld, 0);
          oldTemporalEventSource.putCellTime(oldCell, prevOldCellTime, oldCellTime);
        }
      }
      // step(timeDelta) for new cell if necessary
      var minWrtNew = Duration.min(entryTime, oldEntryTime, endTime.duration());
      if (!foundBeforeEventInNew && cellTime.shorterThan(minWrtNew) && minWrtNew.shorterThan(Duration.MAX_VALUE)) {
        stepped = true;
        var prevCellTime = cellTime;
        cell.step(minWrtNew.minus(cellTime.duration()));
        if (debug) System.out.println("" + i + " stepUp(): cell.step(minWrtOld=" + minWrtNew + " - cellTime=" + cellTime + " = " + minWrtNew.minus(cellTime.duration()) + "), cellState = " + cell.getState().toString());
        cellTime = new SubInstantDuration(minWrtNew, 0);
        putCellTime(cell, prevCellTime, cellTime);
      }

      // check staleness
      boolean timesAreEqual = stale && cellTime.isEqualTo(oldCellTime); // inserted stale thinking it would be faster to skip isEqualTo()
      if (debug) System.out.println("" + i + " stepUp(): timesAreEqual = " + timesAreEqual);

      if (stale && stepped && timesAreEqual) {
        stale = updateStale(cell, oldCell);
      }

      // Apply old EventGraph
      boolean oldCellStateChanged = false;
      boolean cellStateChanged = false;
      if (oldEntry != null &&
          oldEntryTime.isEqualTo(cellTime.duration()) &&
          (oldCellTime.shorterThan(endTime))) {
        var unequalGraphs = entry != null && entryTime.isEqualTo(oldEntryTime) && !oldEntry.getValue().equals(entry.getValue());

        // Step old cell if stale or if the new EventGraph is changed
        var oldEventGraphList = oldEntry.getValue();
        if (stale || unequalGraphs) {
          // If topic is not stale, and old cell is not stepped up, then it was abandoned, and need to create a new one.
          var prevOldCellTime = oldCellTime;
          if (!stale && unequalGraphs && !oldCellTime.isEqualTo(cellTime.duration())) {
            //cellCache.computeIfAbsent(cell.getTopic(), $ -> new TreeMap<>()).put(oldCellTime, oldCell);
            if (debug) System.out.println("" + i + " stepUp(): oldCell = cell.duplicate()");
            oldCell = cell.duplicate();  // Would stepping up old cell be faster in some cases?
            oldCellTime = cellTime;
            oldCellStateChanged = true;
//            oldSubTimeline = mo == null ? null : mo.subMap(cellTime, true, endTime, includeEndTime);
//            oldIter = oldSubTimeline == null ? null : oldSubTimeline.entrySet().iterator();
//            oldEntry = oldIter != null && oldIter.hasNext() ? oldIter.next() : null;
//            oldEntryTime = oldEntry == null ? Duration.MAX_VALUE : oldEntry.getKey();
          }
          final var oldOldState = oldCell.getState(); // getState() generates a copy, so oldState won't change
          var oldCellSteppedAtTime = oldCellTime.index();
          if (!foundBeforeEventInOld && oldCellTime.index() < oldEventGraphList.size() &&
              (!originalOldCellTime.isEqualTo(oldCellTime.duration()) || originalOldCellTime.index() < oldEventGraphList.size())) {
            var maxSteps = Math.min(oldEventGraphList.size(), endTime.duration().isEqualTo(oldCellTime.duration()) ? endTime.index() : Integer.MAX_VALUE);
            for (; oldCellSteppedAtTime < maxSteps; ++oldCellSteppedAtTime) {
              var eventGraph = oldEventGraphList.get(oldCellSteppedAtTime);
              if (beforeEvent == null) eventGraph = oldTemporalEventSource.withoutReadEvents(eventGraph);
              foundBeforeEventInOld = oldCell.apply(eventGraph, beforeEvent, false);
              if (debug) System.out.println("" + i + " stepUp(): oldCell.apply(oldGraph: " + eventGraph + ") oldCellState = " + oldCell);
              if (foundBeforeEventInOld) break;
            }
          }
          oldCellTime = new SubInstantDuration(oldCellTime.duration(), oldCellSteppedAtTime);
          oldTemporalEventSource.putCellTime(oldCell, prevOldCellTime, oldCellTime);
          oldCellStateChanged = oldCellStateChanged || !oldCell.getState().equals(oldOldState);
        }

        // Step up new cell if no new EventGraph at this time.
        var cellSteppedAtTime = cellTime.index();
        if (!foundBeforeEventInNew && (entry == null || entryTime.longerThan(oldEntryTime))) {
          final var oldState = cell.getState(); // getState() generates a copy, so oldState won't change
          if (!originalCellTime.isEqualTo(cellTime.duration()) || originalCellTime.index() < oldEventGraphList.size()) {
            var maxSteps = Math.min(oldEventGraphList.size(), endTime.duration().isEqualTo(cellTime.duration()) ? endTime.index() : Integer.MAX_VALUE);
            for (; cellSteppedAtTime < maxSteps; ++cellSteppedAtTime) {
              var eventGraph = oldEventGraphList.get(cellSteppedAtTime);
              if (beforeEvent == null) eventGraph = oldTemporalEventSource.withoutReadEvents(eventGraph);
              foundBeforeEventInNew = cell.apply(eventGraph, beforeEvent, false);
              if (debug) System.out.println("" + i + " stepUp(): cell.apply(oldGraph: " + eventGraph + ") cellState = " + cell);
              if (foundBeforeEventInNew) break;
            }
            cellTime = new SubInstantDuration(cellTime.duration(), cellSteppedAtTime);
          }
          cellStateChanged = !cell.getState().equals(oldState);
        }
        oldEntry = oldIter != null && oldIter.hasNext() ? oldIter.next() : null;
        oldEntryTime = oldEntry == null ? Duration.MAX_VALUE : oldEntry.getKey();
        if (debug) System.out.println("" + i + " stepUp(): oldEntry = " + oldEntry + ", oldEntryTime = " + oldEntryTime);
      }

      // Apply new EventGraph
      if (!foundBeforeEventInNew && entry != null && entryTime.isEqualTo(cellTime.duration()) &&
          cellTime.shorterThan(endTime)) {
        final var newEventGraphList = entry.getValue();
        final var oldState = cell.getState(); // getState() generates a copy, so oldState won't change
        var cellSteppedAtTime = cellTime.index();
        if (cellSteppedAtTime < newEventGraphList.size() &&
            (!originalCellTime.isEqualTo(cellTime) || originalCellTime.index() < newEventGraphList.size())) {
          var maxSteps = Math.min(newEventGraphList.size(), endTime.duration().isEqualTo(cellTime.duration()) ? endTime.index() : Integer.MAX_VALUE);
          for (; cellSteppedAtTime < maxSteps; ++cellSteppedAtTime) {
            var eventGraph = newEventGraphList.get(cellSteppedAtTime);
            if (beforeEvent == null) eventGraph = withoutReadEvents(eventGraph);
            foundBeforeEventInNew = cell.apply(eventGraph, beforeEvent, false);
            if (debug) System.out.println("" + i + " stepUp(): cell.apply(newGraph: " + eventGraph + ") cellState = " + cell);
            if (foundBeforeEventInNew) break;
          }
          cellTime = new SubInstantDuration(cellTime.duration(), cellSteppedAtTime);
        }
        cellStateChanged = !cell.getState().equals(oldState);
        entry = iter != null && iter.hasNext() ? iter.next() : null;
        entryTime = entry == null ? Duration.MAX_VALUE : entry.getKey();
        if (debug) System.out.println("" + i + " stepUp(): entry = " + entry + ", entryTime = " + entryTime);
      }

      // check staleness
      if (timesAreEqual && (stale || cellStateChanged || oldCellStateChanged)) {
        stale = updateStale(cell, oldCell);
      }
      if ( (foundBeforeEventInNew && foundBeforeEventInOld) ||
           !( (cellTime.shorterThan(endTime.duration()) || (stale && oldCellTime.shorterThan(endTime.duration()))) &&
              (entry != null || oldEntry != null) ) ) {
        ++done;
      }

    }
    var prevCellTime = getCellTime(cell);
    if (prevCellTime == null) prevCellTime = SubInstantDuration.MAX_VALUE;
    if (cellTime.shorterThan(endTime) && endTime.duration().shorterThan(Duration.MAX_VALUE) &&
       (endTime.duration().longerThan(cellTime.duration()) || endTime.index() < Integer.MAX_VALUE)) {
      cellTime = endTime;
    }
    putCellTime(cell, prevCellTime, cellTime);
    if (debug) System.out.println("" + i + " END stepUp(" + cell.getTopic() + ", " + endTime + "): cellState = " + cell.toString() + ", stale = " + stale + ", cellTime = " + cellTime + ", oldCellState = " + oldCell.getState().toString() + ", oldCellTime = " + oldCellTime);
  }

  protected boolean updateStale(Cell<?> cell, Cell<?> oldCell) {
    var time = getCellTime(cell);
    boolean stale = !cell.getState().equals(oldCell.getState());
    boolean wasStale = isTopicStale(cell.getTopic(), time);
    if (stale && !wasStale) {
      setTopicStale(cell.getTopic(), time);
    } else if (!stale && wasStale) {
      setTopicUnstale(cell.getTopic(), time);
    }
    return stale;
  }

  public <State> Cell<State> getCell(Topic<?> topic, SubInstantDuration endTime) {
    Optional<LiveCell<?>> cell = liveCells.getCells(topic).stream().findFirst();
    if (cell.isEmpty()) {
      throw new RuntimeException("Can't find cell for query.");
    }
    return getCell((Cell<State>)cell.get().cell, endTime);
  }

  public <State> Cell<State> getCell(Cell<State> cell, SubInstantDuration endTime) {
    var t = getCellTime(cell);
    // Use the one in LiveCells if not asking for a time in the past.
    if (t == null || t.shorterThan(endTime)) {
      stepUp(cell, endTime);
      return cell;
    }
    // For a cell in the past, use the cell cache
    Cell<State> pastCell = getOrCreateCellInCache(cell.getTopic(), endTime);
    return pastCell;
  }

  public <State> Cell<State> getCell(Query<State> query, SubInstantDuration endTime) {
    Optional<LiveCell<State>> cell = liveCells.getLiveCell(query);
    if (cell.isEmpty()) {
      throw new RuntimeException("Can't find cell for query.");
    }
    return getCell(cell.get().cell, endTime);
  }

  public <State> Cell<State> getOrCreateCellInCache(Topic<?> topic, SubInstantDuration endTime) {
    final TreeMap<SubInstantDuration, Cell<?>> inner = cellCache.computeIfAbsent(topic, $ -> new TreeMap<>());
    final Entry<SubInstantDuration, Cell<?>> entry = inner.floorEntry(endTime);
    Cell<?> cell;
    if (entry != null) {
      cell = entry.getValue();
      // TODO: maybe pass in boolean for whether to duplicate the cell in the cache instead of removing and adding back after stepping up
      if (debug) System.out.println("getOrCreateCellInCache(" + topic + ", " + endTime + "): popped " + cell + " at " + entry.getKey());
      inner.remove(entry.getKey());
    } else {
      if (missionModel == null) {
        throw new NoSuchElementException("No MissionModel initial cells to copy!");
      }
      cell = missionModel.getInitialCells().getCells(topic).stream().findFirst().orElseThrow().cell.duplicate();
      if (debug) System.out.println("getOrCreateCellInCache(" + topic + ", " + endTime + "): duplicated " + cell);
    }
    stepUp(cell, endTime);
    if (debug) System.out.println("getOrCreateCellInCache(" + topic + ", " + endTime + "): put(" + endTime + ", " + cell.toString() + ") with cell time = " + getCellTime(cell));
    var cellTime = getCellTime(cell);
    inner.put(cellTime, cell);
    var newCell = (Cell<State>)cell.duplicate(); // TODO: avoid this force cast and associated compiler warning
    putCellTime(newCell, cellTime);
    return newCell;
  }

  public Optional<LiveCell<?>> getOldCell(LiveCell<?> cell) {
    if (oldTemporalEventSource == null) return Optional.empty();
    return oldTemporalEventSource.liveCells.getCells(cell.cell.getTopic()).stream().findFirst();
  }

  public Optional<Cell<?>> getOldCell(Cell<?> cell) {
    if (oldTemporalEventSource == null) return Optional.empty();
    return oldTemporalEventSource.liveCells.getCells(cell.getTopic()).stream().findFirst().map(lc -> lc.cell);
  }

  public SubInstantDuration getCellTime(Cell<?> cell) {
    var cellTime = cellTimes.get(cell);
    if (cellTime == null) {
      return new SubInstantDuration(Duration.ZERO, 0);
    }
    return cellTime;
  }

  public void putCellTime(Cell<?> cell, Duration cellTime, int cellStepped) {
    putCellTime(cell, new SubInstantDuration(cellTime, cellStepped));
  }

  public void putCellTime(Cell<?> cell, SubInstantDuration d) {
    this.cellTimes.put(cell, d);
  }

  public void putCellTime(Cell<?> cell, Duration oldCellTime, int oldCellStepped, Duration cellTime, int cellStepped) {
    putCellTime(cell, new SubInstantDuration(oldCellTime, oldCellStepped), new SubInstantDuration(cellTime, cellStepped));
  }
  public void putCellTime(Cell<?> cell, SubInstantDuration oldCellTime, SubInstantDuration cellTime) {
    if (debug) System.out.println("putCellTime(" + cell + ", " + oldCellTime + ", " + cellTime + ")");
    putCellTime(cell, cellTime);
  }

  @Override
  public TemporalCursor cursor() {
    return new TemporalCursor();
  }

  public final class TemporalCursor implements Cursor {
//    private final Iterator<TimePoint> iterator;

//    TemporalCursor(Iterator<TimePoint> iterator) {
//      this.iterator = iterator;
//    }

    private TemporalCursor() {
//      this(TemporalEventSource.this.iterator());
    }

    @Override
    public <State> Cell<State> stepUp(final Cell<State> cell) {
      if (getCellTime(cell).longerThan(curTime())) {
        return getOrCreateCellInCache(cell.getTopic(), curTime());
      }
      TemporalEventSource.this.stepUp(cell, curTime());
      return cell;
    }

  }


  public static Set<Topic<?>> extractTopics(final EventGraph<Event> graph) {
    final var set = new ReferenceOpenHashSet<Topic<?>>();
    extractTopics(set, graph);
    set.trim();
    return set;
  }

  public static Set<TaskId> extractTasks(final EventGraph<Event> graph) {
    final var set = new ReferenceOpenHashSet<TaskId>();
    extractTasks(set, graph);
    set.trim();
    return set;
  }

  public static void extractTopics(final Set<Topic<?>> accumulator, EventGraph<Event> graph) {
    extractTopics(accumulator, graph, null);
  }
  public static void extractTopics(final Set<Topic<?>> accumulator, EventGraph<Event> graph, Predicate<Event> p) {
    while (true) {
      if (graph instanceof EventGraph.Empty) {
        // There are no events here!
        return;
      } else if (graph instanceof EventGraph.Atom<Event> g) {
        if(p == null || p.test(g.atom())) {
          accumulator.add(g.atom().topic());
        }
        return;
      } else if (graph instanceof EventGraph.Sequentially<Event> g) {
        extractTopics(accumulator, g.prefix());
        graph = g.suffix();
      } else if (graph instanceof EventGraph.Concurrently<Event> g) {
        extractTopics(accumulator, g.left());
        graph = g.right();
      } else {
        throw new IllegalArgumentException();
      }
    }
  }

  private static void extractTasks(final Set<TaskId> accumulator, EventGraph<Event> graph) {
    while (true) {
      if (graph instanceof EventGraph.Empty) {
        // There are no events here!
        return;
      } else if (graph instanceof EventGraph.Atom<Event> g) {
        accumulator.add(g.atom().provenance());
        return;
      } else if (graph instanceof EventGraph.Sequentially<Event> g) {
        extractTasks(accumulator, g.prefix());
        graph = g.suffix();
      } else if (graph instanceof EventGraph.Concurrently<Event> g) {
        extractTasks(accumulator, g.left());
        graph = g.right();
      } else {
        throw new IllegalArgumentException();
      }
    }
  }

  public sealed interface TimePoint {
    record Delta(Duration delta) implements TimePoint {}
    record Commit(EventGraph<Event> events, Set<Topic<?>> topics) implements TimePoint {}
  }

  @Override
  public void freeze(SubInstantDuration time) {
    this.frozen = true;
    if (timeFroze != null) {
      if (debug) System.out.println(this.i + " TemporalEventSource.freeze(" + time + "): keeping already frozen time, " + timeFroze);
      return;
    }
    this.timeFroze = time;
  }

  @Override
  public SubInstantDuration timeFroze() {
    return this.timeFroze;
  }

  public SubInstantDuration getTimeAfterLastEvent(NavigableMap<Duration, List<EventGraph<Event>>> eventMap) {
    var e = eventMap.lastEntry();
    if (e == null || e.getValue() == null || e.getValue().isEmpty()) {
      return null;
    }
    return new SubInstantDuration(e.getKey(), e.getValue().size());
  }

  public SubInstantDuration getTimeAfterLastEvent(final Topic<?> topic) {
    var events = getCombinedEventsByTopic();
    if (events == null || events.get(topic) == null) {
      return null;
    }
    return getTimeAfterLastEvent(events.get(topic));
  }

}
