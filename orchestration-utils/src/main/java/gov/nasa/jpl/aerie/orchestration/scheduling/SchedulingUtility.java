package gov.nasa.jpl.aerie.orchestration.scheduling;

import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.orchestration.GoalSpecificationParser;
import gov.nasa.jpl.aerie.orchestration.simulation.CanceledListener;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.goals.Procedure;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.plan.SchedulerToProcedurePlanAdapter;
import gov.nasa.jpl.aerie.scheduler.solver.ConflictSatisfaction;
import gov.nasa.jpl.aerie.scheduler.solver.Evaluation;
import gov.nasa.jpl.aerie.types.Plan;

import java.util.ArrayList;
import java.util.List;

import static gov.nasa.jpl.aerie.scheduler.plan.InMemoryEditablePlan.toSchedulingActivity;

public class SchedulingUtility {

  public static void schedule(
      final Plan plan,
      final List<GoalSpecificationParser.GoalRecord> goals,
      CanceledListener canceledListener
  ) throws InterruptedException {
    // ensure list is sorted
    goals.sort(GoalSpecificationParser.GoalRecord::compareTo);

    //on first call to solver; setup fresh solution workspace for problem
    if(canceledListener.get()) throw new InterruptedException("initializing plan");



    final var proceduralPlan = new TypeUtilsProceduralPlan(plan);
    final var horizon = new PlanningHorizon(plan.simulationStartInstant(), plan.simulationEndTimestamp.toInstant());
    final var editablePlan = new TypeUtilsEditablePlan();

    final var evaluation = new Evaluation();

    for(final var g : goals) {
      final var procedure = new Procedure(horizon, g.jarPath(), g.args(), g.simulateAfter());

      if (canceledListener.get()) throw new InterruptedException("satisfying goal");
      final boolean checkSimConfig = g.simulateAfter();

      final ProcedureMapper<?> procedureMapper;
      try {
        procedureMapper = ProcedureLoader.loadProcedure(g.jarPath());
      } catch (ProcedureLoader.ProcedureLoadException e) {
        throw new RuntimeException(e);
      }

      List<SchedulingActivity> newActivities = new ArrayList<>();
      procedureMapper.deserialize(SerializedValue.of(g.args())).run(editablePlan);
    }



    /*
      try {
        initializePlan();
        if(problem.getInitialSimulationResults().isPresent()) {
          logger.debug("Loading initial simulation results from the DB");
          simulationFacade.setInitialSimResults(problem.getInitialSimulationResults().get());
        }
      } catch (SimulationFacade.SimulationException e) {
        logger.error("Tried to initializePlan but at least one activity could not be instantiated", e);
        return Optional.empty();
      }

      //attempt to satisfy the goals in the problem
      //construct a priority sorted goal container

      //update the output solution plan directly to satisfy goal
      if(simulationFacade.getCanceledListener().get()) throw new SchedulingInterruptedException("satisfying goal");
    final boolean checkSimConfig = this.checkSimBeforeInsertingActivities;
    this.checkSimBeforeInsertingActivities = goal.simulateAfter;

      if (!analysisOnly) {
        procedure.run(plan.getEvaluation(), plan, this.problem::getActivityType, this.simulationFacade, this.idGenerator);
      }
       this.checkSimBeforeEvaluatingGoal = goal.simulateAfter;
    this.checkSimBeforeInsertingActivities = checkSimConfig;
    }

      return Optional.of(plan);

    } else { //plan!=null

      //subsequent call after initial solution, so return null
      //(this simple solver only produces a single solution)
      return Optional.empty();
    }
     */

  }
}
