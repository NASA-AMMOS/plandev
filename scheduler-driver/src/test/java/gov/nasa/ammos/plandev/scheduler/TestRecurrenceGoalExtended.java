package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.goals.RecurrenceGoal;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.scheduler.SimulationUtility.buildProblemFromFoo;
import static gov.nasa.jpl.aerie.scheduler.TestUtility.createMutex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRecurrenceGoalExtended {
  private final DirectiveIdGenerator idGenerator = new DirectiveIdGenerator(0);

  /**
   * This test checks that a number activities are placed in the plan. VV
   */
  @Test
  public void testRecurrence() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(6, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(16, SECONDS), activityType));
  }



  @Test
  public void testRecurrenceSecondGoalOutOfWindowAndPlanHorizon() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(activityType)
                            .build())
        .separatedByAtMost(Duration.of(18, SECONDS))
        .separatedByAtLeast(Duration.of(1, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(18, SECONDS), activityType));
    assertTrue((plan.getActivities().size() == 2));
  }

  /**
   * This test checks that in case the repeat interval is larger than the window where the goal can be placed, the scheduler still manages to place one activity. VV
   */
  @Test
  public void testRecurrenceRepeatIntervalLargerThanGoalWindow() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(20, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, SECONDS), activityType));
  }

  /**
   * This test checks that in case the window where the goal can be placed is larger than the plan horizon, then the window is updated to the plan horizon size. xx
   */
  @Test
  public void testGoalWindowLargerThanPlanHorizon() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(5),TestUtility.timeFromEpochSeconds(15));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, SECONDS), Duration.of(5, SECONDS)),
        Interval.between(Duration.of(8, SECONDS), Duration.of(15, SECONDS)),
        Interval.between(Duration.of(17, SECONDS), Duration.of(20, SECONDS))
    ), true);
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(8, SECONDS), activityType));
  }


  /**
   * This test checks that in case the goal duration is larger than goal window, no activity is added to the plan. VV
   */
  @Test
  public void testGoalDurationLargerGoalWindow() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(25, SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(30, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.emptyPlan(plan));
  }

  /**
   * This test checks the behaviour when activity is added to a non-empty plan. How is distance preserved? What happens if activity already existing is deleted?
   */
  @Test
  public void testAddActivityNonEmptyPlan() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(activityType)
                            .build())
        .separatedByAtMost(Duration.of(5, SECONDS))
        .separatedByAtLeast(Duration.of(0, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();
    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution().orElseThrow();

    // Create a new problem with previous plan and add new goal interleaved two time units wrt original goal
    final var problem2 = buildProblemFromFoo(planningHorizon);
    problem2.setInitialPlan(plan);
    RecurrenceGoal goal2 = new RecurrenceGoal.Builder()
        .named("Test recurrence goal 2")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(2, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(activityType)
                            .build())
        .separatedByAtMost(Duration.of(5, SECONDS))
        .separatedByAtLeast(Duration.of(0, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();
    problem2.setGoals(List.of(goal2));
    final var solver2 = new PrioritySolver(problem2);
    var newplan = solver2.getNextSolution().orElseThrow();

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(0, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(5, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(10, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(15, SECONDS), activityType));
  }

  @Test
  public void incompletePlan() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(activityType)
                            .build())
        .separatedByAtMost(Duration.of(5, SECONDS))
        .separatedByAtLeast(Duration.of(0, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();
    problem.setGoals(List.of(goal));
    final var initialPlan = new PlanInMemory();
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), activityType, Duration.ZERO, Duration.of(2, SECONDS), null, true));
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), activityType, SECONDS.times(10), Duration.of(2, SECONDS), null, true));
    problem.setInitialPlan(initialPlan);
    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(0, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(5, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(10, SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(15, SECONDS), activityType));
  }

  @Test
  public void incompletePlanWithMutex() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var controllableActivity = problem.getActivityType("ControllableDurationActivity");
    final var basicActivity = problem.getActivityType("BasicActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(controllableActivity)
                            .build())
        .separatedByAtMost(Duration.of(5, SECONDS))
        .separatedByAtLeast(Duration.of(0, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();
    problem.setGoals(List.of(goal));
    final var initialPlan = new PlanInMemory();
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), controllableActivity, Duration.ZERO, Duration.of(2, SECONDS), null, true));
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), controllableActivity, SECONDS.times(10), Duration.of(2, SECONDS), null, true));
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), basicActivity, SECONDS.times(5), Duration.of(2, SECONDS), null, true));
    problem.setInitialPlan(initialPlan);
    createMutex(controllableActivity, basicActivity).forEach(problem::add);
    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(0, SECONDS), controllableActivity));
    //second controllable activity cannot be inserted
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(5, SECONDS), basicActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(10, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(15, SECONDS), controllableActivity));
  }

  //the minimum number of activities to solve this goal is 1 (at time 10) but there is a mutex act at time [8,12] which prevents it
  //the missing recurrence solver will move to a network of 2 activities to solve and thus find the solution
  @Test
  public void flexibilityWithMutex() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var controllableActivity = problem.getActivityType("ControllableDurationActivity");
    final var basicActivity = problem.getActivityType("BasicActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(controllableActivity)
                            .build())
        .separatedByAtMost(Duration.of(10, SECONDS))
        .separatedByAtLeast(Duration.of(0, SECONDS))
        .lastActivityHappenedAt(Duration.of(0, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();
    problem.setGoals(List.of(goal));
    final var initialPlan = new PlanInMemory();
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), basicActivity, SECONDS.times(8), Duration.of(4, SECONDS), null, true));
    problem.setInitialPlan(initialPlan);
    createMutex(controllableActivity, basicActivity).forEach(problem::add);
    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(6, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(16, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(8, SECONDS), basicActivity));
    assertEquals(3, plan.getActivities().size());
  }

  //the minimum number of activities to solve this goal is 3 (5, 10, 15) but there is a mutex act at time [13,16] which prevents
  //the first iteration from being completely satisfied. It then produces a network with 4 activities but including the 2 activities that succeeded.
  //this test is exercising the behavior when only a part of an activity network has been scheduled, it is moving to a
  //higher number of activities while keeping the already inserted activities in.
  @Test
  public void flexibilityWithMutex2() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var controllableActivity = problem.getActivityType("ControllableDurationActivity");
    final var basicActivity = problem.getActivityType("BasicActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(controllableActivity)
                            .build())
        .separatedByAtMost(Duration.of(5, SECONDS))
        .separatedByAtLeast(Duration.of(0, SECONDS))
        .lastActivityHappenedAt(Duration.of(0, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();
    problem.setGoals(List.of(goal));
    final var initialPlan = new PlanInMemory();
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), basicActivity, SECONDS.times(13), Duration.of(3, SECONDS), null, true));
    problem.setInitialPlan(initialPlan);
    createMutex(controllableActivity, basicActivity).forEach(problem::add);
    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution().orElseThrow();
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(5, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(10, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(16, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(13, SECONDS), basicActivity));
    assertEquals(5, plan.getActivities().size());
  }

  //the mutex activity lasting 7 seconds prevents the goal from satisfying the 6 seconds maximum separation
  //the goal tries to increase the number of activities in the network but duplicate activities are being produced which triggers an exit
  @Test
  public void unsolvableRecurrence() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var controllableActivity = problem.getActivityType("ControllableDurationActivity");
    final var basicActivity = problem.getActivityType("BasicActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, SECONDS), Duration.of(20, SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, SECONDS))
                            .ofType(controllableActivity)
                            .build())
        .separatedByAtMost(Duration.of(6, SECONDS))
        .separatedByAtLeast(Duration.of(0, SECONDS))
        .lastActivityHappenedAt(Duration.of(0, SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();
    problem.setGoals(List.of(goal));
    final var initialPlan = new PlanInMemory();
    initialPlan.add(SchedulingActivity.of(idGenerator.next(), basicActivity, SECONDS.times(7), Duration.of(7, SECONDS), null, true));
    problem.setInitialPlan(initialPlan);
    createMutex(controllableActivity, basicActivity).forEach(problem::add);
    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution().orElseThrow();
    //goal is unsolvable but we partially satisfied it and detected that it is unsatisfiable before exiting
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(5, SECONDS), controllableActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(7, SECONDS), basicActivity));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(17, SECONDS), controllableActivity));
    assertEquals(3, plan.getActivities().size());
  }
}
