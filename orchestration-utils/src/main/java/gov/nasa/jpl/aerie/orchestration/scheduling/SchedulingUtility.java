package gov.nasa.jpl.aerie.orchestration.scheduling;

import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.orchestration.parsers.GoalSpecificationParser;
import gov.nasa.jpl.aerie.orchestration.simulation.CanceledListener;
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.goals.Procedure;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.solver.Evaluation;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SchedulingUtility {
  private final MissionModel<?> model;
  private final SchedulerModel schedulerModel;
  private final Plan plan;
  private final Map<String, ActivityType> typeMap;

  public SchedulingUtility(
      final MissionModel<?> model,
      final SchedulerModel schedulerModel,
      final Plan plan
  )
  {
    this.model = model;
    this.schedulerModel = schedulerModel;
    this.plan = plan;
    this.typeMap = generateTypeMap(model, schedulerModel);
  }

  private static Map<String, ActivityType> generateTypeMap(MissionModel<?> model, SchedulerModel schedulerModel) {
    final var typeMap = new HashMap<String, ActivityType>(model.getDirectiveTypes().directiveTypes().size());

    for(var taskType : model.getDirectiveTypes().directiveTypes().entrySet()) {
      final var actType = new ActivityType(
          taskType.getKey(),
          taskType.getValue(),
          schedulerModel.getDurationTypes().get(taskType.getKey()));

      final String name = actType.getName();
      if (name == null) {
        throw new IllegalArgumentException(
            "adding activity type definition with null name to mission model");
      }

      if (typeMap.containsKey(name)) {
        throw new IllegalArgumentException(
            "adding duplicate activity type definition name=" + name + " to mission model");
      }

      typeMap.put(taskType.getKey(), actType);
    }
    return Collections.unmodifiableMap(typeMap);
  }

  private ActivityType lookupActivityType(String name) {
    if(!typeMap.containsKey(name)) {
      throw new IllegalArgumentException("No activity type named "+name+" in mission model.");
    }
    return typeMap.get(name);
  }


  public void schedule(
      final List<GoalSpecificationParser.GoalRecord> goals,
      CanceledListener canceledListener,
      Optional<SimulationResults> initialResults
  ) throws InterruptedException {
    // ensure list is sorted
    goals.sort(GoalSpecificationParser.GoalRecord::compareTo);

    //on first call to solver; setup fresh solution workspace for problem
    if(canceledListener.get()) throw new InterruptedException("initializing plan");

    final var directiveIdGenerator = new DirectiveIdGenerator(
        plan.activityDirectives()
            .keySet()
            .stream()
            .map(ActivityDirectiveId::id)
            .max(Long::compareTo)
            .orElse(-1L)
        + 1);

    final var simFacade = new CheckpointSimulationFacade(model, null, null, null, null, null);





    final var proceduralPlan = new TypeUtilsProceduralPlan(plan);
    final var horizon = new PlanningHorizon(plan.simulationStartInstant(), plan.simulationEndTimestamp.toInstant());
    final var editablePlan = new TypeUtilsEditablePlan(directiveIdGenerator, proceduralPlan, simFacade, this::lookupActivityType);

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
