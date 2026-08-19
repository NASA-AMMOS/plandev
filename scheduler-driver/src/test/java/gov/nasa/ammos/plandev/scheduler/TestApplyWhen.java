package gov.nasa.ammos.plandev.scheduler;

import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.model.LinearEquation;
import gov.nasa.ammos.plandev.constraints.model.LinearProfile;
import gov.nasa.ammos.plandev.constraints.model.DiscreteProfile;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Segment;
import gov.nasa.ammos.plandev.constraints.time.Spans;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.constraints.tree.And;
import gov.nasa.ammos.plandev.constraints.tree.AssignGaps;
import gov.nasa.ammos.plandev.constraints.tree.DiscreteResource;
import gov.nasa.ammos.plandev.constraints.tree.DiscreteValue;
import gov.nasa.ammos.plandev.constraints.tree.Equal;
import gov.nasa.ammos.plandev.constraints.tree.Expression;
import gov.nasa.ammos.plandev.constraints.tree.GreaterThanOrEqual;
import gov.nasa.ammos.plandev.constraints.tree.ProfileExpression;
import gov.nasa.ammos.plandev.constraints.tree.RealResource;
import gov.nasa.ammos.plandev.constraints.tree.RealValue;
import gov.nasa.ammos.plandev.constraints.tree.SpansFromWindows;
import gov.nasa.ammos.plandev.constraints.tree.SpansWrapperExpression;
import gov.nasa.ammos.plandev.constraints.tree.ValueAt;
import gov.nasa.ammos.plandev.constraints.tree.WindowsWrapperExpression;
import gov.nasa.ammos.plandev.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.ammos.plandev.scheduler.constraints.timeexpressions.TimeAnchor;
import gov.nasa.ammos.plandev.scheduler.goals.CardinalityGoal;
import gov.nasa.ammos.plandev.scheduler.goals.ChildCustody;
import gov.nasa.ammos.plandev.scheduler.goals.CoexistenceGoal;
import gov.nasa.ammos.plandev.scheduler.goals.RecurrenceGoal;
import gov.nasa.ammos.plandev.scheduler.model.Problem;
import gov.nasa.ammos.plandev.scheduler.model.SchedulingActivity;
import gov.nasa.ammos.plandev.scheduler.model.PlanInMemory;
import gov.nasa.ammos.plandev.scheduler.model.PlanningHorizon;
import gov.nasa.ammos.plandev.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.ammos.plandev.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.ammos.plandev.scheduler.solver.PrioritySolver;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.types.MissionModelId;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static gov.nasa.ammos.plandev.scheduler.SimulationUtility.buildProblemFromFoo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class TestApplyWhen {
  private static final Logger logger = LoggerFactory.getLogger(TestApplyWhen.class);

  private final DirectiveIdGenerator idGenerator = new DirectiveIdGenerator(0);

  ////////////////////////////////////////////RECURRENCE////////////////////////////////////////////
  @Test
  public void testRecurrenceCutoff1() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(12, Duration.SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    //enveloppe of the foralltimein includes end time of an activity. Last activity starting at 16 would end after 17 and violate it.
  }

  @Test
  public void testRecurrenceCutoff2() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(17, Duration.SECONDS)), true))) //add colorful tests that make use of windows capability
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    //enveloppe of the foralltimein includes end time of an activity. Last activity starting at 16 would end after 17 and violate it.
  }

  @Test
  public void testRecurrenceShorterWindow() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(19, Duration.SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(16, Duration.SECONDS), activityType));
  }

  @Test
  public void testRecurrenceLongerWindow() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(21, Duration.SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(16, Duration.SECONDS), activityType));
  }

  @Test
  public void testRecurrenceBabyWindow() throws SchedulingInterruptedException {
    /*
    The plan horizon ranges from [0,20).
    The recurrent activities can be placed inside the following window: [1,2). That is, there is exactly 1 unit of time where an activity can be placed
    If the interval was [1,2], the time available to place activities would be 1.000....1
    The activities instantiating the goal have a duration of 1
    The activities should repeat every 5 time units

    It is therefore possible to place an activity in time slot 1, with a duration of 1
    Graphically
    RECURRENCE WINDOW: [+----+----+----+----]
    GOAL WINDOW: [+-------------------]
    RESULT: [+-------------------]
    */
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(2, Duration.SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(1, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .separatedByAtLeast(Duration.of(0, Duration.SECONDS))
        .separatedByAtMost(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(16, Duration.SECONDS), activityType));
  }

  @Test
  public void testRecurrenceWindows() throws SchedulingInterruptedException {
    // RECURRENCE WINDOW: [++---++---++---++---]
    // GOAL WINDOW:       [++++++----+++++++---]
    // RESULT:            [++--------++--------]

    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");

    final var goalWindow = new Windows(false).set(Arrays.asList(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(7, Duration.SECONDS)), //needs to be 2 longer than recurrence interval for second to last occurrence to be scheduled, no partial recurrence scheduling :(
        Interval.between(Duration.of(11, Duration.SECONDS), Duration.of(17, Duration.SECONDS))
    ), true);

    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(16, Duration.SECONDS), activityType));
  }

  @Test
  public void testRecurrenceWindowsCutoffMidInterval() throws SchedulingInterruptedException {
    // RECURRENCE WINDOW: [++---++---++---++---]
    // GOAL WINDOW:       [++++------+++-------]
    // RESULT:            [++--------++--------]

    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");

    final var goalWindow = new Windows(false).set(Arrays.asList(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(4, Duration.SECONDS)),
        Interval.between(Duration.of(11, Duration.SECONDS), Duration.of(13, Duration.SECONDS))
    ), true);

    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .separatedByAtMost(Duration.of(5, Duration.SECONDS))
        .separatedByAtLeast(Duration.of(0, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(16, Duration.SECONDS), activityType));
  }

  @Test
  public void testRecurrenceWindowsGlobalCheck() throws SchedulingInterruptedException {
    //                     123456789012345678901
    // RECURRENCE WINDOW: [++-++-++-++-++-++-++-] (if global)
    // GOAL WINDOW:       [+++++--++++++++-++++-] (if interval is same length as recurrence interval, fails)
    // RESULT:            [++-----++-++----~~---] (if not global)

    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");

    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(5, Duration.SECONDS)),
        Interval.between(Duration.of(8, Duration.SECONDS), Duration.of(15, Duration.SECONDS)),
        Interval.between(Duration.of(17, Duration.SECONDS), Duration.of(20, Duration.SECONDS))
    ), true);

    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(3, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(8, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(17, Duration.SECONDS), activityType)); //interval (len 4) needs to be 2 longer than the recurrence repeatingEvery (len 3)
  }

  @Test
  public void testRecurrenceWindowsCutoffMidActivity() throws SchedulingInterruptedException {
    //                     12345678901234567890
    // RECURRENCE WINDOW: [++---++---++---++---]
    // GOAL WINDOW:       [+-----+++-+++-------]
    // RESULT:            [----------++--------]

    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");

    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(1, Duration.SECONDS)),
        Interval.between(Duration.of(7, Duration.SECONDS), Duration.of(9, Duration.SECONDS)),
        Interval.between(Duration.of(11, Duration.SECONDS), Duration.of(13, Duration.SECONDS))
    ), true);

    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .thereExistsOne(new ActivityExpression.Builder()
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    //assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));  //should fail, won't work if cutoff mid-activity - interval expected to be longer than activity duration!!!
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(16, Duration.SECONDS), activityType));
  }

  @Test
  public void testRecurrenceCutoffUncontrollable() throws SchedulingInterruptedException {
    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(21));
    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("BasicActivity");
    RecurrenceGoal goal = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(12, Duration.SECONDS)), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityType)
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(planningHorizon)
        .build();


    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(6, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(16, Duration.SECONDS), activityType));
  }


  ////////////////////////////////////////////CARDINALITY////////////////////////////////////////////

  @Test
  public void testCardinality() throws SchedulingInterruptedException {
    Interval period = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(5, Duration.SECONDS));

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = buildProblemFromFoo(planningHorizon);

    final var activityType = problem.getActivityType("ControllableDurationActivity");
    TestUtility.createAutoMutexGlobalSchedulingCondition(activityType).forEach(problem::add);

    CardinalityGoal goal = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(19, Duration.SECONDS)))
        .occurences(new Range<>(3, 10))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(problem.getActivityType("ControllableDurationActivity"))
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertEquals(2, plan.get().getActivitiesByTime().size());
    assertEquals(plan.get().getActivitiesByTime().stream()
                     .map(SchedulingActivity::duration)
                     .reduce(Duration.ZERO, Duration::plus), Duration.of(4, Duration.SECOND)); //1 gets added, then throws 4 warnings meaning it tried to schedule 5 in total, not the expected 8...
  }

  @Test
  public void testCardinalityWindows() throws SchedulingInterruptedException {
    // DURATION:    [++]
    // GOAL WINDOW: [++++------++++------]
    // RESULT:      [++++------++++------]

    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));

    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");

    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(5, Duration.SECONDS)),
        Interval.between(Duration.of(11, Duration.SECONDS), Duration.of(15, Duration.SECONDS)) //interval here is exclusive, so I extended it by 1. in the case of recurrence goal, it was exclusive and had to be extended by 2 (one for exclusive/inclusive, and another so that the interval wasn't equal in length to the recurrence interval)
    ), true);

    CardinalityGoal goal = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(19, Duration.SECONDS)))
        .occurences(new Range<>(3, 10))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(problem.getActivityType("ControllableDurationActivity"))
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(planningHorizon)
        .build();


    TestUtility.createAutoMutexGlobalSchedulingCondition(activityType).forEach(problem::add);
    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(3, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(13, Duration.SECONDS), activityType));
  }

  @Test
  public void testCardinalityWindowsCutoffMidActivity() throws SchedulingInterruptedException {
    // DURATION:    [++]
    // GOAL WINDOW: [+-----++--+++-------]
    // RESULT:      [------++--++--------]

    var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0),TestUtility.timeFromEpochSeconds(20));

    final var problem = buildProblemFromFoo(planningHorizon);
    final var activityType = problem.getActivityType("ControllableDurationActivity");

    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(1, Duration.SECONDS)),
        Interval.between(Duration.of(7, Duration.SECONDS), Duration.of(8, Duration.SECONDS)), //exclusive
        Interval.between(Duration.of(11, Duration.SECONDS), Duration.of(13, Duration.SECONDS))
    ), true);

    CardinalityGoal goal = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(19, Duration.SECONDS)))
        .occurences(new Range<>(3, 10))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(problem.getActivityType("ControllableDurationActivity"))
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(planningHorizon)
        .build();


    TestUtility.createAutoMutexGlobalSchedulingCondition(activityType).forEach(problem::add);
    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);

    var plan = solver.getNextSolution().orElseThrow();
    for(SchedulingActivity a : plan.getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(1, Duration.SECONDS), activityType));
    //assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(7, Duration.SECONDS), activityType));
    assertFalse(TestUtility.activityStartingAtTime(plan,Duration.of(9, Duration.SECONDS), activityType));
    assertTrue(TestUtility.activityStartingAtTime(plan,Duration.of(11, Duration.SECONDS), activityType));
  }

  @Test
  public void testCardinalityUncontrollable() throws SchedulingInterruptedException { //ruled unpredictable for now
    /*
      Expect 5 to get scheduled just in a row, as basicactivity's duration should allow that.
     */
    Interval period = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(20, Duration.SECONDS));

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = buildProblemFromFoo(planningHorizon);


    final var activityType = problem.getActivityType("BasicActivity");
    TestUtility.createAutoMutexGlobalSchedulingCondition(activityType).forEach(problem::add);

    CardinalityGoal goal = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(19, Duration.SECONDS)))
        .occurences(new Range<>(3, 10))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityType)
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString());
    }

    var size = plan.get().getActivitiesByTime().size();
    var totalDuration = plan.get().getActivitiesByTime().stream()
                            .map(SchedulingActivity::duration)
                            .reduce(Duration.ZERO, Duration::plus);
    assertTrue(size >= 3 && size <= 10);
    assertTrue(totalDuration.dividedBy(Duration.SECOND) >= 16 && totalDuration.dividedBy(Duration.SECOND) <= 19);
  }


  ////////////////////////////////////////////COEXISTENCE////////////////////////////////////////////

  @Test
  public void testCoexistenceWindowCutoff() throws SchedulingInterruptedException {

    Interval period = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(12, Duration.SECONDS));

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(11, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 11s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(16, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);
    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeA)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    assertEquals(4, plan.get().getActivitiesByTime().size());
  }

  @Test
  public void testCoexistenceJustFits() throws SchedulingInterruptedException {
    Interval period = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(13, Duration.SECONDS));//13, so it just fits in
    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(11, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 11s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(16, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);
    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeA)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    assertEquals(5, plan.get().getActivitiesByTime().size());
  }

  @Test
  public void testCoexistenceUncontrollableCutoff() throws SchedulingInterruptedException { //ruled unpredictable for now
    /*
                     123456789012345678901234
       GOAL WINDOW: [+++++++++++++-- ---------]
       ACTIVITIES:  [+++++-----+++++|+++++----]
       RESULT:      [++--------++--- ---------]

     */

    Interval period = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(13, Duration.SECONDS));

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(11, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 11s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(16, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);
    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 13)
    final var actTypeB = problem.getActivityType("BasicActivity");
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeB)
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    assertEquals(2, plan.get().getActivitiesByType().get(actTypeB).size());
  }

  @Test
  public void testCoexistenceWindows() throws SchedulingInterruptedException {
    // COEXISTENCE LATCH POINTS:
    //    (seek to add Duration 2 activities to each of these)
    //               1234567890123456789012
    //              [++++---++++--++++-++++]
    // GOAL WINDOW: [++++-------+++++++----]
    // RESULT:      [++-----------++-------]

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(1, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start. NOTE: must start at time=1, not time=0, else test fails.
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(8, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 11s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(14, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(19, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start


    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);

    //create goal interval
    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(4, Duration.SECONDS)),
        Interval.between(Duration.of(12, Duration.SECONDS), Duration.of(18, Duration.SECONDS))
    ), true);

    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeA)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(1, Duration.SECONDS), actTypeA));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(14, Duration.SECONDS), actTypeA));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(12, Duration.SECONDS), actTypeA));
  }

  @Test
  public void testCoexistenceWindowsCutoffMidActivity() throws SchedulingInterruptedException {
    // COEXISTENCE LATCH POINTS:
    //    (seek to add Duration [++] activities to each of these)
    //               1234567890123456789012345678
    //              [++++---++++--++++-++++--++++]
    // GOAL WINDOW: [++++-----+++++-+++--+-++++++]
    // RESULT:      [-\\------++----++-------++--] (the first one won't be scheduled, ask Adrien) - FIXED

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(28)); //this boundary is inclusive.
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(1, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(7, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 11s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(14, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(19, Duration.SECONDS)), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(25, Duration.SECONDS)), Duration.of(2, Duration.SECONDS), null, true)); //create an activity that's 2 seconds long, 25s after start


    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);

    //create goal interval
    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(2, Duration.SECONDS), Duration.of(5, Duration.SECONDS)), //FIXED: the first space in a windows object/the lowest time defines whats used in find (more deeply in match) to define the startRange, which goes from this interval's overall start to end. even if the boundaries are weird within the windows, the cutoff here is only at the start and the end. debugging this test can show you as you his the call on line 181 in Coexistence Goal, which goes to 56 in TimeRangeExpression, which goes to 175 in PlanInMemory which goes to 499 in ActivityExpression.
        Interval.between(Duration.of(10, Duration.SECONDS), Duration.of(14, Duration.SECONDS)),
        Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(18, Duration.SECONDS)),
        Interval.between(Duration.of(21, Duration.SECONDS), Duration.of(21, Duration.SECONDS)),
        Interval.between(Duration.of(23, Duration.SECONDS), Duration.of(28, Duration.SECONDS))
    ), true);

    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    final var actTypeB = problem.getActivityType("OtherControllableDurationActivity");
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeB)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();


    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    assertEquals(10, plan.get().getActivitiesById().size());
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(2, Duration.SECONDS), actTypeB));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(10, Duration.SECONDS), actTypeB));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(16, Duration.SECONDS), actTypeB));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(23, Duration.SECONDS), actTypeB));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(25, Duration.SECONDS), actTypeB));
  }

  @Test
  public void testCoexistenceWindowsBisect() throws SchedulingInterruptedException { //bad, should fail completely. worth investigating.
    /*
       COEXISTENCE LATCH POINTS:
       (seek to add Duration [++] activities to each of these, wherever an activity happens/theres a interval)
                           123456789|012 (last 2 not technically included, it is a "fencepost")
                          [++++---++|+++]
                                             DEPRECATED GOAL WINDOW: [++++++-+--++] //after testing, both 1-long interval and 2-long windows fail. They match the activity and all but fail once you get to createActivityForReal.
       FIXED GOAL WINDOW: [++++++-++|+++] //after testing, both 1-long interval and 2-long windows fail. They match the activity and all but fail once you get to createActivityForReal.
       RESULT:            [++-------|++-]
     */

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(12));
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(4, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(8, Duration.SECONDS)), Duration.of(3, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 11s after start

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);

    //create goal interval
    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(6, Duration.SECONDS)), //FIXED: why doesn't this first one doesn't get scheduled? It does if it starts at 0 but should if it starts at 1.
        Interval.between(Duration.of(8, Duration.SECONDS), Duration.of(9, Duration.SECONDS)), //too short
        //Interval.between(Duration.of(11, Duration.SECONDS), Duration.of(13, Duration.SECONDS)) //fails because final "fencepost" lies outside of horizon
        Interval.between(Duration.of(10, Duration.SECONDS), Duration.of(12, Duration.SECONDS)) //passes because even though it touches boundary, large enough. the fence at 12 is not included, just the fencepost at 12.
    ), true);

    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeA)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(1, Duration.SECONDS), actTypeA));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(8, Duration.SECONDS), actTypeA));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(10, Duration.SECONDS), actTypeA));
  }

  @Test
  public void testCoexistenceWindowsBisect2() throws SchedulingInterruptedException { //corrected. Bisection does work successfully.
    /*
       COEXISTENCE LATCH POINTS:
          (seek to add Duration [++] activities to each of these, wherever an activity happens/theres a interval)
                     1234567890123456
                    [++++++++++++++++]
       GOAL WINDOW: [+++-++--+++--+++] //last one fails regardless of length
       RESULT:      [++--++--++---++-]
     */

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(16));
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(13, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);

    //create goal interval
    final var goalWindow = new Windows(false).set(List.of(
        Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(3, Duration.SECONDS)), //FIXED: first one passes, but fails if the above activity starts at 0
        Interval.between(Duration.of(5, Duration.SECONDS), Duration.of(6, Duration.SECONDS)), //second one fails because too short, even though interval shows up in CoexistenceGoal
        Interval.between(Duration.of(9, Duration.SECONDS), Duration.of(11, Duration.SECONDS)), //win! (even though interval bisected)
        Interval.between(Duration.of(14, Duration.SECONDS), Duration.of(16, Duration.SECONDS)) //fourth one fails because of bad edge case behavior, interval doesn't even show up in CoexistenceGoal. If the edge of the new activity touches the end of the horizon (ie 2 second activity scheduled from 14-16, horizon ends at 16), fails. Passes if its a longer interval like 13-16.
    ), true);

    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(goalWindow))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeA)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(1, Duration.SECONDS), actTypeA));
    assertFalse(TestUtility.activityStartingAtTime(plan.get(), Duration.of(5, Duration.SECONDS), actTypeA));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(9, Duration.SECONDS), actTypeA));
    assertFalse(TestUtility.activityStartingAtTime(plan.get(), Duration.of(14, Duration.SECONDS), actTypeA));
  }

  @Test
  public void testCoexistenceUncontrollableJustFits() throws SchedulingInterruptedException {

    Interval period = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(13, Duration.SECONDS));

    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = buildProblemFromFoo(planningHorizon);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("ControllableDurationActivity");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, start at start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(11, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 11s after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(16, Duration.SECONDS)), Duration.of(5, Duration.SECONDS), null, true)); //create an activity that's 5 seconds long, 16s after start

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);
    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    final var actTypeB = problem.getActivityType("BasicActivity");
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeB)
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Bond. James Bond")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    assertEquals(5, plan.get().getActivitiesByTime().size());
  }

  @Test
  public void testCoexistenceExternalResource() throws SchedulingInterruptedException {
    Interval period = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(25, Duration.SECONDS));
    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(25));
    final var problem = SimulationUtility.buildProblemFromFoo(planningHorizon);
    final var r3Value = Map.of("amountInMicroseconds", SerializedValue.of(6));
    final var r1 = new LinearProfile(new Segment<>(Interval.between(Duration.ZERO, Duration.SECONDS.times(5)), new LinearEquation(Duration.ZERO, 5, 1)));
    final var r2 = new DiscreteProfile(new Segment<>(Interval.FOREVER, SerializedValue.of(5)));
    final var r3 = new DiscreteProfile(new Segment<>(Interval.FOREVER, SerializedValue.of(r3Value)));
    final var externalRealProfiles = Map.of("/real/R1", r1);
    final var externalDiscreteProfiles = Map.of(
        "/discrete/R2", r2,
        "/discrete/R3", r3
    );
    problem.setExternalProfile(
        externalRealProfiles,
        externalDiscreteProfiles
    );

    final var profEx = new ProfileExpression<>(new ValueAt<>(
        new ProfileExpression<>(new DiscreteResource("/discrete/R3")),
        new SpansWrapperExpression(new Spans(Interval.at(Duration.of(0, Duration.MICROSECONDS))))));
    final var cond = new And(
        new GreaterThanOrEqual(new RealResource("/real/R1"), new RealValue(6)),
        new Equal<>(new DiscreteResource("/discrete/R2"), new DiscreteValue(SerializedValue.of(5))));
    final var actTypeB = problem.getActivityType("ControllableDurationActivity");
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(period, true).assignGaps(new Windows(Interval.FOREVER, false))))
        .forEach(new SpansFromWindows(new AssignGaps<>(cond, new WindowsWrapperExpression(new Windows(Interval.FOREVER, false)))))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeB)
                            .withArgument("duration", profEx)
                            .build())
        .startsAt(TimeAnchor.START)
        .withinPlanHorizon(planningHorizon)
        .aliasForAnchors("a")
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    final var emptySimulationResults = new SimulationResults(null, null, List.of(), Map.of(), Map.of());
    final var startOfActivity =   cond.evaluate(emptySimulationResults, Interval.FOREVER, new EvaluationEnvironment(externalRealProfiles, externalDiscreteProfiles, Map.of())).iterateEqualTo(true).iterator().next().start;
    assertEquals(1, plan.get().getActivitiesByTime().size());
    final var act = plan.get().getActivitiesByTime().get(0);
    assertEquals(act.duration(), Duration.of(r3Value.get("amountInMicroseconds").asInt().get(), Duration.MICROSECONDS));
    assertEquals(startOfActivity, Duration.of(1, Duration.SECONDS));
    assertEquals(act.startOffset(), startOfActivity);
  }

  @Test
  public void testCoexistenceWithAnchors() throws SchedulingInterruptedException {
    final var period = Interval.betweenClosedOpen(Duration.of(0, Duration.HOURS), Duration.of(20, Duration.HOURS));

    final var bananaMissionModel = SimulationUtility.getBananaMissionModel();
    final var planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochHours(0), TestUtility.timeFromEpochHours(20));

    final var simulationFacade = new CheckpointSimulationFacade(
        bananaMissionModel,
        SimulationUtility.getBananaSchedulerModel(),
        new InMemoryCachedEngineStore(10),
        planningHorizon,
        new SimulationEngineConfiguration(Map.of(), Instant.now(), new MissionModelId(0)),
        () -> false);
    final var problem = new Problem(
        bananaMissionModel,
        planningHorizon,
        simulationFacade,
        SimulationUtility.getBananaSchedulerModel()
    );

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    final var partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("GrowBanana");
    final var actTypeB = problem.getActivityType("PickBanana");
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(3, Duration.HOURS), null, true));
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(5, Duration.HOURS)), Duration.of(3, Duration.HOURS), null, true)); //create an activity that's 5 hours long, start 5 hours after start
    partialPlan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(10, Duration.HOURS)), Duration.of(3, Duration.HOURS), null, true)); //create an activity that's 5 seconds long, starts 10 hours after start

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);
    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    //and cut off in the middle of one of the already present activities (period ends at 18)
    CoexistenceGoal goal = new CoexistenceGoal.Builder()
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
        .forEach(framework)
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(actTypeB)
                            .withArgument("quantity", SerializedValue.of(1))
                            .build())
        .startsAt(TimeAnchor.START)
        .aliasForAnchors("Grow and Pick Bananas")
        .withinPlanHorizon(planningHorizon)
        .build();

    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    assertEquals(6, plan.get().getActivitiesByTime().size());
  }

  @Test
  public void changingForAllTimeIn() throws SchedulingInterruptedException {

    //basic setup
    PlanningHorizon hor = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(hor);

    final var activityTypeIndependent = problem.getActivityType("BasicFooActivity");
    logger.debug("BasicFooActivity: " + activityTypeIndependent.toString());

    final var activityTypeDependent = problem.getActivityType("ControllableDurationActivity");
    logger.debug("ControllableDurationActivity: " + activityTypeDependent.toString());

    TestUtility.createAutoMutexGlobalSchedulingCondition(activityTypeDependent).forEach(problem::add);


    // "Make an expression that depends on a resource (the resource here is mission.activitiesExecuted).
    Expression<Windows> gte = new GreaterThanOrEqual(
        new RealResource("/activitiesExecuted"),
        new RealValue(2.0)
    );

    //[and a goal corresponding to that interval]
    CardinalityGoal whenActivitiesGreaterThan2 = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(19, Duration.SECONDS)))
        .occurences(new Range<>(3, 10))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityTypeDependent)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(gte)
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(hor)
        .build();


    // Then make a goal that adds an activity that changes the resource,"
    //    - Joel
    RecurrenceGoal addRecurringActivityModifyingResource = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor.getHor(), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityTypeIndependent)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(hor)
        .build();

    //problem.setGoals(List.of(whenActivitiesGreaterThan2, addRecurringActivityModifyingResource)); ORDER SENSITIVE
    problem.setGoals(List.of(addRecurringActivityModifyingResource, whenActivitiesGreaterThan2)); //ORDER SENSITIVE

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString() + " -> "+ a.getType().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(0, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(5, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(10, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(15, Duration.SECONDS), activityTypeIndependent));


    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(7, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(9, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(11, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(13, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(15, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(17, Duration.SECONDS), activityTypeDependent));
  }

  @Test
  public void changingForAllTimeInCutoff() throws SchedulingInterruptedException {

    //basic setup
    PlanningHorizon hor = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(18));
    final var problem = buildProblemFromFoo(hor);

    final var activityTypeIndependent = problem.getActivityType("BasicFooActivity");
    logger.debug("BasicFooActivity: " + activityTypeIndependent.toString());

    final var activityTypeDependent = problem.getActivityType("ControllableDurationActivity");
    logger.debug("ControllableDurationActivity: " + activityTypeDependent.toString());

    TestUtility.createAutoMutexGlobalSchedulingCondition(activityTypeDependent).forEach(problem::add);


    // "Make an expression that depends on a resource (the resource here is mission.activitiesExecuted).
    Expression<Windows> gte = new GreaterThanOrEqual(
        new RealResource("/activitiesExecuted"),
        new RealValue(2.0)
    );


    //[and a goal corresponding to that interval]
    CardinalityGoal whenActivitiesGreaterThan2 = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(19, Duration.SECONDS)))
        .occurences(new Range<>(3, 10))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityTypeDependent)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(gte)
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(hor)
        .build();


    // Then make a goal that adds an activity that changes the resource,"
    //    - Joel
    RecurrenceGoal addRecurringActivityModifyingResource = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor.getHor(), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityTypeIndependent)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .separatedByAtLeast(Duration.of(0, Duration.SECONDS))
        .separatedByAtMost(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(hor)
        .build();

    //problem.setGoals(List.of(whenActivitiesGreaterThan2, addRecurringActivityModifyingResource)); ORDER SENSITIVE
    problem.setGoals(List.of(addRecurringActivityModifyingResource, whenActivitiesGreaterThan2)); //ORDER SENSITIVE

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString() + " -> "+ a.getType().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(0, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(5, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(10, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(15, Duration.SECONDS), activityTypeIndependent));


    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(7, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(9, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(11, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(13, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(15, Duration.SECONDS), activityTypeDependent));
    assertFalse(TestUtility.activityStartingAtTime(plan.get(), Duration.of(17, Duration.SECONDS), activityTypeDependent));
  }

  @Test
  public void changingForAllTimeInAlternativeCutoff() throws SchedulingInterruptedException {

    //basic setup
    PlanningHorizon hor = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(20));
    final var problem = buildProblemFromFoo(hor);

    final var activityTypeIndependent = problem.getActivityType("BasicFooActivity");
    logger.debug("BasicFooActivity: " + activityTypeIndependent.toString());

    final var activityTypeDependent = problem.getActivityType("ControllableDurationActivity");
    logger.debug("ControllableDurationActivity: " + activityTypeDependent.toString());

    TestUtility.createAutoMutexGlobalSchedulingCondition(activityTypeDependent).forEach(problem::add);


    // "Make an expression that depends on a resource (the resource here is mission.activitiesExecuted).
    Expression<Windows> gte = new And(
        new LinkedList<>(Arrays.asList(
            new GreaterThanOrEqual(
                new RealResource("/activitiesExecuted"),
                new RealValue(2.0)
            ),
            new WindowsWrapperExpression( //without this would just use planning horizon for time restrictions!
                new Windows(false).set(Interval.between(Duration.of(1, Duration.SECONDS), Duration.of(18, Duration.SECONDS)), true)
            )
        ))
    );


    //[and a goal corresponding to that interval]
    CardinalityGoal whenActivitiesGreaterThan2 = new CardinalityGoal.Builder()
        .duration(Interval.between(Duration.of(16, Duration.SECONDS), Duration.of(19, Duration.SECONDS)))
        .occurences(new Range<>(3, 10))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityTypeDependent)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .named("TestCardGoal")
        .forAllTimeIn(gte)
        .owned(ChildCustody.Jointly)
        .withinPlanHorizon(hor)
        .build();


    // Then make a goal that adds an activity that changes the resource,"
    //    - Joel
    RecurrenceGoal addRecurringActivityModifyingResource = new RecurrenceGoal.Builder()
        .named("Test recurrence goal")
        .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor.getHor(), true)))
        .thereExistsOne(new ActivityExpression.Builder()
                            .ofType(activityTypeIndependent)
                            .durationIn(Duration.of(2, Duration.SECONDS))
                            .build())
        .repeatingEvery(Duration.of(5, Duration.SECONDS))
        .withinPlanHorizon(hor)
        .build();

    //problem.setGoals(List.of(whenActivitiesGreaterThan2, addRecurringActivityModifyingResource)); ORDER SENSITIVE
    problem.setGoals(List.of(addRecurringActivityModifyingResource, whenActivitiesGreaterThan2)); //ORDER SENSITIVE

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString() + " -> "+ a.getType().toString());
    }

    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(0, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(5, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(10, Duration.SECONDS), activityTypeIndependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(15, Duration.SECONDS), activityTypeIndependent));


    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(7, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(9, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(11, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(13, Duration.SECONDS), activityTypeDependent));
    assertTrue(TestUtility.activityStartingAtTime(plan.get(), Duration.of(15, Duration.SECONDS), activityTypeDependent));
    assertFalse(TestUtility.activityStartingAtTime(plan.get(), Duration.of(17, Duration.SECONDS), activityTypeDependent));
  }
}
