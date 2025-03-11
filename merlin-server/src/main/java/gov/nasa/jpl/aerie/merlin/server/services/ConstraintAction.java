package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.*;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.*;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.ConstraintRunRecord;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class ConstraintAction {
  private final ConstraintsDSLCompilationService constraintsDSLCompilationService;
  private final ConstraintService constraintService;
  private final PlanService planService;
  private final SimulationService simulationService;

  public ConstraintAction(
      final ConstraintsDSLCompilationService constraintsDSLCompilationService,
      final ConstraintService constraintService,
      final PlanService planService,
      final SimulationService simulationService
  ) {
    this.constraintsDSLCompilationService = constraintsDSLCompilationService;
    this.constraintService = constraintService;
    this.planService = planService;
    this.simulationService = simulationService;
  }

  /**
   * Check the constraints on a plan's specification for violations.
   *
   * @param planId The plan to check.
   * @param simulationDatasetId If provided, the id of the simulation dataset to check constraints against.
   * Defaults to the latest simulation of the plan
   * @param force If true, ignore cached values and rerun all constraints.
   * @return A mapping of each constraint and its result.
   * @throws NoSuchPlanException If the plan does not exist.
   * @throws MissionModelService.NoSuchMissionModelException If the plan's mission model does not exist.
   * @throws SimulationDatasetMismatchException If the specified simulation is not a simulation of the specified plan.
   */
  public Map<ConstraintRecord, Fallible<?>> getViolations(
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final boolean force
  ) throws NoSuchPlanException, MissionModelService.NoSuchMissionModelException, SimulationDatasetMismatchException {
    final var plan = this.planService.getPlanForValidation(planId);

    // Get a Handle for the Simulation Results
    final SimulationResultsHandle resultsHandle;
    if (simulationDatasetId.isPresent()) {
      resultsHandle = this.simulationService.get(planId, simulationDatasetId.get())
                                            .orElseThrow(() -> new InputMismatchException(
                                                "simulation dataset with id `"
                                                + simulationDatasetId.get().id()
                                                + "` does not exist"));
    } else {
      final var revisionData = this.planService.getPlanRevisionData(planId);
      resultsHandle = this.simulationService.get(planId, revisionData)
                                            .orElseThrow(() -> new InputMismatchException(
                                                "plan with id "
                                                + planId.id()
                                                + " has not yet been simulated at its current revision"));
    }

    final SimulationDatasetId simDatasetId = resultsHandle.getSimulationDatasetId();

    final var constraintCode = new HashMap<>(this.planService.getConstraintsForPlan(planId));
    final var constraintResultMap = new HashMap<Constraint, Fallible<?>>();

    final var validConstraintRuns = this.constraintService.getValidConstraintRuns(constraintCode, simDatasetId);

    // Remove any constraints that we've already checked, so they aren't rechecked.
    for (ConstraintRunRecord constraintRun : validConstraintRuns.values()) {
        constraintResultMap.put(constraintCode.remove(constraintRun.constraintId()), Fallible.of(constraintRun.result()));
    }

    // If the lengths don't match we need check the left-over constraints.
    if (!constraintCode.isEmpty()) {
      final var simStartTime = resultsHandle.startTime();
      final var simDuration = resultsHandle.duration();
      final var simOffset = plan.simulationOffset();

      final var activities = new ArrayList<ActivityInstance>();
      final var simulatedActivities = resultsHandle.getSimulatedActivities();
      for (final var entry : simulatedActivities.entrySet()) {
        final var id = entry.getKey();
        final var activity = entry.getValue();

        final var activityOffset = Duration.of(
            simStartTime.until(activity.start(), ChronoUnit.MICROS),
            Duration.MICROSECONDS);

        activities.add(new ActivityInstance(
            id.id(),
            activity.type(),
            activity.arguments(),
            Interval.between(activityOffset, activityOffset.plus(activity.duration()))));
      }

      final var externalDatasets = this.planService.getExternalDatasets(planId, simDatasetId);
      final var realExternalProfiles = new HashMap<String, LinearProfile>();
      final var discreteExternalProfiles = new HashMap<String, DiscreteProfile>();

      for (final var pair : externalDatasets) {
        final var offsetFromSimulationStart = pair.getLeft().minus(simOffset);
        final var profileSet = pair.getRight();

        for (final var profile : profileSet.discreteProfiles().entrySet()) {
          discreteExternalProfiles.put(
              profile.getKey(),
              DiscreteProfile.fromExternalProfile(
                  offsetFromSimulationStart,
                  profile.getValue().segments()));
        }
        for (final var profile : profileSet.realProfiles().entrySet()) {
          realExternalProfiles.put(
              profile.getKey(),
              LinearProfile.fromExternalProfile(
                  offsetFromSimulationStart,
                  profile.getValue().segments()));
        }
      }

      final var environment = new EvaluationEnvironment(realExternalProfiles, discreteExternalProfiles);

      final var realProfiles = new HashMap<String, LinearProfile>();
      final var discreteProfiles = new HashMap<String, DiscreteProfile>();

      // try to compile and run the constraint that were not
      // successful and cached in the past
      for (final var entry : constraintCode.entrySet()) {
        final var constraint = entry.getValue();
        final Expression<ConstraintResult> expression;

        final ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult constraintCompilationResult;
        try {
          constraintCompilationResult = constraintsDSLCompilationService.compileConstraintsDSL(
              plan.missionModelId(),
              Optional.of(planId),
              Optional.of(simDatasetId),
              constraint.definition()
          );
        } catch (MissionModelService.NoSuchMissionModelException | NoSuchPlanException ex) {
          constraintResultMap.put(
              constraint,
              Fallible.failure(new Error("Constraint " + constraint.name() + ": " + ex.getMessage())));
          continue;
        }

        // Try to compile the constraint and capture failures
        if (constraintCompilationResult instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Success success) {
          expression = success.constraintExpression();
        } else if (constraintCompilationResult instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error error) {
          constraintResultMap.put(
              constraint,
              Fallible.failure(error, "Constraint '" + constraint.name() + "' compilation failed:\n "));
          continue;
        } else {
          constraintResultMap.put(
              constraint,
              Fallible.failure(
                  new ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error(
                      new ArrayList<>() {{
                        add(new ConstraintsCompilationError.UserCodeError(
                            "Unhandled variant of ConstraintsDSLCompilationResult: "
                            + constraintCompilationResult,
                            "",
                            new ConstraintsCompilationError.CodeLocation(
                                0,
                                0),
                            ""));
                      }})));
          continue;
        }

        final var names = new HashSet<String>();
        expression.extractResources(names);

        final var newNames = new HashSet<String>();
        for (final var name : names) {
          if (!realProfiles.containsKey(name) && !discreteProfiles.containsKey(name)) {
            newNames.add(name);
          }
        }

        if (!newNames.isEmpty()) {
          try {
            final var newProfiles = resultsHandle$
                .map($ -> $.getProfiles(new ArrayList<>(newNames)))
                .orElseThrow(() -> new InputMismatchException("no simulation results found for plan id "
                                                              + planId.id()));

            for (final var _entry : ProfileSet.unwrapOptional(newProfiles.realProfiles()).entrySet()) {
              if (!realProfiles.containsKey(_entry.getKey())) {
                realProfiles.put(_entry.getKey(), LinearProfile.fromSimulatedProfile(_entry.getValue().segments()));
              }
            }

            for (final var _entry : ProfileSet.unwrapOptional(newProfiles.discreteProfiles()).entrySet()) {
              if (!discreteProfiles.containsKey(_entry.getKey())) {
                discreteProfiles.put(
                    _entry.getKey(),
                    DiscreteProfile.fromSimulatedProfile(_entry.getValue().segments()));
              }
            }
          } catch (InputMismatchException ex) {
            constraintResultMap.put(constraint, Fallible.failure(ex));
            continue;
          }
        }

        final Interval bounds = Interval.between(Duration.ZERO, simDuration);
        final var preparedResults = new gov.nasa.jpl.aerie.constraints.model.SimulationResults(
            simStartTime,
            bounds,
            activities,
            realProfiles,
            discreteProfiles);

        ConstraintResult constraintResult = expression.evaluate(preparedResults, environment);

        constraintResult.constraintName = entry.getValue().name();
        constraintResult.constraintRevision = entry.getValue().revision();
        constraintResult.constraintId = entry.getKey();
        constraintResult.resourceIds = List.copyOf(names);

        constraintResultMap.put(constraint, Fallible.of(constraintResult));


      }
      // Filter for constraints that were compiled and ran with results
      // convert these successful failables to ConstraintResults
      final var compiledConstraintMap = constraintResultMap.entrySet().stream()
                                                           .filter(set -> {
                                                             Fallible<?> fallible = set.getValue();
                                                             return !fallible.isFailure() && (fallible
                                                                                                  .getOptional()
                                                                                                  .isPresent()
                                                                                              && fallible
                                                                                                  .getOptional()
                                                                                                  .get() instanceof ConstraintResult);
                                                           })
                                                           .collect(Collectors.toMap(
                                                               entry -> entry.getKey().id(),
                                                               set -> (ConstraintResult) set
                                                                   .getValue()
                                                                   .getOptional()
                                                                   .get()));

      // Use the constraints that were compiled and ran with results
      // to filter out the constraintCode map to match
      final var compileConstraintCode =
          constraintCode.entrySet().stream().filter(set -> compiledConstraintMap.containsKey(set.getKey())).collect(
              Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

      // Only update the db when constraints were compiled and ran with results.
      constraintService.createConstraintRuns(
          compileConstraintCode,
          compiledConstraintMap,
          simDatasetId);
    }

    return constraintResultMap;
  }
}
