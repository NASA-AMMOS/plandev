package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.ActivitySpan;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.constraints.tree.ForEachActivitySpans;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeAnchor;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeExpressionRelative;
import gov.nasa.jpl.aerie.scheduler.goals.CoexistenceGoal;
import gov.nasa.jpl.aerie.scheduler.model.*;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.InMemoryCachedEngineStore;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.function.TriFunction;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPersistentAnchor {

  public record TestData(
      Optional<Plan> plan,
      ArrayList<SchedulingActivity> actsToBeAnchored,
      ArrayList<SchedulingActivity> actsWithAnchor,
      ArrayList<SchedulingActivity> actsWithoutAnchorAnchored,
      ArrayList<SchedulingActivity> actsWithoutAnchorNotAnchored,
      ArrayList<SchedulingActivity> actsNewAnchored
  ) {}

  private static final Logger logger = LoggerFactory.getLogger(TestPersistentAnchor.class);

  private static Expression<Spans> spansOfConstraintExpression(
      final ActivityExpression ae)
  {
    return new ForEachActivitySpans(
        new TriFunction<>() {
          @Override
          public Boolean apply(
              final ActivityInstance activityInstance,
              final SimulationResults simResults,
              final EvaluationEnvironment environment)
          {
            final var startTime = activityInstance.interval().start;
            if (!activityInstance.type().equals(ae.type().getName())) return false;
            for (final var arg : ae
                .arguments()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry
                    .getValue()
                    .evaluate(
                        simResults,
                        Interval.at(startTime),
                        environment)
                    .valueAt(startTime)
                    .orElseThrow()))
                .entrySet()) {
              if (!arg.getValue().equals(activityInstance.parameters().get(arg.getKey()))) return false;
            }
            return true;
          }

          @Override
          public String toString() {
            return "(filter by ActivityExpression)";
          }
        },
        "alias" + ae.type(),
        new ActivitySpan("alias" + ae.type())
    );
  }

  public boolean allAnchorsIncluded(final TestData testData) {
    if(testData.actsToBeAnchored == null || testData.actsToBeAnchored.isEmpty())
      return true;
    if(testData.plan.isEmpty())
      return false;

    Set<ActivityDirectiveId> planActivityAnchors = testData.plan.get().getAnchorIds();
    for(final var act : testData.actsToBeAnchored){
      if(!planActivityAnchors.contains(act.anchorId()))
        return false;
    }
    return true;
  }

  /**
   * Test that all directives added manually to the plan in the test (not by the scheduler)
   * with the purpose of being anchored do actually get an anchor
   * @param testData
   * @return
   */
  public boolean checkAnchoredActivities(final TestData testData, final boolean allowCreationAnchors, final boolean missingActAssociationsWithAnchor) {
    if(testData.actsWithAnchor == null || testData.actsWithAnchor.isEmpty())
      return true;
    if(testData.plan.isEmpty())
      return false;

    Set<ActivityDirectiveId> anchorIds = testData.actsToBeAnchored.stream()
                                                                  .map(SchedulingActivity::id)
                                                                  .collect(Collectors.toSet());

    final var mapIdToActivity = testData.plan.get().getActivitiesById();

    if (allowCreationAnchors || missingActAssociationsWithAnchor){
      for (final var act: testData.actsWithAnchor){
        final var directive = mapIdToActivity.get(act.id());
        if (directive.anchorId() == null || !anchorIds.contains(directive.anchorId()))
          return false;
        anchorIds.remove(directive.anchorId());
      }
    }

    for (final var act: testData.actsNewAnchored){
      final var directive = mapIdToActivity.get(act.id());
      if (directive.anchorId() == null || !anchorIds.contains(directive.anchorId()))
        return false;
      anchorIds.remove(directive.anchorId());
    }
    return anchorIds.isEmpty();
  }


  /**
   * Test that all directives added manually to the plan in the test (not by the scheduler)
   * that cannot be anchored (e.g. because it is not allowed to modify them) don't actually get an anchor
   * @param testData
   * @return
   */
  public boolean checkUnanchoredActivities(final TestData testData) {
    if(testData.actsWithoutAnchorNotAnchored == null || testData.actsWithoutAnchorNotAnchored.isEmpty())
      return true;
    if(testData.plan.isEmpty())
      return false;

    final var mapIdToActivity = testData.plan.get().getActivitiesById();
    for (final var act: testData.actsWithoutAnchorNotAnchored){
      final var directive = mapIdToActivity.get(act.id());
      if (directive.anchorId() != null)
        return false;
    }
    return true;
  }


  /*
  Test cases for temporal relation StartsAt Start, with anchor at start and end
   */

  // Anchor Disabled
  @Test
  public void testCaseStartAtStartDisable00() throws SchedulingInterruptedException{

    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, false, false, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtDisable01() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, false, true, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtDisable10() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, true, false, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtDisable11() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, true, true, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(9, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  // Anchor at START
  @Test
  public void testCaseStartAtStartAnchorAtStart00() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, false, false, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtStart01() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, false, true, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtStart10() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, true, false, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtStart11() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, true, true, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(9, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  //Anchor at END

  // In this test the template activities cannot be added as the anchor is in the future
  @Test
  public void testCaseStartAtStartAnchorAtEnd00() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, false, false, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(3, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtEnd01() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, false, true, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtEnd10() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, true, false, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAtEnd11() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, true, true, 2, 0, 20, TimeAnchor.START, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(9, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }


  /*
  Test cases for temporal relation StartsAt End, with anchor at start and end
   */

  // Anchor Disabled
  @Test
  public void testCaseStartAtEndAnchorAtDisable00() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, false, false, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtDisable01() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, false, true, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtDisable10() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, true, false, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtDisable11() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.DISABLED, true, true, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(9, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, false, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  // Anchor to START
  @Test
  public void testCaseStartAtStartAnchorAt00() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, false, false, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAt01() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, false, true, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAt10() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, true, false, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtStartAnchorAt11() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.START, true, true, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(9, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  //Anchor at END
  @Test
  public void testCaseStartAtEndAnchorAt00() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, false, false, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtEndAnchorAt01() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, false, true, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, false));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtEndAnchorAt10() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, true, false, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(6, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }

  @Test
  public void testCaseStartAtEndAnchorAt11() throws SchedulingInterruptedException{
    TestData testData = createTestCaseStartsAt(PersistentTimeAnchor.END, true, true, 2, 0, 20, TimeAnchor.END, null, 0);
    assertTrue(testData.plan.isPresent());
    assertEquals(9, testData.plan.get().getActivitiesById().size());
    assertTrue(allAnchorsIncluded(testData));
    assertTrue(checkAnchoredActivities(testData, true, true));
    assertTrue(checkUnanchoredActivities(testData));
  }


  /* Test cases in which the goal is created with TimeExpression endsAt
   */

  public TestData createTestCaseStartsAt(final PersistentTimeAnchor persistentAnchor,  final boolean missingActAssociationsWithAnchor, final boolean missingActAssociationsWithoutAnchor, final int activityDurationHours, final int goalStartPeriodHours, final int goalEndPeriodHours, final TimeAnchor timeAnchor, final TimeExpressionRelative timeExpression, final long relativeOffsetHours)
  throws SchedulingInterruptedException
  {
    var actsToBeAnchored = new ArrayList<SchedulingActivity>();
    var templateActsAlreadyAnchor = new ArrayList<SchedulingActivity>();
    var templateActsWithoutAnchorAnchored = new ArrayList<SchedulingActivity>();
    var templateActsWithoutAnchorNotAnchored = new ArrayList<SchedulingActivity>();

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

    final var idGenerator = new DirectiveIdGenerator(0);

    //have some activity already present
    //  create a PlanInMemory, add ActivityInstances
    PlanInMemory partialPlan = new PlanInMemory();
    final var actTypeA = problem.getActivityType("GrowBanana");
    SchedulingActivity act1 = SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie(), Duration.of(activityDurationHours, Duration.HOURS), Map.of(
        "quantity", SerializedValue.of(1),
        "growingDuration", SerializedValue.of(Duration.HOUR.times(activityDurationHours).in(Duration.HOURS))
    ), null, null, true);
    partialPlan.add(act1);
    actsToBeAnchored.add(act1);

    SchedulingActivity act2 = SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(5, Duration.HOURS)), Duration.of(activityDurationHours, Duration.HOURS), Map.of(
        "quantity", SerializedValue.of(1),
        "growingDuration", SerializedValue.of(Duration.HOUR.times(activityDurationHours).in(Duration.HOURS))
    ), null, null, true);
    partialPlan.add(act2);
    actsToBeAnchored.add(act2);

    SchedulingActivity act3 = SchedulingActivity.of(idGenerator.next(), actTypeA, planningHorizon.getStartAerie().plus(Duration.of(10, Duration.HOURS)), Duration.of(activityDurationHours, Duration.HOURS), Map.of(
        "quantity", SerializedValue.of(1),
        "growingDuration", SerializedValue.of(Duration.HOUR.times(activityDurationHours).in(Duration.HOURS))
    ), null, null, true);
    partialPlan.add(act3);
    actsToBeAnchored.add(act3);

    final var actTypeB = problem.getActivityType("PickBanana");

    boolean anchoredToStart;
    if(timeAnchor != null)
      anchoredToStart = timeAnchor.equals(TimeAnchor.START);
    else
      anchoredToStart = timeExpression.getAnchor().equals(TimeAnchor.START);

    // Nominal anchor is right at the start or end
    Duration relativeOffset = Duration.of(relativeOffsetHours, Duration.HOURS);
    Duration offsetWithDuration = Duration.of(0, Duration.HOURS);
    // If TimeAnchor or TimeExpressionRelative is related to the end of Goal directive, then need to add goal directive duration
    if(!anchoredToStart){
      offsetWithDuration = offsetWithDuration.plus(activityDurationHours, Duration.HOURS);
    }
    // If in addition the goal uses an offset, it needs to be added
    if(timeAnchor == null){
      offsetWithDuration = offsetWithDuration.plus(relativeOffsetHours, Duration.HOURS);
    }

    if(missingActAssociationsWithAnchor){
      // Activities with anchors
      SchedulingActivity act4 = SchedulingActivity.of(idGenerator.next(), actTypeB, relativeOffset, Duration.of(activityDurationHours, Duration.HOURS), Map.of(
          "quantity", SerializedValue.of(1)), null, act1.id(), anchoredToStart);
      partialPlan.add(act4);
      templateActsAlreadyAnchor.add(act4);

      SchedulingActivity act5 = SchedulingActivity.of(idGenerator.next(), actTypeB, relativeOffset, Duration.of(activityDurationHours, Duration.HOURS), Map.of(
          "quantity", SerializedValue.of(1)), null, act2.id(), anchoredToStart);
      partialPlan.add(act5);
      templateActsAlreadyAnchor.add(act5);

      SchedulingActivity act6 = SchedulingActivity.of(idGenerator.next(), actTypeB, relativeOffset, Duration.of(activityDurationHours, Duration.HOURS), Map.of(
          "quantity", SerializedValue.of(1)), null, act3.id(), anchoredToStart);
      partialPlan.add(act6);
      templateActsAlreadyAnchor.add(act6);
    }

    if(missingActAssociationsWithoutAnchor){
      // Activities without anchors
      SchedulingActivity act7 = SchedulingActivity.of(idGenerator.next(), actTypeB, planningHorizon.getStartAerie().plus(offsetWithDuration), Duration.of(activityDurationHours, Duration.HOURS), Map.of(
          "quantity", SerializedValue.of(1)), null, null, anchoredToStart);
      partialPlan.add(act7);

      SchedulingActivity act8 = SchedulingActivity.of(idGenerator.next(), actTypeB, planningHorizon.getStartAerie().plus(Duration.of(5, Duration.HOURS)).plus(offsetWithDuration), Duration.of(activityDurationHours, Duration.HOURS), Map.of(
          "quantity", SerializedValue.of(1)), null, null, anchoredToStart);
      partialPlan.add(act8);

      SchedulingActivity act9 = SchedulingActivity.of(idGenerator.next(), actTypeB, planningHorizon.getStartAerie().plus(Duration.of(10, Duration.HOURS)).plus(offsetWithDuration), Duration.of(activityDurationHours, Duration.HOURS), Map.of(
          "quantity", SerializedValue.of(1)), null, null, anchoredToStart);
      partialPlan.add(act9);

      if (!persistentAnchor.equals(PersistentTimeAnchor.DISABLED) && !missingActAssociationsWithAnchor) {
        templateActsWithoutAnchorAnchored.add(act7);
        templateActsWithoutAnchorAnchored.add(act8);
        templateActsWithoutAnchorAnchored.add(act9);
      }
      else{
        templateActsWithoutAnchorNotAnchored.add(act7);
        templateActsWithoutAnchorNotAnchored.add(act8);
        templateActsWithoutAnchorNotAnchored.add(act9);
      }
    }

    //  pass this plan as initialPlan to Problem object
    problem.setInitialPlan(partialPlan);
    //want to create another activity for each of the already present activities
    //  foreach with activityexpression
    ActivityExpression framework = new ActivityExpression.Builder()
        .ofType(actTypeA)
        .build();

    Interval period = Interval.betweenClosedOpen(Duration.of(goalStartPeriodHours, Duration.HOURS), Duration.of(goalEndPeriodHours, Duration.HOURS));
    CoexistenceGoal goal;
    if(timeAnchor != null){
      goal = new CoexistenceGoal.Builder()
          .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
          .forEach(spansOfConstraintExpression(framework))
          .thereExistsOne(new ActivityExpression.Builder()
                              .ofType(actTypeB)
                              .withArgument("quantity", SerializedValue.of(1))
                              .build())
          .startsAt(timeAnchor)
          .aliasForAnchors("Grow and Pick Bananas")
          .createPersistentAnchor(persistentAnchor)
          .withinPlanHorizon(planningHorizon)
          .build();
    }
    else{
      goal = new CoexistenceGoal.Builder()
          .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(period, true)))
          .forEach(spansOfConstraintExpression(framework))
          .thereExistsOne(new ActivityExpression.Builder()
                              .ofType(actTypeB)
                              .withArgument("quantity", SerializedValue.of(1))
                              .build())
          .startsAt(timeExpression)
          .aliasForAnchors("Grow and Pick Bananas")
          .createPersistentAnchor(persistentAnchor)
          .withinPlanHorizon(planningHorizon)
          .build();
    }
    problem.setGoals(List.of(goal));

    final var solver = new PrioritySolver(problem);
    var plan = solver.getNextSolution();
    var templateNewActsAnchored = new ArrayList<>(plan.get().getActivities());
    templateNewActsAnchored.removeAll(partialPlan.getActivities());


    for(SchedulingActivity a : plan.get().getActivitiesByTime()){
      logger.debug(a.startOffset().toString() + ", " + a.duration().toString());
    }
    return new TestData(plan, actsToBeAnchored, templateActsAlreadyAnchor, templateActsWithoutAnchorAnchored, templateActsWithoutAnchorNotAnchored, templateNewActsAnchored);
  }
}
