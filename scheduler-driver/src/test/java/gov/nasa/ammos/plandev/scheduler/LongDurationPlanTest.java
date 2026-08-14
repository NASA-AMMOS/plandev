package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.goals.ProceduralCreationGoal;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gov.nasa.jpl.aerie.scheduler.TestUtility.assertSetEquality;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LongDurationPlanTest {

  private static PrioritySolver makeProblemSolver(Problem problem) {
    return new PrioritySolver(problem);
  }

  //test mission with two primitive activity types
  private static Problem makeTestMissionAB() {
    return SimulationUtility.buildProblemFromBanana(h);
  }

  private final static PlanningHorizon h = new PlanningHorizon(TimeUtility.fromDOY("2025-001T01:01:01.001"), TimeUtility.fromDOY("2030-005T01:01:01.001"));
  private final static Duration t0 = h.getStartAerie();
  private final static Duration d1year = Duration.of(1, Duration.SECONDS).times(3600).times(24).times(365);
  private final static Duration d1min = Duration.of(1, Duration.MINUTES);

  private final static Duration t1year = t0.plus(d1year);
  private final static Duration t2year = t1year.plus(d1year);

  private final static Duration t3year = t2year.plus(d1year);

  private static PlanInMemory makePlanA012(Problem problem) {
    final var plan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("GrowBanana");
    final var idGenerator = new DirectiveIdGenerator(0);
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t0, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t1year, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t2year, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t3year, d1min, null, true));
    return plan;
  }

  @Test
  public void getNextSolution_initialPlanInOutput() throws SchedulingInterruptedException {
    final var problem = makeTestMissionAB();
    final var expectedPlan = makePlanA012(problem);
    problem.setInitialPlan(makePlanA012(problem));
    final var solver = makeProblemSolver(problem);

    final var plan = solver.getNextSolution();

    assertTrue(plan.isPresent());
    assertSetEquality(plan.get().getActivitiesByTime(), expectedPlan.getActivitiesByTime());
  }

  @Test
  public void getNextSolution_proceduralGoalCreatesActivities() throws SchedulingInterruptedException {
    final var problem = makeTestMissionAB();
    final var expectedPlan = makePlanA012(problem);
    final var goal = new ProceduralCreationGoal.Builder()
        .named("g0")
        .generateWith((plan) -> expectedPlan.getActivitiesByTime())
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(h.getHor(), true)))
        .withinPlanHorizon(h)
        .build();
    problem.setGoals(List.of(goal));
    final var solver = makeProblemSolver(problem);

    final var plan = solver.getNextSolution().orElseThrow();

    assertSetEquality(plan.getActivitiesByTime(), expectedPlan.getActivitiesByTime());
  }
}
