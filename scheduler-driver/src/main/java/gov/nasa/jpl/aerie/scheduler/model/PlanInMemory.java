package gov.nasa.jpl.aerie.scheduler.model;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.solver.Evaluation;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * an in-memory solution to a planning problem including a schedule of activities
 *
 * may only be a partial solution to the whole planning problem, ie some
 * goals may be left unsatisfied
 */
public class PlanInMemory implements Plan {

  /**
   * the set of all evaluations posted to the plan
   *
   * note that different solvers may evaluate the same plan differently
   */
  protected Evaluation evaluation;

  /**
   * container of all activity instances in plan, indexed by start time
   */
  private final TreeMap<Duration, List<SchedulingActivity>> actsByTime;

  /**
   * ctor creates a new empty solution plan
   *
   */
  public PlanInMemory() {
    this.actsByTime = new TreeMap<>();
  }

  public PlanInMemory(final PlanInMemory other){
    if(other.evaluation != null) this.evaluation = other.evaluation.duplicate();
    this.actsByTime = new TreeMap<>();
    for(final var entry: other.actsByTime.entrySet()){
      this.actsByTime.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
  }

  @Override
  public PlanInMemory duplicate() {
    return new PlanInMemory(this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void add(Collection<SchedulingActivity> acts) {
    for (final var act : acts) {
      add(act);
    }
  }

  public int size(){
    int size = 0;
    for(final var entry: this.actsByTime.entrySet()){
      size += entry.getValue().size();
    }
    return size;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void add(final SchedulingActivity act) {
    if (act == null) {
      throw new IllegalArgumentException(
          "adding null activity to plan");
    }
    final var startT = act.startOffset();
    if (startT == null) {
      throw new IllegalArgumentException(
          "adding activity with null start time to plan");
    }
    actsByTime.computeIfAbsent(startT, k -> new LinkedList<>())
              .add(act);
  }

  @Override
  public void remove(Collection<SchedulingActivity> acts) {
    for (var act : acts) {
      remove(act);
    }
  }

  @Override
  public void remove(SchedulingActivity act) {
    var acts = actsByTime.get(act.startOffset());
    if (acts != null) acts.remove(act);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<SchedulingActivity> getActivitiesByTime() {
    //REVIEW: could probably do something tricky with streams to avoid new
    final var orderedActs = new LinkedList<SchedulingActivity>();

    //NB: tree map ensures that values are in key order, but still need to flatten
    for (final var actsAtT : actsByTime.values()) {
      assert actsAtT != null;
      orderedActs.addAll(actsAtT);
    }

    return Collections.unmodifiableList(orderedActs);
  }

  public void replaceActivity(SchedulingActivity oldAct, SchedulingActivity newAct){
    this.remove(oldAct);
    this.add(newAct);
    if(evaluation != null) this.evaluation.updateGoalEvals(oldAct, newAct);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<ActivityType, List<SchedulingActivity>> getActivitiesByType() {
    final var map = new HashMap<ActivityType, List<SchedulingActivity>>();
    for(final var entry: this.actsByTime.entrySet()){
      for(final var activity : entry.getValue()){
        map.computeIfAbsent(activity.type(), t -> new ArrayList<>()).add(activity);
      }
    }
    return Collections.unmodifiableMap(map);
  }

  @Override
  public Map<ActivityDirectiveId, SchedulingActivity> getActivitiesById() {
    final var map = new HashMap<ActivityDirectiveId, SchedulingActivity>();
    for(final var entry: this.actsByTime.entrySet()){
      for(final var activity : entry.getValue()){
        map.put(activity.id(), activity);
      }
    }
    return Collections.unmodifiableMap(map);
  }

@Override
  public Set<ActivityDirectiveId> getAnchorIds() {
    return getActivities().stream()
                  .map(SchedulingActivity::anchorId)
                  .collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<SchedulingActivity> getActivities() {
    final var set = new HashSet<SchedulingActivity>();
    for(final var entry: this.actsByTime.entrySet()){
      set.addAll(entry.getValue());
    }
    return Collections.unmodifiableSet(set);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Collection<SchedulingActivity> find(
      ActivityExpression template, SimulationResults simulationResults,
      EvaluationEnvironment evaluationEnvironment)
  {
    //REVIEW: could do something clever with returning streams to prevent wasted work
    //REVIEW: something more clever for time-based queries using time index
    LinkedList<SchedulingActivity> matched = new LinkedList<>();
    for (final var actsAtTime : actsByTime.values()) {
      for (final var act : actsAtTime) {
        if (template.matches(act, simulationResults, evaluationEnvironment, true, this)) {
          matched.add(act);
        }
      }
    }
    return matched;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addEvaluation(Evaluation eval) {
    evaluation = eval;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Evaluation getEvaluation() {
    return evaluation;
  }

  @Override
  public Duration calculateAbsoluteStartOffsetAnchoredActivity(SchedulingActivity act){
    if(act == null)
      return null;
    if(act.anchorId() != null){
      SchedulingActivity parent = this.getActivitiesById().get(act.anchorId());
      if(!act.anchoredToStart() && parent.duration() == null)
        throw new IllegalArgumentException("Cannot calculate the absolute duration for an activity that is not anchored to the start while the parent doesn't have duration");
      return calculateAbsoluteStartOffsetAnchoredActivity(parent).plus(act.anchoredToStart() ? act.startOffset() : act.startOffset().plus(parent.duration()));
    }
    return act.startOffset();
  }

  @Override
  public PlanInMemory replaceIds(Map<ActivityDirectiveId, ActivityDirectiveId> map) {
    final var result = duplicate();

    for (final var entry : result.actsByTime.entrySet()) {
      final var actList = entry.getValue()
          .stream()
          .map($ -> {
            if (($.id() != null && map.containsKey($.id())) || ($.anchorId() != null && map.containsKey($.anchorId()))) {
              return new SchedulingActivity(
                  $.id() != null ? map.getOrDefault($.id(), $.id()) : null,
                  $.type(),
                  $.startOffset(),
                  $.duration(),
                  $.arguments(),
                  $.topParent(),
                  $.anchorId() != null ? map.getOrDefault($.anchorId(), $.anchorId()) : null,
                  $.anchoredToStart(),
                  $.name()
              );
            } else return $;
          })
          .toList();
      entry.setValue(actList);
    }

    return result;
  }
}
