package gov.nasa.jpl.aerie.merlin.driver.engine;

import com.google.common.collect.Range;
import gov.nasa.jpl.aerie.merlin.driver.CombinedSimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel.SerializableTopic;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResultsInterface;
import gov.nasa.jpl.aerie.merlin.driver.UnfinishedActivity;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Cell;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Event;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.driver.timeline.LiveCells;
import gov.nasa.jpl.aerie.merlin.driver.timeline.TemporalEventSource;
import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.SubInstantDuration;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Integer.max;

/**
 * A representation of the work remaining to do during a simulation, and its accumulated results.
 */
public final class SimulationEngine implements AutoCloseable {
  private final Map<TaskId, Event> tasksNeedingTimeAlignment = new HashMap<>();
  private boolean closed = false;

  public static boolean debug = false;
  public static boolean trace = false;

  /** The engine from a previous simulation, which we will leverage to avoid redundant computation */
  public final SimulationEngine oldEngine;

  /** The EventGraphs separated by Durations between the events */
  public final TemporalEventSource timeline;
  private LiveCells cells;
  /** The set of all jobs waiting for time to pass. */
  private final JobSchedule<JobId, SchedulingInstant> scheduledJobs;
  /** The set of all jobs waiting on a condition. */
  private final Map<ConditionId, TaskId> waitingTasks;
  /** The set of all tasks blocked on some number of subtasks. */
  private final Map<TaskId, MutableInt> blockedTasks;
  /** The set of conditions depending on a given set of topics. */
  private final Subscriptions<Topic<?>, ConditionId> waitingConditions;
  /** The set of queries depending on a given set of topics. */
  private final Subscriptions<Topic<?>, ResourceId> waitingResources;
  /** The topics referenced (cells read) by the last computation of the resource. */
  private HashMap<ResourceId, Set<Topic<?>>> referencedTopics = new HashMap<>();
  /** Separates generation of resource profile results from other parts of the simulation */
  /** The history of when tasks read topics/cells */
  private HashMap<Topic<?>, TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>>> cellReadHistory = new HashMap<>();
  private TreeMap<SubInstantDuration, HashSet<TaskId>> removedCellReadHistory = new TreeMap<>();

  private final HashMap<Topic<?>, RangeSetMap<SubInstantDuration, ConditionId>> conditionHistoryByTopic = new HashMap<>();
  RangeMapMap<SubInstantDuration, ConditionId, Set<Topic<?>>> conditionHistory = new RangeMapMap<>();

  private final Map<TaskId, Set<ConditionId>> conditionsForTask = new HashMap<>();

  private final MissionModel<?> missionModel;

  /** The start time of the simulation, from which other times are offsets */
  private final Instant startTime;

  /**
   * Counts from 0 the commits/steps at the same timepoint in order to align events of re-executed tasks
   */
  private int stepIndexAtTime = 0;

  public Map<ActivityDirectiveId, ActivityDirective> scheduledDirectives = null;
  public Map<String, Map<ActivityDirectiveId, ActivityDirective>> directivesDiff = null;

  public SpanInfo spanInfo = new SpanInfo(this);

  private Map<ActivityInstanceId, ActivityInstance> simulatedActivities = new LinkedHashMap<>();
  private Set<ActivityInstanceId> removedActivities = new LinkedHashSet<>();
  private Map<ActivityInstanceId, UnfinishedActivity> unfinishedActivities = new LinkedHashMap<>();
  private List<Triple<Integer, String, ValueSchema>> topics = new ArrayList<>();
  private SimulationResults simulationResults = null;
  public static final Topic<ActivityDirectiveId> defaultActivityTopic = new Topic<>();
  private HashMap<String, ActivityInstanceId> taskToSimulatedActivityId = null;
  private HashMap<SpanId, SpanId> activityParents = new HashMap<SpanId, SpanId>();
  ;
  private HashMap<SpanId, LinkedHashSet<SpanId>> activityChildren = new HashMap<SpanId, LinkedHashSet<SpanId>>();
  ;
  private HashMap<SpanId, ActivityDirectiveId> activityDirectiveIds = null;

  /** When tasks become stale */
  private Map<TaskId, SubInstantDuration> staleTasks = new LinkedHashMap<>();
  private Map<TaskId, Event> staleEvents = new LinkedHashMap<>();
  private Map<TaskId, Integer> staleCausalEventIndex = new LinkedHashMap<>();

  /** The execution state for every task. */
  private final Map<TaskId, ExecutionState<?>> tasks;
  /** Remember the TaskFactory for each task so that we can re-run it */
  private Map<TaskId, TaskFactory<?>> taskFactories = new HashMap<>();
  private Map<TaskFactory<?>, TaskId> taskIdsForFactories = new HashMap<>();
  /** Remember which tasks were daemon-spawned */
  private Set<TaskId> daemonTasks = new LinkedHashSet<>();
  /** The getter for each tracked condition. */
  private final Map<ConditionId, Condition> conditions;
  /** The profiling state for each tracked resource. */
  private final Map<ResourceId, Resource<?>> resources;

  /** The task that spawned a given task (if any). */
  private Map<TaskId, TaskId> taskParent = new HashMap<>();
  /** The set of children for each task (if any). */
  @DerivedFrom("taskParent")
  private Map<TaskId, List<TaskId>> taskChildren = new HashMap<>();
  /** Whether the task was called from its parent instead of spawned */
  private HashSet<TaskId> calledTasks = new HashSet<>();

  /** Tasks that have been scheduled, but not started */
  private final Map<TaskId, Duration> unstartedTasks;

  /** The set of all spans of work contributed to by modeled tasks. */
  private final Map<SpanId, Span> spans;
  /** A count of the direct contributors to each span, including child spans and tasks. */
  private final Map<SpanId, MutableInt> spanContributorCount;
  private Map<TaskId, SpanId> taskToSpanMap = new HashMap<>();
  private Map<SpanId, SequencedSet<TaskId>> spanToTaskMap = new HashMap<>();

  private HashMap<SpanId, ActivityInstanceId> spanToSimulatedActivityId = null;

  private HashMap<ActivityDirectiveId, ActivityInstanceId> directiveToSimulatedActivityId = new HashMap<>();

  /** A thread pool that modeled tasks can use to keep track of their state between steps. */
  private final ExecutorService executor;

  /* The top-level simulation timeline. */
  private final TemporalEventSource referenceTimeline;
  private Duration elapsedTime;

  /** whether this engine failed its simulation, in which case it is not suitable for incremental simulation */
  public boolean failed;

  private SubInstantDuration lastStaleReadTime = SubInstantDuration.MAX_VALUE;
  private SubInstantDuration lastStaleConditionReadTime = SubInstantDuration.MAX_VALUE;
  private SubInstantDuration lastStaleTopicTime = SubInstantDuration.MAX_VALUE;
  private SubInstantDuration lastStaleTopicOldEventTime = SubInstantDuration.MAX_VALUE;
  private SubInstantDuration lastConditionTime = SubInstantDuration.MAX_VALUE;
  /** switch for whether an engine can be the oldEngine of more than one engines; this is used to determine whether
   *  to clear an oldEngine's caches to save memory */
  private boolean allowMultipleParentEngines = false;
  public static boolean alwaysRerunParentTasks = true;

  public SimulationEngine(
      Instant startTime,
      MissionModel<?> missionModel,
      SimulationEngine oldEngine) {
    this.startTime = startTime;
    this.missionModel = missionModel;
    this.oldEngine = oldEngine;
    this.timeline = new TemporalEventSource(null, missionModel,
                                            oldEngine == null ? null : oldEngine.timeline);
    if (oldEngine != null) {
      this.referenceTimeline = oldEngine.referenceTimeline;
      oldEngine.cells = new LiveCells(oldEngine.timeline, oldEngine.missionModel.getInitialCells());
      this.cells = new LiveCells(timeline, oldEngine.missionModel.getInitialCells()); // HACK: good for in-memory but with DB or difft mission model configuration,...
    } else {
      this.referenceTimeline = new TemporalEventSource();
      this.cells = new LiveCells(timeline, missionModel.getInitialCells());
    }
    this.timeline.liveCells = this.cells;
    if (debug) System.out.println("new SimulationEngine(startTime=" + startTime + ")");

    elapsedTime = Duration.ZERO;

    scheduledJobs = new JobSchedule<>();
    waitingTasks = new LinkedHashMap<>();
    blockedTasks = new LinkedHashMap<>();
    waitingConditions = new Subscriptions<>();
    waitingResources = new Subscriptions<>();
    tasks = new LinkedHashMap<>();
    conditions = new LinkedHashMap<>();
    resources = new LinkedHashMap<>();
    unstartedTasks = new LinkedHashMap<>();
    spans = new LinkedHashMap<>();
    spanContributorCount = new LinkedHashMap<>();
    executor = Executors.newVirtualThreadPerTaskExecutor();
    this.failed = false;
  }

  public void freeze() {
    SubInstantDuration freezeTime = SubInstantDuration.max(curTime(), new SubInstantDuration(elapsedTime, 0));
    if (!timeline.isFrozen()) timeline.freeze(freezeTime);
    if (!referenceTimeline.isFrozen()) referenceTimeline.freeze(freezeTime);
    cells.freeze(freezeTime);
  }

