package gov.nasa.jpl.aerie.orchestration.scheduling;

import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.orchestration.parsers.GoalSpecificationParser;
import gov.nasa.jpl.aerie.orchestration.simulation.CanceledListener;
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationData;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SchedulingUtility {
  private final MissionModel<?> model;
  private final SchedulerModel schedulerModel;
  private final Map<String, ActivityType> typeMap;
  private final int maxEngines;

  public SchedulingUtility(final MissionModel<?> model, final SchedulerModel schedulerModel) {
    this(model, schedulerModel, 1);
  }

  public SchedulingUtility(final MissionModel<?> model, final SchedulerModel schedulerModel, final int maxEngines) {
    this.model = model;
    this.schedulerModel = schedulerModel;
    this.maxEngines = maxEngines;
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
      final Plan plan,
      CanceledListener canceledListener,
      Optional<SimulationResults> initialResults
  ) throws InterruptedException
  {
    goals.sort(GoalSpecificationParser.GoalRecord::compareTo);

    try (final var engineStore = new InMemoryCachedEngineStore(maxEngines)) {
      final var horizon = new PlanningHorizon(plan.simulationStartInstant(), plan.simulationEndInstant());
      final var directiveIdGenerator = new DirectiveIdGenerator(
          plan.activityDirectives()
              .keySet()
              .stream()
              .map(ActivityDirectiveId::id)
              .max(Long::compareTo)
              .orElse(-1L)
          + 1);
      final var simFacade = new CheckpointSimulationFacade(
          model,
          schedulerModel,
          engineStore,
          horizon,
          new SimulationEngineConfiguration(
              plan.simulationConfiguration(),
              plan.simulationStartInstant(),
              plan.missionModelId()),
          canceledListener
      );

      final var proceduralPlan = new TypeUtilsProceduralPlan(plan);

      final var editablePlan = new TypeUtilsEditablePlan(
          directiveIdGenerator,
          proceduralPlan,
          simFacade,
          this::lookupActivityType);

      final var schedulerPlan = editablePlan.getSchedulerPlan();
      //final var eval = schedulerPlan.getEvaluation();
      initialResults.ifPresent(r -> simFacade.setInitialSimResults(new SimulationData(schedulerPlan, r)));

      for (final var g : goals) {
        //final var procedure = new Procedure(horizon, g.jarPath(), g.args(), g.simulateAfter());

        final ProcedureMapper<?> procedureMapper;
        try {
          procedureMapper = ProcedureLoader.loadProcedure(g.jarPath());
        } catch (ProcedureLoader.ProcedureLoadException e) {
          throw new RuntimeException(e);
        }

        procedureMapper.deserialize(SerializedValue.of(g.args())).run(editablePlan);


        /*
        final var evaluation = eval.forGoal(procedure);

        for (final var edit : editablePlan.getFinalChanges()) {
          if (edit instanceof Edit.Create c) {
            evaluation.associate(toSchedulingActivity(c.getDirective(), this::lookupActivityType, true), true, null);
          } else {
            throw new IllegalStateException("Unexpected value: " + edit);
          }
        }

        evaluation.setConflictSatisfaction(null, ConflictSatisfaction.SAT);
         */
      }
    }
  }
}
