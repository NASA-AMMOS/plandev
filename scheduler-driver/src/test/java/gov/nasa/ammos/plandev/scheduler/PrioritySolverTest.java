package gov.nasa.jpl.aerie.scheduler;

import com.google.common.testing.NullPointerTester;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeAnchor;
import gov.nasa.jpl.aerie.scheduler.goals.CardinalityGoal;
import gov.nasa.jpl.aerie.scheduler.goals.ChildCustody;
import gov.nasa.jpl.aerie.scheduler.goals.CoexistenceGoal;
import gov.nasa.jpl.aerie.scheduler.goals.ProceduralCreationGoal;
import gov.nasa.jpl.aerie.scheduler.goals.RecurrenceGoal;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.scheduler.solver.Evaluation;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.scheduler.TestUtility.assertSetEquality;
import static org.junit.jupiter.api.Assertions.*;

public class PrioritySolverTest {
  private static final DirectiveIdGenerator idGenerator = new DirectiveIdGenerator(0);
  private static PrioritySolver makeEmptyProblemSolver() {
    MissionModel<?> bananaMissionModel = SimulationUtility.getBananaMissionModel();
    final var schedulerModel = SimulationUtility.getBananaSchedulerModel();
    return new PrioritySolver(
            new Problem(
                    bananaMissionModel,
                    h,
                    new CheckpointSimulationFacade(
                        bananaMissionModel,
                        schedulerModel,
                        new InMemoryCachedEngineStore(15),
                        h,
                        new SimulationEngineConfiguration(Map.of(),Instant.EPOCH, new MissionModelId(1)),
                        () -> false),
                    schedulerModel));
  }

  private static PrioritySolver makeProblemSolver(Problem problem) {
    return new PrioritySolver(problem);
  }

  @Test
  public void ctor_onEmptyProblemWorks() {
    new PrioritySolver(new Problem(null,h, null, null));
  }

  @Test
  void ctors_nullArgThrowsNPE() {
    NULL_POINTER_TESTER.testAllPublicConstructors(PrioritySolver.class);
  }

  @Test
  public void getNextSolution_onEmptyProblemGivesEmptyPlanAndOneEmptyEvaluation()
  throws SchedulingInterruptedException
  {
    final var solver = makeEmptyProblemSolver();
    final var plan = solver.getNextSolution();

    assertTrue(plan.isPresent());
    assertEquals(new Evaluation(), plan.get().getEvaluation());
    assertTrue(plan.get().getActivitiesByTime().isEmpty());
  }

  @Test
  public void getNextSolution_givesNoSolutionOnSubsequentCall() throws SchedulingInterruptedException {
    final var solver = makeEmptyProblemSolver();
    solver.getNextSolution();
    final var plan1 = solver.getNextSolution();

    assertTrue(plan1.isEmpty());
  }

  //test mission with two primitive activity types
  private static Problem makeTestMissionAB() {
    return SimulationUtility.buildProblemFromFoo(h, 15);
  }

  private final static PlanningHorizon h = new PlanningHorizon(TimeUtility.fromDOY("2025-001T01:01:01.001"), TimeUtility.fromDOY("2025-005T01:01:01.001"));
  private final static Duration t0 = h.getStartAerie();
  private final static Duration d1min = Duration.of(1, Duration.MINUTE);
  private final static Duration d1hr = Duration.of(1, Duration.HOUR);
  private final static Duration t1hr = t0.plus(d1hr);
  private final static Duration t2hr = t0.plus(d1hr.times(2));

  private static final NullPointerTester NULL_POINTER_TESTER = new NullPointerTester()
      .setDefault(Problem.class, new Problem(null, h, null, null));


