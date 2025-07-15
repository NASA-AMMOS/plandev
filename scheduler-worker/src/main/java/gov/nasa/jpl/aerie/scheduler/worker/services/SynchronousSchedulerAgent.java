package gov.nasa.jpl.aerie.scheduler.worker.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerPlugin;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingCondition;
import gov.nasa.jpl.aerie.scheduler.server.ResultsProtocol;
import gov.nasa.jpl.aerie.scheduler.server.config.PlanOutputMode;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.ResultsProtocolFailure;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.SpecificationLoadException;
import gov.nasa.jpl.aerie.scheduler.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.scheduler.server.http.ResponseSerializers;
import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.models.ExternalProfiles;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalInvocationRecord;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalSource;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalType;
import gov.nasa.jpl.aerie.scheduler.server.models.MerlinPlan;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanMetadata;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingDSL;
import gov.nasa.jpl.aerie.scheduler.server.models.Specification;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.GoalBuilder;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinDatabaseService;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinServiceException;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleRequest;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleResults;
import gov.nasa.jpl.aerie.scheduler.server.services.SchedulerAgent;
import gov.nasa.jpl.aerie.scheduler.server.services.SpecificationService;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationData;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * agent that handles posed scheduling requests by blocking the requester thread until scheduling is complete
 *
 * @param merlinDatabaseService interface for querying plan and mission model details from merlin
 * @param modelJarsDir path to parent directory for mission model jars (interim backdoor jar file access)
 * @param outputMode how the scheduling output should be returned to aerie (eg overwrite or new container)
 */
