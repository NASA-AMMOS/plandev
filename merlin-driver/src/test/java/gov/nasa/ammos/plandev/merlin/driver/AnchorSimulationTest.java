package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public final class AnchorSimulationTest {
  private final Duration tenDays = Duration.duration(10 * 60 * 60 * 24, Duration.SECONDS);
  private final static Duration oneMinute = Duration.of(60, Duration.SECONDS);
  private final Map<String, SerializedValue> arguments = Map.of("unusedArg", SerializedValue.of("test-param"));
  private final SerializedActivity serializedActivity = new SerializedActivity("unusedType", arguments);
  @Nested
  public final class StartOffsetReducerTests {
    @Test
    public void nullInputToStartOffsetReducer(){
      // Due to implementation, this swallows an Empty Map case.
      assertTrue(new StartOffsetReducer(tenDays, null).compute().isEmpty());
    }

    @Test
    @DisplayName("Activities anchored to the plan depend on no activities")
    public void activitiesWithoutAnchor() {
      final var activityDirectives = new HashMap<ActivityDirectiveId, ActivityDirective>(15);

      // Anchored to Plan Start (only positive is allowed)
      for (long l = 0; l < 5; l++) {
        activityDirectives.put(
            new ActivityDirectiveId(l),
            new ActivityDirective(Duration.of(l, Duration.SECONDS), serializedActivity, null, true));
      }
      // Anchored to Plan End, positive
      for (long l = 5; l < 10; l++) {
        activityDirectives.put(
            new ActivityDirectiveId(l),
            new ActivityDirective(Duration.of(l, Duration.SECONDS),
                                  serializedActivity,
                                  null,
                                  false));
      }
      // Anchored to Plan End, negative
      for (long l = 10; l < 15; l++) {
        activityDirectives.put(
            new ActivityDirectiveId(l),
            new ActivityDirective(Duration.of(-l, Duration.SECONDS),
                                  serializedActivity,
                                  null,
                                  false));
      }

      final var reducedOffsets = new StartOffsetReducer(tenDays, activityDirectives).compute();

      assertEquals(1, reducedOffsets.size());
      assertNotNull(reducedOffsets.get(null));
      assertEquals(15, reducedOffsets.get(null).size());

      reducedOffsets.get(null).sort(Comparator.comparingLong(pair -> pair.getLeft().id()));
      // Check offset values
      // Anchored to Plan Start
      for (long l = 0; l < 5; l++) {
        assertEquals(new ActivityDirectiveId(l), reducedOffsets.get(null).get((int) l).getLeft());
        assertEquals(Duration.of(l, Duration.SECONDS), reducedOffsets.get(null).get((int) l).getRight());
      }
      // Anchored to Plan End, positive
      for (long l = 5; l < 10; l++) {
        assertEquals(new ActivityDirectiveId(l), reducedOffsets.get(null).get((int) l).getLeft());
        assertEquals(tenDays.plus(Duration.of(l, Duration.SECONDS)), reducedOffsets.get(null).get((int) l).getRight());
      }
      // Anchored to Plan End, negative
      for (long l = 10; l < 15; l++) {
        assertEquals(new ActivityDirectiveId(l), reducedOffsets.get(null).get((int) l).getLeft());
        assertEquals(tenDays.plus(Duration.of(-l, Duration.SECONDS)), reducedOffsets.get(null).get((int) l).getRight());
      }
    }

    @Test
    @DisplayName("Anchor chains that only contain start-time anchors depend on no activities")
    public void startTimeChains() {
      // Check chains and values
      final var minusOneMinute = Duration.of(-60, Duration.SECONDS);
      final var activityDirectives = new HashMap<ActivityDirectiveId, ActivityDirective>(400);

      activityDirectives.put(
          new ActivityDirectiveId(0),
          new ActivityDirective(oneMinute, serializedActivity, null, true));

      for (long l = 1; l < 400; l++) {
        if ((l & 1) == 0) { // If even
          activityDirectives.put(
              new ActivityDirectiveId(l),
              new ActivityDirective(oneMinute,
                                    serializedActivity,
                                    new ActivityDirectiveId(l - 1),
                                    true));
        } else {
          activityDirectives.put(
              new ActivityDirectiveId(l),
              new ActivityDirective(minusOneMinute,
                                    serializedActivity,
                                    new ActivityDirectiveId(l - 1),
                                    true));
        }
      }

      final var reducedOffsets = new StartOffsetReducer(tenDays, activityDirectives).compute();

      assertEquals(1, reducedOffsets.size());
      assertNotNull(reducedOffsets.get(null));
      assertEquals(400, reducedOffsets.get(null).size());

      reducedOffsets.get(null).sort(Comparator.comparingLong(pair -> pair.getLeft().id()));
      // Check offset values
      for (long l = 0; l < 400; l++) {
        // Offset alternates between 0 and 1 Minute
        if ((l & 1) == 0) { // If even
          assertEquals(new ActivityDirectiveId(l), reducedOffsets.get(null).get((int) l).getLeft());
          assertEquals(oneMinute, reducedOffsets.get(null).get((int) l).getRight());
        } else {
          assertEquals(new ActivityDirectiveId(l), reducedOffsets.get(null).get((int) l).getLeft());
          assertEquals(Duration.ZERO, reducedOffsets.get(null).get((int) l).getRight());
        }
      }
    }

    @Test
    @DisplayName("Anchor chains following an anchor on an activity's end time depend on that activity")
    public void chainsWithEndTimeAnchors() {
      //check chains and values
      final var allEndTimeAnchors = new HashMap<ActivityDirectiveId, ActivityDirective>(400);
      final var endTimeAnchorEveryFifth = new HashMap<ActivityDirectiveId, ActivityDirective>(400);

      allEndTimeAnchors.put(
          new ActivityDirectiveId(0),
          new ActivityDirective(oneMinute, serializedActivity, null, true));
      endTimeAnchorEveryFifth.put(
          new ActivityDirectiveId(0),
          new ActivityDirective(oneMinute, serializedActivity, null, true));

      for (long l = 1; l < 400; l++) {
        allEndTimeAnchors.put(
            new ActivityDirectiveId(l),
            new ActivityDirective(oneMinute,
                                  serializedActivity,
                                  new ActivityDirectiveId(l - 1),
                                  false));
        if (l % 5 == 0) {
          endTimeAnchorEveryFifth.put(
              new ActivityDirectiveId(l),
              new ActivityDirective(oneMinute,
                                    serializedActivity,
                                    new ActivityDirectiveId(l - 1),
                                    false));
        } else {
          endTimeAnchorEveryFifth.put(
              new ActivityDirectiveId(l),
              new ActivityDirective(oneMinute,
                                    serializedActivity,
                                    new ActivityDirectiveId(l - 1),
                                    true));
        }
      }

      final var reducedOffsetsAllETA = new StartOffsetReducer(tenDays, allEndTimeAnchors).compute();
      final var reducedOffsetsEveryFifth = new StartOffsetReducer(tenDays, endTimeAnchorEveryFifth).compute();

      assertEquals(400, reducedOffsetsAllETA.size());
      assertEquals(80, reducedOffsetsEveryFifth.size());

      assertNotNull(reducedOffsetsAllETA.get(null));
      assertEquals(1, reducedOffsetsAllETA.get(null).size());
      assertEquals(new ActivityDirectiveId(0), reducedOffsetsAllETA.get(null).get(0).getLeft());
      assertEquals(oneMinute, reducedOffsetsAllETA.get(null).get(0).getRight());

      assertNotNull(reducedOffsetsEveryFifth.get(null));
      assertEquals(5, reducedOffsetsEveryFifth.get(null).size());
      for (long i = 0; i < 4; i++) {
        assertEquals(new ActivityDirectiveId(i), reducedOffsetsEveryFifth.get(null).get((int) i).getLeft());
        assertEquals(
            Duration.of((i + 1) * 60, Duration.SECONDS),
            reducedOffsetsEveryFifth.get(null).get((int) i).getRight());
      }

      // The last element is checked as part of the l+1's
      for (long l = 1; l < 399; l++) {
        assertNotNull(reducedOffsetsAllETA.get(new ActivityDirectiveId(l)));
        assertEquals(1, reducedOffsetsAllETA.get(new ActivityDirectiveId(l)).size());
        assertEquals(
            new ActivityDirectiveId(l + 1),
            reducedOffsetsAllETA.get(new ActivityDirectiveId(l)).get(0).getLeft());
        assertEquals(oneMinute, reducedOffsetsAllETA.get(new ActivityDirectiveId(l)).get(0).getRight());

        if (l % 5 == 0) {
          assertNotNull(reducedOffsetsEveryFifth.get(new ActivityDirectiveId(l - 1)));
          assertEquals(5, reducedOffsetsEveryFifth.get(new ActivityDirectiveId(l - 1)).size());
          for (long i = 0; i < 4; i++) {
            assertEquals(new ActivityDirectiveId(i + l), reducedOffsetsEveryFifth
                .get(new ActivityDirectiveId(l - 1))
                .get((int) i)
                .getLeft());
            assertEquals(
                Duration.of((i + 1) * 60, Duration.SECONDS),
                reducedOffsetsEveryFifth.get(new ActivityDirectiveId(l - 1)).get((int) i).getRight());
          }
        }
      }
    }

    @Test
    @DisplayName("No duplicates from StartOffsetReducer")
    public void startOffsetReducerManyActivities() {
      // This is not a performance test.
      // This test proves why we can use list.addAll() during the join step instead of using a set to remove duplicates
      // Odd vs Even because the activities are divided in half when the Reducer splits
      final var oddAmountActivities = new HashMap<ActivityDirectiveId, ActivityDirective>(12001);
      final var evenAmountActivities = new HashMap<ActivityDirectiveId, ActivityDirective>(12000);

      oddAmountActivities.put(
          new ActivityDirectiveId(1),
          new ActivityDirective(Duration.of(1, Duration.SECONDS), serializedActivity, null, true));
      evenAmountActivities.put(
          new ActivityDirectiveId(1),
          new ActivityDirective(Duration.of(1, Duration.SECONDS), serializedActivity, null, true));
      for (long l = 2; l < 12001; l++) {
        oddAmountActivities.put(
            new ActivityDirectiveId(l),
            new ActivityDirective(Duration.of(l, Duration.SECONDS),
                                  serializedActivity,
                                  new ActivityDirectiveId(l - 1),
                                  true));
        evenAmountActivities.put(
            new ActivityDirectiveId(l),
            new ActivityDirective(Duration.of(l, Duration.SECONDS),
                                  serializedActivity,
                                  new ActivityDirectiveId(l - 1),
                                  true));
      }
      oddAmountActivities.put(
          new ActivityDirectiveId(12001),
          new ActivityDirective(Duration.of(0, Duration.SECONDS), serializedActivity, null, true));

      final var oddReduced = new StartOffsetReducer(tenDays, oddAmountActivities).compute();
      final var evenReduced = new StartOffsetReducer(tenDays, evenAmountActivities).compute();

      assertEquals(1, oddReduced.size());
      assertEquals(1, evenReduced.size());
      assertNotNull(oddReduced.get(null));
      assertNotNull(evenReduced.get(null));
      assertEquals(12001, oddReduced.get(null).size());
      assertEquals(12000, evenReduced.get(null).size());
      // Ensure that the activities are in ascending order
      oddReduced.get(null).sort(Comparator.comparingLong(pair -> pair.getLeft().id()));
      evenReduced.get(null).sort(Comparator.comparingLong(pair -> pair.getLeft().id()));
      // Check for no dupes in both sets
      for (long l = 1; l < 12001; l++) {
        assertEquals(new ActivityDirectiveId(l), oddReduced.get(null).get((int) (l - 1)).getLeft());
        assertEquals(new ActivityDirectiveId(l), evenReduced.get(null).get((int) (l - 1)).getLeft());
      }
      assertEquals(new ActivityDirectiveId(12001), oddReduced.get(null).get(12000).getLeft());
    }

    @Test
    @DisplayName("adjustStartOffset() adjusts start time correctly")
    public void adjustStartOffsetTest() {
      /*
      Cases:
       1) Zero Difference
       2) Positive Difference
       3) Negative Difference
      */
      final Duration oneMinute = Duration.of(1, Duration.MINUTES);
      final Duration minusOneMin = Duration.of(-1, Duration.MINUTES);
      final Duration twoMinutes = Duration.of(2, Duration.MINUTES);
      final Duration minusTwoMins = Duration.of(-2, Duration.MINUTES);

      // This asserts that the original list is unaffected, as it is an unmodifiable list of immutable elements.
      final var immutableReference = List.of(
          Pair.of(new ActivityDirectiveId(0), Duration.ZERO),
          Pair.of(new ActivityDirectiveId(1), oneMinute),
          Pair.of(new ActivityDirectiveId(2), minusOneMin));

      final var case1 = StartOffsetReducer.adjustStartOffset(immutableReference, Duration.ZERO);
      final var case2 = StartOffsetReducer.adjustStartOffset(immutableReference, oneMinute);
      final var case3 = StartOffsetReducer.adjustStartOffset(immutableReference, minusOneMin);

      // Case 1
      assertEquals(3, case1.size());
      assertEquals(Pair.of(new ActivityDirectiveId(0), Duration.ZERO), case1.get(0));
      assertEquals(Pair.of(new ActivityDirectiveId(1), oneMinute), case1.get(1));
      assertEquals(Pair.of(new ActivityDirectiveId(2), minusOneMin), case1.get(2));

      // Case 2
      assertEquals(3, case2.size());
      assertEquals(Pair.of(new ActivityDirectiveId(0), minusOneMin), case2.get(0));
      assertEquals(Pair.of(new ActivityDirectiveId(1), Duration.ZERO), case2.get(1));
      assertEquals(Pair.of(new ActivityDirectiveId(2), minusTwoMins), case2.get(2));

      // Case 3
      assertEquals(3, case3.size());
      assertEquals(Pair.of(new ActivityDirectiveId(0), oneMinute), case3.get(0));
      assertEquals(Pair.of(new ActivityDirectiveId(1), twoMinutes), case3.get(1));
      assertEquals(Pair.of(new ActivityDirectiveId(2), Duration.ZERO), case3.get(2));
    }

    @Test
    @DisplayName("adjustStartOffset() returns null on null original")
    public void adjustStartOffsetNullOriginal() {
      assertNull(StartOffsetReducer.adjustStartOffset(null, tenDays));
    }

    @Test
    @DisplayName("adjustStartOffset() returns empty list on empty original")
    public void adjustStartOffsetEmptyOriginal() {
      assertTrue(StartOffsetReducer.adjustStartOffset(List.of(), tenDays).isEmpty());
    }

    @Test
    @DisplayName("adjustStartOffset() raises NPE on null difference")
    public void adjustStartOffsetNullDifference() {
      try {
        StartOffsetReducer.adjustStartOffset(
            List.of(Pair.of(new ActivityDirectiveId(1), tenDays)),
            null);
        fail();
      } catch (NullPointerException npe) {
        if (!npe.getMessage().contains("Cannot adjust start offset because \"difference\" is null.")){
          throw npe;
        }
      }
    }

    @Test
    @DisplayName("filterOutNegativeStartOffset() returns null on null input")
    public void filterNullInput() {
      assertNull(StartOffsetReducer.filterOutNegativeStartOffset(null));
    }

    @Test
    @DisplayName("filterOutNegativeStartOffset() returns empty HashMap on empty input")
    public void filterEmptyInput() {
      assertTrue(StartOffsetReducer.filterOutNegativeStartOffset(new HashMap<>()).isEmpty());
    }

    @Test
    @DisplayName("filterOutNegativeStartOffset() raises RTE on impossible input")
    public void filterNothingInNull() {
      final var map = new HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>>(1);
      map.put(new ActivityDirectiveId(1), List.of(Pair.of(new ActivityDirectiveId(2), Duration.ZERO)));
      try {
        StartOffsetReducer.filterOutNegativeStartOffset(map);
        fail();
      } catch (RuntimeException rte) {
        if(!rte.getMessage().contains("None of the activities in \"toFilter\" are anchored to the plan")){
          throw rte;
        }
      }
    }

    @Test
    @DisplayName("Filter only filters out correct activities")
    public void filterTest() {
      /*
        Scenarios Tested:
         1) Negative in null is excluded from output
         2) Entire chain is excluded from output
         3) Negative not in null and not in chain to be excluded (while invalid) is included in output
         4) Positive in null is included in output
         5) Positive not in null and not in chain to be excluded is included in output
         6) Zero in null is included in output
      */
      final Duration minusTenMins = Duration.of(-10, Duration.MINUTES);
      final Duration oneMinute = Duration.of(1, Duration.MINUTES);
      final Duration minusOneMin = Duration.of(-1, Duration.MINUTES);

      /*
        Hashmap Overview:
         null = (1, -10), (2, 1), (3, 0), (4, -1) [Satisfies 1), 4), and 6)]
         1 = (5, 1 min), (6, 0 mins)  [Satisfies 2)]
         2 = (7, 1 min), (8, -1 mins) [Satisfies 5) and 3)]
         5 = (9, 1 min), (10, 0 mins), (11, -1 mins)
         7 = (12, 0 mins)
      */
      final var map = new HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>>(5);
      map.put(null, List.of(
          Pair.of(new ActivityDirectiveId(1), minusTenMins),
          Pair.of(new ActivityDirectiveId(2), oneMinute),
          Pair.of(new ActivityDirectiveId(3), Duration.ZERO),
          Pair.of(new ActivityDirectiveId(4), minusOneMin)));
      map.put(new ActivityDirectiveId(1), List.of(
          Pair.of(new ActivityDirectiveId(5), oneMinute),
          Pair.of(new ActivityDirectiveId(6), Duration.ZERO)));
      map.put(new ActivityDirectiveId(2), List.of(
          Pair.of(new ActivityDirectiveId(7), oneMinute),
          Pair.of(new ActivityDirectiveId(8), minusOneMin)));
      map.put(new ActivityDirectiveId(5), List.of(
          Pair.of(new ActivityDirectiveId(9), oneMinute),
          Pair.of(new ActivityDirectiveId(10), Duration.ZERO),
          Pair.of(new ActivityDirectiveId(11), minusOneMin)));
      map.put(new ActivityDirectiveId(7), List.of(
          Pair.of(new ActivityDirectiveId(12), Duration.ZERO)));

      final var filtered = StartOffsetReducer.filterOutNegativeStartOffset(map);
      /*
        Hashmap should now look like:
         null = (2, 1), (3, 0)
         2 = (7, 1 min), (8, -1 mins)
         7 = (12, 0 mins)
      */
      final var two = new ActivityDirectiveId(2);
      final var three = new ActivityDirectiveId(3);
      final var seven = new ActivityDirectiveId(7);
      final var eight = new ActivityDirectiveId(8);
      final var twelve = new ActivityDirectiveId(12);

      assertEquals(3, filtered.size());
      assertTrue(filtered.containsKey(null));
      assertTrue(filtered.containsKey(two));
      assertTrue(filtered.containsKey(seven));

      assertEquals(2, filtered.get(null).size());
      assertEquals(Pair.of(two, oneMinute), filtered.get(null).get(0));
      assertEquals(Pair.of(three, Duration.ZERO), filtered.get(null).get(1));

      assertEquals(2, filtered.get(two).size());
      assertEquals(Pair.of(seven, oneMinute), filtered.get(two).get(0));
      assertEquals(Pair.of(eight, minusOneMin), filtered.get(two).get(1));

      assertEquals(1, filtered.get(seven).size());
      assertEquals(Pair.of(twelve, Duration.ZERO), filtered.get(seven).get(0));

      // Additionally, assert that the original HashMap is unaffected
      // Since immutable lists were used to create the rows, the issue is if all the keys are still present
      assertEquals(5, map.size());
      assertTrue(map.containsKey(null));
      assertTrue(map.containsKey(new ActivityDirectiveId(1)));
      assertTrue(map.containsKey(two));
      assertTrue(map.containsKey(new ActivityDirectiveId(5)));
      assertTrue(map.containsKey(seven));
    }
  }
  @Nested
  public final class AnchorsSimulationDriverTests {
    private final SerializedActivity serializedDelayDirective = new SerializedActivity(
        "DelayActivityDirective",
        arguments);
    private final SerializedActivity serializedDecompositionDirective = new SerializedActivity(
        "DecomposingActivityDirective",
        arguments);
    private final SerializedValue computedAttributes = new SerializedValue.MapValue(Map.of());
    private final Instant planStart = Instant.EPOCH;

    /**
     * Asserts equality based on the following fields of SimulationResults:
     * - startTime
     * - simulatedActivities
     * - unfinishedActivities (asserted to be empty in actual)
     * - topics
     * Any resource profiles and events are not checked.
     */
    private static void assertEqualsSimulationResults(SimulationResults expected, SimulationResults actual) {
      assertEquals(expected.startTime, actual.startTime);
      assertEquals(expected.duration, actual.duration);
      assertEquals(expected.simulatedActivities.size(), actual.simulatedActivities.size());
      for (final var entry : expected.simulatedActivities.entrySet()) {
        final var key = entry.getKey();
        final var expectedValue = entry.getValue();
        final var actualValue = actual.simulatedActivities.get(key);
        assertNotNull(actualValue);
        assertEquals(expectedValue, actualValue);
      }
      assertTrue(actual.unfinishedActivities.isEmpty());
      assertEquals(expected.topics.size(), actual.topics.size());
      for (int i = 0; i < expected.topics.size(); ++i) {
        assertEquals(expected.topics.get(i), actual.topics.get(i));
      }
    }

    private void constructFullComplete5AryTree(
        int maxLevel,
        int currentLevel,
        long parentNode,
        Map<ActivityDirectiveId, ActivityDirective> activitiesToSimulate,
        Map<ActivityInstanceId, ActivityInstance> simulatedActivities)
    {
      if (currentLevel > maxLevel) return;
      for (int i = 1; i <= 5; i++) {
        long curElement = parentNode * 5 + i;
        activitiesToSimulate.put(
            new ActivityDirectiveId(curElement),
            new ActivityDirective(Duration.ZERO, serializedDelayDirective, new ActivityDirectiveId(parentNode), false));
        simulatedActivities.put(
            new ActivityInstanceId(curElement),
            new ActivityInstance(
                serializedDelayDirective.getTypeName(),
                Map.of(),
                Instant.EPOCH.plus(currentLevel, ChronoUnit.MINUTES),
                oneMinute,
                null,
                List.of(),
                Optional.of(new ActivityDirectiveId(curElement)), computedAttributes));
        constructFullComplete5AryTree(
            maxLevel,
            currentLevel + 1,
            curElement,
            activitiesToSimulate,
            simulatedActivities);
      }
    }

    private static void assertEqualsAsideFromChildren(ActivityInstance expected, ActivityInstance actual) {
      assertEquals(expected.type(), actual.type());
      assertEquals(expected.arguments(), actual.arguments());
      assertEquals(expected.start(), actual.start());
      assertEquals(expected.duration(), actual.duration());
      assertEquals(expected.parentId(), actual.parentId());
      assertEquals(expected.directiveId(), actual.directiveId());
      assertEquals(expected.computedAttributes(), actual.computedAttributes());
    }

    @Test
    @DisplayName("Activities depending on no activities simulate at the correct time")
    public void activitiesAnchoredToPlan() {
      final var minusOneMinute = Duration.of(-60, Duration.SECONDS);
      final var resolveToPlanStartAnchors = new HashMap<ActivityDirectiveId, ActivityDirective>(415);
      final Map<ActivityInstanceId, ActivityInstance> simulatedActivities = new HashMap<>(415);

      // Anchored to Plan Start (only positive is allowed)
      for (long l = 0; l < 5; l++) {
        final var activityDirectiveId = new ActivityDirectiveId(l);
        resolveToPlanStartAnchors.put(
            activityDirectiveId,
            new ActivityDirective(Duration.of(l, Duration.SECONDS), serializedDelayDirective, null, true));
        simulatedActivities.put(new ActivityInstanceId(l), new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            Instant.EPOCH.plus(l, ChronoUnit.SECONDS),
            oneMinute,
            null,
            List.of(),
            Optional.of(activityDirectiveId),
            computedAttributes
        ));
      }
      // Anchored to Plan End (only negative will be simulated)
      for (long l = 10; l < 15; l++) {
        final var activityDirectiveId = new ActivityDirectiveId(l);
        resolveToPlanStartAnchors.put(
            activityDirectiveId,
            new ActivityDirective(
                // Minutes so they finish by simulation end
                Duration.of(-l, Duration.MINUTES),
                serializedDelayDirective,
                null,
                false));
        simulatedActivities.put(new ActivityInstanceId(l), new ActivityInstance(
            serializedDelayDirective.getTypeName(),
            Map.of(),
            Instant.EPOCH.plus(10, ChronoUnit.DAYS).minus(l, ChronoUnit.MINUTES),
            oneMinute,
            null,
            List.of(),
            Optional.of(activityDirectiveId),
            computedAttributes
        ));
      }

      // Chained to plan start
      resolveToPlanStartAnchors.put(
          new ActivityDirectiveId(15),
          new ActivityDirective(Duration.ZERO, serializedDelayDirective, new ActivityDirectiveId(0), true));
      simulatedActivities.put(new ActivityInstanceId(15), new ActivityInstance(
          serializedDelayDirective.getTypeName(),
          Map.of(),
          Instant.EPOCH,
          oneMinute,
          null,
          List.of(),
          Optional.of(new ActivityDirectiveId(15)),
          computedAttributes
      ));

      for (long l = 16; l < 415; l++) {
        final var activityDirectiveId = new ActivityDirectiveId(l);
        if ((l & 1) == 0) { // If even
          resolveToPlanStartAnchors.put(
              activityDirectiveId,
              new ActivityDirective(oneMinute, serializedDelayDirective, new ActivityDirectiveId(l - 1), true));
          simulatedActivities.put(new ActivityInstanceId(l), new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(activityDirectiveId),
              computedAttributes
          ));
        } else {
          resolveToPlanStartAnchors.put(
              activityDirectiveId,
              new ActivityDirective(minusOneMinute, serializedDelayDirective, new ActivityDirectiveId(l - 1), true));
          simulatedActivities.put(new ActivityInstanceId(l), new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              oneMinute,
              null,
              List.of(),
              Optional.of(activityDirectiveId),
              computedAttributes
          ));
        }
      }

      // Assert Simulation results
      final var expectedSimResults = new SimulationResults(
          Map.of(), //real
          Map.of(), //discrete
          simulatedActivities,
          Map.of(), //unfinished
          planStart,
          tenDays,
          TestMissionModel.getModelTopicList(),
          new TreeMap<>() //events
      );
      final var actualSimResults = SimulationDriver.simulate(
          TestMissionModel.missionModel(),
          resolveToPlanStartAnchors,
          planStart,
          tenDays,
          planStart,
          tenDays,
          () -> false);

      assertEqualsSimulationResults(expectedSimResults, actualSimResults);
    }

    @Test
    @DisplayName("Activities depending on another activities simulate at the correct time")
    public void activitiesAnchoredToOtherActivities() {
      final var allEndTimeAnchors = new HashMap<ActivityDirectiveId, ActivityDirective>(400);
      final var endTimeAnchorEveryFifth = new HashMap<ActivityDirectiveId, ActivityDirective>(400);
      final Map<ActivityInstanceId, ActivityInstance> simulatedActivities = new HashMap<>(800);
      final var activitiesToSimulate = new HashMap<ActivityDirectiveId, ActivityDirective>(800);

      allEndTimeAnchors.put(
          new ActivityDirectiveId(0),
          new ActivityDirective(oneMinute, serializedDelayDirective, null, true));
      endTimeAnchorEveryFifth.put(
          new ActivityDirectiveId(400),
          new ActivityDirective(oneMinute, serializedDelayDirective, null, true));
      simulatedActivities.put(
          new ActivityInstanceId(0),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(0)),
              computedAttributes));
      simulatedActivities.put(
          new ActivityInstanceId(400),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(400)),
              computedAttributes));

      for (long l = 1, k = 401, c = 1; l < 400; l++, k++) {
        final var activityDirectiveIdAETA = new ActivityDirectiveId(l);
        final var activityDirectiveIdEveryFifth = new ActivityDirectiveId(k);

        allEndTimeAnchors.put(
            activityDirectiveIdAETA,
            new ActivityDirective(
                oneMinute,
                serializedDelayDirective,
                new ActivityDirectiveId(l - 1),
                false));
        simulatedActivities.put(
            new ActivityInstanceId(l),
            new ActivityInstance(
                serializedDelayDirective.getTypeName(),
                Map.of(),
                Instant.EPOCH.plus((2 * l) + 1, ChronoUnit.MINUTES),
                oneMinute,
                null,
                List.of(),
                Optional.of(activityDirectiveIdAETA),
                computedAttributes));

        if (k % 5 == 0) {
          endTimeAnchorEveryFifth.put(
              activityDirectiveIdEveryFifth,
              new ActivityDirective(
                  oneMinute,
                  serializedDelayDirective,
                  new ActivityDirectiveId(k - 1),
                  false));
          c++;
        } else {
          endTimeAnchorEveryFifth.put(
              activityDirectiveIdEveryFifth,
              new ActivityDirective(
                  oneMinute,
                  serializedDelayDirective,
                  new ActivityDirectiveId(k - 1),
                  true));
        }
        simulatedActivities.put(
            new ActivityInstanceId(k),
            new ActivityInstance(
                serializedDelayDirective.getTypeName(),
                Map.of(),
                Instant.EPOCH.plus(l + c, ChronoUnit.MINUTES),
                oneMinute,
                null,
                List.of(),
                Optional.of(activityDirectiveIdEveryFifth),
                computedAttributes));
      }

      activitiesToSimulate.putAll(allEndTimeAnchors);
      activitiesToSimulate.putAll(endTimeAnchorEveryFifth);

      // Assert Simulation results
      final var expectedSimResults = new SimulationResults(
          Map.of(), //real
          Map.of(), //discrete
          simulatedActivities,
          Map.of(), //unfinished
          planStart,
          tenDays,
          TestMissionModel.getModelTopicList(),
          new TreeMap<>() //events
      );
      final var actualSimResults = SimulationDriver.simulate(
          TestMissionModel.missionModel(),
          activitiesToSimulate,
          planStart,
          tenDays,
          planStart,
          tenDays,
          () -> false);

      assertEqualsSimulationResults(expectedSimResults, actualSimResults);
    }

    @Test
    @DisplayName("Decomposition and anchors do not interfere with each other")
    public void decomposingActivitiesAndAnchors() {
      // Given positions Left, Center, Right in an anchor chain, where each position can either contain a Non-Decomposition (ND) activity or a Decomposition (D) activity,
      // and the connection between Center and Left and Right and Center can be either Start (<-s-) or End (<-e-),
      // and two NDs cannot be adjacent to each other, there are 20 permutations.

      // In order to have fewer activities and more complex subtrees, the first and second elements of a set of sequences will be reused when possible
      final var activitiesToSimulate = new HashMap<ActivityDirectiveId, ActivityDirective>(23);
      // NOTE: This list is intentionally keyed on ActivityDirectiveId, not on SimulatedActivityId.
      // Additionally, because we do not know the order the child activities will generate in, DecompositionDirectives will have a List.of() rather than the correct value
      final var topLevelSimulatedActivities = new HashMap<ActivityDirectiveId, ActivityInstance>(23);
      final var threeMinutes = Duration.of(3, Duration.MINUTES);

      // ND <-s- D <-s- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(1),
          new ActivityDirective(Duration.ZERO, serializedDelayDirective, null, true));
      activitiesToSimulate.put(
          new ActivityDirectiveId(2),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(1),
                                true));
      activitiesToSimulate.put(
          new ActivityDirectiveId(3),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(2),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(1),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(1)),
              computedAttributes));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(2),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(2)),
              computedAttributes));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(3),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(3)),
              computedAttributes));

      // ND <-s- D <-e- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(4),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(2),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(4),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(3, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(4)),
              computedAttributes));

      // ND <-s- D <-s- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(5),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(2),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(5),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(5)),
              computedAttributes));

      // ND <-s- D <-e- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(6),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(2),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(6),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(3, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(6)),
              computedAttributes));

      // ND <-e- D <-s- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(7),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(1),
                                false));
      activitiesToSimulate.put(
          new ActivityDirectiveId(8),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(7),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(7),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(7)),
              computedAttributes));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(8),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(8)),
              computedAttributes));

      // ND <-e- D <-e- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(9),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(7),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(9),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(4, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(9)),
              computedAttributes));

      // ND <-e- D <-s- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(10),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(7),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(10),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(10)),
              computedAttributes));

      // ND <-e- D <-e- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(11),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(7),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(11),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(4, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(11)),
              computedAttributes));

      // D <-s- D <-s- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(12),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(3),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(12),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(12)),
              computedAttributes));

      // D <-s- D <-e- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(13),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(3),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(13),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(3, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(13)),
              computedAttributes));

      // D <-s- D <-s- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(14),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(3),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(14),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(14)),
              computedAttributes));

      // D <-s- D <-e- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(15),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(3),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(15),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(3, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(15)),
              computedAttributes));

      // D <-e- D <-s- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(16),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(4),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(16),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(3, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(16)),
              computedAttributes));

      // D <-e- D <-e- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(17),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(4),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(17),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(6, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(17)),
              computedAttributes));

      // D <-e- D <-s- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(18),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(4),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(18),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(3, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(18)),
              computedAttributes));

      // D <-e- D <-e- ND
      activitiesToSimulate.put(
          new ActivityDirectiveId(19),
          new ActivityDirective(Duration.ZERO,
                                serializedDelayDirective,
                                new ActivityDirectiveId(4),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(19),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(6, ChronoUnit.MINUTES),
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(19)),
              computedAttributes));

      // D <-s- ND <-s- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(20),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(14),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(20),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(20)),
              computedAttributes));

      // D <-s- ND <-e- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(21),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(14),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(21),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(21)),
              computedAttributes));

      // D <-e- ND <-s- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(22),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(15),
                                true));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(22),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(3, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(22)),
              computedAttributes));

      // D <-e- ND <-e- D
      activitiesToSimulate.put(
          new ActivityDirectiveId(23),
          new ActivityDirective(Duration.ZERO,
                                serializedDecompositionDirective,
                                new ActivityDirectiveId(15),
                                false));
      topLevelSimulatedActivities.put(
          new ActivityDirectiveId(23),
          new ActivityInstance(
              serializedDecompositionDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH.plus(4, ChronoUnit.MINUTES),
              threeMinutes,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(23)),
              computedAttributes));

      // Custom assertion, as Decomposition children can end up simulated in different positions between runs
      final var actualSimResults = SimulationDriver.simulate(
          TestMissionModel.missionModel(),
          activitiesToSimulate,
          planStart,
          tenDays,
          planStart,
          tenDays,
          () -> false);

      assertEquals(planStart, actualSimResults.startTime);
      assertTrue(actualSimResults.unfinishedActivities.isEmpty());
      final var modelTopicList = TestMissionModel.getModelTopicList();
      assertEquals(modelTopicList.size(), actualSimResults.topics.size());
      for (int i = 0; i < modelTopicList.size(); ++i) {
        assertEquals(modelTopicList.get(i), actualSimResults.topics.get(i));
      }

      final var childSimulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(28);
      final var otherSimulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(23);
      assertEquals(51, actualSimResults.simulatedActivities.size()); // 23 + 2*(14 Decomposing activities)

      for (final var entry : actualSimResults.simulatedActivities.entrySet()) {
        if (entry.getValue().parentId() == null) {
          otherSimulatedActivities.put(entry.getKey(), entry.getValue());
        } else {
          childSimulatedActivities.put(entry.getKey(), entry.getValue());
        }
      }
      assertEquals(23, otherSimulatedActivities.size());
      assertEquals(28, childSimulatedActivities.size());

      for (final var entry : otherSimulatedActivities.entrySet()) {
        assertTrue(entry.getValue().directiveId().isPresent());
        final ActivityDirectiveId topLevelKey = entry.getValue().directiveId().get();
        assertEqualsAsideFromChildren(topLevelSimulatedActivities.get(topLevelKey), entry.getValue());
        // For decompositions, examine the children
        if (entry.getValue().type().equals(serializedDecompositionDirective.getTypeName())) {
          assertEquals(2, entry.getValue().childIds().size());
          final var firstChild = childSimulatedActivities.remove(entry.getValue().childIds().get(0));
          final var secondChild = childSimulatedActivities.remove(entry.getValue().childIds().get(1));

          // Assert the children look as expected and one starts and the parent's start time, and one starts two minutes later
          assertNotNull(firstChild);
          assertNotNull(secondChild);
          assertTrue(firstChild.childIds().isEmpty());
          assertTrue(secondChild.childIds().isEmpty());

          if (firstChild.start().isBefore(secondChild.start())) {
            assertEqualsAsideFromChildren(
                new ActivityInstance(
                    serializedDelayDirective.getTypeName(),
                    Map.of(),
                    entry.getValue().start(),
                    oneMinute,
                    entry.getKey(),
                    List.of(),
                    Optional.empty(),
                    computedAttributes),
                firstChild);
            assertEqualsAsideFromChildren(
                new ActivityInstance(
                    serializedDelayDirective.getTypeName(),
                    Map.of(),
                    entry.getValue().start().plus(2, ChronoUnit.MINUTES),
                    oneMinute,
                    entry.getKey(),
                    List.of(),
                    Optional.empty(),
                    computedAttributes),
                secondChild);
          } else {
            assertEqualsAsideFromChildren(
                new ActivityInstance(
                    serializedDelayDirective.getTypeName(),
                    Map.of(),
                    entry.getValue().start().plus(2, ChronoUnit.MINUTES),
                    oneMinute,
                    entry.getKey(),
                    List.of(),
                    Optional.empty(),
                    computedAttributes),
                firstChild);
            assertEqualsAsideFromChildren(
                new ActivityInstance(
                    serializedDelayDirective.getTypeName(),
                    Map.of(),
                    entry.getValue().start(),
                    oneMinute,
                    entry.getKey(),
                    List.of(),
                    Optional.empty(),
                    computedAttributes),
                secondChild);
          }
        }
      }

      // We have examined all the children
      assertTrue(childSimulatedActivities.isEmpty());
    }

    @Test
    @DisplayName("Activities arranged in a wide anchor tree simulate at the correct time")
    public void naryTreeAnchorChain() {
      // Full and complete 5-ary tree,  6 levels deep
      // Number of activity directives = 5^0 + 5^1 + 5^2 + 5^3 + 5^4 + 5^5 = 3906

      final var activitiesToSimulate = new HashMap<ActivityDirectiveId, ActivityDirective>(3906);
      final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>(3906);

      activitiesToSimulate.put(
          new ActivityDirectiveId(0),
          new ActivityDirective(Duration.ZERO, serializedDelayDirective, null, true));
      simulatedActivities.put(
          new ActivityInstanceId(0),
          new ActivityInstance(
              serializedDelayDirective.getTypeName(),
              Map.of(),
              Instant.EPOCH,
              oneMinute,
              null,
              List.of(),
              Optional.of(new ActivityDirectiveId(0)),
              computedAttributes));

      constructFullComplete5AryTree(5, 1, 0, activitiesToSimulate, simulatedActivities);

      // Assert Simulation results
      final var expectedSimResults = new SimulationResults(
          Map.of(), //real
          Map.of(), //discrete
          simulatedActivities,
          Map.of(), //unfinished
          planStart,
          tenDays,
          TestMissionModel.getModelTopicList(),
          new TreeMap<>() //events
      );
      final var actualSimResults = SimulationDriver.simulate(
          TestMissionModel.missionModel(),
          activitiesToSimulate,
          planStart,
          tenDays,
          planStart,
          tenDays,
          () -> false);

      assertEquals(3906, expectedSimResults.simulatedActivities.size());
      assertEqualsSimulationResults(expectedSimResults, actualSimResults);
    }
  }
}
