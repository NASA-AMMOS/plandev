package gov.nasa.jpl.aerie.scheduler.worker.services;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.TimeUtility;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.models.ExternalProfiles;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.MerlinPlan;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanMetadata;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinDatabaseService;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinServiceException;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class MockMerlinDatabaseService implements MerlinDatabaseService.OwnerRole {

  private Optional<PlanningHorizon> planningHorizon;
  private ExternalProfiles externalProfiles = new ExternalProfiles(Map.of(), Map.of(), List.of());

  public void setExternalDataset(ExternalProfiles externalProfiles) {
    this.externalProfiles = externalProfiles;
  }

  record MissionModelInfo(Path libPath, Path modelPath, String modelName, MerlinDatabaseService.MissionModelTypes types, Map<String, SerializedValue> config) {}

  private Optional<MissionModelInfo> missionModelInfo = Optional.empty();
  private MerlinPlan initialPlan;
  Collection<ActivityDirective> updatedPlan;
  Plan plan;

  MockMerlinDatabaseService() {
    this.initialPlan = new MerlinPlan();
    this.planningHorizon = Optional.of(new PlanningHorizon(
        TimeUtility.fromDOY("2021-001T00:00:00"),
        TimeUtility.fromDOY("2021-005T00:00:00")));
  }

  void setInitialPlan(final Map<ActivityDirectiveId, ActivityDirective> initialActivities) {
    final var newInitialPlan = new MerlinPlan();
    for (final var activity : initialActivities.entrySet()) {
      newInitialPlan.addActivity(activity.getKey(), activity.getValue());
    }
    this.initialPlan = newInitialPlan;
  }

  void setMissionModel(final MissionModelInfo value) {
    this.missionModelInfo = Optional.of(value);
  }

  void setPlanningHorizon(final PlanningHorizon planningHorizon) {
    this.planningHorizon = Optional.of(planningHorizon);
  }

  @Override
  public long getPlanRevision(final PlanId planId) {
    return 1L;
  }

  @Override
  public PlanMetadata getPlanMetadata(final PlanId planId)
  {
    if (this.missionModelInfo.isEmpty()) throw new RuntimeException("Make sure to call setMissionModel before running a test");
    if (this.planningHorizon.isEmpty()) throw new RuntimeException("Make sure to call setPlanningHorizon before running a test");
    // Checked that revision matches
    // Uses model version info to load mission model jar
    // The PlanningHorizon here is not used for scheduling (at least not directly)
    return new PlanMetadata(
        new PlanId(1L),
        1L,
        planningHorizon.get(),
        1L,
        this.missionModelInfo.get().modelPath(),
        this.missionModelInfo.get().modelName(),
        "1.0.0",
        this.missionModelInfo.get().config());
  }

  @Override
  public MerlinPlan getPlanActivityDirectives(final PlanMetadata planMetadata, final Problem mission)
  {
    // TODO this gets the planMetadata from above
    return this.initialPlan;
  }

  @Override
  public Pair<PlanId, Map<ActivityDirectiveId, ActivityDirectiveId>> createNewPlanWithActivityDirectives(
      final PlanMetadata planMetadata,
      final Plan plan,
      final Map<SchedulingActivity, GoalId> activityToGoal,
      final SchedulerModel schedulerModel
  )
  {
    return null;
  }

  @Override
  public PlanId createEmptyPlan(final String name, final long modelId, final Instant startTime, final Duration duration)
  {
    return null;
  }

  @Override
  public Map<ActivityDirectiveId, ActivityDirectiveId> updatePlanActivityDirectives(
      final PlanId planId,
      final MerlinPlan initialPlan,
      final Plan plan,
      final Map<SchedulingActivity, GoalId> activityToGoal,
      final SchedulerModel schedulerModel
  )
  {
    this.updatedPlan = extractActivityDirectives(plan, schedulerModel);
    this.plan = plan;
    final var res = new HashMap<ActivityDirectiveId, ActivityDirectiveId>();
    for (final var activity : plan.getActivities()) {
      res.put(activity.id(), activity.id());
    }
    return res;
  }

  @Override
  public void ensurePlanExists(final PlanId planId) {

  }

  @Override
  public Optional<Pair<SimulationResults, DatasetId>> getSimulationResults(final PlanMetadata planMetadata)
  {
    return Optional.empty();
  }

  @Override
  public ExternalProfiles getExternalProfiles(final PlanId planId) {
    return externalProfiles;
  }

  @Override
  public Map<String, List<ExternalEvent>> getExternalEvents(final PlanId planId, final Instant horizonStart)
  throws MerlinServiceException, IOException
  {
    return Map.of();
  }

  @Override
  public Collection<ResourceType> getResourceTypes(final PlanId planId)
  {
    return null;
  }

  @Override
  public Map<ActivityDirectiveId, GoalId> getActivityIdToGoalIdMap(final PlanId planId)
  throws MerlinServiceException, IOException
  {
    return Map.of();
  }

  @Override
  public void clearPlanActivityDirectives(final PlanId planId)
  {

  }

  @Override
  public Map<ActivityDirectiveId, ActivityDirectiveId> createAllPlanActivityDirectives(
      final PlanId planId,
      final Plan plan,
      final Map<SchedulingActivity, GoalId> activityToGoalId,
      final SchedulerModel schedulerModel
      )
  {
    return null;
  }

  @Override
  public DatasetId storeSimulationResults(
          final PlanMetadata planMetadata,
          final SimulationResults results,
          final Map<ActivityDirectiveId, ActivityDirectiveId> uploadIdMap
  ) {
    return new DatasetId(0);
  }

  @Override
  public MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final PlanId planId)
  {
    if (this.missionModelInfo.isEmpty()) throw new RuntimeException("Make sure to call setMissionModel before running a test");
    return this.missionModelInfo.get().types();
  }

  @Override
  public MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final MissionModelId missionModelId)
  {
    if (this.missionModelInfo.isEmpty()) throw new RuntimeException("Make sure to call setMissionModel before running a test");
    return this.missionModelInfo.get().types();
  }


  private static Collection<ActivityDirective> extractActivityDirectives(final Plan plan, final SchedulerModel schedulerModel) {
    final var activityDirectives = new ArrayList<ActivityDirective>();
    for (final var activity : plan.getActivities()) {
      final var type = activity.getType();
      final var arguments = new HashMap<>(activity.arguments());
      if(type.getDurationType() instanceof DurationType.Controllable durationType){
        //detect duration parameter and add it to parameters
        if(!arguments.containsKey(durationType.parameterName())){
          arguments.put(
              durationType.parameterName(),
              schedulerModel.serializeDuration(activity.duration()));
        }
      }
      activityDirectives.add(new ActivityDirective(
          activity.startOffset(),
          activity.getType().getName(),
          arguments,
          activity.anchorId(),
          activity.anchoredToStart()));
    }
    return activityDirectives;
  }
}
