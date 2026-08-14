package gov.nasa.jpl.aerie.scheduler.model;


import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.tree.ProfileExpression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Contains all known information about an activity, representing BOTH directive-only information
 * and instance-only information.
 * If an activity only has a directive and hasn't been simulated, instance-specific data like duration
 * will be null. If an activity is generated during simulation and thus doesn't have a directive, directive
 * ID will be null. Only generated activities can have a non-null parent.
 *
 * @param id unique id
 * @param type the descriptor for the behavior invoked by this activity instance
 * @param startOffset the time at which this activity instance is scheduled to start
 * @param duration the length of time this activity instances lasts for after its start
 * @param arguments arguments are stored in a String/SerializedValue hashmap.
 * @param topParent the parent activity if any
 */
public record SchedulingActivity(
    ActivityDirectiveId id,
    ActivityType type,
    Duration startOffset,
    Duration duration,
    Map<String, SerializedValue> arguments,
    ActivityDirectiveId topParent,
    ActivityDirectiveId anchorId,
    boolean anchoredToStart,
    String name
) {

  public static SchedulingActivity of(
      ActivityDirectiveId id,
      ActivityType type,
      Duration startOffset,
      Duration duration,
      ActivityDirectiveId anchorId,
      boolean anchoredToStart
  ) {
    return new SchedulingActivity(
        id,
        type,
        startOffset,
        duration,
        Map.of(),
        null,
        anchorId,
        anchoredToStart,
        null
    );
  }

  public static SchedulingActivity of(
      ActivityDirectiveId id,
      ActivityType type,
      Duration startOffset,
      Duration duration,
      Map<String, SerializedValue> parameters,
      ActivityDirectiveId topParent,
      ActivityDirectiveId anchorId,
      boolean anchoredToStart
  ) {
    return new SchedulingActivity(
        id,
        type,
        startOffset,
        duration,
        parameters,
        topParent,
        anchorId,
        anchoredToStart,
        null
    );
  }

  public SchedulingActivity withNewDuration(Duration duration){
    return new SchedulingActivity(
        this.id,
        this.type,
        this.startOffset,
        duration,
        new HashMap<>(this.arguments),
        this.topParent,
        this.anchorId,
        this.anchoredToStart,
        this.name
    );
  }

  public SchedulingActivity withNewAnchor(ActivityDirectiveId anchorId, boolean anchoredToStart, Duration startOffset) {
    return new SchedulingActivity(
        this.id,
        this.type,
        startOffset,
        this.duration,
        new HashMap<>(this.arguments),
        this.topParent,
        anchorId,
        anchoredToStart,
        this.name
    );
  }

  public SchedulingActivity withNewDirectiveId(ActivityDirectiveId id) {
    return new SchedulingActivity(
        id,
        this.type,
        startOffset,
        this.duration,
        new HashMap<>(this.arguments),
        this.topParent,
        this.anchorId,
        this.anchoredToStart,
        this.name
    );
  }

  public SchedulingActivity withNewTopParent(ActivityDirectiveId topParent) {
    return new SchedulingActivity(
        this.id,
        this.type,
        startOffset,
        this.duration,
        new HashMap<>(this.arguments),
        topParent,
        this.anchorId,
        this.anchoredToStart,
        this.name
    );
  }

  public static SchedulingActivity fromExistingActivityDirective(ActivityDirectiveId id, ActivityDirective activity, ActivityType type, Duration duration){
    return new SchedulingActivity(
        id,
        type,
        activity.startOffset(),
        duration,
        activity.serializedActivity().getArguments(),
        null,
        activity.anchorId(),
        activity.anchoredToStart(),
        null
    );
  }

  /**
   * Returns the id of parent activity if this activity is generated.
   */
  public Optional<ActivityDirectiveId> getParentActivity(){
    return Optional.ofNullable(topParent);
  }

  public Duration getEndTime(){
    return startOffset.plus(duration);
  }

  /**
   * fetches the activity with the earliest end time in a list of activity instances
   *
   * @return the activity
   */
  public static SchedulingActivity getActWithEarliestEndTime(List<SchedulingActivity> acts) {
    if (!acts.isEmpty()) {
      acts.sort(Comparator.comparing(SchedulingActivity::getEndTime));

      return acts.getFirst();
    }
    return null;
  }

  /**
   * fetches the activity with the latest end time in a list of activity instances
   *
   * @return the activity
   */
  public static SchedulingActivity getActWithLatestEndTime(List<SchedulingActivity> acts) {
    if (!acts.isEmpty()) {
      acts.sort(Comparator.comparing(SchedulingActivity::getEndTime));

      return acts.getLast();
    }
    return null;
  }

  /**
   * fetches the activity with the earliest starting time in a list of activity instances
   *
   * @return the activity
   */
  public static SchedulingActivity getActWithEarliestStartTime(List<SchedulingActivity> acts) {
    if (!acts.isEmpty()) {
      acts.sort(Comparator.comparing(SchedulingActivity::startOffset));

      return acts.getFirst();
    }
    return null;
  }

  /**
   * fetches the activity with the latest starting time in a list of activity instances
   *
   * @return the activity
   */
  public static SchedulingActivity getActWithLatestStartTime(List<SchedulingActivity> acts) {
    if (!acts.isEmpty()) {
      acts.sort(Comparator.comparing(SchedulingActivity::startOffset));

      return acts.getLast();
    }
    return null;
  }

  /**
   * fetches the activity type specification that this instance is based on
   *
   * @return the activity type specification that this instance is based on
   */
  public ActivityType getType() {
    return type;
  }

  public String toString() {
    return "[" + this.type.getName() + ","+ this.id + "," + startOffset + "," + ((duration != null) ? getEndTime() : "no duration") + ", "+ topParent + ", " + anchorId+", "+anchoredToStart+"]";
  }

  /**
   * Checks equality but not in name
   * @param that the other activity instance to compare to
   * @return true if they are equal in properties, false otherwise
   */
  public boolean equalsInProperties(final SchedulingActivity that){
    return type.equals(that.type)
           && duration.equals(that.duration)
           && startOffset.equals(that.startOffset)
           && arguments.equals(that.arguments)
           && Objects.equals(topParent, that.topParent)
           && Objects.equals(anchorId, that.anchorId)
           && (anchoredToStart == that.anchoredToStart);
  }

  public static Map<String, SerializedValue> instantiateArguments(final Map<String, ProfileExpression<?>> arguments,
                                                                   final Duration startTime,
                                                                   final SimulationResults simulationResults,
                                                                   final EvaluationEnvironment environment,
                                                                  final ActivityType activityType){
    final var results = new HashMap<String, SerializedValue>();
    arguments.forEach((key, value) ->
                          results.put(key,
                                      value.evaluate(simulationResults, Interval.between(startTime, startTime), environment)
                                           .valueAt(startTime)
                                           .orElseThrow(() -> new Error("Profile for argument " + key + " has no value at time " + startTime)))
    );
    return results;
  }

  /**
   * adds an argument to the activity instance
   *
   * @param argument specification. must be identical as the one defined in the model
   * @param param value of the argument
   */
  public void addArgument(String argument, SerializedValue param) {
    assert(type.isParamLegal(argument));
    arguments.put(argument, param);
  }
}
