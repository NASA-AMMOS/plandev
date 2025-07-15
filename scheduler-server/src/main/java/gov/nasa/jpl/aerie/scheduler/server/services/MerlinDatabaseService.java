package gov.nasa.jpl.aerie.scheduler.server.services;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchActivityInstanceException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchMissionModelException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.scheduler.server.models.ActivityType;
import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.models.ExternalProfiles;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.MerlinPlan;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanMetadata;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MerlinDatabaseService {
  record MissionModelTypes(Collection<ActivityType> activityTypes, Collection<ResourceType> resourceTypes) {}

  interface ReaderRole {

    MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final PlanId planId)
    throws IOException, MerlinServiceException;
    MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final MissionModelId missionModelId)
    throws IOException, MerlinServiceException,
           NoSuchMissionModelException;

    /**
     * fetch current revision number of the target plan stored in aerie
     *
     * @param planId identifier of the plan container whose details should be fetched
     * @return the current revision number of the plan as stored in aerie
     * @throws NoSuchPlanException when the plan container does not exist in aerie
     */
    long getPlanRevision(final PlanId planId)
    throws IOException, NoSuchPlanException, MerlinServiceException;

    /**
     * fetch current metadata of the target plan (not the activity instance content)
     *
     * @param planId identifier of the plan container whose details should be fetched
     * @return metadata about the plan that is useful to the scheduler, including current plan revision
     * @throws NoSuchPlanException when the plan container does not exist in aerie
     */
    PlanMetadata getPlanMetadata(final PlanId planId)
    throws IOException, NoSuchPlanException, MerlinServiceException;

    /**
     * create an in-memory snapshot of the target plan's activity contents from aerie
     *
     * @param planMetadata identifying details of the plan to fetch content for
     * @param mission the mission model that the plan adheres to
     * @return a newly allocated snapshot of the plan contents
     * @throws NoSuchPlanException when the plan container does not exist in aerie
     */
    MerlinPlan getPlanActivityDirectives(final PlanMetadata planMetadata, final Problem mission)
    throws IOException, NoSuchPlanException, MerlinServiceException, InvalidJsonException, InstantiationException;

    /**
     * confirms that the specified plan exists in the aerie database, throwing exception if not
     *
     * @param planId the target plan database identifier
     * @throws NoSuchPlanException when the plan container does not exist in aerie
     */
    //TODO: (defensive) should combine such checks into the mutations they are guarding, but not possible in graphql?
    void ensurePlanExists(final PlanId planId)
    throws IOException, NoSuchPlanException, MerlinServiceException;

    /**
     * Gets existing simulation results for current plan if they exist and are suitable for scheduling purposes (current
     * revision, covers the entire planning horizon)
     * These simulation results do not include events and topics.
     * @param planMetadata the plan metadata
     * @return optionally: simulation results and its dataset id
     */
    Optional<Pair<SimulationResults, DatasetId>> getSimulationResults(PlanMetadata planMetadata) throws MerlinServiceException, IOException, InvalidJsonException;


    /**
     * Gets external profiles associated to a plan, including segments
     * @param planId the plan id
     * @throws MerlinServiceException
     * @throws IOException
     */
    ExternalProfiles getExternalProfiles(final PlanId planId)
    throws MerlinServiceException, IOException;

    Map<String, List<ExternalEvent>> getExternalEvents(final PlanId planId, final Instant horizonStart)
      throws MerlinServiceException, IOException;

    /**
     * Gets resource types associated to a plan, those coming from the mission model as well as those coming from external dataset resources
     * @param planId the plan id
     * @throws IOException
     * @throws MerlinServiceException
     * @throws NoSuchPlanException
     */
    Collection<ResourceType> getResourceTypes(final PlanId planId)
    throws IOException, MerlinServiceException, NoSuchPlanException;

    Map<ActivityDirectiveId, GoalId> getActivityIdToGoalIdMap(final PlanId planId)
      throws MerlinServiceException, IOException;
  }

  interface WriterRole {
    /**
     * create an entirely new plan container in aerie and synchronize the in-memory plan to it
     *
     * does not mutate the original plan, so metadata remains valid for the original plan
     *
     * @param planMetadata identifying details of a plan to emulate in creating new container. id is ignored.
     * @param plan plan with all activity instances that should be stored to target merlin plan container
     * @return the database id of the newly created aerie plan container
     * @throws NoSuchPlanException when the plan container could not be found in aerie after creation
     */
    Pair<PlanId, Map<ActivityDirectiveId, ActivityDirectiveId>> createNewPlanWithActivityDirectives(
        final PlanMetadata planMetadata,
        final Plan plan,
        final Map<SchedulingActivity, GoalId> activityToGoalId,
        final SchedulerModel schedulerModel
    )
    throws IOException, NoSuchPlanException, MerlinServiceException;

    /**
     * create a new empty plan container based on specifications
     *
     * does not attach a scheduling specification to the plan!
     *
     * @param name the human legible label for the new plan container to create
     * @param modelId the database identifier of the mission model to associate with the plan
     * @param startTime the absolute start time of the new plan container
     * @param duration the duration of the new plan container
     * @return the database id of the newly created aerie plan container
     * @throws NoSuchPlanException when the plan container could not be found in aerie after creation
     */
    PlanId createEmptyPlan(final String name, final long modelId, final Instant startTime, final Duration duration)
    throws IOException, NoSuchPlanException, MerlinServiceException;

    /**
     * synchronize the in-memory plan back over to aerie data stores via update operations
     *
     * the plan revision will change!
     *
     * @param planId aerie database identifier of the target plan to synchronize into
     * @param plan plan with all activity instances that should be stored to target merlin plan container
     * @return
     * @throws NoSuchPlanException when the plan container does not exist in aerie
     */
    Map<ActivityDirectiveId, ActivityDirectiveId> updatePlanActivityDirectives(
        PlanId planId,
        MerlinPlan initialPlan,
        Plan plan,
        Map<SchedulingActivity, GoalId> activityToGoalId,
        SchedulerModel schedulerModel
    )
    throws IOException, NoSuchPlanException, MerlinServiceException, NoSuchActivityInstanceException;

    /**
     * delete all the activity instances stored in the target plan container
     *
     * the plan revision will change!
     *
     * @param planId the database id of the plan container to clear
     * @throws NoSuchPlanException when the plan container does not exist in aerie
     */
    void clearPlanActivityDirectives(final PlanId planId)
    throws IOException, NoSuchPlanException, MerlinServiceException;

    /**
     * create activity instances in the target plan container for each activity in the input plan
     *
     * does not attempt to resolve id clashes or do activity instance updates
     *
     * the plan revision will change!
     *
     * @param planId the database id of the plan container to populate with new activity instances
     * @param plan the plan from which to copy all activity instances into aerie
     * @return
     * @throws NoSuchPlanException when the plan container does not exist in aerie
     */
    Map<ActivityDirectiveId, ActivityDirectiveId> createAllPlanActivityDirectives(
        final PlanId planId,
        final Plan plan,
        final Map<SchedulingActivity, GoalId> activityToGoalId,
        final SchedulerModel schedulerModel
    )
    throws IOException, NoSuchPlanException, MerlinServiceException;

    /**
     * Stores the simulation results produced during scheduling
     *
     * @param planMetadata the plan metadata
     * @param results the simulation results
     */
   DatasetId storeSimulationResults(final PlanMetadata planMetadata, final SimulationResults results, Map<ActivityDirectiveId, ActivityDirectiveId> uploadIdMap) throws
                                                                                                                                                  MerlinServiceException, IOException;
  }

  interface OwnerRole extends ReaderRole, WriterRole {}
}