  private SimulationEngine(SimulationEngine other) {
    other.freeze();

    elapsedTime = other.elapsedTime;

    this.timeline = new TemporalEventSource(null, other.getMissionModel(),
                                            other.oldEngine == null ? null : other.oldEngine.timeline);
    setCurTime(other.curTime());
    cells = new LiveCells(timeline, other.cells);
    this.timeline.liveCells = this.cells;
    referenceTimeline = other.combineTimeline();

    // New Executor allows other SimulationEngine to be closed
    executor = Executors.newVirtualThreadPerTaskExecutor();
    scheduledJobs = other.scheduledJobs.duplicate();
    waitingTasks = new LinkedHashMap<>(other.waitingTasks);
    blockedTasks = new LinkedHashMap<>();
    for (final var entry : other.blockedTasks.entrySet()) {
      blockedTasks.put(entry.getKey(), new MutableInt(entry.getValue()));
    }
    waitingConditions = other.waitingConditions.duplicate();
    waitingResources = other.waitingResources.duplicate();
    tasks = new LinkedHashMap<>();
    for (final var entry : other.tasks.entrySet()) {
      tasks.put(entry.getKey(), entry.getValue().duplicate(executor));
    }
    conditions = new LinkedHashMap<>(other.conditions);
    resources = new LinkedHashMap<>(other.resources);
    unstartedTasks = new LinkedHashMap<>(other.unstartedTasks);
    spans = new LinkedHashMap<>(other.spans);
    spanContributorCount = new LinkedHashMap<>();
    for (final var entry : other.spanContributorCount.entrySet()) {
      spanContributorCount.put(entry.getKey(), new MutableInt(entry.getValue().getValue()));
    }
    oldEngine = other.oldEngine;
    startTime = other.startTime;
    stepIndexAtTime = other.stepIndexAtTime;
    missionModel = other.missionModel;
    referencedTopics = new HashMap<>();
    for (final var entry : other.referencedTopics.entrySet()) {
      referencedTopics.put(entry.getKey(), new HashSet<>(entry.getValue()));
    }
    cellReadHistory = new HashMap<>();
    for (final var entry : other.cellReadHistory.entrySet()) {
      var newVal = new TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>>();
      for (final var e2 : entry.getValue().entrySet()) {
        newVal.put(e2.getKey(), new HashMap<>(e2.getValue()));
      }
      cellReadHistory.put(entry.getKey(), newVal);
    }
    removedCellReadHistory = new TreeMap<>();
    for (final var entry : other.removedCellReadHistory.entrySet()) {
      removedCellReadHistory.put(entry.getKey(), new HashSet<>(entry.getValue()));
    }
    scheduledDirectives = other.scheduledDirectives;
    directivesDiff = other.directivesDiff;
    spanInfo = new SpanInfo(other.spanInfo, this);
    simulatedActivities = new LinkedHashMap<>(other.simulatedActivities);
    removedActivities = new LinkedHashSet<>(other.removedActivities);
    unfinishedActivities = new LinkedHashMap<>(other.unfinishedActivities);
    topics = new ArrayList<>(other.topics);
    simulationResults = other.simulationResults;
    taskToSimulatedActivityId = other.taskToSimulatedActivityId == null ? null : new HashMap<>(other.taskToSimulatedActivityId);
    activityDirectiveIds = other.activityDirectiveIds == null ? null : new HashMap<>(other.activityDirectiveIds);
    staleTasks = new LinkedHashMap<>(other.staleTasks);
    staleEvents = new LinkedHashMap<>(other.staleEvents);
    staleCausalEventIndex = new LinkedHashMap<>(other.staleCausalEventIndex);
    taskFactories = new LinkedHashMap<>(other.taskFactories);
    daemonTasks = other.daemonTasks;
    taskParent = new HashMap<>(other.taskParent);
    taskChildren = new HashMap<>();
    for (final var entry : other.taskChildren.entrySet()) {
      taskChildren.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    taskToSpanMap = new HashMap<>(other.taskToSpanMap);
    spanToSimulatedActivityId = other.spanToSimulatedActivityId == null ? null :
        new HashMap<>(other.spanToSimulatedActivityId);
    directiveToSimulatedActivityId = new HashMap<>(other.directiveToSimulatedActivityId);
    this.failed = other.failed;
  }

  private void startDaemons(Duration time) {
    try {
      // TODO -- is it necessary to handle task factories here?  Didn't this work before the 9/28/24 changes below?
      var spanId = scheduleTask(Duration.ZERO, missionModel.getDaemon(), null);
      var taskId = getTaskIds(spanId).getFirst();
      this.taskFactories.put(taskId, missionModel.getDaemon());
      this.taskIdsForFactories.put(missionModel.getDaemon(), taskId);
      step(Duration.MAX_VALUE, $ -> {});
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private void trackResources() {
    // Begin tracking any resources that have not already been simulated.
    for (final var entry : missionModel.getResources().entrySet()) {
      final var name = entry.getKey();
      final var resource = entry.getValue();
      trackResource(name, resource, Duration.ZERO);
    }
  }

  private int daemonStartupStepIndex = 0;

  /** Initialize the engine by tracking resources and kicking off daemon tasks. **/
  public void init(boolean rerunning) {
    // Begin tracking all resources.
    trackResources();

    // Start daemon task(s) immediately, before anything else happens.
    if (!rerunning) {
      startDaemons(curTime().duration());
      daemonStartupStepIndex = stepIndexAtTime;
    } else {
      if (oldEngine != null && oldEngine.daemonStartupStepIndex > 0) {
        stepIndexAtTime = oldEngine.daemonStartupStepIndex;
        setCurTime(new SubInstantDuration(Duration.ZERO, stepIndexAtTime));
      }
    }
  }

  public sealed interface Status {
    record NoJobs() implements Status {}
    record AtDuration() implements Status{}
    record Nominal(
        Duration elapsedTime,
        Map<String, Pair<ValueSchema, RealDynamics>> realResourceUpdates,
        Map<String, Pair<ValueSchema, SerializedValue>> dynamicResourceUpdates
    ) implements Status {}
  }

  public Duration getElapsedTime() {
    var ct = curTime();
    elapsedTime = ct.longerThan(elapsedTime) ? ct.duration() : elapsedTime;
    return elapsedTime;
  }

  /** Performs a collection of tasks concurrently, extending the given timeline by their stateful effects. */
  public Status step(
      final Duration maximumTime,
      final Consumer<Duration> simulationExtentConsumer)
  throws Throwable
  {
    try {
      return reallyStep(maximumTime, simulationExtentConsumer);
    } catch(Throwable t) {
      this.failed = true;
      throw t;
    }
  }
  private Status reallyStep(
      final Duration maximumTime,
      final Consumer<Duration> simulationExtentConsumer)
      throws Throwable
  {
    if (debug) System.out.println("step(): begin -- time = " + curTime() + ", step " + stepIndexAtTime);
    if (stepIndexAtTime == Integer.MAX_VALUE) stepIndexAtTime = 0;
    var timeOfNextJobs = timeOfNextJobs();
    if (timeOfNextJobs.index() == 0 && timeOfNextJobs.duration().isEqualTo(curTime().duration())) {
      timeOfNextJobs = new SubInstantDuration(timeOfNextJobs.duration(), stepIndexAtTime);
    }

    var nextTime = timeOfNextJobs;

    Pair<SubInstantDuration, Map<TaskId, HashSet<Pair<Topic<?>, Set<Event>>>>> earliestStaleReads = null;
    SubInstantDuration staleReadTime = null;
    Pair<SubInstantDuration, Map<Topic<?>, Set<ConditionId>>> earliestStaleConditionReads = null;
    SubInstantDuration staleConditionReadTime = null;
    Pair<List<Topic<?>>, SubInstantDuration> earliestStaleTopics = null;
    Pair<List<Topic<?>>, SubInstantDuration> earliestStaleTopicOldEvents = null;
    SubInstantDuration staleTopicTime = SubInstantDuration.MAX_VALUE;
    SubInstantDuration staleTopicOldEventTime = SubInstantDuration.MAX_VALUE;
    SubInstantDuration conditionTime = SubInstantDuration.MAX_VALUE;
    Pair<List<Topic<?>>, SubInstantDuration> earliestConditionTopics = null;

    if (oldEngine != null && nextTime.noShorterThan(curTime().duration())) {
      // Need to invalidate stale topics just after the event, so the time of the events returned must be incremented
      // by index=1, and the window searched must be 1 index before the current time.
      earliestStaleTopics = earliestStaleTopics(curTime().minus(1), nextTime);  // TODO: might want to not limit by nextTime and cache for future iterations
      if (debug) System.out.println("earliestStaleTopics(" + curTime().minus(1) + ", " + nextTime + ") = " + earliestStaleTopics);
      staleTopicTime = earliestStaleTopics.getRight().plus(1);
      if (!staleTopicTime.isEqualTo(lastStaleTopicTime)) {
        nextTime = SubInstantDuration.min(nextTime, staleTopicTime);
      }

      earliestStaleTopicOldEvents = nextStaleTopicOldEvents(curTime().minus(1), SubInstantDuration.min(nextTime, new SubInstantDuration(maximumTime, 0)));
      if (debug) System.out.println("nextStaleTopicOldEvents(" + curTime().minus(1) + ", " + SubInstantDuration.min(nextTime, new SubInstantDuration(maximumTime, 0)) + ") = " + earliestStaleTopicOldEvents);
      staleTopicOldEventTime = earliestStaleTopicOldEvents.getRight().plus(1);
      if (!staleTopicOldEventTime.isEqualTo(lastStaleTopicOldEventTime)) {
        nextTime = SubInstantDuration.min(nextTime, staleTopicOldEventTime);
      }

      earliestStaleReads = earliestStaleReads(curTime().minus(1), nextTime);  // might want to not limit by nextTime and cache for future iterations
      staleReadTime = SubInstantDuration.max(curTime(), earliestStaleReads.getLeft());
      if (debug) System.out.println("earliestStaleReads(" + curTime() + ", " + nextTime + ") = " + earliestStaleReads + "; lastStaleReadTime = " + lastStaleReadTime + (staleReadTime.equals(lastStaleReadTime) ? " -> ignore" : ""));
      if (!staleReadTime.isEqualTo(lastStaleReadTime)) {
        nextTime = SubInstantDuration.min(nextTime, staleReadTime);
      }

      earliestStaleConditionReads = earliestStaleConditionReads(curTime().minus(1), nextTime);
      staleConditionReadTime = SubInstantDuration.max(curTime(), earliestStaleConditionReads.getLeft());  // max with curTime for when it is curTime().minus(1)
      if (debug) System.out.println("earliestStaleConditionReads(" + curTime() + ", " + nextTime + ") = "
                                    + earliestStaleConditionReads + "; lastConditionStaleReadTime = "
                                    + lastStaleConditionReadTime
                                    + (staleConditionReadTime.equals(lastStaleConditionReadTime) ? " -> ignore" : ""));
      if (!staleConditionReadTime.isEqualTo(lastStaleConditionReadTime)) {
        nextTime = SubInstantDuration.min(nextTime, staleConditionReadTime);
      }

      // Need to invalidate stale topics just after the event, so the time of the events returned must be incremented
      // by index=1, and the window searched must be 1 index before the current time.
      earliestConditionTopics = earliestConditionTopics(curTime().minus(1), nextTime);
      conditionTime = earliestConditionTopics.getRight().plus(1);
      if (debug) System.out.println("earliestConditionTopics(" + curTime().minus(1) + ", " + nextTime + ") = " +
                                    earliestConditionTopics + "; lastConditionTime = " + lastConditionTime +
                                    (conditionTime.equals(lastConditionTime) ? " -> ignore" : ""));
      if (!conditionTime.isEqualTo(lastConditionTime)) {
        nextTime = SubInstantDuration.min(nextTime, conditionTime);
      }
    }

    //SRS HERE was on dev:
//    final var batch = this.extractNextJobs(simulationDuration);
//    // Increment real time, if necessary.
//    final var delta = batch.offsetFromStart().minus(elapsedTime);
//    elapsedTime = batch.offsetFromStart();
//    timeline.add(delta);

    elapsedTime = Duration.min(
        maximumTime,
        Duration.max(elapsedTime, nextTime.duration()));  // avoid lowering elapsed time
    // TODO: Advance a dense time counter so that future tasks are strictly ordered relative to these,
    //   even if they occur at the same real time.

    if (nextTime.longerThan(maximumTime) || nextTime.isEqualTo(Duration.MAX_VALUE)) {
      if (debug) System.out.println("step(): end -- time elapsed ("
                                    + nextTime
                                    + ") past maximum ("
                                    + maximumTime
                                    + ")");
      return new Status.AtDuration();
    }
    if (nextTime.noShorterThan(maximumTime) && !hasJobsScheduledThrough(maximumTime) &&
        (oldEngine == null || nextTime.isEqualTo(Duration.MAX_VALUE))) {
      // TODO -- This never returns Status.NoJobs. Is that okay?  The develop branch (before inc sim) may not, either.
      //return new Status.NoJobs();
      return new Status.AtDuration();
    }
    if (!hasJobsScheduledThrough(maximumTime) && oldEngine == null) {
      return new Status.NoJobs();
    }
    // Increment real time, if necessary.
    nextTime = SubInstantDuration.min(nextTime, new SubInstantDuration(maximumTime, Integer.MAX_VALUE));
    setCurTime(nextTime);
    stepIndexAtTime = nextTime.index();

    Set<Topic<?>> invalidatedTopics = new HashSet<>();
    final var realResourceUpdates = new HashMap<String, Pair<ValueSchema, RealDynamics>>();
    final var dynamicResourceUpdates = new HashMap<String, Pair<ValueSchema, SerializedValue>>();

    if (oldEngine != null) {

      if (staleTopicTime.isEqualTo(nextTime) && !staleTopicTime.isEqualTo(lastStaleTopicTime)) {
        if (debug) System.out.println("earliestStaleTopics at " + nextTime + " = " + earliestStaleTopics);
        lastStaleTopicTime = staleTopicTime;
        for (Topic<?> topic : earliestStaleTopics.getLeft()) {
          invalidateTopic(topic, nextTime.duration());
          invalidatedTopics.add(topic);
        }
      }

      if (staleTopicOldEventTime.isEqualTo(nextTime) && !staleTopicOldEventTime.isEqualTo(lastStaleTopicOldEventTime)) {
        if (debug) System.out.println("nextStaleTopicOldEvents at " + nextTime + " = " + earliestStaleTopicOldEvents);
        lastStaleTopicOldEventTime = staleTopicOldEventTime;
        for (Topic<?> topic : earliestStaleTopicOldEvents
            .getLeft()
            .stream()
            .filter(t -> !invalidatedTopics.contains(t))
            .toList()) {
          invalidateTopic(topic, nextTime.duration());
          invalidatedTopics.add(topic);
        }
      }

      if (conditionTime.isEqualTo(nextTime) && !conditionTime.isEqualTo(lastConditionTime)) {
        //if (debug) System.out.println("earliestConditionTopics at " + nextTime + " = " + earliestConditionTopics);
        lastConditionTime = conditionTime;
        for (Topic<?> topic : earliestConditionTopics
            .getLeft()
            .stream()
            .filter(t -> !invalidatedTopics.contains(t))
            .toList()) {
          invalidateTopic(topic, nextTime.duration());
          invalidatedTopics.add(topic);
        }
      }
    }

    boolean doJobs = invalidatedTopics.isEmpty();
    boolean hasStaleReads = false;
    boolean hasStaleConditionReads = false;
    if (oldEngine != null &&staleReadTime != null && staleReadTime.isEqualTo(nextTime) && !staleReadTime.isEqualTo(lastStaleReadTime)) {
      if (debug) System.out.println("earliestStaleReads at " + nextTime + " = " + earliestStaleReads);
      lastStaleReadTime = staleReadTime;
      hasStaleReads = true;
      doJobs = false;
    }
    if (oldEngine != null && staleConditionReadTime != null && staleConditionReadTime.isEqualTo(nextTime) &&
        !staleConditionReadTime.isEqualTo(lastStaleConditionReadTime)) {
      if (debug) System.out.println("earliestStaleConditionReads at " + nextTime + " = " + earliestStaleConditionReads);
      lastStaleConditionReadTime = staleConditionReadTime;
      hasStaleConditionReads = true;
      doJobs = false;
    }

    // Determine children to remove before setting tasks stale. We don't want to reschedule a child when the parent is
    // already being rescheduled since the child will be rerun by the parent.
    // We first run rescheduleStaleTasks without actually running reschedule just to gather the tasks with stale reads.
    // Then we run again passing in a list of tasks to ignore; that list contains the child tasks and tasks already
    // rescheduled.
    Set<TaskId> staleTasks = !hasStaleReads ? new HashSet<>() : rescheduleStaleTasks(earliestStaleReads, Collections.EMPTY_SET, true);
    if (hasStaleConditionReads) {
      staleTasks.addAll(rescheduleStaleTasks(earliestStaleConditionReads.getKey(), earliestStaleConditionReads.getRight(), staleTasks, true));
    }
    Set<TaskId> childrenToRemove = areChildren(staleTasks);
    if (hasStaleReads) {
      staleTasks = rescheduleStaleTasks(earliestStaleReads, childrenToRemove, false);
      staleTasks.addAll(childrenToRemove);
    } else {
      staleTasks = childrenToRemove;
    }
    if (hasStaleConditionReads) {
      rescheduleStaleTasks(earliestStaleConditionReads.getKey(), earliestStaleConditionReads.getRight(),
                           staleTasks, false);
    }

    if (doJobs && timeOfNextJobs.isEqualTo(nextTime)) {

      // Run the jobs in this batch.
      final var batch = extractNextJobs(maximumTime);
      if (debug) System.out.println("step(): perform job batch at " + nextTime + " : " + batch.jobs().stream().map($ -> $.getClass()).toList());
      //if (batch.jobs().isEmpty()) return new Status.NoJobs();
      final var results = performJobs(batch.jobs(), cells, curTime(), Duration.MAX_VALUE, MissionModel.queryTopic);
      for (final var tip : results.commits()) {

        if (!(tip instanceof EventGraph.Empty) ||
            (!batch.jobs().isEmpty() && (batch.jobs().stream().findFirst().get() instanceof JobId.TaskJobId ||
                                         batch.jobs().stream().findFirst().get() instanceof JobId.SignalJobId))) {
          this.timeline.add(tip, curTime().duration(), stepIndexAtTime, MissionModel.queryTopic);
          //updateTaskInfo(tip);
          if (stepIndexAtTime < Integer.MAX_VALUE) {
            stepIndexAtTime += 1;
            setCurTime(new SubInstantDuration(curTime().duration(), stepIndexAtTime));
          }
          else throw new RuntimeException(
              "Only Resource jobs (not Task jobs) should be run at step index Integer.MAX_VALUE");
        }
      }
      if (results.error.isPresent()) {
        throw results.error.get();
      }
      // Serialize the resources updated in this batch
      if (curTime().noShorterThan(getElapsedTime())) {
        for (final var update : results.resourceUpdates.updates()) {
          final var name = update.resourceId().id();
          final var schema = update.resource().getOutputType().getSchema();

          switch (update.resource.getType()) {
            case "real" -> realResourceUpdates.put(name, Pair.of(schema, SimulationEngine.extractRealDynamics(update)));
            case "discrete" -> dynamicResourceUpdates.put(
                name,
                Pair.of(
                    schema,
                    SimulationEngine.extractDiscreteDynamics(update)));
          }
        }
      }
    }
    if (debug) System.out.println("step(): end -- time = " + curTime() + ", step " + stepIndexAtTime);
    return new Status.Nominal(getElapsedTime(), realResourceUpdates, dynamicResourceUpdates);
  }

  private static <Dynamics> RealDynamics extractRealDynamics(final ResourceUpdates.ResourceUpdate<Dynamics> update) {
    final var resource = update.resource;
    final var dynamics = update.update.dynamics();

    final var serializedSegment = resource.getOutputType().serialize(dynamics).asMap().orElseThrow();
    final var initial = serializedSegment.get("initial").asReal().orElseThrow();
    final var rate = serializedSegment.get("rate").asReal().orElseThrow();

    return RealDynamics.linear(initial, rate);
  }

  private static <Dynamics> SerializedValue extractDiscreteDynamics(final ResourceUpdates.ResourceUpdate<Dynamics> update) {
    return update.resource.getOutputType().serialize(update.update.dynamics());
  }

  /**  */
  public void putInCellReadHistory(Topic<?> topic, TaskId taskId, Event noop, SubInstantDuration time) {
    // TODO: Can't we just get this from eventsByTopic instead of having a separate data structure?
    var inner = cellReadHistory.computeIfAbsent(topic, $ -> new TreeMap<>());
    inner.computeIfAbsent(time, $ -> new HashMap<>()).computeIfAbsent(taskId, $ -> new HashSet<>()).add(noop);
  }

  /**
   * A cache of the combinedHistory so that it does not need to be recomputed after simulation.  The parent engine sets
   * the cache for the child engine per topic and clears it for the grandchild per topic.  This assumes that an engine
   * will not have more than one parent.
   */
  protected HashMap<Topic<?>, TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>>> _combinedHistory = new HashMap<>();
  /**
   * A cache of part of the combinedHistory computation that is the old combined history without the removed task history.
   * This should be cleared by the parent engine.
   */
  //protected HashMap<Topic<?>, TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>>> _oldCleanedHistory = new HashMap<>();
  // protected Duration _combinedHistoryTime = null;

//  public HashMap<Topic<?>, TreeMap<Duration, HashMap<TaskId, Event>>> getCombinedCellReadHistory() {
//
//  }
//  public TreeMap<Duration, HashMap<TaskId, Event>> getCombinedCellReadHistory(Topic<?> topic) {
//    return getCombinedCellReadHistory().get(topic);
//  }

  // An empty map constant that would be immutable if it didn't require significant more code
  private static final TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>> _emptyTreeMap = new TreeMap<>();

  /**
   * Combine a cell topic's read history of past engines with this engine's history to get a complete view.
   * @param topic the topic of a cell whose read history is sought
   * @return the combined cell read history across engines as a map from time to task to the task's read events
   */
  public TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>> getCombinedCellReadHistory(Topic<?> topic) {
    // The strategy below is to cache the combined results of the oldEngine in the oldEngine and flush the cache
    // of the oldEngine's oldEngine.  Then those results are combined with the current to give to the caller.
    // History per topic is cached separately; the history of some topics may be computed and
    // cached while those of others are not.  The results must be cleaned by removing the reads of removed tasks.
    // Those cleaned results are also cached and then combined with the current engine's history, which will
    // be cached by its parent engine's combined history.
    //
    // The tricky part is that the parent stores the oldEngine's results in the oldEngine's cache because the
    // oldEngine by itself does not check to see if it is finished simulating.  So, the engine caches the
    // cleaned history before applying its own history and does not cache the final results because the parent will.
    // TODO -- consider checking this.closed to determine whether to cache results to avoid this trickiness

    // check cache
    var inner = _combinedHistory.get(topic); // TODO -- REVIEW -- does this take into account this engine's results?
    if (inner != null) return inner;

    inner = cellReadHistory.get(topic);
    if (oldEngine == null) {
      // If there's no history from an old engine, then just set the cache to the local history because if it doesn't
      // already have a child engine, it never will.
      _combinedHistory = cellReadHistory;
      if (inner == null) return _emptyTreeMap;
      return inner;
    }

    // Cache oldEngine's combined history and clear cache of the oldEngine's oldEngine for this topic to save memory
    var oldInner = oldEngine.getCombinedCellReadHistory(topic);
    if (oldInner == null) oldInner = _emptyTreeMap;
    // If the oldEngine's cache doesn't have results in the cache, then add them to its cache
    if (oldEngine._combinedHistory.get(topic) == null) {
      oldEngine._combinedHistory.put(topic, oldInner);
      // clear the cache of the oldEngine.oldEngine for this topic
      if (!allowMultipleParentEngines && oldEngine.oldEngine != null && oldEngine.oldEngine._combinedHistory != null) {
        oldEngine.oldEngine._combinedHistory.remove(topic);
        //oldEngine.oldEngine._oldCleanedHistory.remove(topic);
        oldEngine.oldEngine.cellReadHistory.remove(topic);
      }
    }

    // Clean the removed tasks from the old read history
    // Check for cached computation first
    //var oldCleanedHistory = new TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>>(); //_oldCleanedHistory.get(topic);
    //if (oldCleanedHistory == null) {
    TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>> oldCleanedHistory = null;
      Set<SubInstantDuration> commonKeys = oldInner.keySet().stream().filter(d -> removedCellReadHistory.containsKey(d)).collect(
          Collectors.toSet());
      if (commonKeys.isEmpty()) {
        oldCleanedHistory = oldInner;
      } else {
        oldCleanedHistory = new TreeMap<>();
        for (var oDur : commonKeys) {
          var rTasks = removedCellReadHistory.get(oDur);
          var oTaskMap = oldInner.get(oDur);
          if (rTasks == null) {
            oldCleanedHistory.put(oDur, oTaskMap);
          }
          HashMap<TaskId, Set<Event>> cleanTaskMap = new HashMap<>();
          Set<TaskId> commonTasks = oTaskMap.keySet().stream().filter(t -> rTasks.contains(t)).collect(
              Collectors.toSet());
          if (commonTasks.isEmpty()) {
            cleanTaskMap = oTaskMap;
          } else {
            cleanTaskMap = new HashMap<>();
            for (var tEntry : oTaskMap.entrySet()) {
              var oTaskId = tEntry.getKey();
              if (!rTasks.contains(oTaskId)) {
                cleanTaskMap.put(tEntry.getKey(), tEntry.getValue());
              }
            }
          }
          oldCleanedHistory.put(oDur, cleanTaskMap);
        }
      }
      // Now cache the results
      //_oldCleanedHistory.put(topic, oldCleanedHistory);
    //}
    final var oi = oldInner;
    final var och = oldCleanedHistory;
    oi.keySet().stream().filter(d -> !removedCellReadHistory.containsKey(d)).forEach(k -> och.put(k, oi.get(k)));
    // Now merge local history with old cleaned history
    TreeMap<SubInstantDuration, HashMap<TaskId, Set<Event>>> combinedTopicHistory = oldCleanedHistory;
    if (oldCleanedHistory.isEmpty()) {
      combinedTopicHistory = inner;
    } else if (inner == null || inner.isEmpty()) {
    } else if (closed) {
      // merge the new history with the old cleaned history
      combinedTopicHistory = deepMergeMapsFirstWins(inner, oldCleanedHistory);
//      // first make a deep copy of the first
//      combinedTopicHistory = new TreeMap<>();
//      for (final Map.Entry<SubInstantDuration, HashMap<TaskId, Set<Event>>> entry : oldCleanedHistory.entrySet()) {
//        combinedTopicHistory.put(entry.getKey(), new HashMap<>(entry.getValue()));
//      }
//      for (final var entry : inner.entrySet()) {
//        var oldMap = combinedTopicHistory.get(entry.getKey());
//        var mergedMap = TemporalEventSource.mergeHashMapsFirstWins(entry.getValue(), oldMap);
//        combinedTopicHistory.put(entry.getKey(), new HashMap<>(entry.getValue()));
//      }
    }

    // No need to cache this.  The parent engine caches this.
    return closed ? combinedTopicHistory : oldCleanedHistory;
  }

  public static <K, V> TreeMap<K, V> deepMergeMapsFirstWins(TreeMap<K, V> m1, TreeMap<K, V> m2) {
    if (m1 == null) return m2;
    if (m2 == null || m2.isEmpty()) return m1;
    if (m1.isEmpty()) return m2;
    return Stream.of(m1, m2).flatMap(m -> m.entrySet().stream()).collect(Collectors.toMap(t -> t.getKey(),
                                                                                          t -> t.getValue(),
                                                                                          (v1, v2) -> (v1 instanceof HashMap mm1 && v2 instanceof HashMap mm2) ? (V)TemporalEventSource.mergeHashMapsFirstWins(mm1, mm2) : v1,
                                                                                          TreeMap::new));
  }


  /**
   * Get the earliest time within a specified range that potentially stale cells are read by tasks not scheduled
   * to be re-run.
   * @param after start of time range
   * @param before end of time range
   * @return the time of the earliest read, the tasks doing the reads, and the noop Events/Topics read by each task
   */
  /** Get the earliest time that topics become stale and return those topics with the time */
//  public Pair<SubInstantDuration, Map<TaskId, HashSet<Pair<Topic<?>, Event>>>> earliestStaleReadsNew(SubInstantDuration after, SubInstantDuration before, Topic<Topic<?>> queryTopic) {
//    // We need to have the reads sorted according to the event graph.  Currently, this function doesn't
//    // handle a task reading a cell more than once in a graph.  But, we should make sure we handle this case. TODO
//    // TODO -- This case is
//    var earliest = before;
//    final var tasks = new HashMap<TaskId, HashSet<Pair<Topic<?>, Event>>>();
//    ConcurrentSkipListSet<SubInstantDuration> durs = timeline.staleTopics.entrySet().stream().collect(ConcurrentSkipListSet::new,
//                                                                                            (set, entry) -> set.addAll(entry.getValue().keySet().stream().filter(d -> entry.getValue().get(d)).toList()),
//                                                                                            (set1, set2) -> set1.addAll(set2));
//    if (durs.isEmpty()) return Pair.of(SubInstantDuration.MAX_VALUE, Collections.emptyMap());
//    var earliestStaleTopic = durs.higher(after);
//    final TreeMap<Duration, List<EventGraph<Event>>> readEvents = oldEngine.timeline.getCombinedEventsByTopic().get(queryTopic);
//    if (readEvents == null || readEvents.isEmpty()) return Pair.of(SubInstantDuration.MAX_VALUE, Collections.emptyMap());
//    var readEventsSubmap = readEvents.subMap(after.duration(), false, before.duration(), true);
//    for (var te : readEventsSubmap.entrySet()) {
//      final List<EventGraph<Event>> graphList = te.getValue();
//      for (var eventGraph : graphList) {
//        final List<Pair<String, Event>> flatGraph = EventGraphFlattener.flatten(eventGraph);
//        for (var pair : flatGraph) {
//          Event event = pair.getRight();
//          // HERE!
//        }
//      }
//    }
//
//    if (readEvents.isEmpty()) return Pair.of(SubInstantDuration.MAX_VALUE, Collections.emptyMap());
//    for (var entry : timeline.staleTopics.entrySet()) {
//      Topic<?> topic = entry.getKey();
//      var subMap = entry.getValue().subMap(after, false, earliest, true);
//      SubInstantDuration d = null;
//      for (var e : subMap.entrySet()) {
//        if (e.getValue()) {
//          d = e.getKey();
//          var topicEventsSubMap = readEventsSubmap.subMap(d.duration(), true, earliest.duration(), true);
//          break;
//        }
//      }
//      if (d == null) {
//        continue;
//      }
//      int comp = d.compareTo(earliest);
//      if (comp <= 0) {
//        if (comp < 0) tasks.clear();
//        //tasks.add(topic);
//        earliest = d;
//      }
//    }
//    if (tasks.isEmpty()) earliest = SubInstantDuration.MAX_VALUE;
//    return Pair.of(earliest, tasks);
//  }
//
//public String whatsThis(Topic<?> topic) {
//    return missionModel.getResources().entrySet().stream().filter(e -> e.getValue().toString()).findFirst()
//}


  public Pair<SubInstantDuration, Map<TaskId, HashSet<Pair<Topic<?>, Set<Event>>>>> earliestStaleReads(SubInstantDuration after, SubInstantDuration before) {
    // Reads are not sorted according to the event graph.  This function needs to support
    // handling a task reading a cell more than once in a graph.
    // DONE -- This case seems handled in that multiple read events are collected; elsewhere, the events are individually
    //         tested stepping up to them.  If any of them fail, the task will be rescheduled on a SubInstantDuration
    //         boundary.  So, we would need to clean out all of the tasks events in the graph anyway.
    var earliest = before;
    final var tasks = new HashMap<TaskId, HashSet<Pair<Topic<?>, Set<Event>>>>();
    final var topicsStale = timeline.staleTopics.keySet();
    for (var topic : topicsStale) {
      var topicReads = getCombinedCellReadHistory(topic);
      if (topicReads == null || topicReads.isEmpty()) {
        continue;
      }
      NavigableMap<SubInstantDuration, HashMap<TaskId, Set<Event>>> topicReadsAfter =
          topicReads.subMap(after, true, earliest, true);
      if (topicReadsAfter == null || topicReadsAfter.isEmpty()) {
        continue;
      }
      for (var entry : topicReadsAfter.entrySet()) {
        var d = entry.getKey();
        final HashMap<TaskId, Set<Event>> taskEvents = entry.getValue();
        if (taskEvents == null || taskEvents.isEmpty()) continue;
        HashMap<TaskId, Set<Event>> taskIds = new HashMap<>();
        // Don't include tasks which are being re-executed
        for (var e : taskEvents.entrySet()) {
          if (!staleTasks.containsKey(e.getKey())) {
            taskIds.put(e.getKey(), e.getValue());
          }
        }
        if (timeline.isTopicStale(topic, d)) {
          if (d.shorterThan(earliest)) {
            earliest = d;
            tasks.clear();
          } else if (d.longerThan(earliest)) {
            if (!tasks.isEmpty()) break;
            continue;
          }
          taskIds.forEach((id, event) -> tasks.computeIfAbsent(id, $ -> new HashSet<>()).add(Pair.of(topic, event)));
        }
      }
    }
    if (tasks.isEmpty()) earliest = SubInstantDuration.MAX_VALUE;
    return Pair.of(earliest, tasks);
  }

  public Pair<SubInstantDuration, Map<Topic<?>, Set<ConditionId>>> earliestStaleConditionReads(SubInstantDuration after, SubInstantDuration before) {
    Map<Topic<?>, Set<ConditionId>> staleReads = new HashMap<>();
    if (before.shorterThan(after)) {
      return Pair.of(SubInstantDuration.MAX_VALUE, Collections.EMPTY_MAP);
    }
    //var staleTopics = earliestStaleTopics(after, before);
    //var list = new ArrayList<ConditionId>();
    var earliest = before;
    for (var entry : timeline.staleTopics.entrySet()) {
      Topic<?> topic = entry.getKey();
      Optional<Map.Entry<Range<SubInstantDuration>, Set<ConditionId>>> conditionsAtTime = Optional.empty(); // this will be the result for the topic
      var subMap = entry.getValue().subMap(after, true, earliest, true);
      SubInstantDuration staleStart = null;
      SubInstantDuration staleEnd = null;
      for (var e : subMap.entrySet()) {
        // if we are entering a stale period, remember this as staleStart
        if (e.getValue() && staleStart == null) {
          staleStart = e.getKey();
          if (staleStart != null && staleStart.longerThan(earliest)) break;
        }
        // if we are exiting a stale period, remember this as staleEnd
        if (!e.getValue() && staleStart != null) {  // have we found the end of the stale period
          staleEnd = e.getKey();
          conditionsAtTime =
              getEarliestConditionsWaitingOnTopic(topic, staleStart, SubInstantDuration.min(staleEnd, earliest));
          if (conditionsAtTime.isPresent()) break;
          staleStart = null;
          staleEnd = null;
        }
      }
      if (staleStart == null || staleStart.longerThan(earliest)) continue;
      // stale period never ended
      if (!conditionsAtTime.isPresent() && staleEnd == null) {
        conditionsAtTime =
            getEarliestConditionsWaitingOnTopic(topic, staleStart, earliest);
        //continue;
      }
      if (conditionsAtTime.isEmpty()) continue;
      SubInstantDuration start = conditionsAtTime.get().getKey().lowerEndpoint();
      if (start.longerThan(earliest)) continue;  // this should be impossible
      if (start.shorterThan(earliest)) {
        earliest = start;
        staleReads.clear();
      }
      staleReads.put(topic, conditionsAtTime.get().getValue());
    }
    if (staleReads.isEmpty()) earliest = SubInstantDuration.MAX_VALUE;
    return Pair.of(earliest, staleReads);
  }

  private Optional<Map.Entry<Range<SubInstantDuration>, Set<ConditionId>>> getEarliestConditionsWaitingOnTopic(
      Topic<?> topic,
      SubInstantDuration after,
      SubInstantDuration before)
  {
    if (after.longerThan(before)) return Optional.empty();
    var conditionHistoryforTopic = getCombinedConditionHistoryByTopic().get(topic);
    if (conditionHistoryforTopic != null) {
      var topicSubMap = conditionHistoryforTopic.subMap(Range.closed(after, before));
      return topicSubMap.asMapOfRanges().entrySet().stream().findFirst();
    }
    return Optional.empty();
  }

  /**
   * Get the earliest time that stale topics have events in the old simulation.  These are places where we need
   * to update resource profiles but that aren't captured by {@link #earliestStaleTopics(SubInstantDuration, SubInstantDuration)}.
   */
  public Pair<List<Topic<?>>, SubInstantDuration> nextStaleTopicOldEvents(SubInstantDuration after, SubInstantDuration before) {
    var list = new ArrayList<Topic<?>>();
    var earliest = before;
    for (var entry : timeline.staleTopics.entrySet()) {
      Topic<?> topic = entry.getKey();
      Optional<SubInstantDuration> nextStale = timeline.whenIsTopicStale(topic, after.plus(1), before);
      if (nextStale.isEmpty()) continue;
      TreeMap<Duration, List<EventGraph<Event>>> eventsByTime =
          timeline.oldTemporalEventSource.getCombinedEventsByTopic().get(topic);
      if (eventsByTime == null) continue;
      if (nextStale.get().longerThan(earliest)) continue;
      var subMap = eventsByTime.subMap(nextStale.get().duration(), true, earliest.duration(), true);
      SubInstantDuration time = null;
      for (var e : subMap.entrySet()) {
        Duration d = e.getKey();
        final List<EventGraph<Event>> events = e.getValue();
        if (events == null || events.isEmpty()) continue;
//        int step = d.isEqualTo(after.duration()) ? after.index() : 0;
        int step = d.isEqualTo(nextStale.get().duration()) ? nextStale.get().index() : 0;
        int maxSteps = Math.min(events.size(), before.duration().isEqualTo(nextStale.get().duration()) ? before.index() : Integer.MAX_VALUE);
        for (; step < maxSteps; ++step) {
          var graph = events.get(step);
          if (timeline.oldTemporalEventSource.getTopicsForEventGraph(graph).contains(topic)) {
            time = new SubInstantDuration(d, step);
            if (time.longerThan(after) && timeline.isTopicStale(topic, time) ) {
              break;
            }
            time = null;
          }
        }
        if (time != null) break;
      }
      if (time == null) {
        continue;
      }
      int comp = time.compareTo(earliest);
      if (comp <= 0) {
        if (comp < 0) list.clear();
        list.add(topic);
        earliest = time;
      }
    }
    if (list.isEmpty()) earliest = SubInstantDuration.MAX_VALUE;
    return Pair.of(list, earliest);
  }

  /** Get the earliest time that topics become stale and return those topics with the time */
  public Pair<List<Topic<?>>, SubInstantDuration> earliestStaleTopics(SubInstantDuration after, SubInstantDuration before) {
    if (before.noLongerThan(after)) {
      return Pair.of(Collections.emptyList(), SubInstantDuration.MAX_VALUE);
    }
    var list = new ArrayList<Topic<?>>();
    var earliest = before;
    for (var entry : timeline.staleTopics.entrySet()) {
      Topic<?> topic = entry.getKey();
      var subMap = entry.getValue().subMap(after, true, earliest, true);
      SubInstantDuration d = null;
      for (var e : subMap.entrySet()) {
        if (e.getValue()) {
          d = e.getKey();
          break;
        }
      }
      if (d == null) {
        continue;
      }
      int comp = d.compareTo(earliest);
      if (comp <= 0) {
        if (comp < 0) list.clear();
        list.add(topic);
        earliest = d;
      }
    }
    if (list.isEmpty()) earliest = SubInstantDuration.MAX_VALUE;
    return Pair.of(list, earliest);
  }

  public Pair<List<Topic<?>>, SubInstantDuration> earliestConditionTopics(SubInstantDuration after, SubInstantDuration before) {
    if (before.noLongerThan(after)) {
      return Pair.of(Collections.emptyList(), SubInstantDuration.MAX_VALUE);
    }
    var list = new ArrayList<Topic<?>>();
    var earliest = before;
    for (Topic topic : this.waitingConditions.getTopics()) {
      TreeMap<Duration, List<EventGraph<Event>>> eventsByTime =
          timeline.getCombinedEventsByTopic().get(topic);
      if (eventsByTime == null) continue;
      var subMap = eventsByTime.subMap(after.duration(), true, earliest.duration(), true);
      SubInstantDuration time = null;
      for (var e : subMap.entrySet()) {
        final List<EventGraph<Event>> events = e.getValue();
        if (events == null || events.isEmpty()) continue;
        Duration d = e.getKey();
        for (int step = 0; step < events.size(); ++step) {
          var graph = events.get(step);
          var topicForGraph = getTopicsForEventGraph(graph);
          if (topicForGraph.contains(topic)) {
            time = new SubInstantDuration(d, step);
//            if (timeline.isTopicStale(topic, time)) {
              break;
//            }
//            time = null;
          }
        }
        if (time != null) break;
      }
      if (time == null) {
        continue;
      }
      int comp = time.compareTo(earliest);
      if (comp <= 0) {
        if (comp < 0) list.clear();
        list.add(topic);
        earliest = time;
      }
    }
    if (list.isEmpty()) earliest = SubInstantDuration.MAX_VALUE;
    return Pair.of(list, earliest);
  }

  private ExecutionState<?> getTaskExecutionState(TaskId taskId) {
    var execState = tasks.get(taskId);
    if (execState == null && oldEngine != null) {
      execState = oldEngine.getTaskExecutionState(taskId);
    }
    return execState;
  }

  /**
   * If task is not already stale, record the task's staleness at specified time in this.staleTasks,
   * remove task reads and effects from the timeline and cell read history, and then create the task
   * and schedule a job for it.
   *
   * @param taskId id of the task being set stale
   * @param time time when the task becomes stale
   * @param afterEvent
   */
  public void setTaskStale(TaskId taskId, SubInstantDuration time, final Event afterEvent) {
    if (debug) System.out.println("setTaskStale(" + taskId + " (" + getNameForTask(taskId) + "), " + time + ", afterEvent=" + afterEvent + ")");
    var staleTime = staleTasks.get(taskId);
    if (staleTime != null) {
      if (staleTime.shorterThan(time) || (staleTime.isEqualTo(time) &&
                                          (afterEvent == null || staleEvents.get(taskId) == null ||
                                           eventPrecedes(afterEvent, staleEvents.get(taskId), time)))) {
        // already marked stale by this time; a stale task cannot become unstale because we can't see it's internal state
        String taskName = getNameForTask(taskId);
        System.err.println("WARNING: trying to set stale task stale at earlier time; this should not be possible; cannot re-execute a task more than once: TaskId = " + taskId + ", task name = \"" + taskName + "\"");
      }
      return;
    }

    // TODO -- When a spawned child, C1, has a stale read and reruns, it can cause a stale read in the parent, P,
    //         and P needs to rerun, but in this case, it shouldn't re-spawn the already rerunning child.  Should
    //         it rerun another non-respawned child, C2?  Yes, but since C2 might also have a stale read because of
    //         C1, it should go stale at the same time as C1.
    //         _
    //         So, it looks like we need to be able to rerun a task independent of its children or parent.
    //         _
    //         If we were to cache the task factories of children when the parent is rerun, that would make it easier.
    //         -
    //         So, the new algorithm is to rerun each task independently.  If a parent and child are to be re-run, the
    //         parent is first and saves off (caches) its childrens' taskFactories without re-running them so that if
    //         it is necessary to rerun a child, the parent does not need to be rerun again.

    // find parent task to execute and mark parents stale
    TaskId childId = null;
    TaskId parentId = taskId;
    TaskId lastTaskWithFactory = null;
    TaskId taskWithFactory = taskId;
    while (parentId != null) {
      var nextParentId = oldEngine.getTaskParent(parentId);
      // Don't set the parent stale unless it is calling the child (instead of spawning)
      boolean parentStale = childId == null || isTaskCalled(childId) || alwaysRerunParentTasks;
      if (parentStale) {
        if (trace) System.out.println("setTaskStale(" + taskId + " : " + getNameForTask(taskId) + "): adding staleness entry for " + parentId);
        staleTasks.put(parentId, time);
        staleEvents.put(parentId, afterEvent); // TODO -- more efficient to have one map with a pair of (time, afterEvent)
        if (!alwaysRerunParentTasks) taskWithFactory = null;
      }
      // Need task factory for the highest stale parent, or for its lowest parent if it has no task factory
      if (taskWithFactory == null ||alwaysRerunParentTasks) {
        // if we cache task lambdas/TaskFactorys, we want to stop at the first existing lambda/TakFactory
        if (oldEngine.getFactoryForTaskId(parentId) != null) {
          if (trace) System.out.println("setTaskStale(" + taskId + " : " + getNameForTask(taskId) + "): found factory for " + parentId +" : " + getNameForTask(parentId));
          taskWithFactory = parentId;
        } else
        if (oldEngine.isActivity(parentId)) {
          if (trace) System.out.println("setTaskStale(" + taskId + " : " + getNameForTask(taskId) + "): isActivity(" + parentId + " : " + getNameForTask(parentId) + ") = true");
          taskWithFactory = parentId;
        } else
        if (oldEngine.isDaemonTask(parentId)) {
          if (trace) System.out.println("setTaskStale(" + taskId + " : " + getNameForTask(taskId) + "): isDaemonTask(" + parentId + " : " + getNameForTask(parentId) + ") = true");
          taskWithFactory = parentId;
        }
      }
      if (taskWithFactory != null) lastTaskWithFactory = taskWithFactory;
      if (trace) System.out.println("setTaskStale(" + taskId + " : " + getNameForTask(taskId) + "): parent of " + parentId + " (" + getNameForTask(parentId) + ") is " + nextParentId + " : " + getNameForTask(nextParentId));
      if (nextParentId == null) {  // TODO -- make the conditions for this more explicit so that it's not brittle to changes in task generation.  The top-level task (which has no parent) is the sole parent of the activity for the directive, and it calls the activity instead of spawning it
        if (taskWithFactory == null) taskWithFactory = lastTaskWithFactory;
        break;
      }
      childId = parentId;
      parentId = nextParentId;
    }

    Duration taskStart = null;
    var spanId = oldEngine.taskToSpanMap.get(taskWithFactory);
    if (spanId != null) {
      var span = oldEngine.spans.get(spanId);
      if (span != null) {
        taskStart = span.startOffset;
      }
    }
    if (taskStart == null) {
      final ExecutionState<?> execState = oldEngine.getTaskExecutionState(taskWithFactory);
      if (execState != null) taskStart = execState.startOffset(); // WARNING: assumes offset is from same plan start
      else {
        //taskStart = Duration.ZERO;
        throw new RuntimeException("Can't find task start!  task id = " + taskWithFactory + " : " + getNameForTask(taskWithFactory));
      }
    }
    rescheduleTask(taskWithFactory, taskStart, afterEvent);
    removeTaskHistory(taskWithFactory, time, afterEvent);
  }

  private boolean isTaskCalled(TaskId childId) {
    if (calledTasks.contains(childId)) return true;
    if (oldEngine != null) return oldEngine.isTaskCalled(childId); // TODO -- this is inefficient -- need to stop looking if task first introduced in this engine
    return false;
  }

  private boolean eventPrecedes(Event e1, Event e2, SubInstantDuration time) {
    if (e1 == null || e2 == null || time == null) return false;
    List<TemporalEventSource.TimePoint.Commit> commits = timeline.getCombinedCommitsByTime().get(time.duration());
    var commit = commits.get(time.index());
    final Pair<EventGraph<Event>, Boolean> pair = commit.events().filter(e -> e == e2, e1, false);
    if (pair.getRight() && pair.getLeft().countNonEmpty() > 0) {
      return true;
    }
    return false;
  }

  TaskId getTaskIdForConditionId(ConditionId id) {
    return id.sourceTask();
  }

  Set<ConditionId> getConditionIdsForTaskId(TaskId id) {
    Set<ConditionId> s = conditionsForTask.get(id);
    if (s == null && oldEngine != null) {
      s = oldEngine.getConditionIdsForTaskId(id);
    }
    return s == null ? Collections.EMPTY_SET : s;
  }


  private Set<TaskId> areChildren(Set<TaskId> staleTasks) {
    // TODO -- would this be better if done functional programming style, maybe by computing a closure with getTaskParent()?
    Set<TaskId> children = new HashSet<>();
    for (var taskId : new ArrayList<>(staleTasks)) {
      TaskId parentId = getTaskParent(taskId);
      while (parentId != null) {
        if (staleTasks.contains(parentId)) {
          children.add(taskId);
          break;
        }
        parentId = getTaskParent(parentId);
      }
    }
    return children;
  }

  private Set<TaskId> rescheduleStaleTasks(SubInstantDuration time, Map<Topic<?>, Set<ConditionId>> staleConditionReads,
                                           Set<TaskId> tasksToIgnore, boolean justGetTasks) {
    //Map<TaskId, HashSet<Pair<Topic<?>, Event>>> staleReads = new HashMap<>();
    Set<TaskId> staleTasks = new HashSet<>();
    Set<TaskId> processedTasks = new HashSet<>();
    removedCellReadHistory.values().forEach(processedTasks::addAll); // check if just rescheduled for stale read
    for (var e : staleConditionReads.entrySet()) {
      for (ConditionId c : e.getValue()) {
        TaskId taskId = getTaskIdForConditionId(c);
        if (!processedTasks.contains(taskId) && !tasksToIgnore.contains(taskId)) {
          staleTasks.add(taskId);
          processedTasks.add(taskId);
        }
      }
    }
    // Now set remaining tasks stale and reschedule them
    if (!justGetTasks) {
      for (var taskId : staleTasks) {
        setTaskStale(taskId, time, null);
      }
    }
    return staleTasks;
  }


  /**
   * For the next time t that a set of tasks could potentially have a stale read, check if any read is stale for
   * each of those tasks, and, if so, mark them stale at t and schedule them to re-run.
   * <p>
   * This method assumes that these are reads that occurred in the previous simulation and thus have an EventGraph
   * in the old SimulationEngine's timeline with the read noop.  If the current timeline has an EventGraph at this
   * same time, it is assumed to also have the noop events.
   *
   * @param earliestStaleReads the time of the potential stale reads along with the tasks and the potentially stale topics they read
   * @param justGetTasks don't actually set the tasks stale and reschedule; just get the tasks that would have been
   * @return the tasks that were or would be set stale and rescheduled
   */
  public Set<TaskId> rescheduleStaleTasks(
      Pair<SubInstantDuration, Map<TaskId, HashSet<Pair<Topic<?>, Set<Event>>>>> earliestStaleReads,
      Set<TaskId> tasksToIgnore, boolean justGetTasks) {
    if (debug) System.out.println("rescheduleStaleTasks(" + earliestStaleReads + ")");
    Set<TaskId> tasksSetStale = new HashSet<>();
    // Test to see if read value has changed.  If so, reschedule the affected task
    var timeOfStaleReads = earliestStaleReads.getLeft();
    for (Map.Entry<TaskId, HashSet<Pair<Topic<?>, Set<Event>>>> entry : earliestStaleReads.getRight().entrySet()) {
      final var taskId = entry.getKey();
      if (tasksToIgnore.contains(taskId)) continue;
      for (Pair<Topic<?>, Set<Event>> pair : entry.getValue()) {
        final var topic = pair.getLeft();
        final var events = pair.getRight();
        // Need to step cell up to the point of the read
        // First, step up the cell to the time before the event graph where the read takes place and then
        // make a duplicate of the cell since partial evaluation of an event graph makes the cell unusable
        // for stepping further.
        Cell<?> steppedCell = timeOfStaleReads.index() > 0 ?
                              timeline.getCell(topic, new SubInstantDuration(timeOfStaleReads.duration(),
                                                                             timeOfStaleReads.index()-1)) :
                              timeline.liveCells.getCells(topic).stream().findFirst().orElseThrow().cell;
        if (debug) System.out.println("rescheduleStaleTasks(): steppedCell = " + steppedCell + ", cell time = " + timeline.getCellTime(steppedCell));
        boolean didSetTaskStale = false;
        for (Event noop : events) {
          final Cell<?> tempCell = steppedCell.duplicate();
          timeline.putCellTime(tempCell,timeline.getCellTime(steppedCell));
          timeline.stepUp(tempCell, timeOfStaleReads, noop);
          timeline.putCellTime(tempCell, null);

          Cell<?> oldCell = timeline.oldTemporalEventSource.getCell(topic, new SubInstantDuration(timeOfStaleReads.duration(),
                                                                                                  max(0, timeOfStaleReads.index()-1)));
          if (debug) System.out.println("rescheduleStaleTasks(): oldCell = " + oldCell + ", cell time = " + timeline.oldTemporalEventSource.getCellTime(oldCell));
          final Cell<?> tempOldCell = oldCell.duplicate();
          timeline.oldTemporalEventSource.putCellTime(tempOldCell,timeline.oldTemporalEventSource.getCellTime(oldCell));
          timeline.oldTemporalEventSource.stepUp(tempOldCell, timeOfStaleReads, noop);
          timeline.oldTemporalEventSource.putCellTime(tempOldCell, null);

          if (!tempCell.getState().equals(tempOldCell.getState())) {
            if (debug) System.out.println("rescheduleStaleTasks(): Stale read: new cell state (" + tempCell + ") != old cell state (" + tempOldCell + ")");
            tasksSetStale.add(taskId);
            // Mark stale and reschedule task
            if (!justGetTasks) {
              setTaskStale(taskId, timeOfStaleReads, noop);
            }
            didSetTaskStale = true;
            break;  // rescheduled task, so can move on to the next task
          }
        }
        if (didSetTaskStale) break;
      }  // for Pair<Topic<?>, Event>
    }  // for Map.Entry<TaskId, HashSet<Pair<Topic<?>, Event>>>
    return tasksSetStale;
  }


  public SpanId putSpanId(TaskId taskId, SpanId spanId) {
    spanToTaskMap.computeIfAbsent(spanId, $ -> new LinkedHashSet<>()).add(taskId);
    return taskToSpanMap.put(taskId, spanId);
  }
  public SpanId getSpanId(TaskId taskId) {
    var s = taskToSpanMap.get(taskId);
    if (s == null && oldEngine != null) {
      return oldEngine.getSpanId(taskId);  // TODO -- do we need caches to avoid walking a long chain of oldEngines?
    }
    return s;
  }
  public SequencedSet<TaskId> getTaskIds(final SpanId spanId) {
    var s = spanToTaskMap.get(spanId);
    SpanId sId = spanId;
    while (s == null) {
      final Span span = spans.get(sId);
      if (span != null) {
        if (span.parent().isPresent()) {
          sId = span.parent().get();
          s = spanToTaskMap.get(sId);
        } else {
          break;
        }
      } else {
        break;
      }
    }
    if (s == null && oldEngine != null) {
      return oldEngine.getTaskIds(spanId);  // TODO -- do we need caches to avoid walking a long chain of oldEngines?
    }
    return s;
  }

  public TaskId getTaskIdForDirectiveId(ActivityDirectiveId id) {
    var spanId = getSpanIdForDirectiveId(id);
    SequencedSet<TaskId> taskIds = null;
    TaskId taskId = null;
    if (spanId != null) {
      taskIds = getTaskIds(spanId);
      if (taskIds != null && !taskIds.isEmpty()) {
        taskId = taskIds.getFirst();
      }
    }
    if (taskId == null && oldEngine != null) {
      taskId = oldEngine.getTaskIdForDirectiveId(id);  // TODO -- do we need caches to avoid walking a long chain of oldEngines?
      // NOTE -- We do not need filter out removed tasks because the directive would also be removed, in which case we do want the removed task.
    }
    return taskId;
  }

  public ActivityInstanceId getSimulatedActivityIdForDirectiveId(ActivityDirectiveId directiveId) {
    ActivityInstanceId simId = null;
    if (directiveToSimulatedActivityId != null) {
      simId = directiveToSimulatedActivityId.get(directiveId);
    }
    if (simId == null && oldEngine != null) {
      simId = oldEngine.getSimulatedActivityIdForDirectiveId(directiveId);
    }
    return simId;
  }

  public SpanId getSpanIdForDirectiveId(ActivityDirectiveId id) {
    var spanId = this.spanInfo.getSpanIdForDirectiveId(id);
    if (spanId == null && oldEngine != null) {
      spanId = oldEngine.getSpanIdForDirectiveId(id);  // TODO -- do we need caches to avoid walking a long chain of oldEngines?
    }
    return spanId;
  }

  private TaskId getTaskIdForFactory(TaskFactory<?> taskFactory) {
    var taskId = taskIdsForFactories.get(taskFactory);
    if (taskId == null && oldEngine != null) {
      taskId = oldEngine.getTaskIdForFactory(taskFactory);
    }
    return taskId;
  }

  private TaskFactory<?> getFactoryForTaskId(TaskId taskId) {
    var taskFactory = taskFactories.get(taskId);
    if (taskFactory == null && oldEngine != null) {
      taskFactory = oldEngine.getFactoryForTaskId(taskId);
    }
    return taskFactory;
  }

  private Set<Topic<?>> getTopicsForEventGraph(EventGraph graph) {
    var r = this.timeline.topicsForEventGraph.get(graph);
    if (r == null && oldEngine != null) {
      r = oldEngine.getTopicsForEventGraph(graph);
    }
    if (r == null) return Collections.emptySet();
    return r;
  }

  private TreeMap<Duration, List<EventGraph<Event>>> getCombinedEventsByTask(TaskId taskId) {
    var newEvents = this.timeline.eventsByTask.get(taskId);
    if (oldEngine == null) return newEvents;
    SimulationEngine engine = this;
    ArrayList<SimulationEngine> engines = new ArrayList<>();
    TreeMap<Duration, List<EventGraph<Event>>> oldEvents = null;
    // Find the shallowest engine that saved old events
    while (engine != null) {
      engines.add(engine);
      if (engine._oldEventsByTask.containsKey(taskId)) {
        oldEvents = engine._oldEventsByTask.get(taskId);
        if (engine != this) engine._oldEventsByTask.remove(taskId); // purge old caches being replaced by new cache
        break;
      }
      engine = engine.oldEngine;
    }
    // Walk backwards, combining graphs
    for (int i=engines.size()-1; i >= 0; --i) {
      engine = engines.get(i);
      if (i == 0) engine._oldEventsByTask.put(taskId, oldEvents); // only update this engine's cache
      newEvents = engine.timeline.eventsByTask.get(taskId);
      var tmp_old = TemporalEventSource.mergeMapsFirstWins(newEvents, oldEvents);
      oldEvents = tmp_old;
    }
    return oldEvents;
  }
  private HashMap<TaskId, TreeMap<Duration, List<EventGraph<Event>>>> _oldEventsByTask = new HashMap<>();


  // TODO -- make recursive calls here non-recursive (like in getCombinedEventsByTask()),
  // TODO -- including getSimulatedActivityIdForTaskId(), setCurTime(), and CombinedSimulationResults

  //private HashSet<TaskId> _missingOldSimulatedActivityIds = new HashSet<>(); // short circuit deeply nested searches for taskIds that have
  private ActivityInstanceId getSimulatedActivityIdForTaskId(TaskId taskId) {
    //if (_missingOldSimulatedActivityIds.contains(taskId)) return
    ActivityInstanceId simId = null;
    var spanId = getSpanId(taskId);
    if (spanId == null && oldEngine != null) {
      spanId = oldEngine.getSpanId(taskId);
    }
    if (spanId != null) {
      if (spanToSimulatedActivityId != null) {
        simId = spanToSimulatedActivityId.get(spanId);
      } else if (oldEngine != null && oldEngine.spanToSimulatedActivityId != null) {
        simId = oldEngine.spanToSimulatedActivityId.get(spanId);
      }
    }
    //var simId = taskToSimulatedActivityId == null ? null : taskToSimulatedActivityId.get(taskId.id());
    if (simId == null && oldEngine != null) {
      // If this activity hasn't been seen in this simulation, it may be in a past one; this check avoids unnecessarily recursing
      if (this.isActivity(taskId)) {
        simId = oldEngine.getSimulatedActivityIdForTaskId(taskId);
      }
    }
    return simId;
  }

  public void removeActivity(final ActivityDirectiveId directiveId) {
    var simId = getSimulatedActivityIdForDirectiveId(directiveId);
    if (simId == null) {
      throw new RuntimeException("Could not find SimulatedActivityId for ActivityDirectiveId, " + directiveId);
    }
    removedActivities.add(simId);
    TaskId taskId = getTaskIdForDirectiveId(directiveId);
    if (taskId == null) {
      throw new RuntimeException("Could not find TaskId for ActivityDirectiveId, " + directiveId);
    }
    removeTaskHistory(taskId, SubInstantDuration.MIN_VALUE, null);
  }

  public void removeTaskHistory(final TaskId taskId, SubInstantDuration startingAfterTime, Event afterEvent) { // TODO -- need graph index with time
    // Look for the task's Events in the old and new timelines.
    if (debug) System.out.println("removeTaskHistory(taskId=" + taskId + " : " + getNameForTask(taskId) + ", startingAfterTime=" + startingAfterTime + ", afterEvent=" + afterEvent + ") BEGIN");
    final TreeMap<Duration, List<EventGraph<Event>>> graphsForTask = this.timeline.eventsByTask.get(taskId);
    final TreeMap<Duration, List<EventGraph<Event>>> oldGraphsForTask = this.oldEngine.getCombinedEventsByTask(taskId);
    if (debug) System.out.println("old combined graphs = " + oldGraphsForTask);
    if (debug) System.out.println("new local graphs =    " + graphsForTask);
    if (debug) {
      final TreeMap<Duration, List<EventGraph<Event>>> combinedGraphsForTask = this.getCombinedEventsByTask(taskId);
      if (debug) System.out.println("new combined graphs = " + combinedGraphsForTask);
    }
    var allKeys = new TreeSet<Duration>();
    if (graphsForTask != null) {
      allKeys.addAll(graphsForTask.keySet());
    }
    if (oldGraphsForTask != null) {
      allKeys.addAll(oldGraphsForTask.keySet());
    }
    for (Duration time : allKeys.tailSet(startingAfterTime.duration(), true)) {
      //if (time.shorterThan(startingAfterTime.duration())) continue;
      List<EventGraph<Event>> gl = graphsForTask == null ? null : graphsForTask.get(time); // If old graph is already replaced used the replacement
      if (gl == null || gl.isEmpty()) gl = oldGraphsForTask == null ? null : oldGraphsForTask.get(time);  // else we can replace the old graph
      if (gl == null) continue;
      final int firstStep = time.isEqualTo(startingAfterTime.duration()) ? startingAfterTime.index() : 0;
//      if (afterEvent != null && (firstStep >= gl.size() || gl.get(firstStep).filter(e -> e == afterEvent).countNonEmpty() != 1)) {
//        //System.err.println("ERROR! Could not find event " + afterEvent + " in graph for index " + firstStep + " in " + gl);
//        throw new RuntimeException("Could not find event " + afterEvent + " in graph for index " + firstStep + " in " + gl);
//      }
      //if (debug) System.out.println("comparing old graphs replacing old graph=" + g + " with new graph=" + newG + " at time " + time);
      for (int step=firstStep; step < gl.size(); ++step) {
        var g = gl.get(step);
        SubInstantDuration staleTime = new SubInstantDuration(time, step);
//        // invalidate topics for cells affected by the task in the old graph so that resource values are checked at
//        // this time to erase effects on resources  -- TODO: this doesn't work!  only one scheduled job per resource
        var s = new HashSet<Topic<?>>();
        TemporalEventSource.extractTopics(s, g, e -> taskId.equals(e.provenance()));
        //s.forEach(topic -> invalidateTopic(topic, time));
        s.forEach(topic -> timeline.setTopicStale(topic, staleTime));
        // replace the old graph with one without the task's events, updating data structures
        var pair = g.filter(e -> !taskId.equals(e.provenance()),
                            // we don't determine staleness within a graph when rerunning a task, so we just wipe out and rerun everything
                            null,  // step == firstStep && time.isEqualTo(startingAfterTime.duration()) ? afterEvent : null,
                            true);
        var newG = pair.getLeft();
        if (newG != g) {
          if (debug) System.out.println("replacing old graph=" + g + " with new graph=" + newG + " at time " + time);
          timeline.replaceEventGraph(g, newG);
          updateTaskInfo(newG);
          removedCellReadHistory.computeIfAbsent(staleTime, $ -> new HashSet<>()).add(taskId);
        }
      }
    }
    // remove span from spanInfo data structures
    SpanId spanId = getSpanId(taskId);
    if (spanId != null) spanInfo.removeSpan(spanId); // TODO -- REVIEW -- should this have no effect and be unnecessary since it would be in the old engine?

    // Remove children, too!
    var children = this.oldEngine.getTaskChildren(taskId);
    if (children != null) children.forEach(c -> removeTaskHistory(c, startingAfterTime, afterEvent));
    if (debug) {
      final TreeMap<Duration, List<EventGraph<Event>>> localGraphsForTask = this.timeline.eventsByTask.get(taskId);
      final TreeMap<Duration, List<EventGraph<Event>>> combinedGraphsForTask = this.getCombinedEventsByTask(taskId);
      System.out.println("resulting local graphs = " + localGraphsForTask);
      System.out.println("resulting combined graphs = " + combinedGraphsForTask);
    }
    if (debug) System.out.println("removeTaskHistory(taskId=" + taskId + " : " + getNameForTask(taskId) + ", startingAfterTime=" + startingAfterTime + ", afterEvent=" + afterEvent + ") END");
  }

//  private static ExecutorService getLoomOrFallback() {
//    // Try to use Loom's lightweight virtual threads, if possible. Otherwise, just use a thread pool.
//    // This approach is inspired by that of Javalin 5.
//    // https://github.com/javalin/javalin/blob/97e9e23ebe8f57aa353bc7a45feb560ad61e50a0/javalin/src/main/java/io/javalin/util/ConcurrencyUtil.kt#L48-L51
//    try {
//      // Use reflection to avoid needing `--enable-preview` at compile-time.
//      // If the runtime JVM is run with `--enable-preview`, this should succeed.
//      return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
//    } catch (final ReflectiveOperationException ex) {
//      return Executors.newCachedThreadPool($ -> {
//        final var t = new Thread($);
//        // TODO: Make threads non-daemons.
//        //  We're marking these as daemons right now solely to ensure that the JVM shuts down cleanly in lieu of
//        //  proper model lifecycle management.
//        //  In fact, daemon threads can mask bad memory leaks: a hanging thread is almost indistinguishable
//        //  from a dead thread.
//        t.setDaemon(true);
//        return t;
//      });
//    }
//  }
//
  /** Schedule a new task to be performed at the given time. */
  public <Output> SpanId scheduleTask(final Duration startTime, final TaskFactory<Output> state, TaskId taskIdToUse) {
    if (this.closed) throw new IllegalStateException("Cannot schedule task on closed simulation engine");
    if (startTime.isNegative()) throw new IllegalArgumentException(
        "Cannot schedule a task before the start time of the simulation");

    SpanId spanIdToUse = taskIdToUse == null ? null : getSpanId(taskIdToUse);
    final var span = spanIdToUse == null ? SpanId.generate() : spanIdToUse;
    this.spans.put(span, new Span(Optional.empty(), startTime, Optional.empty()));

    final var task = taskIdToUse == null ? TaskId.generate() : taskIdToUse;
    this.spanContributorCount.put(span, new MutableInt(1));
    this.tasks.put(task, new ExecutionState<>(span, Optional.empty(), state.create(this.executor), startTime));
    putSpanId(task, span);

    if (trace) System.out.println("scheduleTask(" + startTime + "): TaskId = " + task + " (" + getNameForTask(task) + "), SpanId = " + span);
    this.scheduledJobs.schedule(JobId.forTask(task), SubInstant.Tasks.at(startTime));

    this.unstartedTasks.put(task, startTime);

    return span;
  }

  /**
   * Has this resource already been simulated?
   * @param name the name of the resource used for lookup
   * @return whether the resource already has segments recorded, indicating that it has at least been partly simulated
   */
  public boolean hasSimulatedResource(final String name) {
    final var id = new ResourceId(name);
    final Resource<?> state = this.resources.get(id);
    if (state == null) {
      return false;
    }
    return true;
  }

  /**
   * Register (if not already registered) a resource whose profile should be accumulated over time.
   * Schedule a job to get resource values starting at the time specified.
   */
  public <Dynamics>
  void trackResource(final String name, final Resource<Dynamics> resource, final Duration nextQueryTime) {
    if (this.closed) throw new IllegalStateException("Cannot track resource on closed simulation engine");
    final var id = new ResourceId(name);
    final var state = this.resources.get(id);
    if (state == null) {
      this.resources.put(id, resource);
    } else {
      // TODO -- should we do some kind of reset, like clearing segments after nextQueryTime?
    }
    this.scheduledJobs.schedule(JobId.forResource(id), SubInstant.Resources.at(nextQueryTime));
  }

  public boolean isTaskStale(TaskId taskId, SubInstantDuration timeOffset, long causalEventIndex) {
    final SubInstantDuration staleTime = this.staleTasks.get(taskId);
    if (staleTime == null) {
      return true;  // This is only asked of scheduled tasks, so if there is no stale time,
      // then the task must be new or modified by the user, so it should always be considered stale.
      // NOTE: In the case of a modified task, is it possible to predict that it will have no effect?
      // NOTE: No, even if only the start time changed, effects could depend on the start time.  A new interface would
      // NOTE: be needed to convey how to determine staleness.
    }
    if (staleTime.shorterThan(timeOffset)) return true;
//    if (staleTime.isEqualTo(timeOffset)) {
//      var staleEventIndex = this.staleCausalEventIndex.get(taskId);
//      if ()
//    }
    return staleTime.noLongerThan(timeOffset);
  }
  public boolean isTaskStale(TaskId taskId, SubInstantDuration timeOffset) {
    final SubInstantDuration staleTime = this.staleTasks.get(taskId);
    if (staleTime == null) {
      return true;  // This is only asked of scheduled tasks, so if there is no stale time,
                    // then the task must be new or modified by the user, so it should always be considered stale.
      // NOTE: In the case of a modified task, is it possible to predict that it will have no effect?
      // NOTE: No, even if only the start time changed, effects could depend on the start time.  A new interface would
      // NOTE: be needed to convey how to determine staleness.
    }
    tasksNeedingTimeAlignment.remove(taskId);
    return staleTime.noLongerThan(timeOffset);
  }

  /** Schedules any conditions or resources dependent on the given topic to be re-checked at the given time. */
  public void invalidateTopic(final Topic<?> topic, final Duration invalidationTime) {
    if (debug) System.out.println("invalidateTopic(" + topic + ", " + invalidationTime + ")");
    if (this.closed) throw new IllegalStateException("Cannot invalidate topic on closed simulation engine");
    final var resources = this.waitingResources.invalidateTopic(topic);
    if (debug && !resources.isEmpty()) {
      if (debug) System.out.println("SimulationEngine.invalidateTopic(): " + topic + " at " + invalidationTime + " and schedule jobs for " + resources.stream().map(r -> r.id()).toList());
    }
    for (final var resource : resources) {
      this.scheduledJobs.schedule(JobId.forResource(resource), SubInstant.Resources.at(invalidationTime));
    }

    final var conditions = this.waitingConditions.invalidateTopic(topic);
    if (trace) System.out.println("invalidateTopic(): conditions waiting on topic: " + conditions);
    for (final var condition : conditions) {
      // If we were going to signal tasks on this condition, well, don't do that.
      // Schedule the condition to be rechecked ASAP.
      this.scheduledJobs.unschedule(JobId.forSignal(condition));
      final var cjid = JobId.forCondition(condition);
      final var t = SubInstant.Conditions.at(invalidationTime);
      if (trace) System.out.println("invalidateTopic(): schedule(ConditionJobId " + cjid + " at time " + t + ")");
      this.scheduledJobs.schedule(cjid, t);
    }
  }

  /** Returns the offset time of the next batch of scheduled jobs. */
  public SubInstantDuration timeOfNextJobs() {
    return this.scheduledJobs.timeOfNextJobs();
  }

  /** Removes and returns the next set of jobs to be performed concurrently. */
  public JobSchedule.Batch<JobId> extractNextJobs(final Duration maximumTime) {
    if (this.closed) throw new IllegalStateException("Cannot extract next jobs on closed simulation engine");
    final var batch = this.scheduledJobs.extractNextJobs(maximumTime);

    // If we're signaling based on a condition, we need to untrack the condition before any tasks run.
    // Otherwise, we could see a race if one of the tasks running at this time invalidates state
    // that the condition depends on, in which case we might accidentally schedule an update for a condition
    // that no longer exists.
    for (final var job : batch.jobs()) {
      if (!(job instanceof JobId.SignalJobId s)) continue;

      endConditionHistory(s.id());
      this.conditions.remove(s.id());
      this.waitingConditions.unsubscribeQuery(s.id());
    }

    return batch;
  }

  public record ResourceUpdates(List<ResourceUpdate<?>> updates) {
    public boolean isEmpty() {
      return updates.isEmpty();
    }

    public int size() {
      return updates.size();
    }

    ResourceUpdates() {
      this(new ArrayList<>());
    }

    public <Dynamics> void add(ResourceUpdate<Dynamics> update) {
      this.updates.add(update);
    }

    public record ResourceUpdate<Dynamics>(
        ResourceId resourceId,
        Resource<Dynamics> resource,
        Update<Dynamics> update
    ) {
      public record Update<Dynamics>(Duration startOffset, Dynamics dynamics) {}

      public ResourceUpdate(
          final Querier querier,
          final Duration currentTime,
          final ResourceId resourceId,
          final Resource<Dynamics> resource
      ) {
        this(resourceId, resource, new Update<>(currentTime, resource.getDynamics(querier)));
      }
    }
  }

  public record StepResult(
      List<EventGraph<Event>> commits,
      ResourceUpdates resourceUpdates,
      Optional<Throwable> error
  ) {}

  /** Performs a collection of tasks concurrently, extending the given timeline by their stateful effects. */
  public StepResult performJobs(
      final Collection<JobId> jobs,
      final LiveCells context,
      final SubInstantDuration currentTime,
      final Duration maximumTime,
      final Topic<Topic<?>> queryTopic
  ) throws SpanException {
    if (this.closed) throw new IllegalStateException("Cannot perform jobs on closed simulation engine");
    var tip = EventGraph.<Event>empty();
    Mutable<Optional<Throwable>> exception = new MutableObject<>(Optional.empty());
    final var resourceUpdates = new ResourceUpdates();
    for (final var job$ : jobs) {
      tip = EventGraph.concurrently(tip, TaskFrame.run(job$, context, (job, frame) -> {
        try {
          this.performJob(job, frame, currentTime, maximumTime, resourceUpdates, queryTopic);
        } catch (Throwable ex) {
          exception.setValue(Optional.of(ex));
        }
      }));

      if (exception.getValue().isPresent()) {
        return new StepResult(List.of(tip), resourceUpdates, exception.getValue());
      }
    }
    return new StepResult(List.of(tip), resourceUpdates, Optional.empty());
  }

  /** Performs a single job. */
  private void performJob(
      final JobId job,
      final TaskFrame<JobId> frame,
      final SubInstantDuration currentTime,
      final Duration maximumTime,
      final ResourceUpdates resourceUpdates,
      final Topic<Topic<?>> queryTopic
  ) throws SpanException {
    switch (job) {
      case JobId.TaskJobId j -> this.stepTask(j.id(), frame, currentTime, queryTopic);
      case JobId.SignalJobId j -> this.stepTask(this.waitingTasks.remove(j.id()), frame, currentTime, queryTopic);
      case JobId.ConditionJobId j -> this.updateCondition(j.id(), frame, currentTime, maximumTime, queryTopic);
      case JobId.ResourceJobId j -> this.updateResource(j.id(), frame, currentTime, resourceUpdates);
      case null -> throw new IllegalArgumentException("Unexpected null value for JobId");
      default -> throw new IllegalArgumentException("Unexpected subtype of %s: %s".formatted(
          JobId.class,
          job.getClass()));
    }
  }

  /** Perform the next step of a modeled task. */
  public void stepTask(final TaskId task, final TaskFrame<JobId> frame, final SubInstantDuration currentTime,
                       final Topic<Topic<?>> queryTopic) throws SpanException {
    if (this.closed) throw new IllegalStateException("Cannot step task on closed simulation engine");
    this.unstartedTasks.remove(task);
    // The handler for the next status of the task is responsible
    //   for putting an updated state back into the task set.
    var state = this.tasks.remove(task);

    stepEffectModel(task, state, frame, currentTime, queryTopic);
  }

  /** Make progress in a task by stepping its associated effect model forward. */
  private <Output> void stepEffectModel(
      final TaskId task,
      final ExecutionState<Output> progress,
      final TaskFrame<JobId> frame,
      final SubInstantDuration currentTime,
      final Topic<Topic<?>> queryTopic
  ) throws SpanException {
    // Step the modeling state forward.
    final var scheduler = new EngineScheduler(currentTime, task, progress.span(), progress.caller(), frame, queryTopic);
    if (trace) System.out.println("Stepping task at " + currentTime + ": TaskId = " + task + " (" + getNameForTask(task) + "), progress.span() = " + progress.span() + ", progress.caller() = " + progress.caller());
    final TaskStatus<Output> status;
    try {
      status = progress.state().step(scheduler);
    } catch (Throwable ex) {
      throw new SpanException(scheduler.span, ex);
    }
    // TODO: Report which topics this activity wrote to at this point in time. This is useful insight for any user.
    // TODO: Report which cells this activity read from at this point in time. This is useful insight for any user.

    // Based on the task's return status, update its execution state and schedule its resumption.
    switch (status) {
      case TaskStatus.Completed<Output> s -> {
        // Propagate completion up the span hierarchy.
        // TERMINATION: The span hierarchy is a finite tree, so eventually we find a parentless span.
        var span = scheduler.span;
        while (true) {
          if (this.spanContributorCount.get(span).decrementAndGet() > 0) break;
          this.spanContributorCount.remove(span);

          this.spans.compute(span, (_id, $) -> $.close(currentTime.duration()));

          final var span$ = this.spans.get(span).parent;
          if (span$.isEmpty()) break;

          span = span$.get();
        }

        // Notify any blocked caller of our completion.
        progress.caller().ifPresent($ -> {
          if (this.blockedTasks.get($).decrementAndGet() == 0) {
            this.blockedTasks.remove($);
            if (trace) System.out.println("stepEffectModel(" + currentTime + ", TaskId = " + task + " : " + getNameForTask(task) + "): scheduledJobs.schedule(blocked caller TaskId = " + $ + " : " + getNameForTask($) + ", " + currentTime.duration() + ")");

            this.scheduledJobs.schedule(JobId.forTask($), SubInstant.Tasks.at(currentTime.duration()));
          }
        });
      }

      case TaskStatus.Delayed<Output> s -> {
        if (s.delay().isNegative()) throw new IllegalArgumentException("Cannot schedule a task in the past");
        this.tasks.put(task, progress.continueWith(s.continuation()));
        if (trace) System.out.println("stepEffectModel(" + currentTime + ", TaskId = " + task + "): scheduledJobs.schedule(delayed TaskId = " + task + " : " + getNameForTask(task) + ", " + currentTime.duration().plus(s.delay()) + ")");
          this.scheduledJobs.schedule(JobId.forTask(task), SubInstant.Tasks.at(currentTime.duration().plus(s.delay())));
      }

      case TaskStatus.CallingTask<Output> s -> {
        final boolean daemonTaskOrSpawn = daemonTasks.contains(task) || getMissionModel().isDaemon(s.child());

        // Reuse the child ids of this task from the old engine if possible
        final TaskId childTask = getNextChildTaskId(task, currentTime);

        if (daemonTaskOrSpawn) {
          daemonTasks.add(task);
        }

        // Prepare a span for the child task.
        final var childSpan = switch (s.childSpan()) {
          case Parent -> scheduler.span;

          case Fresh -> {
            var oldChildSpan = getSpanId(childTask);
            final var freshSpan = oldChildSpan == null ? SpanId.generate() : oldChildSpan;
            SimulationEngine.this.spans.put(
                freshSpan,
                new Span(Optional.of(scheduler.span), currentTime.duration(), Optional.empty()));
            SimulationEngine.this.spanContributorCount.put(freshSpan, new MutableInt(1));
            yield freshSpan;
          }
        };

        // Record staleness if currently not stale
        if (!isTaskStale(task, currentTime)) {
          var staleTime = staleTasks.get(task);
          var afterEvent = staleEvents.get(task);
          staleTasks.put(childTask, staleTime);
          staleEvents.put(childTask, afterEvent); // TODO -- more efficient to have one map with a pair of (time, afterEvent)
        }

        SimulationEngine.this.spanContributorCount.get(scheduler.span).increment();
        SimulationEngine.this.tasks.put(
            childTask,
            new ExecutionState<>(
                childSpan,
                Optional.of(task),
                s.child().create(this.executor),
                currentTime.duration()));
        frame.signal(JobId.forTask(childTask));

        // Arrange for the parent task to resume.... later.
        SimulationEngine.this.blockedTasks.put(task, new MutableInt(1));
        wireTasksAndSpans(childTask, task, childSpan, scheduler.span, true);  // considering not wiring span parent to span child
        if (trace) System.out.println("stepEffectModel(" + currentTime + ", TaskId = " + task + " : " + getNameForTask(task) + "): calling TaskId = " + childTask + " : " + getNameForTask(childTask));
        this.tasks.put(task, progress.continueWith(s.continuation()));
      }

      case TaskStatus.AwaitingCondition<Output> s -> {
        final var condition = ConditionId.generate(task);
        this.conditions.put(condition, s.condition());
        final var jid = JobId.forCondition(condition);
        final var t = SubInstant.Conditions.at(currentTime.duration());
        if (trace) System.out.println("stepEffectModel(TaskId=" + task + " : " + getNameForTask(task) + "): scheduling Condition job with conditionId = " + condition + ", AwaitingCondition s = " + s + ", condition = " + s.condition() + ", ConditionJobId = " + jid + ", at time " + t);
        this.scheduledJobs.schedule(jid, t);

        this.tasks.put(task, progress.continueWith(s.continuation()));
        this.waitingTasks.put(condition, task);
      }
    }
  }

  private void wireTasksAndSpans(TaskId childTaskId, TaskId parentTaskId, SpanId childSpanId, SpanId parentSpanId,
                                 boolean isCalling) {
    if (childTaskId != null && parentTaskId != null) {
      taskParent.put(childTaskId, parentTaskId);
      if (isCalling) calledTasks.add(childTaskId);
      taskChildren.computeIfAbsent(parentTaskId, x -> new ArrayList<>()).add(childTaskId);
    }
    if (childTaskId != null && childSpanId != null) {
      putSpanId(childTaskId, childSpanId);
    }
    if (parentTaskId != null && parentSpanId != null) { // This one is probably already linked
      putSpanId(parentTaskId, parentSpanId);
    }
    if (childSpanId != null && parentSpanId != null && childSpanId != parentSpanId) {
      activityParents.put(childSpanId, parentSpanId);
      activityChildren.computeIfAbsent(parentSpanId, $ -> new LinkedHashSet<>()).add(childSpanId);
    }
  }

  /** Determine when a condition is next true, and schedule a signal to be raised at that time. */
  public void updateCondition(
      final ConditionId condition,
      final TaskFrame<JobId> frame,
      final SubInstantDuration currentTime,
      final Duration horizonTime,
      final Topic<Topic<?>> queryTopic
  ) {
    if (this.closed) throw new IllegalStateException("Cannot update condition on closed simulation engine");
    if (trace) System.out.println("updateCondition(ConditionId=" + condition + ", queryTopic=" + queryTopic + ")");
    final var querier = new EngineQuerier(currentTime, frame, queryTopic, condition.sourceTask(), null);
    final var prediction = this.conditions
        .get(condition)
        .nextSatisfied(querier, Duration.MAX_VALUE) //horizonTime.minus(currentTime)
        .map(currentTime.duration()::plus);

    if (trace) System.out.println("updateCondition(): prediction = " + prediction);

    if (trace) System.out.println("updateCondition(): waitingConditions.subscribeQuery(conditionId=" + condition + ", querier.referencedTopics=" + querier.referencedTopics + ")");
    this.waitingConditions.subscribeQuery(condition, querier.referencedTopics);
    addConditionHistory(condition, querier.referencedTopics);

    final Optional<Duration> expiry = querier.expiry.map(d -> currentTime.duration().plus((Duration)d));
    if (trace) System.out.println("updateCondition(): expiry = " + expiry);
    if (prediction.isPresent() && (expiry.isEmpty() || prediction.get().shorterThan(expiry.get()))) {
      var sjid = JobId.forSignal(condition);
      var t = SubInstant.Tasks.at(prediction.get());
      if (trace) System.out.println("updateCondition(): schedule(SignalJobId " + sjid + " at time " + t + ")");
      this.scheduledJobs.schedule(sjid, t);
    } else {
      // Try checking again later -- where "later" is in some non-zero amount of time!
      final var nextCheckTime = Duration.max(expiry.orElse(Duration.MAX_VALUE), currentTime.duration().plus(Duration.EPSILON));
      var cjid = JobId.forCondition(condition);
      var t = SubInstant.Conditions.at(nextCheckTime);
      if (trace) System.out.println("updateCondition(): schedule(ConditionJobId " + cjid + " at time " + t + ")");
      this.scheduledJobs.schedule(cjid, t);
    }
  }

  /**
   * During incremental simulation, a task may be re-run, in which case it can have a different history of condition
   * reads.  Thus, the previous read data must be hidden/removed by the current engine.
   * @return
   */
  private RangeMapMap<SubInstantDuration, ConditionId, Set<Topic<?>>> getCombinedConditionHistory() {
    if (_combinedConditionHistory != null) return _combinedConditionHistory;
    if (oldEngine == null) {
      return conditionHistory;
    }
    // Clean history by getting the oldEngine's combined history and remove history for conditions whose tasks were
    // removed (found in removedCellReadHistory)
    RangeMapMap<SubInstantDuration, ConditionId, Set<Topic<?>>> cleanedConditionHistory = null;
      RangeMapMap<SubInstantDuration, ConditionId, Set<Topic<?>>> oldHistory = oldEngine.getCombinedConditionHistory();
      Set<TaskId> removedTasks = new HashSet<>();
      removedCellReadHistory.values().forEach(removedTasks::addAll);
      cleanedConditionHistory = new RangeMapMap<>(oldHistory);
      for (var taskId : removedTasks) {
        var conditions = conditionsForTask.get(taskId);
        for (ConditionId c : conditions) {
          cleanedConditionHistory.remove(_combinedConditionHistory.span(), c);
        }
      }
    //}
    var result = cleanedConditionHistory;
    if (closed) {
      result.merge(conditionHistory);
      _combinedConditionHistory = result;
    }
    return result;
  }
  private RangeMapMap<SubInstantDuration, ConditionId, Set<Topic<?>>> _combinedConditionHistory = null;

  // TODO -- consider doing this like getCombinedCellReadHistory() and cache each topic separately
  private HashMap<Topic<?>, RangeSetMap<SubInstantDuration, ConditionId>> getCombinedConditionHistoryByTopic() {
    if (_combinedConditionHistoryByTopic != null) return _combinedConditionHistoryByTopic;
    if (oldEngine == null) {
      return conditionHistoryByTopic;
    }
    // Clean history by getting the oldEngine's combined history and remove history for conditions whose tasks were
    // removed (found in removedCellReadHistory)
    var tempCleanedConditionHistoryByTopic = new HashMap<Topic<?>, RangeSetMap<SubInstantDuration, ConditionId>>();
    final HashMap<Topic<?>, RangeSetMap<SubInstantDuration, ConditionId>> oldHistory =
        oldEngine.getCombinedConditionHistoryByTopic();
    Set<TaskId> removedTasks = new HashSet<>();
    removedCellReadHistory.values().forEach(removedTasks::addAll);
    for (Topic<?> t : oldHistory.keySet()) {
      tempCleanedConditionHistoryByTopic.put(t, new RangeSetMap<>(oldHistory.get(t)));
      var cleanedConditionHistoryForTopic = tempCleanedConditionHistoryByTopic.get(t);
      for (var taskId : removedTasks) {
        var conditions = oldEngine.getConditionIdsForTaskId(taskId);
        if (conditions != null) {
          for (ConditionId c : conditions) {
            cleanedConditionHistoryForTopic.remove(cleanedConditionHistoryForTopic.span(), c);
          }
        }
      }
    }
    var result = tempCleanedConditionHistoryByTopic;
    Set<Topic<?>> topics = new HashSet<>(tempCleanedConditionHistoryByTopic.keySet());
    topics.addAll(conditionHistoryByTopic.keySet());
    if (closed) {
      for (Topic<?> t : topics) {
        result.computeIfAbsent(t, $ -> new RangeSetMap<>()).merge(conditionHistoryByTopic.get(t));
      }
      _combinedConditionHistoryByTopic = result;
    }
    return result;
  }
  private HashMap<Topic<?>, RangeSetMap<SubInstantDuration, ConditionId>> _combinedConditionHistoryByTopic = null;

  /**
   * Condition history records when a condition/task is waiting on different topics (i.e. cells).  Do not assume
   * that the topics referenced by the condition will be the same every time the condition is evaluated.
   *
   * @param conditionId
   * @param referencedTopics
   */
  private void addConditionHistory(ConditionId conditionId, Set<Topic<?>> referencedTopics) {
    var task = waitingTasks.get(conditionId);
    if (task == null) {
      throw new RuntimeException("No task waiting for conditionId " + conditionId);
    }
    conditionsForTask.computeIfAbsent(task, $ -> new HashSet<>()).add(conditionId);
    conditionHistory.add(Range.closed(curTime(), SubInstantDuration.MAX_VALUE), conditionId, referencedTopics);
    referencedTopics.forEach(tt -> conditionHistoryByTopic
        .computeIfAbsent(tt, $ -> new RangeSetMap<>())
        .add(Range.closed(curTime(), SubInstantDuration.MAX_VALUE), conditionId));
  }

  private void endConditionHistory(ConditionId conditionId) {
    // Find topics in conditionHistory for conditionId, remove the conditionId from conditionHistoryByTopic per topic
    // from now forward, and then also remove conditionId from conditionHistory from now forward.
    final Map<ConditionId, Set<Topic<?>>> waitingConditionHistory = conditionHistory.get(SubInstantDuration.MAX_VALUE);
    if (waitingConditionHistory == null) {
      if (debug) System.out.println("WARNING!  No history for conditionId " + conditionId + " extending to SubInstantDuration.MAX_VALUE");
    } else {
      var topics = waitingConditionHistory.get(conditionId);
      if (topics == null) {
        if (debug) System.out.println("WARNING!  No topics in history for conditionId " + conditionId + " extending to SubInstantDuration.MAX_VALUE");
      } else {
        for (var topic : topics) {
          final RangeSetMap<SubInstantDuration, ConditionId> topicHistory = conditionHistoryByTopic.get(topic);
          if (topicHistory == null) {
            if (debug) System.out.println("WARNING!  No condition history for topic " + topic + " as expected for conditionId + " + conditionId + " extending to SubInstantDuration.MAX_VALUE");
          } else {
            topicHistory.remove(Range.closed(curTime(), SubInstantDuration.MAX_VALUE), conditionId);
          }
        }
      }
    }
    conditionHistory.remove(Range.closed(curTime(), SubInstantDuration.MAX_VALUE), conditionId);
  }

  // TODO?
  private void removeConditionHistory(TaskId task) {
    var conditions = conditionsForTask.get(task);
    if (conditions == null && oldEngine != null) {
      oldEngine.removeConditionHistory(task);
    }
    if (conditions == null) return;
    for (ConditionId cid :  conditions) {

    }
  }

  /** Get the current behavior of a given resource and accumulate it into the resource's profile. */
  public void updateResource(
      final ResourceId resourceId,
      final TaskFrame<JobId> frame,
      final SubInstantDuration currentTime,
      final ResourceUpdates resourceUpdates) {
    if (this.closed) throw new IllegalStateException("Cannot update resource on closed simulation engine");
    if (debug) System.out.println("SimulationEngine.updateResource(" + resourceId + ", " + currentTime + ")");
    // We want to avoid saving profile segments if they aren't changing.  We also don't want to compute the resource if
    // none of the cells on which it depends are stale.
    boolean skipResourceEvaluation = false;
    Set<Topic<?>> referencedTopics = null;
    if (oldEngine != null) {
      var ebt = oldEngine.timeline.getCombinedCommitsByTime();
      var latestTime = ebt.floorKey(currentTime.duration());
      // Don't skip at the start of simulation.  We need the initial topics to know when stale.
      // TODO: REVIEW: Actually, we could derive the initial topics from the events in the old timeline.  Should we?
      if (currentTime.isEqualTo(Duration.ZERO)) {  // Duration.ZERO is assumed to be simulationStartTime
        skipResourceEvaluation = false;
      }
      // If no events since plan start, then can't be stale, so nothing to do.
      else if (latestTime == null) skipResourceEvaluation = true;
      else {
        // Note that there may or may not be events at this currentTime.
        // So, how can we know the resource is not stale?
        // - No cells are stale
        // - If the past resource value was not based on stale information and matched the previous simulation
        //   (henceforth, the resource is not stale), and if the resource's referencedTopics in waitingResources
        //   are not stale, hen the evaluation may be skipped.
        // - So, should we choose a different expiry? Probably not--just make this evaluation fast.
        //   And, with staleness, we can determine that we need not invalidate a topic in some cases.

        // Check if any of the resource's referenced topics are stale
        referencedTopics = this.referencedTopics.get(resourceId); //this.waitingResources.getTopics(resource);
        if (debug) System.out.println("topics for resource " + resourceId.id() + " at " + currentTime + ": " + referencedTopics);
        var resourceIsStale = referencedTopics.stream().anyMatch(t -> timeline.isTopicStale(t, currentTime));
        if (debug) System.out.println("topic is stale for " + resourceId.id() + " at " + currentTime + ": " +
                                      referencedTopics.stream().map(t -> "" + t + "=" +
                                                                         timeline.isTopicStale(t, currentTime)).toList());
        if (debug) System.out.println("timeline.staleTopics: " + timeline.staleTopics);
        if (!resourceIsStale) {
          if (debug) System.out.println("skipping evaluation of resource " + resourceId.id() + " at " + currentTime);
          skipResourceEvaluation = true;
        } else {
          // Check for the case where the effect is removed.  If the timeline has events at this time, but they do not
          // include any of this resource's referenced topics, then the events were removed, and we need not generate
          // a profile segment for the resource (setting skipResourceEvaluation = true).
          skipResourceEvaluation = false;
          final List<TemporalEventSource.TimePoint.Commit> commits = timeline.commitsByTime.get(currentTime.duration());
          var topicsRemoved = timeline.topicsOfRemovedEvents.get(currentTime.duration());
          skipResourceEvaluation =
              topicsRemoved != null &&
              referencedTopics.stream().allMatch(t -> !timeline.isTopicStale(t, currentTime) ||
                                                      (commits.stream().noneMatch(c -> c.topics().contains(t)) &&  // assumes replaced EventGraphs in current timeline
                                                       topicsRemoved.contains(t)));
          if (skipResourceEvaluation) {
            this.timeline.removedResourceSegments.computeIfAbsent(currentTime.duration(), $ -> new HashSet<>()).add(resourceId.id());
          }
          if (debug) System.out.println("check for removed effects for resource " + resourceId.id() + " at " + currentTime.duration() + "; skipResourceEvaluation = " + skipResourceEvaluation);
        }
      }
    }

    final var querier = new EngineQuerier(currentTime, frame);
    if (!skipResourceEvaluation) {
      resourceUpdates.add(new ResourceUpdates.ResourceUpdate<>(
          querier,
          currentTime.duration(),
          resourceId,
          this.resources.get(resourceId)));
      if (debug) System.out.println("resource " + resourceId.id() + " updates");
      referencedTopics = querier.referencedTopics;
    }

    // Even if we aren't going to update the resource profile, we need to at least re-subscribe to the old cell topics
    if (referencedTopics != null && !referencedTopics.isEmpty()) {
      this.waitingResources.subscribeQuery(resourceId, referencedTopics);
      this.referencedTopics.put(resourceId, referencedTopics);
      if (debug) System.out.println("querier, " + querier + " subscribing " + resourceId.id() + " to referenced topics: " + querier.referencedTopics);
    }

    final Optional<Duration> expiry = querier.expiry.map(d -> currentTime.duration().plus((Duration)d));
    if (expiry.isPresent()) {
      this.scheduledJobs.schedule(JobId.forResource(resourceId), SubInstant.Resources.at(expiry.get()));
    }
  }

  /** Resets all tasks (freeing any held resources). The engine should not be used after being closed. */
  @Override
  public void close() {
    freeze();

    for (final var task : this.tasks.values()) {
      task.state().release();
    }

    this.executor.shutdownNow();
    this.closed = true;
  }

  public void unscheduleAfter(final Duration duration) {
    if (this.closed) throw new IllegalStateException("Cannot unschedule jobs on closed simulation engine");
    for (final var taskId : new ArrayList<>(this.tasks.keySet())) {
      if (this.unstartedTasks.containsKey(taskId) && this.unstartedTasks.get(taskId).longerThan(duration)) {
        this.tasks.remove(taskId);
        this.scheduledJobs.unschedule(JobId.forTask(taskId));
      }
    }
  }

  public MissionModel<?> getMissionModel() {
    return this.missionModel;
  }

  public SubInstantDuration curTime() {
    if (timeline == null) {
      return SubInstantDuration.ZERO;
    }
    return timeline.curTime();
  }

  public void setCurTime(Duration time) {
    if (!time.isEqualTo(curTime().duration())) {
      setCurTime(new SubInstantDuration(time, 0));
    }
  }

  public void setCurTime(SubInstantDuration time) {
    this.timeline.setCurTime(time);
    if (this.oldEngine != null) {
      this.oldEngine.setCurTime(time);
    }
  }

  public Map<String, Map<ActivityDirectiveId, ActivityDirective>> diffDirectives(Map<ActivityDirectiveId, ActivityDirective> newDirectives) {
    Map<String, Map<ActivityDirectiveId, ActivityDirective>> diff = new LinkedHashMap<>();
    final var oldDirectives = scheduledDirectives;
    diff.put("added", newDirectives.entrySet().stream().filter(e -> !oldDirectives.containsKey(e.getKey())).collect(
        Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
    diff.put("removed", oldDirectives.entrySet().stream().filter(e -> !newDirectives.containsKey(e.getKey())).collect(
        Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
    diff.put("modified", newDirectives.entrySet().stream().filter(e -> oldDirectives.containsKey(e.getKey()) && !e.getValue().equals(oldDirectives.get(e.getKey()))).collect(
        Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
    return diff;
  }

  public boolean hasJobsScheduledThrough(final Duration givenTime) {
    return this.scheduledJobs
        .min()
        .map($ -> $.project().noLongerThan(givenTime))
        .orElse(false);
  }

  public record SpanInfo(
      Map<SpanId, ActivityDirectiveId> spanToPlannedDirective,
      Map<ActivityDirectiveId, SpanId> directiveIdToSpanId,
      Map<SpanId, SerializedActivity> input,
      Map<SpanId, SerializedValue> output,
      SimulationEngine engine
  ) {
    public SpanInfo(SimulationEngine engine) {
      this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), engine);
    }
    public SpanInfo(SpanInfo spanInfo, SimulationEngine engine) {
      this(new HashMap<>(spanInfo.spanToPlannedDirective), new HashMap<>(spanInfo.directiveIdToSpanId),
           new HashMap<>(spanInfo.input), new HashMap<>(spanInfo.output), engine);
    }

    public boolean isActivity(final SpanId id) {
      return this.input.containsKey(id);
    }

    public SpanId getSpanIdForDirectiveId(ActivityDirectiveId id) {
      return directiveIdToSpanId.get(id);
    }

    public void removeSpan(final SpanId id) {
      var directiveId = spanToPlannedDirective.remove(id.id());
      if (directiveId != null) directiveIdToSpanId.remove(directiveId);
      input.remove(id.id());
      output.remove(id.id());
    }

    public boolean isDirective(SpanId id) {
      return this.spanToPlannedDirective.containsKey(id);
    }

    public ActivityDirectiveId getDirective(SpanId id) {
      return this.spanToPlannedDirective.get(id);
    }

    public record Trait(Map<Topic<?>, SerializableTopic<?>> topics, Topic<ActivityDirectiveId> activityTopic)
        implements EffectTrait<Consumer<SpanInfo>>
    {
      @Override
      public Consumer<SpanInfo> empty() {
        return spanInfo -> {};
      }

      @Override
      public Consumer<SpanInfo> sequentially(final Consumer<SpanInfo> prefix, final Consumer<SpanInfo> suffix) {
        return spanInfo -> {
          prefix.accept(spanInfo);
          suffix.accept(spanInfo);
        };
      }

      @Override
      public Consumer<SpanInfo> concurrently(final Consumer<SpanInfo> left, final Consumer<SpanInfo> right) {
        // SAFETY: `left` and `right` should commute. HOWEVER, if a span happens to directly contain two activities
        //   -- that is, two activities both contribute events under the same span's provenance -- then this
        //   does not actually commute.
        //   Arguably, this is a model-specific analysis anyway, since we're looking for specific events
        //   and inferring model structure from them, and at this time we're only working with models
        //   for which every activity has a span to itself.
        return spanInfo -> {
          left.accept(spanInfo);
          right.accept(spanInfo);
        };
      }

      public Consumer<SpanInfo> atom(final Event ev) {
        return spanInfo -> {
          // Identify activities.
          ev.extract(this.activityTopic)
            .ifPresent(directiveId -> {
              spanInfo.spanToPlannedDirective.put(spanInfo.engine.getSpanId(ev.provenance()), directiveId);
              spanInfo.directiveIdToSpanId.put(directiveId, spanInfo.engine.getSpanId(ev.provenance()));
            });

          for (final var topic : this.topics.values()) {
            // Identify activity inputs.
            extractInput(topic, ev, spanInfo);

            // Identify activity outputs.
            extractOutput(topic, ev, spanInfo);
          }
        };
      }

      private static <T>
      void extractInput(final SerializableTopic<T> topic, final Event ev, final SpanInfo spanInfo) {
        if (!topic.name().startsWith("ActivityType.Input.")) return;

        ev.extract(topic.topic()).ifPresent(input -> {
          final var activityType = topic.name().substring("ActivityType.Input.".length());

          spanInfo.input.put(
              spanInfo.engine.getSpanId(ev.provenance()),
              new SerializedActivity(activityType, topic.outputType().serialize(input).asMap().orElseThrow()));
        });
      }

      private static <T>
      void extractOutput(final SerializableTopic<T> topic, final Event ev, final SpanInfo spanInfo) {
        if (!topic.name().startsWith("ActivityType.Output.")) return;

        ev.extract(topic.topic()).ifPresent(output -> {
          spanInfo.output.put(
              spanInfo.engine.getSpanId(ev.provenance()),
              topic.outputType().serialize(output));
        });
      }
    }
  }

  private SpanInfo.Trait spanInfoTrait = null;
  public void updateTaskInfo(EventGraph<Event> g) {
    if (true) return;
    if (spanInfoTrait == null) spanInfoTrait = new SpanInfo.Trait(getMissionModel().getTopics(), defaultActivityTopic);
    g.evaluate(spanInfoTrait, spanInfoTrait::atom).accept(spanInfo);
  }

  public Map<String, Resource<?>> generateResourceProfiles(final Duration simulationDuration) {
    return this.resources
               .entrySet()
               .stream()
               .collect(Collectors.toMap($ -> $.getKey().id(),
                                         Map.Entry::getValue));
  }

  /**
   * Get an Activity Directive Id from a SpanId, if the span is a descendent of a directive.
   */
  public DirectiveDetail getDirectiveDetailsFromSpan(
      final Topic<ActivityDirectiveId> activityTopic,
      final Map<Topic<?>, SerializableTopic<?>> serializableTopics,
      final SpanId spanId
  ) {
    // Collect per-span information from the event graph.
    final var spanInfo = computeSpanInfo(activityTopic, serializableTopics, this.timeline);

    // Identify the nearest ancestor directive by walking up the parent
    // span tree. Save the activity trace along the way
    Optional<SpanId> directiveSpanId = Optional.of(spanId);
    final var activityStackTrace = new LinkedList<SerializedActivity>();
    while (directiveSpanId.isPresent() && !spanInfo.isDirective(directiveSpanId.get())) {
      activityStackTrace.add(spanInfo.input().get(directiveSpanId.get()));
      directiveSpanId = this.getSpan(directiveSpanId.get()).parent();
    }

    // Add final top level parent activity to the stack trace if present
    if (directiveSpanId.isPresent()) {
      activityStackTrace.add(spanInfo.input().get(directiveSpanId.get()));
    }

    var directiveId = directiveSpanId.map(spanInfo::getDirective);
    if (directiveId.isEmpty() && oldEngine != null) {
      System.err.println("WARNING!  Looking at child engine for directive id!");
      var details = oldEngine.getDirectiveDetailsFromSpan(activityTopic, serializableTopics, spanId);
      directiveId = details.directiveId();
      if (directiveId.isPresent()) {
        System.err.println("WARNING!  Found directive id in child engine!");
      }
      return details;
    }
    return new DirectiveDetail(
        directiveSpanId.map(spanInfo::getDirective),
        // remove null activities from the stack trace and reverse order
        activityStackTrace.stream().filter(a -> a != null).collect(Collectors.toList()).reversed());
  }

  public record SimulationActivityExtract(
      Instant startTime,
      Duration duration,
      Map<ActivityInstanceId, ActivityInstance> simulatedActivities,
      Map<ActivityInstanceId, UnfinishedActivity> unfinishedActivities
  ) {}

  private SpanInfo computeSpanInfo(
      final Topic<ActivityDirectiveId> activityTopic,
      final Map<Topic<?>, SerializableTopic<?>> serializableTopics,
      final TemporalEventSource timeline
  ) {
    if (true) {
      return this.spanInfo;
    }
    // Collect per-span information from the event graph.
    final var spanInfo = new SpanInfo(this);

    for (final var point : timeline) {
      if (!(point instanceof TemporalEventSource.TimePoint.Commit p)) continue;

      final var trait = new SpanInfo.Trait(serializableTopics, activityTopic);
      p.events().evaluate(trait, trait::atom).accept(spanInfo);
    }
    return spanInfo;
  }

  public SimulationActivityExtract computeActivitySimulationResults(
      final Instant startTime,
      final Topic<ActivityDirectiveId> activityTopic,
      final Map<Topic<?>, SerializableTopic<?>> serializableTopics
  ) {
    return computeActivitySimulationResults(
        startTime,
        true
    );
  }

  private HashMap<SpanId, ActivityDirectiveId> spanToActivityDirectiveId(
      final SpanInfo spanInfo
  )
  {
    final var activityDirectiveIds = new HashMap<SpanId, ActivityDirectiveId>();
    this.spans.forEach((span, state) -> {
      if (!spanInfo.isActivity(span)) return;
      if (spanInfo.isDirective(span)) activityDirectiveIds.put(span, spanInfo.getDirective(span));
    });
    return activityDirectiveIds;
  }

  private HashMap<SpanId, ActivityInstanceId> spanToSimulatedActivities(
      final SpanInfo spanInfo
  ) {
    final var activityDirectiveIds = spanToActivityDirectiveId(spanInfo);
    final var spanToActivityInstanceId = new HashMap<SpanId, ActivityInstanceId>(activityDirectiveIds.size());
    final var usedActivityInstanceIds = new HashSet<>();
    for (final var entry : activityDirectiveIds.entrySet()) {
      var simActId = new ActivityInstanceId(entry.getValue().id());
      spanToActivityInstanceId.put(entry.getKey(), simActId);
      directiveToSimulatedActivityId.put(entry.getValue(), simActId);
      usedActivityInstanceIds.add(entry.getValue().id());
    }
    // Create ActivtyInstanceIds for spans that don't have them.
    long counter = 1L;
    for (final var span : this.spans.keySet()) {
      if (!spanInfo.isActivity(span)) continue;
      if (spanToActivityInstanceId.containsKey(span)) continue;

      while (usedActivityInstanceIds.contains(counter)) counter++;
      spanToActivityInstanceId.put(span, new ActivityInstanceId(counter++));
    }
    return spanToActivityInstanceId;
  }

  /**
   * Computes only activity-related results when resources are not needed
   */
  public SimulationActivityExtract computeCombinedActivitySimulationResults(
      final Instant startTime
  ) {
    return computeActivitySimulationResults(startTime, true);
  }
  public SimulationActivityExtract computeActivitySimulationResults(
      final Instant startTime,
      final boolean combined
  ) {
    // Identify the nearest ancestor *activity* (excluding intermediate anonymous tasks).
    activityDirectiveIds = spanToActivityDirectiveId(spanInfo); // TODO -- REVIEW -- this is called again later in this function by spanToSimulatedActivities(); can we remove this?
    this.spans.forEach((span, state) -> {
      if (!spanInfo.isActivity(span)) return;

      var parent = state.parent();
      while (parent.isPresent() && !spanInfo.isActivity(parent.get())) {
        parent = this.spans.get(parent.get()).parent();
      }
      parent.ifPresent(spanId -> activityParents.put(span, spanId));
    });

    activityParents.forEach((activity, parent) -> {
      activityChildren.computeIfAbsent(parent, $ -> new LinkedHashSet<>()).add(activity);
    });

    // Give every task corresponding to a child activity an ID that doesn't conflict with any root activity.
    final var spanToActivityInstanceId = spanToSimulatedActivities(spanInfo);

    final var simulatedActivities = new LinkedHashMap<ActivityInstanceId, ActivityInstance>();
    final var unfinishedActivities = new LinkedHashMap<ActivityInstanceId, UnfinishedActivity>();
    final var emptySet = new LinkedHashSet<SpanId>(0);
    this.spans.forEach((span, state) -> {
      if (!spanInfo.isActivity(span)) return;

      final var activityId = spanToActivityInstanceId.get(span);
      final var directiveId = activityDirectiveIds.get(span);

      if (state.endOffset().isPresent()) {
        final var inputAttributes = spanInfo.input().get(span);
        final var outputAttributes = spanInfo.output().get(span);


        simulatedActivities.put(activityId, new ActivityInstance(
            inputAttributes.getTypeName(),
            inputAttributes.getArguments(),
            startTime.plus(state.startOffset().in(Duration.MICROSECONDS), ChronoUnit.MICROS),
            state.endOffset().get().minus(state.startOffset()),
            spanToActivityInstanceId.get(activityParents.get(span)),
            activityChildren
                .getOrDefault(span, emptySet)
                .stream().filter(spanToActivityInstanceId::containsKey)
                .map(spanToActivityInstanceId::get)
                .toList(),
            Optional.ofNullable(directiveId),
            outputAttributes
        ));
      } else {
        final var inputAttributes = spanInfo.input().get(span);
        unfinishedActivities.put(activityId, new UnfinishedActivity(
            inputAttributes.getTypeName(),
            inputAttributes.getArguments(),
            startTime.plus(state.startOffset().in(Duration.MICROSECONDS), ChronoUnit.MICROS),
            spanToActivityInstanceId.get(activityParents.get(span)),
            activityChildren
                .getOrDefault(span, emptySet)
                .stream()
                .map(spanToActivityInstanceId::get)
                .toList(),
            Optional.ofNullable(directiveId)
        ));
      }
    });
    var extract = new SimulationActivityExtract(startTime, getElapsedTime(), simulatedActivities, unfinishedActivities);
    if (oldEngine != null && combined) {
      var oldExtract = oldEngine.computeActivitySimulationResults(startTime, true);
      final var newSimulatedActivities = new LinkedHashMap<>(oldExtract.simulatedActivities);
      removedActivities.forEach(act -> newSimulatedActivities.remove(act));
      newSimulatedActivities.putAll(simulatedActivities);
      final var newUnfinishedActivities = new LinkedHashMap<>(oldExtract.unfinishedActivities);
      removedActivities.forEach(act -> newUnfinishedActivities.remove(act));
      newUnfinishedActivities.putAll(unfinishedActivities);
      var combinedExtract = new SimulationActivityExtract(startTime, Duration.max(getElapsedTime(), oldExtract.duration),
                                                          newSimulatedActivities, newUnfinishedActivities);
      return combinedExtract;
    }
    return extract;
  }

  private TreeMap<Duration, List<EventGraph<EventRecord>>> createSerializedTimeline(
      final TemporalEventSource combinedTimeline,
      final Map<Topic<?>, SerializableTopic<?>> serializableTopics,
      final HashMap<SpanId, ActivityInstanceId> spanToActivities,
      final HashMap<SerializableTopic<?>, Integer> serializableTopicToId) {
    final var serializedTimeline = new TreeMap<Duration, List<EventGraph<EventRecord>>>();
    var time = Duration.ZERO;
    for (var point : combinedTimeline) {
      if (point instanceof TemporalEventSource.TimePoint.Delta delta) {
        time = time.plus(delta.delta());
      } else if (point instanceof TemporalEventSource.TimePoint.Commit commit) {
        final var serializedEventGraph = commit.events().substitute(
            event -> {
              // TODO can we do this more efficiently?
              EventGraph<EventRecord> output = EventGraph.empty();
              var spanId = event.provenance() == null ? null : getSpanId(event.provenance());
              if (spanId == null) return output;
              for (final var serializableTopic : serializableTopics.values()) {
                Optional<SerializedValue> serializedEvent = trySerializeEvent(event, serializableTopic);
                if (serializedEvent.isPresent()) {
                  // If the event's `provenance` has no simulated activity id, search its ancestors to find the nearest
                  // simulated activity id, if one exists
                  if (!spanToActivities.containsKey(spanId)) {
                    var spanId2 = spanId;
                    while (true) {
                      if (spanToActivities.containsKey(spanId2)) {
                        spanToActivities.put(spanId, spanToActivities.get(spanId2));
                        break;
                      }
                      spanId2 = this.getSpan(spanId2).parent().orElse(null);
                      if (spanId2 == null) {
                        break;
                      }
                    }
                  }
                  var activitySpanID = Optional.ofNullable(spanToActivities.get(spanId)).map(ActivityInstanceId::id);
                  output = EventGraph.concurrently(
                      output,
                      EventGraph.atom(
                          new EventRecord(serializableTopicToId.get(serializableTopic),
                                          activitySpanID,
                                          serializedEvent.get())));
                }
              }
              return output;
            }
        ).evaluate(new EventGraph.IdentityTrait<>(), EventGraph::atom);
        if (!(serializedEventGraph instanceof EventGraph.Empty)) {
          serializedTimeline
              .computeIfAbsent(time, x -> new ArrayList<>())
              .add(serializedEventGraph);
        }
      }
    }
    return serializedTimeline;
  }

  /** Compute a set of results from the current state of simulation. */
  // TODO: Move result extraction out of the SimulationEngine.
  //   The Engine should only need to stream events of interest to a downstream consumer.
  //   The Engine cannot be cognizant of all downstream needs.
  // TODO: Whatever mechanism replaces `computeResults` also ought to replace `isTaskComplete`.
  // TODO: Produce results for all tasks, not just those that have completed.
  //   Planners need to be aware of failed or unfinished tasks.
  public SimulationResultsInterface computeResults(
      final Instant startTime,
      final Duration elapsedTime,
      final Topic<ActivityDirectiveId> activityTopic,
      final Map<Topic<?>, SerializableTopic<?>> serializableTopics,
      final SimulationResourceManager resourceManager
  ) {
    if (debug) System.out.println("computeResults(startTime=" + startTime + ", elapsedTime=" + elapsedTime + "...) at time " + curTime());
    final var combinedTimeline = this.combineTimeline();
    // Collect per-task information from the event graph.
    //final var spanInfo = computeSpanInfo(activityTopic, serializableTopics, combinedTimeline);

    // Extract profiles for every resource.
    final var resourceProfiles = resourceManager.computeProfiles(elapsedTime);
    final var realProfiles = resourceProfiles.realProfiles();
    final var discreteProfiles = resourceProfiles.discreteProfiles();

    final var activityResults = computeActivitySimulationResults(startTime, false);
    simulatedActivities = activityResults.simulatedActivities;
    unfinishedActivities = activityResults.unfinishedActivities;

    final var serializableTopicToId = new HashMap<SerializableTopic<?>, Integer>();
    for (final var serializableTopic : serializableTopics.values()) {
      serializableTopicToId.put(serializableTopic, this.topics.size());
      this.topics.add(Triple.of(this.topics.size(), serializableTopic.name(), serializableTopic.outputType().getSchema()));
    }

    final var serializedTimeline = createSerializedTimeline(
        combinedTimeline,
        serializableTopics,
        // TODO -- This is redundant to spanToSimulatedActivities() in computeActivitySimulationResults()
        spanToSimulatedActivities(spanInfo),
        serializableTopicToId
    );

    this.simulationResults = new SimulationResults(realProfiles,
                                 discreteProfiles,
                                 this.simulatedActivities,
                                 this.removedActivities,
                                 this.unfinishedActivities,
                                 startTime,
                                 elapsedTime,
                                 this.topics,
                                 serializedTimeline);
    return getCombinedSimulationResults(serializableTopics, resourceManager, elapsedTime);
  }

  public SimulationResultsInterface getCombinedSimulationResults(
      final Map<Topic<?>, SerializableTopic<?>> serializableTopics,
      final SimulationResourceManager resourceManager, final Duration until) {
    if (this.simulationResults == null ) {
      return computeResults(
          this.startTime, until,
          defaultActivityTopic, serializableTopics, resourceManager);
      //      return computeResults(this.startTime, curTime(), defaultActivityTopic);
    }
    if (oldEngine == null) {
      return this.simulationResults;
    }
    return new CombinedSimulationResults(
        this.simulationResults,
        oldEngine.getCombinedSimulationResults(serializableTopics, resourceManager, until),
        timeline);
  }

  public Span getSpan(SpanId spanId) {
    Span s = this.spans.get(spanId);
    if (s == null) {  // TODO -- Not sure checking older engine is correct.  Used to pass tests without doing this.
      s = this.oldEngine.getSpan(spanId);   // TODO -- seems like a place where engine data needs to be rolled up.
    }
    return s;
  }


  private static <EventType> Optional<SerializedValue> trySerializeEvent(
      Event event,
      SerializableTopic<EventType> serializableTopic
  ) {
    return event.extract(serializableTopic.topic(), serializableTopic.outputType()::serialize);
  }

  /** A handle for processing requests from a modeled resource or condition. */
  public final class EngineQuerier<Job> implements Querier {
    private SubInstantDuration currentTime;
    public final TaskFrame<Job> frame;
    public final Set<Topic<?>> referencedTopics = new HashSet<>();
    private final Optional<Triple<Topic<Topic<?>>, TaskId, SpanId>> queryTrackingInfo;
    public Optional<Duration> expiry = Optional.empty();

    public EngineQuerier(final SubInstantDuration currentTime, final TaskFrame<Job> frame, final Topic<Topic<?>> queryTopic,
                         final TaskId associatedActivity, final SpanId associatedSpan) {
      this.currentTime = currentTime;
      this.frame = Objects.requireNonNull(frame);
      this.queryTrackingInfo = Optional.of(Triple.of(Objects.requireNonNull(queryTopic), associatedActivity, associatedSpan));
    }

    public EngineQuerier(final SubInstantDuration currentTime, final TaskFrame<Job> frame) {
      this.currentTime = currentTime;
      this.frame = Objects.requireNonNull(frame);
      this.queryTrackingInfo = Optional.empty();
    }

    @Override
    public <State> State getState(final CellId<State> token) {
      // SAFETY: The only queries the model should have are those provided by us (e.g. via MissionModelBuilder).
      @SuppressWarnings("unchecked")
      final var query = ((EngineCellId<?, State>) token);

      this.expiry = min(this.expiry, this.frame.getExpiry(query.query()));
      this.referencedTopics.add(query.topic());

      // TODO: Cache the state (until the query returns) to avoid unnecessary copies
      //  if the same state is requested multiple times in a row.
      final var state$ = this.frame.getState(query.query());

      this.queryTrackingInfo.ifPresent(info -> {
        TaskId taskId = info.getMiddle();
        if (oldEngine != null && tasksNeedingTimeAlignment.containsKey(taskId)) {
          checkForTimeAlignment(taskId, query.topic());
          this.currentTime = curTime();
        }

        if (isTaskStale(taskId, currentTime)) {
          // Create a noop event to mark when the read occurred in the EventGraph
          var noop = Event.create(info.getLeft(), query.topic(), taskId);
          this.frame.emit(noop);
          putInCellReadHistory(query.topic(), taskId, noop, currentTime);
        }
      });

      return state$.orElseThrow(IllegalArgumentException::new);
    }

    private static Optional<Duration> min(final Optional<Duration> a, final Optional<Duration> b) {
      if (a.isEmpty()) return b;
      if (b.isEmpty()) return a;
      return Optional.of(Duration.min(a.get(), b.get()));
    }
  }

  /**
   * Reset the current time to the SubInstantDuration that corresponds to the first event
   * for a task for the specified topic in the oldEngine's history. This is to make sure that
   * the execution of this task is timed such that it becomes stale at the right time.
   * If this first event is the cell read event that turns the task stale, the time will be updated
   * appropriately.  If an initial waiting condition is the reason for staleness, this isn't
   * guaranteed to work.
   *
   * @param taskId the task that may be turning stale
   * @param topic the topic of the read or emit event, whose time from past sim history will be used
   *              as the new current time
   */
  private void checkForTimeAlignment(TaskId taskId, Topic<?> topic) {
    if (oldEngine == null || !tasksNeedingTimeAlignment.containsKey(taskId)) {
      return;
    }
    final TreeMap<Duration, List<EventGraph<Event>>> eventsForTask = oldEngine.getCombinedEventsByTask(taskId);
    // The list of EventGraphs in eventsForTask represents all commits at the Duration, so the step index for a
    // SubInstantDuration can be inferred.
    var stepIndex = 0;
    for (var eventGraph : eventsForTask.firstEntry().getValue()) { // Can assume the first entry has it because the time just needs to be set for the first event
      Duration d = eventsForTask.firstEntry().getKey();
      var eventsMatchingThisOne = eventGraph.filter(event -> {
        if (!event.provenance().equals(taskId)) return false;
        if (event.topic().equals(topic)) return true;
        var x = event.extract(defaultActivityTopic);
        if (x.isPresent() && topic.equals(x.get())) return true;
        return false;
      });
      if (eventsMatchingThisOne.countNonEmpty() > 0) {
        Duration eventTime = oldEngine.timeline.getTimeForEventGraph(eventGraph);
        if (!d.equals(eventTime)) {
          System.err.println("Unexpected time of first event for rescheduled task!  " + d + " != " + eventTime);
          Thread.dumpStack();
        }
        // Need to get stepIndex
        var newTime = new SubInstantDuration(eventTime, stepIndex);
        setCurTime(newTime);  // TODO -- create a SubInstantDuration.of() to save instances in a symbol table to reduce memory usage
        tasksNeedingTimeAlignment.remove(taskId);
        if (debug) System.out.println("checkForTimeAlignment(" + taskId + " (" + getNameForTask(taskId) + "), " + topic + "): setting current time to " + newTime);
        return;
      }
      ++stepIndex;
    }
    if (false) {
      throw new RuntimeException("Couldn't correlate event by " + taskId + " (" + getNameForTask(taskId) + ") on " + topic + " with history!  " + eventsForTask.firstEntry());
    } else {
      if (debug) System.out.println("Assuming timing is self-correlating since we couldn't correlate event by " + taskId + " (" + getNameForTask(taskId) + ") on " + topic + " with history!  " + eventsForTask.firstEntry());
    }
  }

  public SubInstantDuration getSubInstantDurationForEvent(EventGraph<Event> eventGraph) {
    Duration time = timeline.getTimeForEventGraph(eventGraph);
    var commitsAtTime = timeline.getCombinedCommitsByTime().get(time);
    int stepIndex = 0;
    for (var commit : commitsAtTime) {
      if (commit.events() == eventGraph) {
        return new SubInstantDuration(time, stepIndex);
      }
      ++stepIndex;
    }
    throw new RuntimeException("Couldn't find EventGraph in commit history!  " + eventGraph);
  }

  // Fix time by matching the event

  /** A handle for processing requests and effects from a modeled task. */
  private final class EngineScheduler implements Scheduler {
    private SubInstantDuration currentTime;
    private TaskId activeTask;
    private SpanId span;
    private final Optional<TaskId> caller;
    private final TaskFrame<JobId> frame;
    private final Topic<Topic<?>> queryTopic;

    public EngineScheduler(
        final SubInstantDuration currentTime,
        final TaskId activeTask,
        final SpanId span,
        final Optional<TaskId> caller,
        final TaskFrame<JobId> frame,
        final Topic<Topic<?>> queryTopic)
    {
      this.currentTime = Objects.requireNonNull(currentTime);
      this.activeTask = activeTask;
      this.span = Objects.requireNonNull(span);
      this.caller = Objects.requireNonNull(caller);
      this.frame = Objects.requireNonNull(frame);
      this.queryTopic = Objects.requireNonNull(queryTopic);
    }

    @Override
    public <State> State get(final CellId<State> token) {
      // SAFETY: The only queries the model should have are those provided by us (e.g. via MissionModelBuilder).
      @SuppressWarnings("unchecked")
      final var query = (EngineCellId<?, State>) token;

      // Don't emit a noop event for the read if the task is not yet stale.
      // The time that this task becomes stale was determined when it was created.
      checkForTimeAlignment(activeTask, query.topic());
      currentTime = curTime();
      if (isTaskStale(this.activeTask, currentTime)) {
        // TODONE: REVIEW: What if the task becomes stale in the middle of a sequence of events within the same
        //       timepoint/EventGraph?  Should this be emitting an event in that case?
        //       Is there a problem of combining the existing or old EventGraph with a new one?
        // ANSWER: The task is conservatively considered stale before the EventGraph.

        // Create a noop event to mark when the read occurred in the EventGraph
        var noop = Event.create(queryTopic, query.topic(), activeTask);
        this.frame.emit(noop);
        putInCellReadHistory(query.topic(), activeTask, noop, currentTime);
      }

      // TODO: Cache the return value (until the next emit or until the task yields) to avoid unnecessary copies
      //  if the same state is requested multiple times in a row.
      final var state$ = this.frame.getState(query.query());
      return state$.orElseThrow(IllegalArgumentException::new);
    }

    @Override
    public <EventType> void emit(final EventType event, final Topic<EventType> topic) {
      if (debug) System.out.println("emit(" + event + ", " + topic + ")");
      checkForTimeAlignment(activeTask, topic);
      this.currentTime = curTime();
      boolean taskIsStale = isTaskStale(this.activeTask, this.currentTime);
      if (debug) System.out.println("emit(): isTaskStale(" + activeTask + " : " + getNameForTask(activeTask) + ", " + currentTime + ") --> " + taskIsStale);
      if (taskIsStale) {
        // Append this event to the timeline.
        this.frame.emit(Event.create(topic, event, this.activeTask));
        if (debug) System.out.println("emit(): isTopicStale(" + topic + ") --> " + timeline.isTopicStale(topic, this.currentTime));
        if (!timeline.isTopicStale(topic, this.currentTime)) {
          SimulationEngine.this.timeline.setTopicStale(topic, this.currentTime);
        }
        SimulationEngine.this.invalidateTopic(topic, this.currentTime.duration());
      } else {
        if (debug) System.out.println("emit(): not emitting because task is being rerun and is not yet stale.isTopicStale(" + topic + ") --> " + timeline.isTopicStale(topic, this.currentTime));
      }
    }

    @Override
    public <ActDirectiveId> void startDirective(
        final ActDirectiveId activityDirectiveId,
        final Topic<ActDirectiveId> activityTopic)
    {
      if (activityDirectiveId instanceof ActivityDirectiveId aId) {
        SimulationEngine.this.startDirective(aId, (Topic<ActivityDirectiveId>)activityTopic, this.span);
      }
    }

    @Override
    public <T> void startActivity(final T activity, final Topic<T> inputTopic) {
      SimulationEngine.this.startActivity(activity, inputTopic, this.span);
    }

    @Override
    public <T> void endActivity(final T result, final Topic<T> outputTopic) {
      SimulationEngine.this.endActivity(result, outputTopic, this.span);
    }

    /**
     * Return the taskId from the old simulation for the new (or old) TaskFactory.
     * @param taskFactory the TaskFactory used to create the task
     * @return the TaskId generated for the task created by taskFactory
     */
    public TaskId getOldTaskIdForDaemon(TaskFactory<?> taskFactory) {
      var taskId = oldEngine.getTaskIdForFactory(taskFactory);
      if (taskId != null) return taskId;
      String daemonId = getMissionModel().getDaemonId(taskFactory);
      if (daemonId == null) return null;
      var oldTaskFactory = oldEngine.getMissionModel().getDaemon(daemonId);
      if (oldTaskFactory == null) return null;
      taskId = oldEngine.getTaskIdForFactory(oldTaskFactory);
      return taskId;
    }


    @Override
    public void spawn(final InSpan inSpan, final TaskFactory<?> state) {
      final boolean daemonTaskOrSpawn = daemonTasks.contains(this.activeTask) || getMissionModel().isDaemon(state);

        // Reuse the child ids of this task from the old engine if possible
        final TaskId task = getNextChildTaskId(activeTask, currentTime);

        if (daemonTaskOrSpawn) {
          daemonTasks.add(task);
        }

        // Prepare a span for the child task
        final var childSpan = switch (inSpan) {
          case Parent -> this.span;

          case Fresh -> {
            var oldChildSpan = getSpanId(task);
            final var freshSpan = oldChildSpan == null ? SpanId.generate() : oldChildSpan;
            SimulationEngine.this.spans.put(freshSpan, new Span(Optional.of(this.span), currentTime.duration(), Optional.empty()));
            SimulationEngine.this.spanContributorCount.put(freshSpan, new MutableInt(1));
            yield freshSpan;
          }
        };

        // Record staleness if currently not stale
        if (!isTaskStale(activeTask, currentTime)) {
          var staleTime = staleTasks.get(activeTask);
          var afterEvent = staleEvents.get(activeTask);
          staleTasks.put(task, staleTime);
          staleEvents.put(task, afterEvent); // TODO -- more efficient to have one map with a pair of (time, afterEvent)
        }

        // Record task information
        if (trace) System.out.println("spawn TaskId = " + task + " (" + getNameForTask(task) + ") from " + activeTask + " (" + getNameForTask(activeTask) + ")");
        SimulationEngine.this.spanContributorCount.get(this.span).increment();
        SimulationEngine.this.tasks.put( task, new ExecutionState<>(
            childSpan, this.caller,
            state.create(SimulationEngine.this.executor),
            currentTime.duration()));
        this.caller.ifPresent($ -> SimulationEngine.this.blockedTasks.get($).increment());
        wireTasksAndSpans(task, this.activeTask, childSpan, this.span, false); // considering not recording span parent/child
        SimulationEngine.this.taskFactories.put(task, state);  // TODO -- shouldn't we be selective and only save some?
        SimulationEngine.this.taskIdsForFactories.put(state, task);
        this.frame.signal(JobId.forTask(task));
    }
  }

  /**
   * Reuse the child ids of the active task from the old engine if possible
   * @return the next child taskId corresponding to the past execution if not stale; else, a new one
   */
  private TaskId getNextChildTaskId(final TaskId activeTask, final SubInstantDuration currentTime) {
    final TaskId task;
    // Reuse the child ids of this task from the old engine
    if (isTaskStale(activeTask, currentTime)) {
      return TaskId.generate();
    }
    // Get the old taskId of the child.
    var currentChildren = taskChildren.get(activeTask);
    var oldChildren = oldEngine.getTaskChildren(activeTask);
    if (currentChildren == null) {
      task = oldChildren.get(0);  // oldChildren should not be empty because activeTask is not stale
    } else {
      // If the activeTask somehow used to be stale, then we would expect the right child to be the next one
      // after the last matching child.
      int i = currentChildren.size()-1;
      int pos = -1;
      for (;i >= 0; --i) {
        pos = oldChildren.lastIndexOf(currentChildren.get(i));
        if (pos >= 0) break;
      }
      if (i >= 0 && pos >= 0 && pos < oldChildren.size()-1) {
        task = oldChildren.get(pos+1);
      } else {
        task = TaskId.generate();
      }
    }
    return task;
  }


  private void startDirective(ActivityDirectiveId directiveId, Topic<ActivityDirectiveId> activityTopic, SpanId activeSpan) {
    if (trace) System.out.println("startDirective(" + directiveId + ", " + activityTopic + ", " +  activeSpan + ")");
    spanInfo.spanToPlannedDirective.put(activeSpan, directiveId);
    spanInfo.directiveIdToSpanId.put(directiveId, activeSpan);
  }

  private <T> void startActivity(T activity, Topic<T> inputTopic, final SpanId activeSpan) {
    if (trace) System.out.println("startActivity(" + activity + ", " + inputTopic + ", " +  activeSpan + ")");
    final SerializableTopic<T> sTopic = (SerializableTopic<T>) getMissionModel().getTopics().get(inputTopic);
    if (sTopic == null) return; // ignoring unregistered activity types!
    final var activityType = sTopic.name().substring("ActivityType.Input.".length());
    startActivity(new SerializedActivity(activityType, sTopic.outputType().serialize(activity).asMap().orElseThrow()),
                  activeSpan);
  }

  private void startActivity(SerializedActivity serializedActivity, final SpanId activeSpan) {
    if (trace) System.out.println("startActivity(" + serializedActivity + ", " + activeSpan + ")");
    spanInfo.input.put(activeSpan, serializedActivity);
  }

  private <T> void endActivity(T result, Topic<T> outputTopic, SpanId activeSpan) {
    final SerializableTopic<T> sTopic = (SerializableTopic<T>) getMissionModel().getTopics().get(outputTopic);
    if (sTopic == null) return; // ignoring unregistered activity types!
    spanInfo.output.put(
        activeSpan,
        sTopic.outputType().serialize(result));
  }

  private boolean isActivity(final TaskId taskId) {
    SpanId spanId = getSpanId(taskId);
    if (spanId != null && this.spanInfo.isActivity(spanId)) return true;
    if (this.daemonTasks.contains(taskId)) return false;
    if (oldEngine == null) return false;
    return this.oldEngine.isActivity(taskId);
  }

  private TaskId getTaskParent(TaskId taskId) {
    var parent = this.taskParent.get(taskId);
    if (parent == null && oldEngine != null) {
      parent = oldEngine.getTaskParent(taskId);
    }
    return parent;
  }

  TaskId getActivityParentTaskId(TaskId taskId, boolean tryOldEngine) {
    SpanId spanId = getSpanId(taskId);
    if (spanId != null && this.spanInfo.isActivity(spanId)) {
      return taskId;
    }
    if (taskId.equals(getDaemonTaskId()) || this.daemonTasks.contains(taskId)) {
      return null;
    }
    var parent = this.taskParent.get(taskId);
    if (parent != null) {
      var t = getActivityParentTaskId(parent, false);
      if (t != null) {
        return t;
      }
    }
    if (oldEngine == null || !tryOldEngine) return null;
    var t = this.oldEngine.getActivityParentTaskId(taskId, true);
    return t;
  }

  private TaskId getTaskParentFromSpan(TaskId taskId) {
    var spanId = getSpanId(taskId);
    TaskId parent = null;
    if (spanId != null && activityParents != null && !activityParents.isEmpty()) {
      var parentSpanId = activityParents.get(spanId);
      if (parentSpanId != null) {
        var tasks = getTaskIds(spanId);
        if (tasks != null && !tasks.isEmpty()) {
          parent = tasks.getFirst();
        }
      }
    }
    if (parent == null && oldEngine != null) {
      parent = oldEngine.getTaskParent(taskId);
    }
    return parent;
  }

  TaskId getDaemonTaskId() {
    TaskId daemonTaskId = getTaskIdForFactory(getMissionModel().getDaemon());
    if (daemonTaskId != null) {
      return daemonTaskId;
    }
    if (oldEngine != null) {
      return oldEngine.getDaemonTaskId();
    }
    return null;
  }

  boolean isDaemonTask(TaskId taskId) {
    if (daemonTasks.contains(taskId)) return true;
    SpanId spanId = getSpanId(taskId);
    if (spanId != null && spanInfo.isActivity(spanId)) return false;
    TaskId daemonTaskId = getTaskIdForFactory(getMissionModel().getDaemon());
    if (daemonTaskId != null && daemonTaskId.equals(taskId)) {
      return true;
    }
    if (oldEngine != null) {
      return oldEngine.isDaemonTask(taskId);
    }
    return false;
  }

  boolean isDaemonTaskOld(TaskId taskId) {
    if (daemonTasks.contains(taskId)) return true;
    SpanId spanId = getSpanId(taskId);
    if (spanId != null && spanInfo.isActivity(spanId)) return false;
    if (oldEngine != null) {
      return oldEngine.isDaemonTask(taskId);
    }
    return false;
  }

  public ActivityDirectiveId getActivityDirectiveId(TaskId taskId) {
    var spanId = getSpanId(taskId);
    var activityDirectiveId = spanId == null ? null : spanInfo.spanToPlannedDirective.get(spanId);
    if (activityDirectiveId == null && oldEngine != null) {
      activityDirectiveId = oldEngine.getActivityDirectiveId(taskId);
    }
    return activityDirectiveId;
  }

  public ActivityDirective getActivityDirective(TaskId taskId) {
    var activityDirectiveId = getActivityDirectiveId(taskId);
    if (activityDirectiveId == null) return null;
    ActivityDirective directive = scheduledDirectives.get(activityDirectiveId);
    if (directive == null && oldEngine != null) {
      directive = oldEngine.getActivityDirective(taskId);
    }
    return directive;
  }

  public SerializedActivity getSerializedActivity(TaskId taskId) {
    var spanId = getSpanId(taskId);
    SerializedActivity serializedActivity = spanId == null ? null : this.spanInfo.input.get(spanId);
    if (serializedActivity == null && oldEngine != null) {
      serializedActivity = oldEngine.getSerializedActivity(taskId);
    }
    return serializedActivity;
  }

  public String getActivityTypeName(TaskId taskId) {
    SerializedActivity act = getSerializedActivity(taskId);
    if (act != null) return act.getTypeName();
    var directive = getActivityDirective(taskId);
    if (directive != null) {
      return directive.serializedActivity().getTypeName();
    }
    return null;
  }

  public String getNameForTask(TaskId taskId) {
    if (isDaemonTask(taskId)) {
      TaskFactory<?> factory = getFactoryForTaskId(taskId);
      if (factory == null) {
        return "unknown daemon task";
      }
      String daemonId = missionModel.getDaemonId(factory);
      if (daemonId == null) return "unknown daemon task";
      return daemonId;
    }
    if (isActivity(taskId)) {
      String name = getActivityTypeName(taskId);
      if (name != null) return name;
      return "unknown activity";
    }
    return "unknown task";
  }

  public List<TaskId> getTaskChildren(TaskId taskId) {
    var children = this.taskChildren.get(taskId);
    if (children == null && oldEngine != null) {
      children = oldEngine.getTaskChildren(taskId);
    }
    return children;
  }

  /**
   * This method gets a {@link TaskFactory} for the old {@link TaskId} and calls
   * {@link SimulationEngine#scheduleTask(Duration, TaskFactory, TaskId)}
   *
   * @param taskId
   * @param startOffset
   * @param afterEvent
   */
  public void rescheduleTask(TaskId taskId, Duration startOffset, final Event afterEvent) {  // TODO -- don't we need the startOffset to be a SubInstantDuration?
    if (debug) System.out.println("rescheduleTask(" + taskId + " (" + getNameForTask(taskId) + "), " + startOffset + ")");
    if (oldEngine.isDaemonTask(taskId)) {
      if (trace) System.out.println("rescheduleTask(" + taskId + " : " + getNameForTask(taskId) + "): is daemon task");
      TaskFactory<?> factory = oldEngine.getFactoryForTaskId(taskId);
      if (factory != null && startOffset != null && startOffset != Duration.MAX_VALUE) {
        scheduleTask(startOffset, factory, taskId);
      } else {
        String daemonId = missionModel.getDaemonId(factory);
        throw new RuntimeException("Can't reschedule daemon task " + daemonId + " (" + taskId + ") at time offset " + startOffset +
                                   (factory == null ? " because there is no TaskFactory." : "."));
      }
    } else if (oldEngine.isActivity(taskId)) {
      if (trace) System.out.println("rescheduleTask(" + taskId + " : " + getNameForTask(taskId) + "): is activity");
      // Get the SerializedActivity for the taskId.
      // If an activity is found, see if it is associated with a directive and, if so, use the directive instead.
      var spanId = getSpanId(taskId);
      SerializedActivity serializedActivity = this.oldEngine.spanInfo.input.get(spanId);
      var activityDirectiveId = oldEngine.spanInfo.spanToPlannedDirective.get(spanId);
      ActivityInstance simulatedActivity = oldEngine.simulatedActivities.get(activityDirectiveId);
      if (startOffset == null || startOffset == Duration.MAX_VALUE) {
        if (simulatedActivity != null) {
          // TODO -- not possible to get here?  See println below.
          System.out.println("It is not possible to reach this code because simulatedActivities should be empty.");
          Instant actStart = simulatedActivity.start();
          startOffset = Duration.minus(actStart, this.startTime);
        } else {
          throw new RuntimeException("No SimulatedActivity for ActivityDirectiveId, " + activityDirectiveId);
        }
      }
      TaskFactory<?> task;
      try {
        task = missionModel.getTaskFactory(serializedActivity);
      } catch (InstantiationException ex) {
        // All activity instantiations are assumed to be validated by this point
        throw new Error("Unexpected state: activity instantiation %s failed with: %s"
                            .formatted(serializedActivity.getTypeName(), ex.toString()));
      }
      // TODO: What if there is no activityDirectiveId?
      if (activityDirectiveId != null) {
        scheduleTask(startOffset, //emitAndThen(activityDirectiveId, defaultActivityTopic, task),
                     executor1 -> scheduler1 -> {
                       this.startDirective(activityDirectiveId, null, spanId);
                       return task.create(executor1).step(scheduler1);
                     },
                     taskId);
      } else {
        scheduleTask(startOffset,
                     executor1 -> scheduler1 -> {
                       this.startActivity(serializedActivity, spanId);
                       return task.create(executor1).step(scheduler1);
                     },
                     taskId);
      }
      // TODO: No need to emit(), right?  So, what about below instead?
      // scheduleTask(startOffset, task, taskId);
    } else {
      if (trace) System.out.println("rescheduleTask(" + taskId + " : " + getNameForTask(taskId) + "): WARNING!  unknown whether task is daemon or activity spawned!");
      // We have a TaskFactory even though it's not an activity or daemon -- maybe a cached TaskFactory to avoid rerunning parents
      TaskFactory<?> factory = oldEngine.getFactoryForTaskId(taskId);
      if (factory != null && startOffset != null && startOffset != Duration.MAX_VALUE) {
        scheduleTask(startOffset, factory, taskId);  // TODO: Emit something like with emitAndThen() in the isAct case below?
        // TODO: Should that be       scheduler1.startActivity(activityId, activityTopic);
        //       Maybe just throw an exception for this else case that probably shouldn't happen.
      } else {
        throw new RuntimeException("Can't reschedule task " + taskId + " (" + getNameForTask(taskId) + ") at time offset " + startOffset +
                                   (factory == null ? " because there is no TaskFactory." : "."));
      }
    }

    // The 0 here may not be right, so we use an EventGraph instead of the time to determine when we've reached
    // the stale time.  But, we need the accurate time to keep cell times at least.  So, we lookup
    // the correct time of the first event based on the history.  So, when the activity generates its first event,
    // we align it with the event history.  If there is no event then the timing isn't a problem.
    setCurTime(new SubInstantDuration(startOffset, 0));
    tasksNeedingTimeAlignment.put(taskId, afterEvent);
  }

  /** A representation of a job processable by the {@link SimulationEngine}. */
  public sealed interface JobId {
    /** A job to step a task. */
    record TaskJobId(TaskId id) implements JobId {}

    /** A job to resume a task blocked on a condition. */
    record SignalJobId(ConditionId id) implements JobId {}

    /** A job to query a resource. */
    record ResourceJobId(ResourceId id) implements JobId {}

    /** A job to check a condition. */
    record ConditionJobId(ConditionId id) implements JobId {}

    static TaskJobId forTask(final TaskId task) {
      return new TaskJobId(task);
    }

    static SignalJobId forSignal(final ConditionId signal) {
      return new SignalJobId(signal);
    }

    static ResourceJobId forResource(final ResourceId resource) {
      return new ResourceJobId(resource);
    }

    static ConditionJobId forCondition(final ConditionId condition) {
      return new ConditionJobId(condition);
    }
  }

  /** The state of an executing task. */
  private record ExecutionState<Output>(SpanId span, Optional<TaskId> caller, Task<Output> state, Duration startOffset) {
    public ExecutionState<Output> continueWith(final Task<Output> newState) {
      return new ExecutionState<>(this.span, this.caller, newState, this.startOffset);
    }

    public ExecutionState<Output> duplicate(Executor executor) {
      return new ExecutionState<>(span, caller, state.duplicate(executor), startOffset);
    }
  }

  /** The span of time over which a subtree of tasks has acted. */
  public record Span(Optional<SpanId> parent, Duration startOffset, Optional<Duration> endOffset) {
    /** Close out a span, marking it as inactive past the given time. */
    public Span close(final Duration endOffset) {
      if (this.endOffset.isPresent()) throw new Error("Attempt to close an already-closed span");
      return new Span(this.parent, this.startOffset, Optional.of(endOffset));
    }

    public Optional<Duration> duration() {
      return this.endOffset.map($ -> $.minus(this.startOffset));
    }

    public boolean isComplete() {
      return this.endOffset.isPresent();
    }
  }

  public boolean spanIsComplete(SpanId spanId) {
    return this.spans.get(spanId).isComplete();
  }

  public SimulationEngine duplicate() {
    return new SimulationEngine(this);
  }

  public Optional<Duration> peekNextTime() {
    return this.scheduledJobs.peekNextTime();
  }

  /**
   * Create a timeline that in the output of the engine's reference timeline combined with its expanded timeline.
   */
  public TemporalEventSource combineTimeline() {
    final TemporalEventSource combinedTimeline = new TemporalEventSource();
    // TODO -- Would it make sense to use the getCombinedCommitsByTime() approach usign mergeMapsFirstWins() to combine these?
    //      -- add() seems pretty heavy duty
    for (final var entry : referenceTimeline.getCombinedCommitsByTime().entrySet()) {
      var commits = entry.getValue();
      int step = 0;   // TODO -- not sure if we can just increment the step number as we do in this loop -BJC
      for (var c : commits) {
        combinedTimeline.add(c.events(), entry.getKey(), step++, MissionModel.queryTopic);
      }
    }

    for (final var entry : timeline.getCombinedCommitsByTime().entrySet()) {
      var commits = entry.getValue();
      int step = 0;
      for (var c : commits) {
        combinedTimeline.add(c.events(), entry.getKey(), step++, MissionModel.queryTopic);
      }
    }
    return combinedTimeline;
  }

  public Map<ResourceId,Resource<?>> getResources() { return resources; }
}
