package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scenarios tested:
 * 1) Ten-day plan, no anchors: Simulate first half of plan
 * 2) Ten-day plan, no anchors: Simulate second half of plan
 * 3) Ten-day plan, no anchors: Simulate Day 3 to Day 8
 * 4) Ten-day plan, no anchors: Simulate from Day -2 to Day 3
 * 5) Ten-day plan, no anchors: Simulate from Day 8 to Day 13
 * 6) One-day plan, anchors: Simulate around anchors
 * 7) One-day plan, anchors: Start simulation between two anchored activities
 * 8) One-day plan, anchors: End simulation between two anchored activities
 * 9) One-day plan, no anchors: Simulate no duration. Start at hour 5
 */
public class TemporalSubsetSimulationTests {
  final Duration tenDays = Duration.of(10*24, Duration.HOURS);
  final Duration fiveDays = Duration.of(5*24, Duration.HOURS);
  final Duration oneDay = Duration.of(24, Duration.HOURS);
  final Duration oneMinute = Duration.of(1, Duration.MINUTES);
  final Duration fourAndAHalfHours = Duration.of(270, Duration.MINUTES);

  private final Instant planStart = Instant.parse("2023-01-01T00:00:00Z");

  private final Map<String, SerializedValue> arguments = Map.of("unusedArg", SerializedValue.of("test-param"));
  private final SerializedActivity serializedDelayDirective = new SerializedActivity("DelayActivityDirective", arguments);
  private final SerializedValue computedAttributes = new SerializedValue.MapValue(Map.of());

  private static void assertEqualsSimulationResults(SimulationResults expected, SimulationResults actual){
    assertEquals(expected.startTime, actual.startTime);
    assertEquals(expected.duration, actual.duration);
    assertEquals(expected.simulatedActivities.size(), actual.simulatedActivities.size());
    for(final var entry : expected.simulatedActivities.entrySet()){
      final var key = entry.getKey();
      final var expectedValue = entry.getValue();
      final var actualValue = actual.simulatedActivities.get(key);
      assertNotNull(actualValue);
      assertEquals(expectedValue, actualValue);
    }
    assertEquals(expected.unfinishedActivities.size(), actual.unfinishedActivities.size());
    for(final var entry: expected.unfinishedActivities.entrySet()){
      final var key = entry.getKey();
      final var expectedValue = entry.getValue();
      final var actualValue = actual.unfinishedActivities.get(key);
      assertNotNull(actualValue);
      assertEquals(expectedValue, actualValue);
    }
    assertEquals(expected.topics.size(), actual.topics.size());
    for(int i = 0; i < expected.topics.size(); ++i){
      assertEquals(expected.topics.get(i), actual.topics.get(i));
    }
  }

