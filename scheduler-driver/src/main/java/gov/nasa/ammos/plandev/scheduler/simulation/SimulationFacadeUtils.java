package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResultsComputerInputs;
import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class SimulationFacadeUtils {
  private static final Logger LOGGER = LoggerFactory.getLogger(SimulationFacadeUtils.class);

  public static SimulationFacade.PlanSimCorrespondence scheduleFromPlan(final Plan plan, final SchedulerModel schedulerModel){
    final var activities = plan.getActivities();
    if(activities.isEmpty()) return new SimulationFacade.PlanSimCorrespondence(Map.of());
    //filter out child activities
    final var activitiesWithoutParent = activities.stream().filter(a -> a.topParent() == null).toList();
    final Map<ActivityDirectiveId, ActivityDirective> directivesToSimulate = new HashMap<>();

    for(final var activity : activitiesWithoutParent) {
      final var activityDirective = schedulingActToActivityDir(activity, schedulerModel);
      directivesToSimulate.put(
          activity.id(),
          activityDirective);
    }
    return new SimulationFacade.PlanSimCorrespondence(directivesToSimulate);
  }

  /**
   * For activities that have a null duration (in an initial plan for example) and that have been simulated, we pull the duration and
   * replace the original instance with a new instance that includes the duration, both in the plan and the simulation facade
   */
  public static void pullActivityDurationsIfNecessary(
      final Plan plan,
      final SimulationFacade.PlanSimCorrespondence correspondence,
      final SimulationEngine.SimulationActivityExtract activityExtract
  ) {
    final var toReplace = new HashMap<SchedulingActivity, SchedulingActivity>();
    for (final var activity : plan.getActivities()) {
      if (activity.duration() == null) {
        final var activityDirective = findSimulatedActivityById(
            activityExtract.simulatedActivities().values(),
            activity.id()
        );
        if (activityDirective.isPresent()) {
          final var replacementAct = activity.withNewDuration(activityDirective.get().duration());
          toReplace.put(activity, replacementAct);
        }
        //if not, maybe the activity is not finished
      }
    }
    toReplace.forEach(plan::replaceActivity);
  }

  private static Optional<ActivityInstance> findSimulatedActivityById(
      Collection<ActivityInstance> simulatedActivities,
      final ActivityDirectiveId activityDirectiveId
  ){
    return simulatedActivities.stream()
                              .filter(a -> a.directiveId().isPresent() && a.directiveId().get().equals(activityDirectiveId))
                              .findFirst();
  }

  public static void updatePlanWithChildActivities(
      final SimulationEngine.SimulationActivityExtract activityExtract,
      final Map<String, ActivityType> activityTypes,
      final Plan plan,
      final PlanningHorizon planningHorizon)
  {
    //remove all activities with parents
    final var toRemove = plan.getActivities().stream().filter(a -> a.topParent() != null).toList();
    toRemove.forEach(plan::remove);
    //pull child activities
    activityExtract.simulatedActivities().forEach( (activityInstanceId, activity) -> {
      if (activity.parentId() == null) return;
      final var rootParent = getIdOfRootParent(activityExtract, activityInstanceId);
      if(rootParent.isPresent()) {
        final var activityInstance = SchedulingActivity.of(
            null,
            activityTypes.get(activity.type()),
            planningHorizon.toDur(activity.start()),
            activity.duration(),
            activity.arguments(),
            rootParent.get(),
            null,
            true
        );
        plan.add(activityInstance);
      }
    });
    //no need to replace in Evaluation because child activities are not referenced in it
  }

  private static Optional<ActivityDirectiveId> getIdOfRootParent(
      final SimulationEngine.SimulationActivityExtract results,
      final ActivityInstanceId instanceId){
    if(!results.simulatedActivities().containsKey(instanceId)){
      if(!results.unfinishedActivities().containsKey(instanceId)){
        LOGGER.debug("The simulation of the parent of activity with id "+ instanceId.id() + " has been finished");
      }
      return Optional.empty();
    }
    final var act = results.simulatedActivities().get(instanceId);
    if(act.parentId() == null){
      // SAFETY: any activity that has no parent must have a directive id.
      return Optional.of(act.directiveId().get());
    } else {
      return getIdOfRootParent(results, act.parentId());
    }
  }

  public static Optional<Duration> getActivityDuration(
      final ActivityDirectiveId activityDirectiveId,
      final SimulationResultsComputerInputs simulationResultsInputs
  ){
    return simulationResultsInputs.engine()
                                  .getSpan(simulationResultsInputs.activityDirectiveIdTaskIdMap()
                                                                  .get(activityDirectiveId))
                                  .duration();
  }

  public static ActivityDirective schedulingActToActivityDir(
      final SchedulingActivity activity,
      final SchedulerModel schedulerModel) {
    if(activity.getParentActivity().isPresent()) {
      throw new Error("This method should not be called with a generated activity but with its top-level parent.");
    }
    final var arguments = new HashMap<>(activity.arguments());
    if (activity.duration() != null) {
      final var durationType = activity.getType().getDurationType();
      if (durationType instanceof DurationType.Controllable dt) {
        arguments.put(dt.parameterName(), schedulerModel.serializeDuration(activity.duration()));
      } else if (
          !(durationType instanceof DurationType.Uncontrollable
          || durationType instanceof DurationType.Fixed
          || durationType instanceof DurationType.Parametric)
      ) {
        throw new Error("Unhandled variant of DurationType: " + durationType);
      }
    }
    final var serializedActivity = new SerializedActivity(activity.getType().getName(), arguments);
    return new ActivityDirective(
        activity.startOffset(),
        serializedActivity,
        activity.anchorId(),
        activity.anchoredToStart());
  }
}