//TODO: will eventually need scheduling goal service arg to pull goals from scheduler's own data store
public record SynchronousSchedulerAgent(
    SpecificationService specificationService,
    MerlinDatabaseService.OwnerRole merlinDatabaseService,
    Path modelJarsDir,
    PlanOutputMode outputMode,
    SchedulingDSLCompilationService schedulingDSLCompilationService
)
    implements SchedulerAgent
{
  private static final Logger LOGGER = LoggerFactory.getLogger(SynchronousSchedulerAgent.class);

  public SynchronousSchedulerAgent {
    Objects.requireNonNull(merlinDatabaseService);
    Objects.requireNonNull(modelJarsDir);
    Objects.requireNonNull(schedulingDSLCompilationService);
  }

  /**
   * {@inheritDoc}
   *
   * consumes any ResultsProtocolFailure exception generated by the scheduling process and writes its message as a
   * failure reason to the given output port (eg aerie could not be reached, mission model could not be loaded from jar
   * file, requested plan revision has changed in the database, scheduler could not find a solution, etc).
   * Any remaining exceptions passed upward represent fatal service configuration problems
   */
  @Override
  public void schedule(
      final ScheduleRequest request,
      final ResultsProtocol.WriterRole writer,
      final Supplier<Boolean> canceledListener,
      final int sizeCachedEngineStore
  ) {
    try(final var cachedEngineStore = new InMemoryCachedEngineStore(sizeCachedEngineStore)) {
      //confirm requested plan to schedule from/into still exists at targeted version (request could be stale)
      //TODO: maybe some kind of high level db transaction wrapping entire read/update of target plan revision

      final var specification = specificationService.getSpecification(request.specificationId());
      final var planMetadata = merlinDatabaseService.getPlanMetadata(specification.planId());
      ensurePlanRevisionMatch(specification, planMetadata.planRev());
      ensureRequestIsCurrent(specification, request);
      //create scheduler problem seeded with initial plan
      final var schedulerMissionModel = loadMissionModel(planMetadata);
      final var planningHorizon = new PlanningHorizon(
          specification.horizonStartTimestamp().toInstant(),
          specification.horizonEndTimestamp().toInstant()
      );
      final var simulationFacade = new CheckpointSimulationFacade(
          schedulerMissionModel.missionModel(),
          schedulerMissionModel.schedulerModel(),
          cachedEngineStore,
          planningHorizon,
          new SimulationEngineConfiguration(
              planMetadata.modelConfiguration(),
              planMetadata.horizon().getStartInstant(),
              new MissionModelId(planMetadata.modelId())),
          canceledListener
      );
      final var oldActivityIdToGoalId = merlinDatabaseService.getActivityIdToGoalIdMap(specification.planId());
      final var problem = new Problem(
          schedulerMissionModel.missionModel(),
          planningHorizon,
          simulationFacade,
          schedulerMissionModel.schedulerModel(),
          oldActivityIdToGoalId
      );
      final var externalProfiles = loadExternalProfiles(planMetadata.planId());
      final var externalEventsByDerivationGroup = loadExternalEvents(planMetadata.planId(), planMetadata.horizon().getStartInstant());
      final var initialSimulationResultsAndDatasetId = loadSimulationResults(planMetadata);
      //seed the problem with the initial plan contents
      final var loadedPlanComponents = loadInitialPlan(planMetadata, problem,
                                                       initialSimulationResultsAndDatasetId.map(Pair::getKey));
      problem.setInitialPlan(loadedPlanComponents.schedulerPlan(), initialSimulationResultsAndDatasetId.map(Pair::getKey));
      problem.setExternalProfile(externalProfiles.realProfiles(), externalProfiles.discreteProfiles());
      problem.setEventsByDerivationGroup(externalEventsByDerivationGroup);
      //apply constraints/goals to the problem
      final var compiledGlobalSchedulingConditions = new ArrayList<SchedulingCondition>();
      final var failedGlobalSchedulingConditions = new ArrayList<List<SchedulingCompilationError.UserCodeError>>();
      specification.schedulingConditions().forEach($ -> {
        final var result = schedulingDSLCompilationService.compileGlobalSchedulingCondition(
            merlinDatabaseService,
            planMetadata.planId(),
            $.source().source(),
            externalProfiles.resourceTypes());
        if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.ConditionSpecifier> r) {
          compiledGlobalSchedulingConditions.addAll(conditionBuilder(r.value(), problem));
        } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.ConditionSpecifier> r) {
          failedGlobalSchedulingConditions.add(r.errors());
        } else {
          throw new Error("Unhandled variant of %s: %s".formatted(
              SchedulingDSLCompilationService.SchedulingDSLCompilationResult.class.getSimpleName(),
              result));
        }
      });

      if (!failedGlobalSchedulingConditions.isEmpty()) {
        writer.failWith(b -> b
            .type("GLOBAL_SCHEDULING_CONDITIONS_FAILED")
            .message("Global scheduling condition%s failed".formatted(failedGlobalSchedulingConditions.size() > 1
                                                                          ? "s"
                                                                          : ""))
            .data(ResponseSerializers.serializeFailedGlobalSchedulingConditions(failedGlobalSchedulingConditions)));
        return;
      }

      compiledGlobalSchedulingConditions.forEach(problem::add);

      final var orderedGoals = new ArrayList<Goal>();
      final var goals = new HashMap<Goal, GoalId>();
      final var compiledGoals = new ArrayList<Pair<GoalInvocationRecord, SchedulingDSL.GoalSpecifier>>();
      final var failedGoals = new ArrayList<Pair<GoalId, List<SchedulingCompilationError.UserCodeError>>>();
      for (final var goalRecord : specification.goalsByPriority()) {
        switch (goalRecord.type()) {
          case GoalType.EDSL edsl -> {
            final var result = compileGoalDefinition(
                merlinDatabaseService,
                planMetadata.planId(),
                edsl.source(),
                schedulingDSLCompilationService,
                externalProfiles.resourceTypes());
            if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
              compiledGoals.add(Pair.of(goalRecord, r.value()));
            } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
              failedGoals.add(Pair.of(goalRecord.id(), r.errors()));
            } else {
              throw new Error("Unhandled variant of %s: %s".formatted(
                  SchedulingDSLCompilationService.SchedulingDSLCompilationResult.class.getSimpleName(),
                  result));
            }
          }
          case GoalType.JAR jar -> {
            compiledGoals.add(Pair.of(goalRecord, new SchedulingDSL.GoalSpecifier.Procedure(modelJarsDir.resolve(jar.path()), goalRecord.args())));
          }
        }
      }
      if (!failedGoals.isEmpty()) {
        writer.failWith(b -> b
            .type("SCHEDULING_GOALS_FAILED")
            .message("Scheduling goal%s failed".formatted(failedGoals.size() > 1 ? "s" : ""))
            .data(ResponseSerializers.serializeFailedGoals(failedGoals)));
        return;
      }
      for (final var compiledGoal : compiledGoals) {
        final var goal = GoalBuilder
            .goalOfGoalSpecifier(
                compiledGoal.getValue(),
                specification.horizonStartTimestamp(),
                specification.horizonEndTimestamp(),
                problem::getActivityType,
                compiledGoal.getKey().simulateAfter(),
                compiledGoal.getKey().id()
            );
        orderedGoals.add(goal);
        goals.put(goal, compiledGoal.getKey().id());
        }
        problem.setGoals(orderedGoals);

      final var scheduler = new PrioritySolver(problem, specification.analysisOnly());
      //run the scheduler to find a solution to the posed problem, if any
      final var solutionPlan = scheduler.getNextSolution().orElseThrow(
          () -> new ResultsProtocolFailure("scheduler returned no solution"));

      final var newActivityToGoalId = new HashMap<SchedulingActivity, GoalId>();
      for (final var entry : solutionPlan.getEvaluation().getGoalEvaluations().entrySet()) {
        for (final var activity : entry.getValue().getInsertedActivities()) {
          newActivityToGoalId.put(activity, goals.get(entry.getKey()));
        }
      }
      //store the solution plan back into merlin (and reconfirm no intervening mods!)
      //TODO: make revision confirmation atomic part of plan mutation (plan might have been modified during scheduling!)
      ensurePlanRevisionMatch(specification, getMerlinPlanRev(specification.planId()));
      final var uploadIdMap = storeFinalPlan(
          planMetadata,
          loadedPlanComponents.merlinPlan(),
          solutionPlan,
          newActivityToGoalId,
          schedulerMissionModel.schedulerModel()
      );

      final var planMetadataAfterChanges = merlinDatabaseService.getPlanMetadata(specification.planId());
      Optional<DatasetId> datasetId = initialSimulationResultsAndDatasetId.map(Pair::getRight);
      final var lastGoalSimulateAfter = !problem.getGoals().isEmpty() && problem.getGoals().getLast().simulateAfter;
      if(lastGoalSimulateAfter && planMetadataAfterChanges.planRev() != specification.planRevision()) {
        datasetId = storeSimulationResults(
            simulationFacade.simulateWithResults(solutionPlan, planningHorizon.getEndAerie()),
            planMetadataAfterChanges,
            uploadIdMap
        );
      } else if (simulationFacade.getLatestSimulationData().isPresent()
                 && problem.getInitialSimulationResults().isPresent()
                 && simulationFacade.getLatestSimulationData().get() != problem.getInitialSimulationResults().get()) {
        final var latest = simulationFacade.getLatestSimulationData().get();
        datasetId = storeSimulationResults(
            latest,
            planMetadataAfterChanges,
            uploadIdMap
        );
      }


      //collect results and notify subscribers of success
      final var results = collectResults(solutionPlan, uploadIdMap, goals);
      LOGGER.info("Simulation cache saved " + cachedEngineStore.getTotalSavedSimulationTime() + " in simulation time");
      writer.succeedWith(results, datasetId);
    } catch (final SpecificationLoadException e) {
      writer.failWith(b -> b
          .type("SPECIFICATION_LOAD_EXCEPTION")
          .message(e.toString())
          .data(SchedulingCompilationError.schedulingErrorJsonP.unparse(e.errors))
          .trace(e));
    } catch (final ResultsProtocolFailure e) {
      writer.failWith(b -> b
          .type("RESULTS_PROTOCOL_FAILURE")
          .message(e.toString())
          .trace(e));
    } catch (final NoSuchSpecificationException e) {
      writer.failWith(b -> b
          .type("NO_SUCH_SPECIFICATION")
          .message(e.toString())
          .data(ResponseSerializers.serializeNoSuchSpecificationException(e))
          .trace(e));
    } catch (final NoSuchPlanException e) {
      writer.failWith(b -> b
          .type("NO_SUCH_PLAN")
          .message(e.toString())
          .data(ResponseSerializers.serializeNoSuchPlanException(e))
          .trace(e));
    } catch (final MerlinServiceException e) {
      writer.failWith(b -> b
          .type("PLAN_SERVICE_EXCEPTION")
          .message(e.toString())
          .trace(e));
    } catch (final IOException e) {
      writer.failWith(b -> b
          .type("IO_EXCEPTION")
          .message(e.toString())
          .trace(e));
    } catch (SchedulingInterruptedException e) {
      writer.reportCanceled(e);
    } catch (Exception e) {
      writer.failWith(b -> b
          .type("OTHER_EXCEPTION")
          .message(e.toString())
          .trace(e));
    }
  }

  private Optional<Pair<SimulationResults, DatasetId>> loadSimulationResults(final PlanMetadata planMetadata){
    try {
      return merlinDatabaseService.getSimulationResults(planMetadata);
    } catch (MerlinServiceException | IOException | InvalidJsonException e) {
      throw new ResultsProtocolFailure(e);
    }
  }

  private ExternalProfiles loadExternalProfiles(final PlanId planId)
  throws MerlinServiceException, IOException
  {
    return merlinDatabaseService.getExternalProfiles(planId);
  }

  private Map<String, List<ExternalEvent>> loadExternalEvents(final PlanId planId, final Instant horizonStart)
  throws MerlinServiceException, IOException
  {
    return merlinDatabaseService.getExternalEvents(planId, horizonStart);
  }

  private Optional<DatasetId> storeSimulationResults(
      SimulationData simulationData,
      PlanMetadata planMetadata,
      Map<ActivityDirectiveId, ActivityDirectiveId> uploadIdMap
  )
  throws MerlinServiceException, IOException, SchedulingInterruptedException {
    return Optional.of(merlinDatabaseService.storeSimulationResults(planMetadata, simulationData.driverResults(), uploadIdMap));
  }

  private static SchedulingDSLCompilationService.SchedulingDSLCompilationResult<SchedulingDSL.GoalSpecifier> compileGoalDefinition(
      final MerlinDatabaseService.ReaderRole merlinDatabaseService,
      final PlanId planId,
      final GoalSource source,
      final SchedulingDSLCompilationService schedulingDSLCompilationService,
      final Collection<ResourceType> additionalResourceTypes)
  {
    return schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        planId,
        source.source(),
        additionalResourceTypes
    );
  }

  private void ensurePlanRevisionMatch(final Specification specification, final long actualPlanRev) {
    if (actualPlanRev != specification.planRevision()) {
      throw new ResultsProtocolFailure("plan with id %s at revision %d is no longer at revision %d".formatted(
          specification.planId(), actualPlanRev, specification.planRevision()));
    }
  }
  /**
   * fetch just the current revision number of the target plan from aerie services
   *
   * @param planId identifier of the target plan to load metadata for
   * @return the current revision number of the target plan according to a fresh query
   * @throws ResultsProtocolFailure when the requested plan cannot be found, or aerie could not be reached
   */
  private long getMerlinPlanRev(final PlanId planId)
  throws MerlinServiceException, NoSuchPlanException, IOException
  {
    return merlinDatabaseService.getPlanRevision(planId);
  }

  /**
   * confirms that the scheduling request is still relevant
   * (spec hasn't been updated between request being made and now)
   *
   * @param request the original request for scheduling, containing an intended starting specification revision
   * @throws ResultsProtocolFailure when the requested specification revision does not match the actual revision
   */
  private void ensureRequestIsCurrent(final Specification specification, final ScheduleRequest request)
  throws NoSuchSpecificationException {
    if (specification.specificationRevision() != request.specificationRev().specificationRevision()) {
      throw new ResultsProtocolFailure("schedule specification with id %s is no longer at revision %d".formatted(
          request.specificationId(), request.specificationRev().specificationRevision()));
    }
  }

  /**
   * load the activity instance content of the specified merlin plan into scheduler-ready objects
   *
   * @param planMetadata metadata of plan container to load from
   * @param problem the problem that the plan adheres to
   * @param initialSimulationResults initial simulation results (optional)
   * @return a plan with all activity instances loaded from the target merlin plan container
   * @throws ResultsProtocolFailure when the requested plan cannot be loaded, or the target plan revision has
   *     changed, or aerie could not be reached
   */
  private PlanComponents loadInitialPlan(
      final PlanMetadata planMetadata,
      final Problem problem,
      final Optional<SimulationResults> initialSimulationResults) {
    //TODO: maybe paranoid check if plan rev has changed since original metadata?
    try {
      final var merlinPlan =  merlinDatabaseService.getPlanActivityDirectives(planMetadata, problem);
      final var plan = new PlanInMemory();
      final var activityTypes = problem.getActivityTypes().stream().collect(Collectors.toMap(ActivityType::getName, at -> at));
      for(final var elem : merlinPlan.getActivitiesById().entrySet()){
        final var activity = elem.getValue();
        final var id = elem.getKey();
        if(!activityTypes.containsKey(activity.serializedActivity().getTypeName())){
          throw new IllegalArgumentException("Activity type found in JSON object after request to merlin server has "
                                             + "not been found in types extracted from mission model. Probable "
                                             + "inconsistency between mission model used by scheduler server and "
                                             + "merlin server.");
        }
        final var schedulerActType = activityTypes.get(activity.serializedActivity().getTypeName());
        Duration actDuration = null;
        switch (schedulerActType.getDurationType()) {
          case DurationType.Controllable s -> {
            final var serializedDuration = activity.serializedActivity().getArguments().get(s.parameterName());
            if (serializedDuration != null) {
              actDuration = problem.getSchedulerModel().deserializeDuration(serializedDuration);
            }
          }
          case DurationType.Fixed fixedDurationType -> actDuration = fixedDurationType.duration();
          case DurationType.Parametric parametricDurationType ->
              actDuration = parametricDurationType.durationFunction().apply(activity
                                                                                .serializedActivity()
                                                                                .getArguments());
          case DurationType.Uncontrollable ignored -> {
            if (initialSimulationResults.isPresent()) {
              for (final var simAct : initialSimulationResults.get().simulatedActivities.entrySet()) {
                if (simAct.getValue().directiveId().isPresent() &&
                    simAct.getValue().directiveId().get().equals(id)) {
                  actDuration = simAct.getValue().duration();
                }
              }
            }
          }
          case null, default -> throw new Error("Unhandled variant of DurationType:"
                                                + schedulerActType.getDurationType());
        }
        final var act = SchedulingActivity.fromExistingActivityDirective(id, activity, schedulerActType, actDuration);
        plan.add(act);
      }
      return new PlanComponents(plan, merlinPlan);
    } catch (Exception e) {
      throw new ResultsProtocolFailure(e);
    }
  }

  record PlanComponents(Plan schedulerPlan, MerlinPlan merlinPlan) {}
  record SchedulerMissionModel(MissionModel<?> missionModel, SchedulerModel schedulerModel) {}

  /**
   * creates an instance of the mission model referenced by the specified plan
   *
   * @param plan metadata of the target plan indicating which mission model to load and how to configure the mission
   *     model for that plan data
   * @return instance of the mission model to extract any activity types, constraints, and simulations from
   * @throws ResultsProtocolFailure when the mission model could not be loaded: eg jar file not found, declared
   *     version/name in jar does not match, or aerie filesystem could not be mounted
   */
  private SchedulerMissionModel loadMissionModel(final PlanMetadata plan) {
    try {
      final var missionConfig = SerializedValue.of(plan.modelConfiguration());
      final var modelJarPath = modelJarsDir.resolve(plan.modelPath());
      return new SchedulerMissionModel(
          MissionModelLoader.loadMissionModel(plan.horizon().getStartInstant(), missionConfig, modelJarPath, plan.modelName(), plan.modelVersion()),
          loadSchedulerModelProvider(modelJarPath, plan.modelName(), plan.modelVersion()).getSchedulerModel());
    } catch (MissionModelLoader.MissionModelLoadException | SchedulerModelLoadException e) {
      throw new ResultsProtocolFailure(e);
    }
  }

  public static SchedulerPlugin loadSchedulerModelProvider(final Path path, final String name, final String version)
  throws MissionModelLoader.MissionModelLoadException, SchedulerModelLoadException
  {
    // Look for a MerlinMissionModel implementor in the mission model. For correctness, we're assuming there's
    // only one matching MerlinMissionModel in any given mission model.
    final var className = getImplementingClassName(path, name, version);

    // Construct a ClassLoader with access to classes in the mission model location.
    final var parentClassLoader = Thread.currentThread().getContextClassLoader();
    final URLClassLoader classLoader;
    try {
      classLoader = new URLClassLoader(new URL[] {path.toUri().toURL()}, parentClassLoader);
    } catch (MalformedURLException ex) {
      throw new Error(ex);
    }

    try {
      final var factoryClass$ = classLoader.loadClass(className);
      if (!SchedulerPlugin.class.isAssignableFrom(factoryClass$)) {
        throw new SchedulerModelLoadException(path, name, version);
      }

      // SAFETY: We checked above that SchedulerPlugin is assignable from this type.
      @SuppressWarnings("unchecked")
      final var factoryClass = (Class<? extends SchedulerPlugin>) factoryClass$;

      return factoryClass.getConstructor().newInstance();
    } catch (final ClassNotFoundException | NoSuchMethodException | InstantiationException
        | IllegalAccessException | InvocationTargetException ex)
    {
      throw new SchedulerModelLoadException(path, name, version, ex);
    }
  }

  public static String getImplementingClassName(final Path jarPath, final String name, final String version)
  throws SchedulerModelLoadException
  {
    try {
      final var jarFile = new JarFile(jarPath.toFile());
      final var jarEntry = jarFile.getEntry("META-INF/services/" + SchedulerPlugin.class.getCanonicalName());
      if (jarEntry == null) {
        throw new Error("JAR file `" + jarPath + "` did not declare a service called " + SchedulerPlugin.class.getCanonicalName());
      }
      final var inputStream = jarFile.getInputStream(jarEntry);

      final var classPathList = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.toList());

      if (classPathList.size() != 1) {
        throw new SchedulerModelLoadException(jarPath, name, version);
      }

      return classPathList.get(0);
    } catch (final IOException ex) {
      throw new SchedulerModelLoadException(jarPath, name, version, ex);
    }
  }

  public static class SchedulerModelLoadException extends Exception {
    private SchedulerModelLoadException(final Path path, final String name, final String version) {
      this(path, name, version, null);
    }

    private SchedulerModelLoadException(final Path path, final String name, final String version, final Throwable cause) {
      super(
          String.format(
              "No implementation found for `%s` at path `%s` wih name \"%s\" and version \"%s\"",
              SchedulerPlugin.class.getSimpleName(),
              path,
              name,
              version),
          cause);
    }
  }

  /**
   * place the modified activity plan back into the target merlin plan container
   *
   * this will obsolete the locally cached planMetadata since the plan revision will change!
   *
   * @param planMetadata metadata of plan container to store into; outdated after return
   * @param newPlan plan with all activity instances that should be stored to target merlin plan container
   * @throws ResultsProtocolFailure when the plan could not be stored to aerie, the target plan revision has
   *     changed, or aerie could not be reached
   */
  private Map<ActivityDirectiveId, ActivityDirectiveId> storeFinalPlan(
    final PlanMetadata planMetadata,
    final MerlinPlan initialPlan,
    final Plan newPlan,
    final Map<SchedulingActivity, GoalId> goalToActivity,
    final SchedulerModel schedulerModel
  ) {
    try {
      switch (this.outputMode) {
        case CreateNewOutputPlan -> {
          return merlinDatabaseService
              .createNewPlanWithActivityDirectives(planMetadata, newPlan, goalToActivity, schedulerModel).getValue();
        }
        case UpdateInputPlanWithNewActivities -> {
          return merlinDatabaseService.updatePlanActivityDirectives(
              planMetadata.planId(),
              initialPlan,
              newPlan,
              goalToActivity,
              schedulerModel
          );
        }
        default -> throw new IllegalArgumentException("unsupported scheduler output mode " + this.outputMode);
      }
    } catch (Exception e) {
      throw new ResultsProtocolFailure(e);
    }
  }

  public static List<SchedulingCondition> conditionBuilder(SchedulingDSL.ConditionSpecifier conditionSpecifier, Problem problem){
    if(conditionSpecifier instanceof SchedulingDSL.ConditionSpecifier.AndCondition andCondition){
      final var conditions = new ArrayList<SchedulingCondition>();
      andCondition.conditionSpecifiers().forEach( (condition) -> conditions.addAll(conditionBuilder(condition, problem)));
      return conditions;
    } else if(conditionSpecifier instanceof SchedulingDSL.ConditionSpecifier.GlobalSchedulingCondition globalSchedulingCondition){
      return List.of(new SchedulingCondition(
          globalSchedulingCondition.expression(),
          globalSchedulingCondition.activityTypes().stream().map((problem::getActivityType)).toList()));
    }
    throw new Error("Unhandled variant of %s: %s".formatted(SchedulingDSL.ConditionSpecifier.class.getSimpleName(), conditionSpecifier));
  }

  /**
   * collect output summary of the scheduling run
   *
   * depending on service configuration, this result may be cached and served to later requesters
   *
   * only reports one evaluation's score per goal, even if the goal is scored in multiple evaluations
   *
   * @param plan the target plan after the scheduling run has completed
   * @return summary of the state of the plan after scheduling ran; eg goal success metrics, associated instances, etc
   */
  private ScheduleResults collectResults(final Plan plan, Map<ActivityDirectiveId, ActivityDirectiveId> uploadIdMap, Map<Goal, GoalId> goalsToIds) {
    Map<GoalId, ScheduleResults.GoalResult> goalResults = new HashMap<>();
      for (var goalEval : plan.getEvaluation().getGoalEvaluations().entrySet()) {
        var goalId = goalsToIds.get(goalEval.getKey());
        //goal could be anonymous, a subgoal of a composite goal for example, and thus have no meaning for results sent back
        if(goalId != null) {
          final var goalResult = new ScheduleResults.GoalResult(
              goalEval
                  .getValue()
                  .getInsertedActivities().stream()
                  .map(SchedulingActivity::id)
                  .filter(Objects::nonNull)
                  .map(uploadIdMap::get)
                  .toList(),
              goalEval
                  .getValue()
                  .getAssociatedActivities().stream()
                  .map(SchedulingActivity::id)
                  .filter(Objects::nonNull)
                  .map(uploadIdMap::get)
                  .toList(),
              goalEval.getValue().getScore() >= 0);
          goalResults.put(goalId, goalResult);
        }
      }
    return new ScheduleResults(goalResults);
  }
}