  @Test
  @DisplayName("Ten-day plan, no anchors: Simulate first half of plan")
  public void simulateFirstHalf(){
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(120);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(1);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(240);

    for(int i = 0; i < 120; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
      simulatedActivities.put(
          new ActivityInstanceId(i),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              planStart.plus(i, ChronoUnit.HOURS),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(i)),
              computedAttributes));
    }

    for(int i = 120; i < 240; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    // Activity 120 won't be finished since it starts at the simulation end time
    unfinishedActivities.put(
        new ActivityInstanceId(120),
        new UnfinishedActivity(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(120, ChronoUnit.HOURS),
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(120))));

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart,
        fiveDays,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart,
        fiveDays,
        planStart,
        tenDays,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("Ten-day plan, no anchors: Simulate second half of plan")
  public void simulateSecondHalf(){
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(120);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(240);

    for(int i = 0; i < 120; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    for(int i = 120; i < 240; ++i){
      simulatedActivities.put(
          new ActivityInstanceId(i),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              planStart.plus(i, ChronoUnit.HOURS),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(i)),
              computedAttributes));
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        Map.of(), //The last activity starts an hour before the simulation ends
        planStart.plus(5, ChronoUnit.DAYS),
        fiveDays,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(5, ChronoUnit.DAYS),
        fiveDays,
        planStart,
        tenDays,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("Ten-day plan, no anchors: Simulate Day 3 to Day 8")
  void simulateMiddle() {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(120);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(1);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(240);

    for(int i = 0; i < 72; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    for(int i = 72; i < 192; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
      simulatedActivities.put(
          new ActivityInstanceId(i),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              planStart.plus(i, ChronoUnit.HOURS),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(i)),
              computedAttributes));
    }

    for(int i = 192; i < 240; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    // Activity 192 won't be finished since it starts at the simulation end time
    unfinishedActivities.put(
        new ActivityInstanceId(192),
        new UnfinishedActivity(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(192, ChronoUnit.HOURS),
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(192))));

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart.plus(3, ChronoUnit.DAYS),
        fiveDays,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(3, ChronoUnit.DAYS),
        fiveDays,
        planStart,
        tenDays,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("Ten-day plan, no anchors: Simulate from Day -2 to Day 3")
  void simulateBeforePlanStart() {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(120);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(1);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(240);

    for(int i = -48; i < 72; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
      simulatedActivities.put(
          new ActivityInstanceId(i),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              planStart.plus(i, ChronoUnit.HOURS),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(i)),
              computedAttributes));
    }

    for(int i = 72; i < 240; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    // Activity 72 won't be finished since it starts at the simulation end time
    unfinishedActivities.put(
        new ActivityInstanceId(72),
        new UnfinishedActivity(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(72, ChronoUnit.HOURS),
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(72))));

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart.plus(-2, ChronoUnit.DAYS),
        fiveDays,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(-2, ChronoUnit.DAYS),
        fiveDays,
        planStart,
        tenDays,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("Ten-day plan, no anchors: Simulate from Day 8 to Day 13")
  void simulateAfterPlanEnd() {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(120);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(1);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(240);

    for(int i = 72; i < 192; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    for(int i = 192; i < 312; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
      simulatedActivities.put(
          new ActivityInstanceId(i),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              planStart.plus(i, ChronoUnit.HOURS),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(i)),
              computedAttributes));
    }

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart.plus(8, ChronoUnit.DAYS),
        fiveDays,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(8, ChronoUnit.DAYS),
        fiveDays,
        planStart,
        tenDays,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("One-day plan, anchors: Simulate around anchors")
  void simulateAroundAnchors() {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(16);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(0);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(37);

    // 4 sets of activities, each size 4, Hours 3, 4, 5, 6, 7
    // Base
    activitiesInPlan.put(
        new ActivityDirectiveId(0),
        new ActivityDirective(Duration.of(3, Duration.HOURS), serializedDelayDirective, null, true));
    simulatedActivities.put(
        new ActivityInstanceId(0),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(3, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(0)),
            computedAttributes));

    // Set 1: Start Time Anchor Chain
    activitiesInPlan.put(
        new ActivityDirectiveId(1),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(0), true));
    simulatedActivities.put(
        new ActivityInstanceId(1),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(4, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(1)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(2),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(1), true));
    simulatedActivities.put(
        new ActivityInstanceId(2),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(2)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(3),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(2), true));
    simulatedActivities.put(
        new ActivityInstanceId(3),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(6, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(3)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(4),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(3), true));
    simulatedActivities.put(
        new ActivityInstanceId(4),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(7, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(4)),
            computedAttributes));

    // Set 2: End Time Anchor Chain
    activitiesInPlan.put(
        new ActivityDirectiveId(5),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(0), false));
    simulatedActivities.put(
        new ActivityInstanceId(5),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(4, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(5)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(6),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(5), false));
    simulatedActivities.put(
        new ActivityInstanceId(6),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS).plus(2, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(6)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(7),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(6), false));
    simulatedActivities.put(
        new ActivityInstanceId(7),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(6, ChronoUnit.HOURS).plus(3, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(7)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(8),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(7), false));
    simulatedActivities.put(
        new ActivityInstanceId(8),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(7, ChronoUnit.HOURS).plus(4, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(8)),
            computedAttributes));

    // Set 3: Start-End-Start-End Anchor Chain
    activitiesInPlan.put(
        new ActivityDirectiveId(9),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(0), true));
    simulatedActivities.put(
        new ActivityInstanceId(9),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(4, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(9)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(10),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(9), false));
    simulatedActivities.put(
        new ActivityInstanceId(10),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(10)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(11),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(10), true));
    simulatedActivities.put(
        new ActivityInstanceId(11),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(6, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(11)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(12),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(11), false));
    simulatedActivities.put(
        new ActivityInstanceId(12),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(7, ChronoUnit.HOURS).plus(2, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(12)),
            computedAttributes));

    for(int i = 0; i < 24; i++){
      activitiesInPlan.put(
          new ActivityDirectiveId(i+13),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }
    // Set 4: No Anchors
    for(int i = 3; i < 8; i++){
      simulatedActivities.put(
          new ActivityInstanceId(i + 13),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              planStart.plus(i, ChronoUnit.HOURS),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(i+13)),
              computedAttributes));
    }

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart.plus(3, ChronoUnit.HOURS),
        fourAndAHalfHours,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(3, ChronoUnit.HOURS),
        fourAndAHalfHours,
        planStart,
        oneDay,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("One-day plan, anchors: Start simulation between two anchored activities")
  void simulateStartBetweenAnchors() {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(8);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(0);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(37);

    // Three chains, two interrupted (one end-time, one start-time), one not
    // Chain 1: Interrupted, end-time
    activitiesInPlan.put(
        new ActivityDirectiveId(0),
        new ActivityDirective(Duration.of(2, Duration.HOURS), serializedDelayDirective, null, true));
    activitiesInPlan.put(
        new ActivityDirectiveId(1),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(0), false));
    activitiesInPlan.put(
        new ActivityDirectiveId(2),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(1), false));
    activitiesInPlan.put(
        new ActivityDirectiveId(3),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(2), false));

    // Chain 2: Interrupted, start-time
    activitiesInPlan.put(
        new ActivityDirectiveId(4),
        new ActivityDirective(Duration.of(2, Duration.HOURS), serializedDelayDirective, null, true));
    activitiesInPlan.put(
        new ActivityDirectiveId(5),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(4), true));
    simulatedActivities.put(
        new ActivityInstanceId(5),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(3, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(5)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(6),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(5), true));
    simulatedActivities.put(
        new ActivityInstanceId(6),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(4, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(6)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(7),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(6), true));
    simulatedActivities.put(
        new ActivityInstanceId(7),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(7)),
            computedAttributes));

    // Chain 3: Uninterrupted
    activitiesInPlan.put(
        new ActivityDirectiveId(8),
        new ActivityDirective(Duration.of(3, Duration.HOURS), serializedDelayDirective, null, true));
    simulatedActivities.put(
        new ActivityInstanceId(8),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(3, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(8)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(9),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(8), true));
    simulatedActivities.put(
        new ActivityInstanceId(9),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(4, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(9)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(10),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(9), true));
    simulatedActivities.put(
        new ActivityInstanceId(10),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(10)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(11),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(10), true));
    simulatedActivities.put(
        new ActivityInstanceId(11),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(6, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(11)),
            computedAttributes));

    for(int i = 0; i < 3; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i+24),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }
    for(int i = 8; i < 24; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i+24),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart.plus(3, ChronoUnit.HOURS),
        fourAndAHalfHours,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(3, ChronoUnit.HOURS),
        fourAndAHalfHours,
        planStart,
        oneDay,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("One-day plan, anchors: End simulation between two anchored activities")
  void simulateEndBetweenAnchors() {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(8);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(0);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(37);

    // Three chains, two interrupted (one end-time, one start-time), one not
    // Chain 1: Interrupted, end-time
    activitiesInPlan.put(
        new ActivityDirectiveId(0),
        new ActivityDirective(Duration.of(5, Duration.HOURS), serializedDelayDirective, null, true));
    simulatedActivities.put(
        new ActivityInstanceId(0),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(0)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(1),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(0), false));
    simulatedActivities.put(
        new ActivityInstanceId(1),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(6, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(1)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(2),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(1), false));
    simulatedActivities.put(
        new ActivityInstanceId(2),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(7, ChronoUnit.HOURS).plus(2, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(2)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(3),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(2), false));

    // Chain 2: Interrupted, start-time
    activitiesInPlan.put(
        new ActivityDirectiveId(4),
        new ActivityDirective(Duration.of(5, Duration.HOURS), serializedDelayDirective, null, true));
    simulatedActivities.put(
        new ActivityInstanceId(4),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(4)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(5),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(4), true));
    simulatedActivities.put(
        new ActivityInstanceId(5),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(6, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(5)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(6),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(5), true));
    simulatedActivities.put(
        new ActivityInstanceId(6),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(7, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(6)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(7),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(6), true));

    // Chain 3: Uninterrupted
    activitiesInPlan.put(
        new ActivityDirectiveId(8),
        new ActivityDirective(Duration.of(3, Duration.HOURS), serializedDelayDirective, null, true));
    simulatedActivities.put(
        new ActivityInstanceId(8),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(3, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(8)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(9),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(8), true));
    simulatedActivities.put(
        new ActivityInstanceId(9),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(4, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(9)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(10),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(9), true));
    simulatedActivities.put(
        new ActivityInstanceId(10),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(5, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(10)),
            computedAttributes));
    activitiesInPlan.put(
        new ActivityDirectiveId(11),
        new ActivityDirective(Duration.of(1, Duration.HOURS), serializedDelayDirective, new ActivityDirectiveId(10), true));
    simulatedActivities.put(
        new ActivityInstanceId(11),
        new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(6, ChronoUnit.HOURS),
            oneMinute,
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(11)),
            computedAttributes));


    for(int i = 0; i < 3; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i+24),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }
    for(int i = 8; i < 24; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i+24),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart.plus(3, ChronoUnit.HOURS),
        fourAndAHalfHours,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(3, ChronoUnit.HOURS),
        fourAndAHalfHours,
        planStart,
        oneDay,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }

  @Test
  @DisplayName("One-day plan, no anchors: Simulate no duration. Start at hour 5")
  void simulateNoDuration() {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(0);
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>(1);
    final var activitiesInPlan = new HashMap<ActivityDirectiveId, ActivityDirective>(24);

    for(int i = 0; i < 24; ++i){
      activitiesInPlan.put(
          new ActivityDirectiveId(i),
          new ActivityDirective(Duration.of(i, Duration.HOURS), serializedDelayDirective, null, true));
    }
    unfinishedActivities.put(
        new ActivityInstanceId(12),
        new UnfinishedActivity(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            planStart.plus(12, ChronoUnit.HOURS),
            null,
            List.of(),
            Optional.of(new ActivityDirectiveId(12))));

    // Assert Simulation results
    final var expectedSimResults = new SimulationResults(
        Map.of(), //real
        Map.of(), //discrete
        simulatedActivities,
        unfinishedActivities,
        planStart.plus(12, ChronoUnit.HOURS),
        Duration.ZERO,
        TestMissionModel.getModelTopicList(),
        new TreeMap<>() //events
    );
    final var actualSimResults = SimulationDriver.simulate(
        TestMissionModel.missionModel(),
        activitiesInPlan,
        planStart.plus(12, ChronoUnit.HOURS),
        Duration.ZERO,
        planStart,
        oneDay,
        () -> false);

    assertEqualsSimulationResults(expectedSimResults, actualSimResults);
  }
}
