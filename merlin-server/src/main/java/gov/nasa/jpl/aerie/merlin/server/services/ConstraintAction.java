package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.*;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.*;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

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
   * Update the parameter schema of a procedural constraint's definition
   * @param constraintId The id of the constraint's metadata
   * @param revision The definition to be updated
   */
  public void refreshConstraintProcedureParameterTypes(long constraintId, long revision) {
    constraintService.refreshConstraintProcedureParameterTypes(constraintId, revision);
  }

  /**
   * Check the constraints on a plan's specification for violations.
   *
   * @param planId The plan to check.
   * @param simulationDatasetId If provided, the id of the simulation dataset to check constraints against.
   * Defaults to the latest simulation of the plan
   * @param force If true, ignore cached values and rerun all constraints.
   * @param userSession The Hasura Session that made the request.
   * @return A mapping of each constraint and its result.
   * @throws NoSuchPlanException If the plan does not exist.
   * @throws MissionModelService.NoSuchMissionModelException If the plan's mission model does not exist.
   * @throws SimulationDatasetMismatchException If the specified simulation is not a simulation of the specified plan.
   */
  public Pair<Integer, Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>>> getViolations(
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final boolean force,
      final HasuraAction.Session userSession
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

    final var constraints = new ArrayList<>(this.planService.getConstraintsForPlan(planId));
    final var constraintResultMap = new HashMap<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>>();

    // Load cached results if the force rerun flag is not set
    final var validConstraintRuns = force ? new HashMap<ConstraintRecord, ConstraintResult>() :
        this.constraintService.getValidConstraintRuns(constraints, simDatasetId);

    // Remove any constraints that we've already checked, so they aren't rechecked.
    for (var entry : validConstraintRuns.entrySet()) {
        final var constraint = entry.getKey();
        final var cachedResult = entry.getValue();
        constraints.remove(constraint);
        constraintResultMap.put(constraint, Fallible.of(cachedResult));
    }

    // If the lengths don't match we need check the left-over constraints.
    if (!constraints.isEmpty()) {
      final var externalDatasets = this.planService.getExternalDatasets(planId, simDatasetId);
      final var realExternalProfiles = new HashMap<String, LinearProfile>();
      final var discreteExternalProfiles = new HashMap<String, DiscreteProfile>();

      for (final var pair : externalDatasets) {
        final var offsetFromSimulationStart = pair.getLeft().minus(plan.simulationOffset());
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

      // try to compile and run the constraint that were not
      // successful and cached in the past


      //compile
      final var compiledConstraints = new ArrayList<ExecutableConstraint>();
      for (final var constraint : constraints) {
        switch (constraint.type()) {
          case ConstraintType.EDSL e -> {
            final var compilationResult = tryCompileEDSLConstraint(
                plan.missionModelId(),
                planId,
                simDatasetId,
                constraint);

            if (compilationResult.isFailure()) {
              final Fallible<ConstraintResult, List<? extends Exception>> r = Fallible.failure(compilationResult.getFailure().errors(), compilationResult.getMessage());
              constraintResultMap.put(constraint, r);
              continue;
            }

            compiledConstraints.add(new ExecutableConstraint.EDSLConstraint(constraint, compilationResult.get()));
          }
          case ConstraintType.JAR j -> compiledConstraints.add(new ExecutableConstraint.JARConstraint(constraint));
        }
      }

      // sort constraints
      Collections.sort(compiledConstraints);

      // prepare simulation results -- all resources need to be fetched ahead of time as it is unknown what profiles
      //    a procedural constraint will access
      final var merlinSimResults = resultsHandle.getSimulationResults();
      final var edslSimResults = new SimulationResults(merlinSimResults);
      final var environment = new EvaluationEnvironment(realExternalProfiles, discreteExternalProfiles);

      final var timelinePlan = new ReadonlyPlan(plan, environment);
      final var timelineSimResults = new ReadonlyProceduralSimResults(merlinSimResults, timelinePlan);


      // run constraints
      for(final var constraint : compiledConstraints) {
        final var record = constraint.record();
        try {
          switch (constraint) {
            case ExecutableConstraint.EDSLConstraint edsl: {
              constraintResultMap.put(record, Fallible.of(edsl.run(edslSimResults, environment)));
              break;
            }
            case ExecutableConstraint.JARConstraint jar: {
              constraintResultMap.put(record, Fallible.of(jar.run(timelinePlan, timelineSimResults, merlinSimResults)));
              break;
            }
          }
        } catch (Exception e) {
          constraintResultMap.put(record, Fallible.failure(List.of(e), e.getMessage()));
        }
      }
    }

    // Store the outcome of the constraint run
    final var requestId = constraintService.createConstraintRuns(
        new ConstraintRequestConfiguration(planId, simDatasetId, force, userSession.hasuraUserId()),
        constraintResultMap);

    return Pair.of(requestId, constraintResultMap);
  }

  /**
   * Attempt to compile an EDSL Constraint.
   * @param modelId The mission model id to get activity and resource types from.
   * @param planId The plan id to get external resource types from.
   * @param simDatasetId The simulation dataset id to filter external resource types on.
   * @param constraint The constraint to be compiled.
   * @return
   *    On success, return a {@code Fallible<Expression<EDSLConstraintResult>>} containing the compiled constraint code
   *      in a form that can be evaluated against simulation results.
   *    On failure, return a {@code Fallible<Error>} in the Failure state containing the compilation error.
   */
  private Fallible<Expression<EDSLConstraintResult>, ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error> tryCompileEDSLConstraint(
      MissionModelId modelId,
      PlanId planId,
      SimulationDatasetId simDatasetId,
      ConstraintRecord constraint
  ) {
    final ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult constraintCompilationResult;
    try {
      constraintCompilationResult = constraintsDSLCompilationService.compileConstraintsDSL(
          modelId,
          Optional.of(planId),
          Optional.of(simDatasetId),
          ((ConstraintType.EDSL) constraint.type()).definition()
      );
    } catch (MissionModelService.NoSuchMissionModelException | NoSuchPlanException ex) {
      return Fallible.failure(
          new ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error(
             List.of(
                 new ConstraintsCompilationError(
                     "Constraint '" +constraint.name()+ "' compilation failed:\n " + ex.getMessage(),
                     ex.toString(),
                     new ConstraintsCompilationError.CodeLocation(0,0),
                     ex.toString()))));
    }

    // Try to compile the constraint and capture failures
    if (constraintCompilationResult instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Success success) {
      return Fallible.of(success.constraintExpression());
    } else if (constraintCompilationResult instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error error) {
      // Add the leading error message to the errors
      error.errors().forEach(e -> e.prependMessage("Constraint '" + constraint.name() + "' compilation failed:\n "));
      return Fallible.failure(error, "Constraint '" + constraint.name() + "' compilation failed:\n ");
    } else {
      return Fallible.failure(
          new ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error(List.of(
              new ConstraintsCompilationError(
                  "Unhandled variant of ConstraintsDSLCompilationResult: " + constraintCompilationResult, "",
                  new ConstraintsCompilationError.CodeLocation(0, 0),
                  ""))));
    }
  }
}
