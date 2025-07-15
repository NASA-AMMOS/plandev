package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.ammos.aerie.procedural.scheduling.ActivityAutoDelete;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.ammos.aerie.procedural.scheduling.utils.DefaultEditablePlanDriver;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit;
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.plan.SchedulerPlanEditAdapter;
import gov.nasa.jpl.aerie.scheduler.plan.SchedulerToProcedurePlanAdapter;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.scheduler.solver.ConflictSatisfaction;
import gov.nasa.jpl.aerie.scheduler.solver.Evaluation;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.scheduler.plan.SchedulerPlanEditAdapter.toSchedulingActivity;

public class Procedure extends Goal {
  private final Path jarPath;
  private final Map<String, SerializedValue> args;

  private gov.nasa.ammos.aerie.procedural.scheduling.Goal goal;

  private ActivityAutoDelete shouldDelete;
  private final GoalId goalId;

  public Procedure(
      final PlanningHorizon planningHorizon,
      Path jarPath,
      Map<String, SerializedValue> args,
      boolean simulateAfter,
      GoalId goalId
  ) {
    this.simulateAfter = simulateAfter;
    this.planHorizon = planningHorizon;
    this.jarPath = jarPath;
    this.args = args;
    this.goalId = goalId;
  }

  public boolean deleteAtBeginning(
      final Problem problem,
      final Plan plan,
      final MissionModel<?> missionModel,
      final Function<String, ActivityType> lookupActivityType,
      final SimulationFacade simulationFacade,
      final DirectiveIdGenerator idGenerator,
      Map<String, List<ExternalEvent>> eventsByDerivationGroup
  ) {
    final var planAdapter = new SchedulerToProcedurePlanAdapter(
        plan,
        planHorizon,
        eventsByDerivationGroup,
        problem.getDiscreteExternalProfiles(),
        problem.getRealExternalProfiles()
    );

    final var editAdapter = new SchedulerPlanEditAdapter(
        missionModel,
        idGenerator,
        planAdapter,
        simulationFacade,
        lookupActivityType::apply
    );
    final var editablePlan = new DefaultEditablePlanDriver(editAdapter);

    final var simResults = editablePlan.latestResults();

    instantiateGoal();

    this.shouldDelete = this.goal.shouldDeletePastCreations(editablePlan, simResults);

    if (shouldDelete instanceof ActivityAutoDelete.AtBeginning ab) {
      deletePastCreations(editablePlan, ab.getAnchorStrategy(), problem.sourceSchedulingGoals);
      return ab.getSimulateAfter();
    }
    return false;
  }

  public void run(
      final Problem problem,
      final Evaluation eval,
      final Plan plan,
      final MissionModel<?> missionModel,
      final Function<String, ActivityType> lookupActivityType,
      final SimulationFacade simulationFacade,
      final DirectiveIdGenerator idGenerator,
      Map<String, List<ExternalEvent>> eventsByDerivationGroup
  ) {
    instantiateGoal();

    List<SchedulingActivity> newActivities = new ArrayList<>();

    final var planAdapter = new SchedulerToProcedurePlanAdapter(
        plan,
        planHorizon,
        eventsByDerivationGroup,
        problem.getDiscreteExternalProfiles(),
        problem.getRealExternalProfiles()
    );

    final var editAdapter = new SchedulerPlanEditAdapter(
        missionModel,
        idGenerator,
        planAdapter,
        simulationFacade,
        lookupActivityType::apply
    );

    final var editablePlan = new DefaultEditablePlanDriver(editAdapter);

    if (shouldDelete instanceof ActivityAutoDelete.JustBefore jb) {
      deletePastCreations(editablePlan, jb.getAnchorStrategy(), problem.sourceSchedulingGoals);
    }

    this.goal.run(editablePlan);

    if (editablePlan.isDirty()) {
      throw new IllegalStateException("procedural goal %s had changes that were not committed or rolled back".formatted(jarPath.getFileName()));
    }
    for (final var edit : editablePlan.getTotalDiff()) {
      if (edit instanceof Edit.Create c) {
        newActivities.add(toSchedulingActivity(c.getDirective(), lookupActivityType::apply));
      } else if (!(edit instanceof Edit.Delete)) {
        throw new IllegalStateException("Unexpected edit type: " + edit);
      }
    }

    final var evaluation = eval.forGoal(this);
    for (final var activity : newActivities) {
      evaluation.associate(activity, true, null);
    }
    evaluation.setConflictSatisfaction(null, ConflictSatisfaction.SAT);
  }

  private void instantiateGoal() {
    if (this.goal == null) {
      final ProcedureMapper<?> procedureMapper;
      try {
        procedureMapper = ProcedureLoader.loadProcedure(jarPath);
      } catch (ProcedureLoader.ProcedureLoadException e) {
        throw new RuntimeException(e);
      }
      this.goal = procedureMapper.deserialize(SerializedValue.of(this.args));
    }
  }

  private void deletePastCreations(
      final DefaultEditablePlanDriver plan,
      final DeletedAnchorStrategy strategy,
      final Map<ActivityDirectiveId, GoalId> sourceSchedulingGoals
  ) {
    for (final var activity: ((SchedulerPlanEditAdapter) plan.getAdapter()).getPlan().getActivities()) {
      final var goalId = sourceSchedulingGoals.getOrDefault(activity.id(), null);
      if (goalId != null && goalId.goalInvocationId().equals(this.goalId.goalInvocationId())) {
        plan.delete(activity.id(), strategy);
      }
    }
  }
}
