package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.SpansFromWindows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeExpressionRelative;
import gov.nasa.jpl.aerie.scheduler.goals.CoexistenceGoal;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParametricDurationTest {

  PlanningHorizon planningHorizon;
  Problem problem;

  @BeforeEach
  void setUp(){
    planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochDays(3));
    problem = SimulationUtility.buildBananaProblem(planningHorizon);
  }

  @Test
  public void testStartConstraint() throws SchedulingInterruptedException {

    final var parameterizedDurationActivityTemplate = new ActivityExpression.Builder()
        .ofType(problem.getActivityType("DownloadBanana"))
        .withArgument("connection", SerializedValue.of("DietaryFiberOptic"))
        .withTimingPrecision(Duration.of(500, Duration.MILLISECOND))
        .build();

    final var start = TimeExpressionRelative.atStart();
    final var coexistence = new CoexistenceGoal.Builder()
        .thereExistsOne(parameterizedDurationActivityTemplate)
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(planningHorizon.getHor(), true)))
        .forEach(new SpansFromWindows(new WindowsWrapperExpression(new Windows(false).set(Interval.between(1, 3, Duration.MINUTE), true))))
        .startsAt(start)
        .named("ParamDurationCoexistenceGoal")
        .aliasForAnchors("its a me")
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(coexistence));

    final var solver = new PrioritySolver(problem);
    final var plan = solver.getNextSolution().get();
    solver.printEvaluation();
    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT1M"), planningHorizon.fromStart("PT2M"), problem.getActivityType("DownloadBanana")));
  }

  @Test
  public void testEndConstraint() throws SchedulingInterruptedException {

    final var parameterizedDurationActivityTemplate = new ActivityExpression.Builder()
        .ofType(problem.getActivityType("DownloadBanana"))
        .withArgument("connection", SerializedValue.of("FiberOptic"))
        .withTimingPrecision(Duration.of(500, Duration.MILLISECOND))
        .build();

    final var coexistence = new CoexistenceGoal.Builder()
        .thereExistsOne(parameterizedDurationActivityTemplate)
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(planningHorizon.getHor(), true)))
        .forEach(new SpansFromWindows(new WindowsWrapperExpression(new Windows(false).set(Interval.between(10, 13, Duration.MINUTE), true))))
        .endsBeforeEnd()
        .startsAt(TimeExpressionRelative.offsetByBeforeStart(Duration.of(8, Duration.MINUTE)))
        .named("ParamDurationCoexistenceGoal")
        .aliasForAnchors("its a me")
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(coexistence));

    final var solver = new PrioritySolver(problem);
    final var plan = solver.getNextSolution().get();
    solver.printEvaluation();
    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT2M"), planningHorizon.fromStart("PT12M"), problem.getActivityType("DownloadBanana")));
  }
}