  private static PlanInMemory makePlanA012(Problem problem) {
    final var plan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t0, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t1hr, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t2hr, d1min, null, true));
    return plan;
  }

  private static PlanInMemory makePlanA12(Problem problem) {
    final var plan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t1hr, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t2hr, d1min, null, true));
    return plan;
  }

  private static PlanInMemory makePlanAB012(Problem problem) {
    final var plan = makePlanA012(problem);
    final var actTypeB = problem.getActivityType("OtherControllableDurationActivity");
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeB, t0, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeB, t1hr, d1min, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeB, t2hr, d1min, null, true));
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

  @Test
  public void getNextSolution_proceduralGoalAttachesActivitiesToEvaluation() throws SchedulingInterruptedException {
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

    assertNotNull(plan.getEvaluation());
    final var eval = plan.getEvaluation().forGoal(goal);
    assertNotNull(eval);
    assertSetEquality(eval.getAssociatedActivities().stream().toList(), expectedPlan.getActivitiesByTime());
  }

  @Test
  public void getNextSolution_recurrenceGoalWorks() throws SchedulingInterruptedException {
    final var problem = makeTestMissionAB();
    final var goal = new RecurrenceGoal.Builder()
        .named("g0")
        .startingAt(t0)
        .endingAt(t2hr.plus(Duration.of(10, Duration.MINUTE)))
        .separatedByAtLeast(d1hr)
        .separatedByAtMost(d1hr)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(problem.getActivityType("ControllableDurationActivity"))
                            .durationIn(d1min)
                            .build())
        .withinPlanHorizon(h)
        .build();
    problem.setGoals(List.of(goal));
    final var solver = makeProblemSolver(problem);

    final var plan = solver.getNextSolution().orElseThrow();

    final var expectedPlan = makePlanA012(problem);
    //TODO: evaluation should have association of instances to goal
    //TODO: should ensure no other spurious acts yet need to ignore special interval activities
    //TODO: may want looser expectation (eg allow flexibility as long as right repeat pattern met)
    assertTrue(plan.getActivitiesByTime().get(0).equalsInProperties(expectedPlan.getActivitiesByTime().get(0)));
    assertSetEquality(plan.getActivitiesByTime(), expectedPlan.getActivitiesByTime());
  }

  @Test
  public void getNextSolution_coexistenceGoalOnActivityWorks() throws SchedulingInterruptedException {
    final var problem = makeTestMissionAB();
    problem.setInitialPlan(makePlanA012(problem));
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    final var actTypeB = problem.getActivityType("OtherControllableDurationActivity");
    final var goal = new CoexistenceGoal.Builder()
        .named("g0")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(h.getHor(), true)))
        .forEach(new ActivityExpression.Builder()
                     .ofType(actTypeA)
                     .build())
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeB)
                            .durationIn(d1min)
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(h)
        .build();
    problem.setGoals(List.of(goal));
    final var solver = makeProblemSolver(problem);

    final var plan = solver.getNextSolution().orElseThrow();

    final var expectedPlan = makePlanAB012(problem);
    //TODO: evaluation should have association of instances to goal
    //TODO: should ensure no other spurious acts yet need to ignore special interval activities
    assertSetEquality(plan.getActivitiesByTime(), expectedPlan.getActivitiesByTime());
  }

  /**
   * This test is the same as getNextSolution_coexistenceGoalOnActivityWorks except for the initial simulation results that
   * are loaded with the initial plan. This results in 1 less simulation as the initial results are used for generating conflicts.
   */
  @Test
  public void getNextSolution_coexistenceGoalOnActivityWorks_withInitialSimResults()
  throws SimulationFacade.SimulationException, SchedulingInterruptedException
  {
    final var problem = makeTestMissionAB();
    final var adHocFacade = new CheckpointSimulationFacade(
        problem.getMissionModel(),
        problem.getSchedulerModel(),
        new InMemoryCachedEngineStore(10),
        problem.getPlanningHorizon(),
        new SimulationEngineConfiguration(Map.of(),Instant.EPOCH, new MissionModelId(1)),
        () -> false);
    final var simResults = adHocFacade.simulateWithResults(makePlanA012(problem), h.getEndAerie());
    problem.setInitialPlan(makePlanA012(problem), Optional.of(simResults.driverResults()));
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    final var actTypeB = problem.getActivityType("OtherControllableDurationActivity");
    final var goal = new CoexistenceGoal.Builder()
        .named("g0")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(h.getHor(), true)))
        .forEach(new ActivityExpression.Builder()
                     .ofType(actTypeA)
                     .build())
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeB)
                            .durationIn(d1min)
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(h)
        .build();
    problem.setGoals(List.of(goal));
    final var solver = makeProblemSolver(problem);
    final var plan = solver.getNextSolution().orElseThrow();
    final var expectedPlan = makePlanAB012(problem);
    assertSetEquality(plan.getActivitiesByTime(), expectedPlan.getActivitiesByTime());
  }

  @Test
  public void testCardGoalWithApplyWhen() throws SchedulingInterruptedException {
    final var problem = SimulationUtility.buildProblemFromFoo(h);
    final var activityType = problem.getActivityType("ControllableDurationActivity");

    //act at t=1hr and at t=2hrs
    problem.setInitialPlan(makePlanA12(problem));

    //and the goal window in between [0, 50 min[ so the activities already present in the plan can't count towards satisfying the goal
    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(0, Duration.SECONDS), Duration.of(50, Duration.MINUTE))
    ), true);

    CardinalityGoal cardGoal = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(2, Duration.SECONDS), Duration.of(65, Duration.HOUR)))
        .occurences(new Range<>(1, 3))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(problem.getActivityType("ControllableDurationActivity"))
                            .durationIn(d1min)
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(h)
        .build();

    TestUtility.createAutoMutexGlobalSchedulingCondition(activityType).forEach(problem::add);
    problem.setGoals(List.of(cardGoal));
    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    //will insert an activity at the beginning of the plan in addition of the two already-present activities
    assertEquals(3, plan.getActivities().size());
  }
}
