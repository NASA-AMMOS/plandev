package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.SpansFromWindows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeExpressionRelative;
import gov.nasa.jpl.aerie.scheduler.goals.CoexistenceGoal;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class FixedDurationTest {

  PlanningHorizon planningHorizon;
  Problem problem;

  @BeforeEach
  void setUp(){
    planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochDays(3));
    MissionModel<?> bananaMissionModel = SimulationUtility.getBananaMissionModel();
    problem = new Problem(
        bananaMissionModel,
        planningHorizon,
        new CheckpointSimulationFacade(
            bananaMissionModel,
            SimulationUtility.getBananaSchedulerModel(),
            new InMemoryCachedEngineStore(10),
            planningHorizon,
            new SimulationEngineConfiguration(Map.of(), Instant.EPOCH, new MissionModelId(1)),
            ()-> false),
        SimulationUtility.getBananaSchedulerModel());
  }

  @Test
  public void testFieldAnnotation() throws SchedulingInterruptedException {

    final var fixedDurationActivityTemplate = new ActivityExpression.Builder()
        .ofType(problem.getActivityType("BananaNap"))
        .withTimingPrecision(Duration.of(500, Duration.MILLISECOND))
        .build();

    final var start = TimeExpressionRelative.atStart();
    final var coexistence = new CoexistenceGoal.Builder()
        .thereExistsOne(fixedDurationActivityTemplate)
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(planningHorizon.getHor(), true)))
        .forEach(new SpansFromWindows(new WindowsWrapperExpression(new Windows(false).set(Interval.between(1, 2, Duration.MINUTE), true))))
        .startsAt(start)
        .named("FixedCoexistenceGoal")
        .aliasForAnchors("its a me")
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(coexistence));

    final var solver = new PrioritySolver(problem);
    final var plan = solver.getNextSolution().get();
    solver.printEvaluation();
    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT1M"), planningHorizon.fromStart("PT1H1M"), problem.getActivityType("BananaNap")));
  }


  @Test
  public void testMethodAnnotation() throws SchedulingInterruptedException {

    final var fixedDurationActivityTemplate = new ActivityExpression.Builder()
        .ofType(problem.getActivityType("RipenBanana"))
        .withTimingPrecision(Duration.of(500, Duration.MILLISECOND))
        .build();

    final var start = TimeExpressionRelative.afterStart();
    final var coexistence = new CoexistenceGoal.Builder()
        .thereExistsOne(fixedDurationActivityTemplate)
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(planningHorizon.getHor(), true)))
        .forEach(new SpansFromWindows(new WindowsWrapperExpression(new Windows(false).set(Interval.between(1, 2, Duration.MINUTE), true))))
        .startsAt(start)
        .named("FixedCoexistenceGoal")
        .aliasForAnchors("its a me")
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(coexistence));

    final var solver = new PrioritySolver(problem);
    final var plan = solver.getNextSolution().get();
    solver.printEvaluation();
    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT1M"), planningHorizon.fromStart("P2DT1M"), problem.getActivityType("RipenBanana")));
  }

}
