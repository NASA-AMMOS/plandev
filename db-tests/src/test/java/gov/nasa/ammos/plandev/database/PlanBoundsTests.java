package gov.nasa.ammos.plandev.database;

import gov.nasa.ammos.plandev.database.types.Activity;
import gov.nasa.ammos.plandev.database.types.PlanDatasetRecord;
import gov.nasa.ammos.plandev.database.types.SimulationDatasetRecord;
import gov.nasa.ammos.plandev.database.types.SimulationSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PlanBoundsTests {
  private static final int oneHour = 60 * 60;
  private static final int thirtyMinutes = 30 * 60;

  private static DatabaseTestHelper helper;
  private static Connection connection;
  private static MerlinDatabaseTestHelper merlinHelper;

  private static ZonedDateTime planStartTime;
  private static Duration planDuration;

  private int planId;
  private int unrelatedPlanId;

  @BeforeAll
  static void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_plan_bounds_tests", "Plan Boundary Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);

    planStartTime = ZonedDateTime.parse("2026-01-01T00:00:00+00");
    planDuration = Duration.ofHours(24);
  }

  @AfterAll
  static void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  @BeforeEach
  void beforeEach() throws SQLException {
    final int missionModelId = merlinHelper.insertMissionModel(merlinHelper.insertFileUpload());
    planId = merlinHelper.insertPlan(
        missionModelId,
        merlinHelper.user.name(),
        "Plan Bounds Test Plan",
        planStartTime.toString(),
        planDuration.toString());

    unrelatedPlanId = merlinHelper.insertPlan(
        missionModelId,
        merlinHelper.user.name(),
        "Unrelated Plan Plan Bounds Test",
        planStartTime.toString(),
        planDuration.toString());
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  //region Helper Methods
  static Stream<Arguments> startDurationAdjustments() {
    return Stream.of(
        // Start and Duration are adjusted in the same direction, same magnitude
        Arguments.arguments(oneHour, oneHour),
        Arguments.arguments(-oneHour, -oneHour),
        // Start and Duration are adjusted in the same direction, different magnitudes
        Arguments.arguments(oneHour, thirtyMinutes),
        Arguments.arguments(thirtyMinutes, oneHour),
        Arguments.arguments(-oneHour, -thirtyMinutes),
        Arguments.arguments(-thirtyMinutes, -oneHour),
        // Start and Duration are adjusted in opposite directions, same magnitude
        Arguments.arguments(oneHour, -oneHour),
        Arguments.arguments(-oneHour, oneHour),
        // Start and Duration are adjusted in opposite directions, different magnitudes
        Arguments.arguments(oneHour, -thirtyMinutes),
        Arguments.arguments(thirtyMinutes, -oneHour),
        Arguments.arguments(-oneHour, thirtyMinutes),
        Arguments.arguments(-thirtyMinutes, oneHour)
    );
  }

  private void updateSimulationSpecificationBounds(int planId, ZonedDateTime newStart, ZonedDateTime newEnd) throws SQLException {
    try(final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          update merlin.simulation
          set simulation_start_time = '%s',
              simulation_end_time = '%s'
          where plan_id = %d
          """.formatted(newStart.toString(), newEnd.toString(), planId)
      );
    }
  }
  //endregion

  @Nested
  class SimulationSpecificationBoundsAdjustment {
    private SimulationSpecification simSpec;
    private SimulationSpecification unrelatedSimSpec;

    @BeforeEach
    void beforeEach() throws SQLException {
      simSpec = merlinHelper.getSimulationSpecification(planId);
      unrelatedSimSpec = merlinHelper.getSimulationSpecification(unrelatedPlanId);
    }

    /**
     * If the simulation specification bounds are the same as the old plan bounds,
     * then the simulation specification's boundaries are updated to the new plan bounds.
     */
    @Nested
    class SpecBoundsEqualPlanBounds {
      @Test
      void planStartAdjusted() throws SQLException {
        // Adjust plan start time back one hour
        merlinHelper.updatePlanStartTime(planId, planStartTime.minusSeconds(oneHour).toString());

        // Sim spec should be snapped to the plan bounds
        var refreshedSimSpec = simSpec.refresh(merlinHelper);
        assertEquals(planStartTime.minusSeconds(oneHour), refreshedSimSpec.simStartTime());
        assertEquals(planStartTime.minusSeconds(oneHour).plusSeconds(planDuration.getSeconds()),
                     refreshedSimSpec.simEndTime());
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));

        // Reset the plan bounds
        merlinHelper.updatePlanStartTime(planId, planStartTime.toString());

        // Sim spec should match its starting state
        refreshedSimSpec = simSpec.refresh(merlinHelper);
        assertEquals(simSpec, refreshedSimSpec);
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));

        // Adjust plan start time forward one hour
        merlinHelper.updatePlanStartTime(planId, planStartTime.plusSeconds(oneHour).toString());

        // Sim spec should be snapped to the plan bounds
        refreshedSimSpec = simSpec.refresh(merlinHelper);
        assertEquals(planStartTime.plusSeconds(oneHour), refreshedSimSpec.simStartTime());
        assertEquals(planStartTime.plusSeconds(oneHour).plusSeconds(planDuration.getSeconds()),
                     refreshedSimSpec.simEndTime());
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      @Test
      void planDurationAdjusted() throws SQLException {
        // Adjust plan duration time back one hour
        merlinHelper.updatePlanDuration(planId, planDuration.minusSeconds(oneHour).toString());

        // Sim spec should be snapped to the plan bounds
        var refreshedSimSpec = simSpec.refresh(merlinHelper);
        assertEquals(planStartTime, refreshedSimSpec.simStartTime());
        assertEquals(planStartTime.plusSeconds(planDuration.minusSeconds(oneHour).getSeconds()),
                     refreshedSimSpec.simEndTime());
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));

        // Reset the plan bounds
        merlinHelper.updatePlanDuration(planId, planDuration.toString());

        // Sim spec should match its starting state
        refreshedSimSpec = simSpec.refresh(merlinHelper);
        assertEquals(simSpec, refreshedSimSpec);
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));

        // Adjust plan duration forward one hour
        merlinHelper.updatePlanDuration(planId, planDuration.plusSeconds(oneHour).toString());

        // Sim spec should be snapped to the plan bounds
        refreshedSimSpec = simSpec.refresh(merlinHelper);
        assertEquals(planStartTime, refreshedSimSpec.simStartTime());
        assertEquals(planStartTime.plusSeconds(planDuration.plusSeconds(oneHour).getSeconds()),
                     refreshedSimSpec.simEndTime());
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      void bothPlanStartAndDurationAdjusted(int startTimeAdjustment, int durationAdjustment) throws SQLException {
        final var newPlanStartTime = planStartTime.plusSeconds(startTimeAdjustment);
        final var newPlanDuration = planDuration.plusSeconds(durationAdjustment);

        // Adjust plan bounds as specified
        merlinHelper.updatePlanBounds(
            planId,
            newPlanStartTime.toString(),
            newPlanDuration.toString());

        // Sim spec should be snapped to the plan bounds
        final var refreshedSimSpec = simSpec.refresh(merlinHelper);
        assertEquals(newPlanStartTime, refreshedSimSpec.simStartTime());
        assertEquals(newPlanStartTime.plusSeconds(newPlanDuration.getSeconds()), refreshedSimSpec.simEndTime());
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));

        // Reset plan bounds
        merlinHelper.updatePlanBounds(
            planId,
            planStartTime.toString(),
            planDuration.toString());

        // Sim spec should match its starting state
        assertEquals(simSpec, refreshedSimSpec.refresh(merlinHelper));
        // Unrelated spec should be untouched
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }
    }

    @Nested
    class TemporalSubset {
      /**
       * Additional adjustments that only adjust one of start or duration
       */
      private static Stream<Arguments> additionalStartDurationAdjustments() {
        return Stream.of(
            // Only Start time is adjusted
            Arguments.arguments(oneHour, 0),
            Arguments.arguments(-oneHour, 0),
            Arguments.arguments(thirtyMinutes, 0),
            Arguments.arguments(-thirtyMinutes, 0),
            // Only Duration is adjusted
            Arguments.arguments(0, oneHour),
            Arguments.arguments(0, -oneHour),
            Arguments.arguments(0, thirtyMinutes),
            Arguments.arguments(0, -thirtyMinutes)
        );
      }

      /**
       * If the simulation specification bounds are a temporal subset of both the old and new plan bounds,
       * then the simulation specification's boundaries are unaffected.
       */
      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      @MethodSource("additionalStartDurationAdjustments")
      void trueTemporalSubset(int startTimeAdjustment, int durationAdjustment) throws SQLException {
        // Set sim spec bounds to a true temporal subset (2 hours in the middle of the plan)
        updateSimulationSpecificationBounds(planId, planStartTime.plusHours(4), planStartTime.plusHours(6));
        // Get an updated spec
        final var adjustedSimulationSpec = simSpec.refresh(merlinHelper);

        // Update the plan bounds
        merlinHelper.updatePlanBounds(
            planId,
            planStartTime.plusSeconds(startTimeAdjustment).toString(),
            planDuration.plusSeconds(durationAdjustment).toString());

        // Sim spec and unrelated spec should be unimpacted
        assertEquals(adjustedSimulationSpec, adjustedSimulationSpec.refresh(merlinHelper));
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      /**
       * If the simulation specification bounds differ from the plan bounds,
       * but the start time is before the new plan start time,
       * then the specification start time is cropped to the new plan start time.
       */
      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      @MethodSource("additionalStartDurationAdjustments")
      void startTimeCropped(int startTimeAdjustment, int durationAdjustment) throws SQLException {
        // Set sim spec bounds to start four hours before the plan does and end in the middle of the new bounds
        updateSimulationSpecificationBounds(planId, planStartTime.minusHours(4), planStartTime.plusHours(6));
        // Get an updated spec
        final var adjustedSimulationSpec = simSpec.refresh(merlinHelper);

        // Update the plan bounds
        merlinHelper.updatePlanBounds(
            planId,
            planStartTime.plusSeconds(startTimeAdjustment).toString(),
            planDuration.plusSeconds(durationAdjustment).toString());

        // Sim spec should have had only its start time adjusted
        final var updatedSimSpec = adjustedSimulationSpec.refresh(merlinHelper);
        assertEquals(planStartTime.plusSeconds(startTimeAdjustment), updatedSimSpec.simStartTime());
        assertEquals(adjustedSimulationSpec.simEndTime(), updatedSimSpec.simEndTime());
        // Unrelated spec should be unimpacted
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      /**
       * If the simulation specification bounds differ from the plan bounds,
       * but the end time is after the new plan end time,
       * then the specification end time is cropped to the new plan end time.
       */
      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      @MethodSource("additionalStartDurationAdjustments")
      void endTimeCropped(int startTimeAdjustment, int durationAdjustment) throws SQLException {
        // Set sim spec bounds to in the middle of the plan does and end after plan end
        updateSimulationSpecificationBounds(planId, planStartTime.plusHours(4), planStartTime.plusDays(2));
        // Get an updated spec
        final var adjustedSimulationSpec = simSpec.refresh(merlinHelper);

        // Update the plan bounds
        merlinHelper.updatePlanBounds(
            planId,
            planStartTime.plusSeconds(startTimeAdjustment).toString(),
            planDuration.plusSeconds(durationAdjustment).toString());

        // Sim spec should have had only its end time adjusted
        final var newPlanEndTime = planStartTime.plusSeconds(startTimeAdjustment)
                                                .plusSeconds(planDuration.plusSeconds(durationAdjustment).getSeconds());
        final var updatedSimSpec = adjustedSimulationSpec.refresh(merlinHelper);
        assertEquals(adjustedSimulationSpec.simStartTime(), updatedSimSpec.simStartTime());
        assertEquals(newPlanEndTime, updatedSimSpec.simEndTime());
        // Unrelated spec should be unimpacted
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      /**
       * If the simulation specification bounds are entirely before the new plan start time,
       * then the specification bounds are reset to the plan bounds
       */
      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      @MethodSource("additionalStartDurationAdjustments")
      void entirelyBefore(int startTimeAdjustment, int durationAdjustment) throws SQLException  {
        // Set sim spec bounds to be entirely before the plan
        updateSimulationSpecificationBounds(planId, planStartTime.minusDays(4), planStartTime.minusDays(2));
        // Get an updated spec
        final var adjustedSimulationSpec = simSpec.refresh(merlinHelper);

        // Update the plan bounds
        final var newPlanStartTime = planStartTime.plusSeconds(startTimeAdjustment);
        final var newPlanEndTime = newPlanStartTime
            .plusSeconds(planDuration.plusSeconds(durationAdjustment).getSeconds());
        merlinHelper.updatePlanBounds(
            planId,
            newPlanStartTime.toString(),
            planDuration.plusSeconds(durationAdjustment).toString());

        // Sim spec should have been snapped to the new planBounds
        final var updatedSimSpec = adjustedSimulationSpec.refresh(merlinHelper);
        assertEquals(newPlanStartTime, updatedSimSpec.simStartTime());
        assertEquals(newPlanEndTime, updatedSimSpec.simEndTime());
        // Unrelated spec should be unimpacted
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      /**
       * If the simulation specification bounds are entirely after the new plan end time,
       * then the specification bounds are reset to the plan bounds
       */
      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      @MethodSource("additionalStartDurationAdjustments")
      void entirelyAfter(int startTimeAdjustment, int durationAdjustment) throws SQLException  {
        // Set sim spec bounds to be entirely after the plan
        updateSimulationSpecificationBounds(planId, planStartTime.plusDays(3), planStartTime.plusDays(5));
        // Get an updated spec
        final var adjustedSimulationSpec = simSpec.refresh(merlinHelper);

        // Update the plan bounds
        final var newPlanStartTime = planStartTime.plusSeconds(startTimeAdjustment);
        final var newPlanEndTime = newPlanStartTime
            .plusSeconds(planDuration.plusSeconds(durationAdjustment).getSeconds());
        merlinHelper.updatePlanBounds(
            planId,
            newPlanStartTime.toString(),
            planDuration.plusSeconds(durationAdjustment).toString());

        // Sim spec should have been snapped to the new planBounds
        final var updatedSimSpec = adjustedSimulationSpec.refresh(merlinHelper);
        assertEquals(newPlanStartTime, updatedSimSpec.simStartTime());
        assertEquals(newPlanEndTime, updatedSimSpec.simEndTime());
        // Unrelated spec should be unimpacted
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      /**
       * If the simulation specification bounds ends at the new plan start time,
       * then the specification bounds are reset to the plan bounds
       */
      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      @MethodSource("additionalStartDurationAdjustments")
      void endsAtNewStart(int startTimeAdjustment, int durationAdjustment) throws SQLException  {
        final var newPlanStartTime = planStartTime.plusSeconds(startTimeAdjustment);
        final var newPlanEndTime = newPlanStartTime
            .plusSeconds(planDuration.plusSeconds(durationAdjustment).getSeconds());

        // Set sim spec bounds to be entirely after the plan
        updateSimulationSpecificationBounds(planId, newPlanStartTime.minusDays(1), newPlanStartTime);
        // Get an updated spec
        final var adjustedSimulationSpec = simSpec.refresh(merlinHelper);

        // Update the plan bounds
        merlinHelper.updatePlanBounds(
            planId,
            newPlanStartTime.toString(),
            planDuration.plusSeconds(durationAdjustment).toString());

        // Sim spec should have been snapped to the new planBounds
        final var updatedSimSpec = adjustedSimulationSpec.refresh(merlinHelper);
        assertEquals(newPlanStartTime, updatedSimSpec.simStartTime());
        assertEquals(newPlanEndTime, updatedSimSpec.simEndTime());
        // Unrelated spec should be unimpacted
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }

      /**
       * If the simulation specification bounds begin at the new plan end time,
       * then the specification bounds are reset to the plan bounds
       */
      @ParameterizedTest
      @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
      @MethodSource("additionalStartDurationAdjustments")
      void beginsAtNewEnd(int startTimeAdjustment, int durationAdjustment) throws SQLException  {
        final var newPlanStartTime = planStartTime.plusSeconds(startTimeAdjustment);
        final var newPlanEndTime = newPlanStartTime
            .plusSeconds(planDuration.plusSeconds(durationAdjustment).getSeconds());

        // Set sim spec bounds to be entirely after the plan
        updateSimulationSpecificationBounds(planId, newPlanEndTime, newPlanEndTime.plusDays(1));
        // Get an updated spec
        final var adjustedSimulationSpec = simSpec.refresh(merlinHelper);

        // Update the plan bounds
        merlinHelper.updatePlanBounds(
            planId,
            newPlanStartTime.toString(),
            planDuration.plusSeconds(durationAdjustment).toString());

        // Sim spec should have been snapped to the new planBounds
        final var updatedSimSpec = adjustedSimulationSpec.refresh(merlinHelper);
        assertEquals(newPlanStartTime, updatedSimSpec.simStartTime());
        assertEquals(newPlanEndTime, updatedSimSpec.simEndTime());
        // Unrelated spec should be unimpacted
        assertEquals(unrelatedSimSpec, unrelatedSimSpec.refresh(merlinHelper));
      }
    }
  }

  @Nested
  class ActivityAdjustment {
    Activity startActivity;
    Activity endActivity;
    Activity anchoredActivity;
    Activity unrelatedActivity;

    /**
     * Compares two activities and asserts that they are identical except for lastModifiedAt and startOffset.
     * Instead, expectedStartOffset contains the expected value for the activity's offset.
     */
    private void assertActivityStartOffsetAdjustment(Activity expectedActivity, String expectedStartOffset, Activity actualActivity) {
      // Assert the startOffset is the expected value
      assertEquals(expectedStartOffset, actualActivity.startOffset());

      // Assert no other properties differ
      assertEquals(expectedActivity.activityId(), actualActivity.activityId());
      assertEquals(expectedActivity.planId(), actualActivity.planId());
      assertEquals(expectedActivity.name(), actualActivity.name());
      assertEquals(expectedActivity.sourceSchedulingGoalId(), actualActivity.sourceSchedulingGoalId());
      assertEquals(expectedActivity.sourceSchedulingGoalInvocationId(), actualActivity.sourceSchedulingGoalInvocationId());
      assertEquals(expectedActivity.createdAt(), actualActivity.createdAt());
      assertEquals(expectedActivity.createdBy(), actualActivity.createdBy());
      assertEquals(expectedActivity.lastModifiedBy(), actualActivity.lastModifiedBy());
      assertEquals(expectedActivity.type(), actualActivity.type());
      assertEquals(expectedActivity.arguments(), actualActivity.arguments());
      assertEquals(expectedActivity.lastModifiedArgumentsAt(), actualActivity.lastModifiedArgumentsAt());
      assertEquals(expectedActivity.metadata(), actualActivity.metadata());
      assertEquals(expectedActivity.anchorId(), actualActivity.anchorId());
      assertEquals(expectedActivity.anchoredToStart(), actualActivity.anchoredToStart());
    }

    /**
     * Inserts four activities:
     *  - one anchored to plan start
     *  - one anchored to plan end
     *  - one anchored to the plan start activity (anchoredToStart = true)
     *  - one anchored to the end of an unrelated plan
     */
    @BeforeEach
    void addActivities() throws SQLException {
      // Place one activity anchored to plan start
      startActivity = merlinHelper.getActivity(planId, merlinHelper.insertActivity(planId));

      // Place one activity anchored to plan end
      final var endActivityId = merlinHelper.insertActivity(planId);
      merlinHelper.setAnchor(-1, false, endActivityId, planId);
      endActivity = merlinHelper.getActivity(planId, endActivityId);

      // Anchor one activity to startActivity
      final var anchoredActivityId = merlinHelper.insertActivity(planId);
      merlinHelper.setAnchor(startActivity.activityId(), true, anchoredActivityId, planId);
      anchoredActivity = merlinHelper.getActivity(planId, anchoredActivityId);

      // Add an unrelated plan and anchor an activity to it
      final var unrelatedActivityId = merlinHelper.insertActivity(unrelatedPlanId);
      merlinHelper.setAnchor(-1, false, unrelatedActivityId, unrelatedPlanId);
      unrelatedActivity = merlinHelper.getActivity(unrelatedPlanId, unrelatedActivityId);
    }

    /**
     * When only the plan start is adjusted, the three activities are affected as follows:
     *  - startActivity: has the opposite adjustment applied to its startOffset
     *  - endActivity: has the opposite adjustment applied to its startOffset
     *  - anchoredActivity: has no adjustment applied to its startOffset
     */
    @Test
    void planStartAdjusted() throws SQLException {
      // Adjust plan start time back one hour
      merlinHelper.updatePlanStartTime(planId, planStartTime.minusSeconds(oneHour).toString());

      // startActivity's start time is moved forward one hour
      assertActivityStartOffsetAdjustment(startActivity, "01:00:00", startActivity.refresh(merlinHelper));
      // endActivity should have its start time moved forward one hour
      assertActivityStartOffsetAdjustment(endActivity, "01:00:00", endActivity.refresh(merlinHelper));
      // anchoredActivity should be unchanged
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));
      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));


      // Reset the plan bounds
      merlinHelper.updatePlanStartTime(planId, planStartTime.toString());

      // All three activities should match their starting state
      Activity.assertActivityEquals(startActivity, startActivity.refresh(merlinHelper));
      Activity.assertActivityEquals(endActivity, endActivity.refresh(merlinHelper));
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));
      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));

      // Adjust plan start time forward one hour
      merlinHelper.updatePlanStartTime(planId, planStartTime.plusSeconds(oneHour).toString());

      // startActivity's start time is moved back one hour
      assertActivityStartOffsetAdjustment(startActivity, "-01:00:00", startActivity.refresh(merlinHelper));
      // endActivity should have its start time moved back one hour
      assertActivityStartOffsetAdjustment(endActivity, "-01:00:00", endActivity.refresh(merlinHelper));
      // anchoredActivity should be unchanged
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));
      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));
    }

    /**
     * When only the plan duration is adjusted, the three activities are affected as follows:
     *  - startActivity: has no adjustment applied to its startOffset
     *  - endActivity: has the opposite adjustment applied to its startOffset
     *  - anchoredActivity: has no adjustment applied to its startOffset
     */
    @Test
    void planDurationAdjusted() throws SQLException {
      // Adjust plan duration time back one hour
      merlinHelper.updatePlanDuration(planId, planDuration.minusSeconds(oneHour).toString());

      // startActivity's should be unchanged
      Activity.assertActivityEquals(startActivity, startActivity.refresh(merlinHelper));
      // endActivity should have its start time moved forward one hour
      assertActivityStartOffsetAdjustment(endActivity, "01:00:00", endActivity.refresh(merlinHelper));
      // anchoredActivity should be unchanged
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));
      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));

      // Reset the plan bounds
      merlinHelper.updatePlanDuration(planId, planDuration.toString());

      // All three activities should match their starting state
      Activity.assertActivityEquals(startActivity, startActivity.refresh(merlinHelper));
      Activity.assertActivityEquals(endActivity, endActivity.refresh(merlinHelper));
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));
      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));

      // Adjust plan duration forward one hour
      merlinHelper.updatePlanDuration(planId, planDuration.plusSeconds(oneHour).toString());

      // startActivity's should be unchanged
      Activity.assertActivityEquals(startActivity, startActivity.refresh(merlinHelper));
      // endActivity should have its start time moved back one hour
      assertActivityStartOffsetAdjustment(endActivity, "-01:00:00", endActivity.refresh(merlinHelper));
      // anchoredActivity should be unchanged
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));
      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));
    }

    /**
     * When both the start time and duration are adjusted, the three activities are affected as follows:
     *  - startActivity: has its startOffset adjusted by negative the start time adjustment
     *  - endActivity: has its startOffset adjusted by negative (the start time adjustment plus the duration adjustment)
     *  - anchoredActivity: has no adjustment applied to its startOffset
     * @param startTimeAdjustment how much to adjust the plan's start time, in seconds
     * @param durationAdjustment how much to adjust the plan's duration, in seconds
     */
    @ParameterizedTest
    @MethodSource("gov.nasa.ammos.plandev.database.PlanBoundsTests#startDurationAdjustments")
    void bothPlanStartAndDurationAdjusted(int startTimeAdjustment, int durationAdjustment) throws SQLException {
      // Adjust plan bounds as specified
      merlinHelper.updatePlanBounds(
          planId,
          planStartTime.plusSeconds(startTimeAdjustment).toString(),
          planDuration.plusSeconds(durationAdjustment).toString());

      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));

      // anchoredActivity should be unchanged
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));

      // Start time of startActivity should be adjusted to equal -startTimeAdjustment
      final int startTimeInMins = Math.abs(startTimeAdjustment/60); // int division to avoid decimals
      final int startTimeInHrs = startTimeInMins/60;      // int division to avoid decimals
      final String expectedStartActivityAdjustment =
          (startTimeAdjustment > 0 ? "-":"") +
          "%02d:%02d:00".formatted(startTimeInHrs, startTimeInMins%60);

      assertActivityStartOffsetAdjustment(startActivity, expectedStartActivityAdjustment, startActivity.refresh(merlinHelper));

      // Start time of endActivity should be adjusted to equal -(startTimeAdjustment+durationAdjustment)
      final int endTimeInMins = Math.abs((startTimeAdjustment+durationAdjustment)/60); // int division to avoid decimals
      final int endTimeInHrs = endTimeInMins/60;      // int division to avoid decimals
      final String expectedEndActivityAdjustment =
          (startTimeAdjustment+durationAdjustment > 0 ? "-":"") +
          "%02d:%02d:00".formatted(endTimeInHrs, endTimeInMins%60);
      assertActivityStartOffsetAdjustment(endActivity, expectedEndActivityAdjustment, endActivity.refresh(merlinHelper));

      // Reset plan bounds
      merlinHelper.updatePlanBounds(
          planId,
          planStartTime.toString(),
          planDuration.toString());

      // All three activities should match their starting state
      Activity.assertActivityEquals(startActivity, startActivity.refresh(merlinHelper));
      Activity.assertActivityEquals(endActivity, endActivity.refresh(merlinHelper));
      Activity.assertActivityEquals(anchoredActivity, anchoredActivity.refresh(merlinHelper));
      // unrelatedActivity should be unchanged
      Activity.assertActivityEquals(unrelatedActivity, unrelatedActivity.refresh(merlinHelper));
    }
  }

  @Nested
  class DatasetAdjustment {
    private SimulationDatasetRecord simDataset;
    private PlanDatasetRecord planDataset;

    private SimulationDatasetRecord unrelatedSimDataset;
    private PlanDatasetRecord unrelatedPlanDataset;

    @BeforeEach
    void beforeEach() throws SQLException {
      simDataset = merlinHelper.insertSimulationDataset(planId);
      unrelatedSimDataset = merlinHelper.insertSimulationDataset(unrelatedPlanId);

      planDataset = merlinHelper.insertPlanDataset(planId);
      unrelatedPlanDataset = merlinHelper.insertPlanDataset(unrelatedPlanId);
    }

    /**
     * When the plan start is adjusted, the startOffset of the plan's sim- and plan datasets are adjusted by the opposite amount.
     */
    @Test
    void planStartAdjusted() throws SQLException {
      // Adjust plan start time back one hour
      merlinHelper.updatePlanStartTime(planId, planStartTime.minusSeconds(oneHour).toString());

      // Both datasets should have their startOffsets moved forward by one hour
      assertEquals("01:00:00", planDataset.refresh(merlinHelper).startOffset());
      assertEquals("01:00:00", simDataset.refresh(merlinHelper).startOffset());
      // Unrelated datasets should be unaffected
      assertEquals(unrelatedPlanDataset, unrelatedPlanDataset.refresh(merlinHelper));
      assertEquals(unrelatedSimDataset, unrelatedSimDataset.refresh(merlinHelper));

      // Reset the plan bounds
      merlinHelper.updatePlanStartTime(planId, planStartTime.toString());

      // Both datasets should match their starting state
      assertEquals(planDataset, planDataset.refresh(merlinHelper));
      assertEquals(simDataset, simDataset.refresh(merlinHelper));
      // Unrelated datasets should be unaffected
      assertEquals(unrelatedPlanDataset, unrelatedPlanDataset.refresh(merlinHelper));
      assertEquals(unrelatedSimDataset, unrelatedSimDataset.refresh(merlinHelper));

      // Adjust plan start time forward one hour
      merlinHelper.updatePlanStartTime(planId, planStartTime.plusSeconds(oneHour).toString());

      // Both datasets should have their startOffsets moved back by one hour
      assertEquals("-01:00:00", planDataset.refresh(merlinHelper).startOffset());
      assertEquals("-01:00:00", simDataset.refresh(merlinHelper).startOffset());
      // Unrelated datasets should be unaffected
      assertEquals(unrelatedPlanDataset, unrelatedPlanDataset.refresh(merlinHelper));
      assertEquals(unrelatedSimDataset, unrelatedSimDataset.refresh(merlinHelper));
    }

    /**
     * Adjustments to the plan's duration does not affect the startOffset of the plan's sim- and plan datasets.
     */
    @Test
    void planDurationAdjusted() throws SQLException {
      // Adjust plan duration time back one hour
      merlinHelper.updatePlanDuration(planId, planDuration.minusSeconds(oneHour).toString());

      // Nothing should be adjusted
      assertEquals(planDataset, planDataset.refresh(merlinHelper));
      assertEquals(simDataset, simDataset.refresh(merlinHelper));
      assertEquals(unrelatedPlanDataset, unrelatedPlanDataset.refresh(merlinHelper));
      assertEquals(unrelatedSimDataset, unrelatedSimDataset.refresh(merlinHelper));

      // Reset the plan bounds
      merlinHelper.updatePlanDuration(planId, planDuration.toString());

      // Nothing should be adjusted
      assertEquals(planDataset, planDataset.refresh(merlinHelper));
      assertEquals(simDataset, simDataset.refresh(merlinHelper));
      assertEquals(unrelatedPlanDataset, unrelatedPlanDataset.refresh(merlinHelper));
      assertEquals(unrelatedSimDataset, unrelatedSimDataset.refresh(merlinHelper));

      // Adjust plan duration forward one hour
      merlinHelper.updatePlanDuration(planId, planDuration.plusSeconds(oneHour).toString());

      // Nothing should be adjusted
      assertEquals(planDataset, planDataset.refresh(merlinHelper));
      assertEquals(simDataset, simDataset.refresh(merlinHelper));
      assertEquals(unrelatedPlanDataset, unrelatedPlanDataset.refresh(merlinHelper));
      assertEquals(unrelatedSimDataset, unrelatedSimDataset.refresh(merlinHelper));
    }
  }
}
