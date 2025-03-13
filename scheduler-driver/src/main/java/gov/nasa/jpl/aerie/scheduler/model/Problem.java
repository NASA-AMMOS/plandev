package gov.nasa.jpl.aerie.scheduler.model;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.scheduler.constraints.scheduling.GlobalConstraintWithIntrospection;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * description of a planning problem to be solved
 */
public class Problem {

  /**
   * the mission model that this problem is based on
   */
  private final MissionModel<?> missionModel;

  /**
   * The scheduler-specific aspects of the mission model
   */
  private final SchedulerModel schedulerModel;

  private final SimulationFacade simulationFacade;

  //should be accessible inside problem definition
  protected final PlanningHorizon planningHorizon;

  /**
   * global constraints in the mission model, indexed by name
   */
  private final List<GlobalConstraintWithIntrospection> globalConstraints
      = new java.util.LinkedList<>();

  private final Map<String, LinearProfile> realExternalProfiles = new HashMap<>();
  private final Map<String, DiscreteProfile> discreteExternalProfiles = new HashMap<>();
  private Map<String, List<ExternalEvent>> eventsByDerivationGroup = new HashMap<>();

  /**
   * the initial seed plan to start scheduling from
   */
  private Plan initialPlan;

  /**
   * initial simulation results loaded from the DB
   */
  private Optional<SimulationData> initialSimulationResults;

  /**
   * container of all goals in the problem, indexed by name
   */
  protected final List<Goal> goalsOrderedByPriority = new ArrayList<>();

  /**
   * activity type definitions in the mission model, indexed by name
   */
  private final java.util.Map<String, ActivityType> actTypeByName
      = new java.util.HashMap<>();

  /**
   * creates a new empty problem based in the given mission model
   *
   * @param mission IN the mission model that this problem is based on
   */
  public Problem(
      MissionModel<?> mission,
      PlanningHorizon planningHorizon,
      SimulationFacade simulationFacade,
      SchedulerModel schedulerModel) {
    this.missionModel = mission;
    this.schedulerModel = schedulerModel;
    this.initialPlan = new PlanInMemory();
    this.planningHorizon = planningHorizon;
    //add all activity types known to aerie to scheduler index
    if( missionModel != null ) {
      for(var taskType : missionModel.getDirectiveTypes().directiveTypes().entrySet()){
        this.add(new ActivityType(taskType.getKey(), taskType.getValue(), schedulerModel.getDurationTypes().get(taskType.getKey())));
      }
    }
    this.simulationFacade = simulationFacade;
    if(this.simulationFacade != null) {
      this.simulationFacade.addActivityTypes(this.getActivityTypes());
    }
    this.initialSimulationResults = Optional.empty();
  }

  public SimulationFacade getSimulationFacade(){
    return simulationFacade;
  }

  public PlanningHorizon getPlanningHorizon(){
    return planningHorizon;
  }

  public SchedulerModel getSchedulerModel() { return schedulerModel; }

  /**
   * adds a new global constraint to the mission model
   *
   * @param globalConstraint IN the global constraint
   */
  public void add(GlobalConstraintWithIntrospection globalConstraint) {
    this.globalConstraints.add(globalConstraint);
  }

  public List<GlobalConstraintWithIntrospection> getGlobalConstraints() {
    return this.globalConstraints;
  }

  /**
   * fetches the mission model that this problem is based on
   *
   * @return the mission model that this problem is based on
   */
  public MissionModel<?> getMissionModel() {
    return missionModel;
  }

  /**
   * fetches the initial seed plan that schedulers may start from
   *
   * @return the initial seed plan that schedulers may start from
   */
  public Plan getInitialPlan() {
    return initialPlan.duplicate();
  }

  /**
   * sets the initial seed plan that schedulers may start from
   * @param initialSimulationResults optional initial simulation results associated to the initial plan
   * @param plan the initial seed plan that schedulers may start from
   */
  public void setInitialPlan(
      final Plan plan,
      final Optional<SimulationResults> initialSimulationResults
  ) {
    initialPlan = plan;
    this.initialSimulationResults = initialSimulationResults.map(simulationResults -> new SimulationData(
        getInitialPlan(),
        simulationResults,
        new gov.nasa.jpl.aerie.constraints.model.SimulationResults(simulationResults)
    ));
  }

  /**
   * sets the initial seed plan that schedulers may start from
   *
   * @param plan the initial seed plan that schedulers may start from
   */
  public void setInitialPlan(final Plan plan) {
    setInitialPlan(plan, Optional.empty());
  }

  public Optional<SimulationData> getInitialSimulationResults(){ return initialSimulationResults; }

  public void setExternalProfile(final Map<String, LinearProfile> realExternalProfiles,
                                  final Map<String, DiscreteProfile> discreteExternalProfiles){
    this.realExternalProfiles.putAll(realExternalProfiles);
    this.discreteExternalProfiles.putAll(discreteExternalProfiles);
  }

  public void setEventsByDerivationGroup(final Map<String, List<ExternalEvent>> events) {
    this.eventsByDerivationGroup = events;
  }

  public Map<String, LinearProfile> getRealExternalProfiles(){
    return this.realExternalProfiles;
  }

  public Map<String, DiscreteProfile> getDiscreteExternalProfiles(){
    return this.discreteExternalProfiles;
  }

  public Map<String, List<ExternalEvent>> getEventsByDerivationGroup() { return this.eventsByDerivationGroup; }

  public void setGoals(List<Goal> goals){
    goalsOrderedByPriority.clear();
    goalsOrderedByPriority.addAll(goals);
  }


  /**
   * retrieves the set of all requested plan goals
   *
   * planning algorithms should attempt to satisfy these goals when generating
   * or updating plans. the details of exactly how the goals are weighted
   * against eachother is up to the algorithm, and not all goals must be
   * satisfied in a proposed solution plan
   *
   * @return an un-modifiable container of the goals requested for this plan
   */
  public List<Goal> getGoals() {
    return Collections.unmodifiableList(goalsOrderedByPriority);
  }

  private void failIfActivityTypeAbsent(String name){
    if (!this.actTypeByName.containsKey(name)) {
      throw new IllegalArgumentException(
          "no activity type definition name=" + name +
          " in mission model. Either add it manually to the Problem or it should already exists in the mission model");
    }
  }

  /**
   * adds a new activity type definition to the mission model
   *
   * @param actType IN the activity type definition to add to the mission
   *     model, which must not already have a type definition with matching
   *     identifier
   */
  public void add(ActivityType actType) {

    if (actType == null) {
      throw new IllegalArgumentException(
          "adding null activity type to mission model");
    }

    final String name = actType.getName();
    if (name == null) {
      throw new IllegalArgumentException(
          "adding activity type definition with null name to mission model");
    }
    if (this.actTypeByName.containsKey(name)) {
      throw new IllegalArgumentException(
          "adding duplicate activity type definition name=" + name + " to mission model");
    }

    this.actTypeByName.put(name, actType);
  }

  /**
   * fetches the activity type object with the given name
   *
   * @param name IN the name associated with the requested activity type
   * @return the activity type with a matching name, or null if there is
   *     no such activity type in the mission model
   */
  public ActivityType getActivityType(String name) {
    failIfActivityTypeAbsent(name);
    return actTypeByName.get(name);
  }

  public Collection<ActivityType> getActivityTypes(){
    return Collections.unmodifiableCollection(actTypeByName.values());
  }

}
