package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit;
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.plan.InMemoryEditablePlan;
import gov.nasa.jpl.aerie.scheduler.plan.SchedulerToProcedurePlanAdapter;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.scheduler.solver.ConflictSatisfaction;
import gov.nasa.jpl.aerie.scheduler.solver.Evaluation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.scheduler.plan.InMemoryEditablePlan.toSchedulingActivity;

public class Procedure extends Goal {
  private final Path jarPath;
  private final Map<String, SerializedValue> args;

  public Procedure(final PlanningHorizon planningHorizon, Path jarPath, Map<String, SerializedValue> args, boolean simulateAfter) {
    this.simulateAfter = simulateAfter;
    this.planHorizon = planningHorizon;
    this.jarPath = jarPath;
    this.args = args;
  }

  public void run(
      final Problem problem,
      final Evaluation eval,
      final Plan plan,
      final MissionModel<?> missionModel,
      final Function<String, ActivityType> lookupActivityType,
      final SimulationFacade simulationFacade,
      final DirectiveIdGenerator idGenerator
  ) {
    final ProcedureMapper<?> procedureMapper;
    try {
      procedureMapper = ProcedureLoader.loadProcedure(jarPath);
    } catch (ProcedureLoader.ProcedureLoadException e) {
      throw new RuntimeException(e);
    }

    List<SchedulingActivity> newActivities = new ArrayList<>();

    final var planAdapter = new SchedulerToProcedurePlanAdapter(
        plan,
        planHorizon,
        problem.getDiscreteExternalProfiles(),
        problem.getRealExternalProfiles()
    );

    final var editablePlan = new InMemoryEditablePlan(
        missionModel,
        idGenerator,
        planAdapter,
        simulationFacade,
        lookupActivityType::apply
    );

    procedureMapper.deserialize(SerializedValue.of(this.args)).run(editablePlan);

    if (!editablePlan.getUncommittedChanges().isEmpty()) {
      throw new IllegalStateException("procedural goal %s had changes that were not committed or rolled back".formatted(jarPath.getFileName()));
    }
    for (final var edit : editablePlan.getTotalDiff()) {
      if (edit instanceof Edit.Create c) {
        newActivities.add(toSchedulingActivity(c.getDirective(), lookupActivityType::apply, true));
      } else {
        throw new IllegalStateException("Unexpected value: " + edit);
      }
    }

    final var evaluation = eval.forGoal(this);
    for (final var activity : newActivities) {
      evaluation.associate(activity, true, null);
    }
    evaluation.setConflictSatisfaction(null, ConflictSatisfaction.SAT);
  }
}
