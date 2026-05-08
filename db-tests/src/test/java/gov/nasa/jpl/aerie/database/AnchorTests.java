package gov.nasa.jpl.aerie.database;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PGInterval;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AnchorTests {
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;
  int fileId;
  int missionModelId;
  int planId;
  int unrelatedActId;

  @BeforeEach
  void beforeEach() throws SQLException {
    fileId = merlinHelper.insertFileUpload();
    missionModelId = merlinHelper.insertMissionModel(fileId);
    planId = merlinHelper.insertPlan(missionModelId);
    unrelatedActId = merlinHelper.insertActivity(planId); // This activity should always be valid.
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_anchor_test", "Anchor Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  //region Helper Methods
  private void updateOffsetFromAnchor(PGInterval newOffset, int activityId, int planId) throws SQLException {
    try(final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          update merlin.activity_directive
          set start_offset = '%s'
          where id = %d and plan_id = %d;
          """.formatted(newOffset.toString(), activityId, planId));
    }
  }

  private Activity getActivity(final int planId, final int activityId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
        //language=sql
        """
        SELECT id, plan_id, start_offset, anchor_id, anchored_to_start, approximate_start_time
        FROM merlin.activity_directive_extended
        WHERE id = %d
        AND plan_id = %d;
        """.formatted(activityId, planId));
      res.next();
      return new Activity(
          res.getInt("id"),
          res.getInt("plan_id"),
          (PGInterval) res.getObject("start_offset"),
          res.getString("anchor_id"),
          res.getBoolean("anchored_to_start"),
          res.getString("approximate_start_time")
      );
    }
  }

  private ArrayList<Activity> getActivities(final int planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
        //language=sql
        """
        SELECT *
        FROM merlin.activity_directive_extended
        WHERE plan_id = %d
        ORDER BY id;
        """.formatted(planId));

      final var activities = new ArrayList<Activity>();
      while (res.next()){
        activities.add(new Activity(
            res.getInt("id"),
            res.getInt("plan_id"),
            (PGInterval) res.getObject("start_offset"),
            res.getString("anchor_id"),
            res.getBoolean("anchored_to_start"),
            res.getString("approximate_start_time")
        ));
      }
      return activities;
    }
  }

  private AnchorValidationStatus getValidationStatus(final int planId, final int activityId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT *
          FROM merlin.anchor_validation_status
          WHERE activity_id = %d
          AND plan_id = %d;
          """.formatted(activityId, planId));
      res.next();
      return new AnchorValidationStatus(
          res.getInt("activity_id"),
          res.getInt("plan_id"),
          res.getString("reason_invalid")
      );
    }
  }

  private AnchorValidationStatus refresh(AnchorValidationStatus original) throws SQLException{
    return getValidationStatus(original.planId, original.activityId);
  }

  int insertActivityWithAnchor(final int planId, final PGInterval startOffset, final int anchorId, final boolean anchoredToStart) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
              //language=sql
              """
              INSERT INTO merlin.activity_directive (type, plan_id, start_offset, arguments, anchor_id, anchored_to_start)
              VALUES ('test-activity', '%s', '%s', '{}', %d, %b)
              RETURNING id;
              """.formatted(planId, startOffset.toString(), anchorId, anchoredToStart));
      res.next();
      return res.getInt("id");
    }
  }

  private static void assertActivityEquals(final Activity expected, final Activity actual) {
    assertEquals(expected.activityId, actual.activityId);
    assertEquals(expected.planId, actual.planId);
    assertEquals(expected.startOffset, actual.startOffset);
    assertEquals(expected.anchorId, actual.anchorId);
    assertEquals(expected.anchoredToStart, actual.anchoredToStart);
  }
  //endregion

  //region Records
  private record Activity(
      int activityId,
      int planId,
      PGInterval startOffset,
      Integer anchorId,  // Since anchor_id allows for null values, this is an Integer to avoid confusion over what a number means.
      boolean anchoredToStart,
      String approximateStartTime
  ) {
    private Activity(
        int activityId,
        int planId,
        PGInterval startOffset,
        String anchorId,
        boolean anchoredToStart,
        String approximateStartTime
    ) {
      this(
          activityId,
          planId,
          startOffset,
          anchorId == null ? null : Integer.valueOf(anchorId),
          anchoredToStart,
          approximateStartTime);
    }
  }

  private record AnchorValidationStatus(int activityId, int planId, String reasonInvalid) {}
  //endregion

  @Nested
  class AnchorCreationAndExceptions {
    @Test
    void createAnchor() throws SQLException {
      final PGInterval oneDay = new PGInterval("1 day");
      final PGInterval tenMinutes = new PGInterval("10 minutes");

      final int anchorActId = merlinHelper.insertActivity(planId);
      final int otherActId = merlinHelper.insertActivity(planId, oneDay);

      // Assert that otherActId has an anchor of null but an offset equal to the input
      Activity otherActivity = getActivity(planId, otherActId);
      assertNull(otherActivity.anchorId);
      assertTrue(otherActivity.anchoredToStart);
      assertEquals(oneDay, otherActivity.startOffset);
      assertEquals("2020-01-02 00:00:00+00", otherActivity.approximateStartTime);

      // Set the anchor and assert that otherActivity was updated as expected.
      merlinHelper.setAnchor(anchorActId, false, otherActId, planId);
      updateOffsetFromAnchor(tenMinutes, otherActId, planId);

      otherActivity = getActivity(planId, otherActId);
      assertNotNull(otherActivity.anchorId);
      assertEquals(anchorActId, otherActivity.anchorId);
      assertFalse(otherActivity.anchoredToStart);
      assertEquals(tenMinutes, otherActivity.startOffset);
      assertEquals("2020-01-01 00:10:00+00", otherActivity.approximateStartTime);

      // Anchor activity has the correct offset
      Activity anchorActivity = getActivity(planId, anchorActId);
      assertEquals("2020-01-01 00:00:00+00", anchorActivity.approximateStartTime);
    }

    /**
     * An activity can't be directly anchored to itself.
     */
    @Test
    void cantAnchorToSelf() throws SQLException {
      final int activityId = merlinHelper.insertActivity(planId);
      final var sqlEx = assertThrows(SQLException.class,
                                     () -> merlinHelper.setAnchor(activityId, true, activityId, planId));
      assertTrue(sqlEx.getMessage().contains("Cannot anchor activity " + activityId + " to itself."));
    }

    /**
     * An activity can't be indirectly anchored to itself via an anchor cycle
     */
    @Test
    void noCyclesInAnchors() throws SQLException {
      final int actAId = merlinHelper.insertActivity(planId);
      final int actBId = merlinHelper.insertActivity(planId);

      merlinHelper.setAnchor(actAId, true, actBId, planId);

      final var sqlEx = assertThrows(SQLException.class,
                                     () -> merlinHelper.setAnchor(actBId, true, actAId, planId));
      assertTrue(sqlEx.getMessage().contains("Cycle detected. Cannot apply changes."));
    }

    /**
     * An activity's anchor must exist in the same plan as the activity.
     */
    @Test
    void cannotAnchorToActivityNotInPlan() throws SQLException {
      final int activityId = merlinHelper.insertActivity(planId);

      final int otherPlanId = merlinHelper.insertPlan(missionModelId);
      final int otherPlanActivity = merlinHelper.insertActivity(otherPlanId);

      final var sqlEx = assertThrows(SQLException.class,
                                     () -> merlinHelper.setAnchor(otherPlanActivity, true, activityId, planId));
      assertTrue(sqlEx.getMessage().contains("insert or update on table \"activity_directive\" violates foreign key constraint \"anchor_in_plan\""));
    }
  }

  /**
   * Validation tests focusing on cases where an activity has a negative start offset relative to the end time of its anchor.
   */
  @Nested
  class NetNegativeEndTimeStatus {
    /**
     * Once an activity no longer has a Negative Start Offset relative to the end time of its anchor,
     * the warning status is cleared.
     */
    @Test
    void invalidMessageClearedUponResolution() throws SQLException {
      final var minusTenMinutes = new PGInterval("-10 minutes");
      final var zeroSeconds = new PGInterval("0 seconds");
      final var tenMinutes = new PGInterval("10 minutes");

      // Create a chain of anchored activities
      final int parentActId = merlinHelper.insertActivity(planId);
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);
      final int childActId = insertActivityWithAnchor(planId, zeroSeconds, baseActId, true);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      final var parentStatus = getValidationStatus(planId, parentActId);
      final var baseStatus = getValidationStatus(planId, baseActId);
      final var childStatus = getValidationStatus(planId, childActId);

      // Base and Child are currently invalid
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());
      assertTrue(parentStatus.reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset relative to an end-time"
                   + " anchor on Activity Directive " +parentActId +".", baseStatus.reasonInvalid);
      assertEquals("Activity Directive " +childActId +" has a net negative offset relative to an end-time"
                   + " anchor on Activity Directive " +parentActId +".", childStatus.reasonInvalid);

      // Update base to have a positive offset relative to the end time of parent, making it and child valid
      updateOffsetFromAnchor(tenMinutes, baseActId, planId);

      // The warning messages have been cleared
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
    }

    /**
     * In the event an activity is both before the plan start and has a Negative Start Offset relative to the
     *  end time of its anchor, the warning for the Negative Start Offset takes priority
     */
    @Test
    void netNegativeEndTimeTakesPriority() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int parentId = merlinHelper.insertActivity(planId, minusTenMinutes);
      final int childId = insertActivityWithAnchor(planId, minusTenMinutes, parentId, false);

      // Get a handle on the validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentId).reasonInvalid;
      final var childStatus = getValidationStatus(planId, childId).reasonInvalid;

      // Both parent and child are invalid, but for different stated reasons
      assertTrue(unrelatedStatus.isEmpty());
      assertEquals("Activity Directive " +parentId +" has a net negative offset relative to Plan Start.", parentStatus);
      assertEquals("Activity Directive " + childId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentId +".", childStatus);
    }

    /**
     * Case:
     *  Activity B has a negative anchor relative to the end of Activity A.
     * Behavior:
     *  Activity B has an invalid anchor.
     */
    @Test
    void baseActivityIsInvalid() throws SQLException {
      final var minusTenMinutes = new PGInterval("-10 minutes");

      // Create a chain of anchored activities
      final int parentActId = merlinHelper.insertActivity(planId);
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentActId).reasonInvalid;
      final var baseStatus = getValidationStatus(planId, baseActId).reasonInvalid;

      // The base activity has a negative start offset relative to the parent activity, so it is flagged as invalid
      assertTrue(unrelatedStatus.isEmpty());
      assertTrue(parentStatus.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset relative to an end-time"
                   + " anchor on Activity Directive " +parentActId +".", baseStatus);
    }

    /**
     * Case:
     *  Activity B has a positive anchor relative to the end of Activity A.
     *  Activity C has a negative anchor relative to the start of Activity B,
     *    where the magnitude of its anchor is greater than Activity B's start offset.
     * Behavior:
     *  Activity C has an invalid anchor.
     */
    @Test
    void invalidChild() throws SQLException {
      final var fiveMinutes = new PGInterval("5 minutes");
      final var minusTenMinutes = new PGInterval("-10 minutes");

      // Create a chain
      final int parentActId = merlinHelper.insertActivity(planId);
      final int baseActId = insertActivityWithAnchor(planId, fiveMinutes, parentActId, false);
      final int childActId = insertActivityWithAnchor(planId, minusTenMinutes, baseActId, true);

      // Get a handle on their validation statuses
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentValidation = getValidationStatus(planId, parentActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var childValidation = getValidationStatus(planId, childActId).reasonInvalid;

      // Base activity is invalid, as its net offset from the end of grandparent is -5 minutes
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(parentValidation.isEmpty());
      assertTrue(baseValidation.isEmpty());
      assertEquals("Activity Directive " +childActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", childValidation);
    }

    /**
     * Case:
     *  Activity B has a positive anchor relative to the end of Activity A.
     *  Activity C has a negative anchor relative to the start of Activity B,
     *    where the magnitude of its anchor is less than or equal to Activity B's start offset.
     * Behavior:
     *  No activities have invalid anchors.
     */
    @ParameterizedTest
    @ValueSource(strings = {"-5 minutes", "-10 minutes"})
    void validChild(String childInterval) throws SQLException {
      final var tenMinutes = new PGInterval("10 minutes");

      // Create a chain
      final int parentActId = merlinHelper.insertActivity(planId, tenMinutes);
      final int baseActId = insertActivityWithAnchor(planId, tenMinutes, parentActId, false);
      final int childActId = insertActivityWithAnchor(planId, new PGInterval(childInterval), baseActId, true);

      // Get a handle on their validation statuses
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentValidation = getValidationStatus(planId, parentActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var childValidation = getValidationStatus(planId, childActId).reasonInvalid;

      // No activity is invalid
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(parentValidation.isEmpty());
      assertTrue(baseValidation.isEmpty());
      assertTrue(childValidation.isEmpty());
    }

    /**
     * Case:
     *  Activity B has a negative anchor relative to the end of Activity A.
     *  Activity C has a positive anchor relative to the start of Activity B,
     *    where the magnitude of its anchor is less than Activity B's start offset
     * Behavior:
     *  Both activity B and C have invalid anchors
     */
    @Test
    void indirectlyInvalidChild() throws SQLException {
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval fiveMinutes = new PGInterval("5 minutes");

      // Create a chain
      final int parentActId = merlinHelper.insertActivity(planId, fifteenMinutes);
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);
      final int childActId = insertActivityWithAnchor(planId, fiveMinutes, baseActId, true);

      // Get a handle on the validation statuses
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentValidation = getValidationStatus(planId, parentActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var childValidation = getValidationStatus(planId, childActId).reasonInvalid;

      // Base and Child should both have warning messages, as they both have a negative offset relative to Parent
      // (-10 mins and -5 mins, respectively)
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(parentValidation.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", baseValidation);
      assertEquals("Activity Directive " +childActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", childValidation);
    }

    /**
     * Case:
     *  Activity B has a negative anchor relative to the end of Activity A.
     *  Activity C has a positive anchor relative to the start of Activity B,
     *    where the magnitude of its anchor is greater than or equal to Activity B's start offset
     * Behavior:
     *  Only activity B has an invalid anchor
     */
    @ParameterizedTest
    @ValueSource(strings = {"10 minutes", "15 minutes"})
    void onlyInvalidParent(String childInterval) throws SQLException {
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      // Create a chain
      final int parentActId = merlinHelper.insertActivity(planId, fifteenMinutes);
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);
      final int childActId = insertActivityWithAnchor(planId, new PGInterval(childInterval), baseActId, true);

      // Get a handle on the validation statuses
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentValidation = getValidationStatus(planId, parentActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var childValidation = getValidationStatus(planId, childActId).reasonInvalid;

      // Only Base should have a warning message
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(parentValidation.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", baseValidation);
      assertTrue(childValidation.isEmpty());
    }

    /**
     * Case:
     *  Activity B has a negative anchor relative to the end of Activity A.
     *  Activity C has a positive anchor relative to the end of Activity B.
     * Behavior:
     *  Only activity B has an invalid anchor.
     *  Because C is anchored to the end of B, whether its invalid relative to A depends on the duration of B.
     *  As such, anchor validation only checks to the nearest end anchor.
     */
    @Test
    void onlyNearestEndTimeAnchorChecked() throws SQLException {
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      // Create a chain
      final int parentActId = merlinHelper.insertActivity(planId, fifteenMinutes);
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);
      final int childActId = insertActivityWithAnchor(planId, fiveMinutes, baseActId, false);

      // Get a handle on the validation statuses
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentValidation = getValidationStatus(planId, parentActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var childValidation = getValidationStatus(planId, childActId).reasonInvalid;

      // Only Base is marked as invalid
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(parentValidation.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", baseValidation);
      assertTrue(childValidation.isEmpty());
    }

    /**
     * Case:
     *  Activity B is anchored to the end of Activity A with a negative start offset.
     *  There is a long chain of activities anchored to the start of B with an start offset of 0.
     * Behavior:
     *  Both B and the entire chain of activities attached to B are invalid.
     */
    @Test
    void invalidFarDescendant() throws SQLException {
      final var fifteenMinutes = new PGInterval("15 minutes");
      final var minusTenMinutes = new PGInterval("-10 minutes");
      final var zeroSeconds = new PGInterval("0 seconds");

      // Create a chain
      final int parentActId = merlinHelper.insertActivity(planId, fifteenMinutes);
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);
      final int[] chainActIds = new int[100];
      chainActIds[0] = insertActivityWithAnchor(planId, zeroSeconds, baseActId, true);
      for(int i = 1; i < 100; i++){
        chainActIds[i] = insertActivityWithAnchor(planId, zeroSeconds, chainActIds[i-1], true);
      }

      // Get a handle on all the validations
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentValidation = getValidationStatus(planId, parentActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var chainValidations = new String[100];
      for(int i = 0; i < 100; i++){
        chainValidations[i] = getValidationStatus(planId, chainActIds[i]).reasonInvalid;
      }

      // Everything besides parent and the unrelated activity are invalid
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(parentValidation.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", baseValidation);
      for(int i = 0; i < 100; i++){
        assertEquals("Activity Directive " +chainActIds[i] +" has a net negative offset "
                     + "relative to an end-time anchor on Activity Directive " +parentActId +".", chainValidations[i]);
      }
    }
  }

  /**
   * Validation tests focusing on cases where an activity has a negative start offset relative to the start of the plan.
   */
  @Nested
  class NetNegativePlanStartStatus {
    /**
     * Once an activity no longer has a Negative Start Offset relative to the start of the plan,
     * the warning status is cleared.
     */
    @Test
    void invalidMessageClearedUponResolution() throws SQLException {
      final var minusTenMinutes = new PGInterval("-10 minutes");
      final var tenMinutes = new PGInterval("10 minutes");

      final int activityId = merlinHelper.insertActivity(planId, minusTenMinutes);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      final var activityStatus = getValidationStatus(planId, activityId);

      // Only activity has an invalid status
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +activityId +" has a net negative offset relative to Plan Start.", activityStatus.reasonInvalid);

      // Update activity to have a positive offset relative to the plan start, making it valid
      updateOffsetFromAnchor(tenMinutes, activityId, planId);

      // The warning message has been cleared
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(activityStatus).reasonInvalid.isEmpty());
    }


    /**
     * Activities directly anchored to plan start cannot have a negative start offset.
     */
    @Test
    void negativeToPlanStart() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final int activityId = merlinHelper.insertActivity(planId, minusTenMinutes);

      // Activity is invalid
      final var activityStatus = getValidationStatus(planId, activityId).reasonInvalid;
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      assertEquals("Activity Directive " +activityId +" has a net negative offset relative to Plan Start.", activityStatus);
      assertTrue(unrelatedStatus.isEmpty());
    }

    /**
     * Case:
     *  Activity A has a negative start offset relative to plan start.
     *  Activity B has a positive start offset relative to the start of A,
     *    where the magnitude of its anchor is less than Activity A's start offset
     * Result:
     *  Activity A and B are both invalid.
     */
    @Test
    void indirectlyInvalidChild() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval nineMinutes = new PGInterval("9 minutes");

      final int parentId = merlinHelper.insertActivity(planId, minusTenMinutes);
      final int childId = insertActivityWithAnchor(planId, nineMinutes, parentId, true);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentId).reasonInvalid;
      final var childStatus = getValidationStatus(planId, childId).reasonInvalid;

      // Only the unrelated activity is valid, as parent has a net start offset of "-10 mins",
      // and child has a net start offset of "-1 minute"
      assertTrue(unrelatedStatus.isEmpty());
      assertEquals("Activity Directive " +parentId +" has a net negative offset relative to Plan Start.", parentStatus);
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", childStatus);
    }

    /**
     * Case:
     *  Activity A has a negative start offset relative to plan start.
     *  Activity B and Activity C each have positive start offsets, but not enough to put the chain back within plan bounds.
     * Result:
     *  Activity A, B, and C are all invalid.
     */
    @Test
    void recursivelyInvalidPositiveOffsetDescendant() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fourMinutes = new PGInterval("4 minutes");

      final int parentId = merlinHelper.insertActivity(planId, minusTenMinutes);
      final int childId = insertActivityWithAnchor(planId, fiveMinutes, parentId, true);
      final int grandchildId = insertActivityWithAnchor(planId, fourMinutes, childId, true);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentId).reasonInvalid;
      final var childStatus = getValidationStatus(planId, childId).reasonInvalid;
      final var grandchildStatus = getValidationStatus(planId, grandchildId).reasonInvalid;

      // Only the unrelated activity is valid, as the net start offsets are "-10 minutes", "-5 minutes", and "-1 minute"
      assertTrue(unrelatedStatus.isEmpty());
      assertEquals("Activity Directive " +parentId +" has a net negative offset relative to Plan Start.", parentStatus);
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", childStatus);
      assertEquals("Activity Directive " +grandchildId +" has a net negative offset relative to Plan Start.", grandchildStatus);
    }

    /**
     * Case:
     *  Activity A has a negative start offset relative to plan start.
     *  Activity B has a positive start offset relative to the start of A,
     *    where the magnitude of its anchor is greater than or equal to Activity A's start offset
     * Result:
     *  Only Activity A is invalid.
     */
    @ParameterizedTest
    @ValueSource(strings = {"10 minutes", "15 minutes"})
    void onlyInvalidParent(String childOffset) throws SQLException {
      final int parentId = merlinHelper.insertActivity(planId, new PGInterval("-10 minutes"));
      final int childId = insertActivityWithAnchor(planId, new PGInterval(childOffset), parentId, true);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentId).reasonInvalid;
      final var childStatus = getValidationStatus(planId, childId).reasonInvalid;

      // Only the parent is invalid, as child is within the plan bounds
      assertTrue(unrelatedStatus.isEmpty());
      assertEquals("Activity Directive " +parentId +" has a net negative offset relative to Plan Start.", parentStatus);
      assertTrue(childStatus.isEmpty());
    }

    /**
     * Case:
     *  Activity A has a negative start offset relative to plan start.
     *  Activity B has a positive start offset relative to the end of A.
     * Result:
     *  Only Activity A is invalid.
     *  Because B is anchored to the end of A, whether its invalid relative to plan start depends on the duration of A.
     *  As such, anchor validation only checks to the nearest end anchor.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0 minutes", "9 minutes", "10 minutes", "11 minutes"})
    void onlyNearestEndTimeAnchorChecked(String childOffset) throws SQLException {
      final int parentId = merlinHelper.insertActivity(planId, new PGInterval("-10 minutes"));
      final int childId = insertActivityWithAnchor(planId, new PGInterval(childOffset), parentId, false);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentId).reasonInvalid;
      final var childStatus = getValidationStatus(planId, childId).reasonInvalid;

      // Only parent is flagged as invalid
      assertTrue(unrelatedStatus.isEmpty());
      assertEquals("Activity Directive " +parentId +" has a net negative offset relative to Plan Start.", parentStatus);
      assertTrue(childStatus.isEmpty());
    }

    /**
     * Case:
     *  Activity A has a negative start offset relative to plan start.
     *  Activity B has a negative start offset relative to start of A.
     * Result:
     *  Both Activities A and B are invalid.
     */
    @Test
    void invalidParentAndChild() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int parentActId = merlinHelper.insertActivity(planId, minusTenMinutes);
      final int childId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, true);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentActId).reasonInvalid;
      final var childStatus = getValidationStatus(planId, childId).reasonInvalid;

      // Both activities are invalid
      assertTrue(unrelatedStatus.isEmpty());
      assertEquals("Activity Directive " +parentActId +" has a net negative offset relative to Plan Start.", parentStatus);
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", childStatus);
    }

    /**
     * Case:
     *  Activity A has a positive start offset relative to plan start.
     *  Activity B has a negative start offset relative to start of A,
     *    where the magnitude of its anchor is greater than Activity A's start offset
     * Result:
     *  Only Activity B is invalid.
     */
    @Test
    void invalidChild() throws SQLException {
      final PGInterval tenMinutes = new PGInterval("10 minutes");
      final PGInterval minusFifteenMinutes = new PGInterval("-15 minutes");

      final int parentId = merlinHelper.insertActivity(planId, tenMinutes);
      final int childId = insertActivityWithAnchor(planId, minusFifteenMinutes, parentId, true);

      // Get a handle on their validation statuses
      final var unrelatedStatus = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var parentStatus = getValidationStatus(planId, parentId).reasonInvalid;
      final var childStatus = getValidationStatus(planId, childId).reasonInvalid;

      // Only child activity is invalid
      assertTrue(unrelatedStatus.isEmpty());
      assertTrue(parentStatus.isEmpty());
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", childStatus);
    }

    /**
     * Case:
     *  Activity A has a start offset relative to plan start of 0.
     *  There is a long chain of activities anchored to the start of A with a start offset of -1s.
     * Behavior:
     *  The entire chain of activities attached to A are invalid.
     */
    @Test
    void invalidChain() throws SQLException {
      final var zeroSeconds = new PGInterval("0 seconds");
      final var minusOneSecond = new PGInterval("-1 seconds");

      // Create a chain
      final int baseActId = merlinHelper.insertActivity(planId, zeroSeconds);
      final int[] chainActIds = new int[100];
      chainActIds[0] = insertActivityWithAnchor(planId, minusOneSecond, baseActId, true);
      for(int i = 1; i < 100; i++){
        chainActIds[i] = insertActivityWithAnchor(planId, minusOneSecond, chainActIds[i-1], true);
      }

      // Get a handle on all the validations
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var chainValidations = new String[100];
      for(int i = 0; i < 100; i++){
        chainValidations[i] = getValidationStatus(planId, chainActIds[i]).reasonInvalid;
      }

      // Only the unrelated and base activities are vaid
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(baseValidation.isEmpty());
      for(int i = 0; i < 100; i++){
        assertEquals("Activity Directive " +chainActIds[i] +" has a net negative offset relative to Plan Start.", chainValidations[i]);
      }
    }

    /**
     * Case:
     *  Activity A has a positive start offset relative to plan start.
     *  There is a long chain of activities anchored to the start of A with a start offset of 0.
     *  Activity B is anchored to the end of the chain with a negative offset relative to the start of the final
     *    activity in the chain, with a magnitude great enough to put it before plan start.
     * Behavior:
     *  Activity B is invalid.
     */
    @Test
    void invalidFarDescendant() throws SQLException {
      final var tenMinutes = new PGInterval("10 minutes");
      final var minusFifteenMinutes = new PGInterval("-15 minutes");
      final var zeroSeconds = new PGInterval("0 seconds");

      // Create a chain
      final int baseActId = merlinHelper.insertActivity(planId, tenMinutes);
      final int[] chainActIds = new int[100];
      chainActIds[0] = insertActivityWithAnchor(planId, zeroSeconds, baseActId, true);
      for(int i = 1; i < 100; i++){
        chainActIds[i] = insertActivityWithAnchor(planId, zeroSeconds, chainActIds[i-1], true);
      }
      final int descendantActId = insertActivityWithAnchor(planId, minusFifteenMinutes, chainActIds[99], true);

      // Get a handle on all the validations
      final var unrelatedValidation = getValidationStatus(planId, unrelatedActId).reasonInvalid;
      final var baseValidation = getValidationStatus(planId, baseActId).reasonInvalid;
      final var chainValidations = new String[100];
      for(int i = 0; i < 100; i++){
        chainValidations[i] = getValidationStatus(planId, chainActIds[i]).reasonInvalid;
      }
      final var descendantValidation = getValidationStatus(planId, descendantActId).reasonInvalid;

      // Everything besides descendant is valid
      assertTrue(unrelatedValidation.isEmpty());
      assertTrue(baseValidation.isEmpty());
      for(int i = 0; i < 100; i++){
        assertTrue(chainValidations[i].isEmpty());
      }
      assertEquals("Activity Directive " +descendantActId +" has a net negative offset relative to Plan Start.", descendantValidation);
    }
  }

  @Nested
  class AnchorDeletion {
    private final static String reanchorPlanStartStatement =
        //language=sql
        """
        select hasura.delete_activity_by_pk_reanchor_plan_start(%s, %s, %s)
        """;
    private final static String reanchorToAnchorStatement =
        //language=sql
        """
        select hasura.delete_activity_by_pk_reanchor_to_anchor(%s, %s, %s)
        """;
    private final static String deleteRemainingChainStatement =
        //language=sql
        """
        select hasura.delete_activity_by_pk_delete_subtree(%s, %s, %s)
        """;

    private static Stream<Arguments> nullInputTestArgs() {
      return Stream.of(
          // Reanchor to Plan Start Cases
          Arguments.arguments(reanchorPlanStartStatement, 1),
          Arguments.arguments(reanchorPlanStartStatement, 2),
          Arguments.arguments(reanchorPlanStartStatement, 3),
          // Reanchor to Ascendant Anchor Cases
          Arguments.arguments(reanchorToAnchorStatement, 1),
          Arguments.arguments(reanchorToAnchorStatement, 2),
          Arguments.arguments(reanchorToAnchorStatement, 3),
          // Delete Remaining Chain Cases
          Arguments.arguments(deleteRemainingChainStatement, 1),
          Arguments.arguments(deleteRemainingChainStatement, 2),
          Arguments.arguments(deleteRemainingChainStatement, 3)
      );
    }

    /**
     * An activity cannot be removed using a DELETE statement if it has activities anchored to it
     */
    @Test
    void cantDeleteActivityWithAnchors() throws SQLException {
      final int anchorId = merlinHelper.insertActivity(planId);
      insertActivityWithAnchor(planId, new PGInterval("0 seconds"), anchorId, true);

      final var sqlEx = assertThrows(SQLException.class, () -> merlinHelper.deleteActivityDirective(planId, anchorId));
      assertTrue(sqlEx.getMessage().contains(
          "update or delete on table \"activity_directive\" violates foreign key constraint \"anchor_in_plan\" on table \"activity_directive\""));
    }

    /**
     * The Hasura functions are defined as STRICT. This means that if a NULL value is passed to a parameter,
     * the function immediately returns NULL rather than raising an exception.
     */
    @ParameterizedTest
    @MethodSource("nullInputTestArgs")
    void rebasesDoNotRunOnNullParameters(final String sqlStatement, int nullPosition) throws SQLException {
      final int activityId = merlinHelper.insertActivity(planId);
      final var userSession = "'%s'::json".formatted(merlinHelper.admin.session());
      String executingStatement = "";

      // Since the variables to be supplied to the non-null parameters of "sqlStatement" are non-static,
      // we have to apply the formatting here rather than in the argument source
      switch (nullPosition) {
        case 1 -> executingStatement = sqlStatement.formatted(null, planId, userSession);
        case 2 -> executingStatement = sqlStatement.formatted(activityId, null, userSession);
        case 3 -> executingStatement = sqlStatement.formatted(activityId, planId, null);
        default -> fail("Invalid nullPosition: "+nullPosition);
      }

      assertFalse(executingStatement.isBlank());

      try (final var statement = connection.createStatement()) {
        final var results = statement.executeQuery(executingStatement);
        if (results.next()) {
          fail();
        }
      }
    }

    @ParameterizedTest
    @ValueSource(strings = {reanchorPlanStartStatement, reanchorToAnchorStatement, deleteRemainingChainStatement})
    void cannotRebaseActivityThatDoesNotExist(String sqlStatement) {
      final var executingStatement = sqlStatement.formatted(-1, planId, "'%s'::json".formatted(merlinHelper.admin.session()));

      final var sqlEx = assertThrows(SQLException.class, () -> {
        try(final var statement = connection.createStatement()) {
          statement.execute(executingStatement);
        }
      });
      assertTrue(sqlEx.getMessage().contains("Activity Directive -1 does not exist in Plan "+planId));
    }


    private int insertUnanchoredActivities(int planId) throws SQLException {
      final PGInterval oneDay = new PGInterval("1 day");
      int lastInsertedId = merlinHelper.insertActivity(planId);
      for(int i = 0; i < 10; i++){
        lastInsertedId = insertActivityWithAnchor(planId, oneDay, lastInsertedId, true);
      }
      return lastInsertedId;
    }

    private void insertChains(int chain1BaseId, int chain2BaseId, int chain3BaseId) throws SQLException {
      final PGInterval zeroSeconds = new PGInterval("0 seconds");

      int mostRecentChain1Id = chain1BaseId;
      int mostRecentChain2Id = chain2BaseId;
      int mostRecentChain3Id = chain3BaseId;

      for(int i = 0; i < 100; i++) {
        mostRecentChain1Id = insertActivityWithAnchor(planId, zeroSeconds, mostRecentChain1Id, true);
        mostRecentChain2Id = insertActivityWithAnchor(planId, zeroSeconds, mostRecentChain2Id, false);
        mostRecentChain3Id = insertActivityWithAnchor(planId, zeroSeconds, mostRecentChain3Id, (i & 1) == 0); // alternates true and false
      }
    }

    @Test
    void rebaseToAscendantAnchor() throws SQLException{
      final PGInterval minusTwoDays = new PGInterval("-2 days");
      final PGInterval minusFourDays = new PGInterval("-4 days");
      final PGInterval zeroSeconds = new PGInterval("0 seconds");

      // Set-up: add a bunch of unanchored activities that won't be touched by the delete
      final var parentId = insertUnanchoredActivities(planId);
      final var untouchedActivities = getActivities(planId);

      // Add the activity that will be deleted
      final int baseId = insertActivityWithAnchor(planId, minusTwoDays, parentId, true);

      /* Add three chains of activities, each anchored to the base activity
       *    - In chain 1, all anchors are "start time anchors"
       *    - In chain 2, all anchors are "end time anchors"
       *    - In chain 3, anchors alternate between being "start time" and "end time"
       */
      final int chain1BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      final int chain2BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, false);
      final int chain3BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      insertChains(chain1BaseId, chain2BaseId, chain3BaseId);

      assertEquals(304+untouchedActivities.size(), getActivities(planId).size());

      // Delete the Base activity using the "reanchor chain to this activity's anchor" strategy
      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_to_anchor(%d, %d, '%s'::json)
            """.formatted(baseId, planId, merlinHelper.admin.session()));
      }

      // Only one activity should've been deleted
      final var remainingActivities = getActivities(planId);
      assertEquals(303+untouchedActivities.size(), remainingActivities.size());

      // The unanchored activities should be untouched
      for(int i = 0; i < untouchedActivities.size(); i++){
        assertActivityEquals(untouchedActivities.get(i), remainingActivities.get(i));
      }

      // The parent activity has inherited the chains, with the start offsets being appropriately adjusted
      // so that the chains still start at the same time
      assertEquals(minusFourDays, getActivity(planId, chain1BaseId).startOffset);
      assertEquals(parentId, getActivity(planId,chain1BaseId).anchorId);
      assertEquals(minusFourDays, getActivity(planId, chain2BaseId).startOffset);
      assertEquals(parentId, getActivity(planId,chain2BaseId).anchorId);
      assertEquals(minusFourDays, getActivity(planId, chain3BaseId).startOffset);
      assertEquals(parentId, getActivity(planId,chain3BaseId).anchorId);

      // The rest of the chains are unaffected by the change
      for(int i = untouchedActivities.size()+3; i < remainingActivities.size(); i++){
        assertEquals(zeroSeconds, remainingActivities.get(i).startOffset);
        assertNotNull(remainingActivities.get(i).anchorId);
      }
    }

    @Test
    void rebaseChainsToPlanStart() throws SQLException{
      final PGInterval zeroSeconds = new PGInterval("0 seconds");
      final PGInterval minusTwoDays = new PGInterval("-2 days");
      final PGInterval sixDays = new PGInterval("6 days");

      // Set-up: add a bunch of unanchored activities that won't be touched by the delete
      final var parentId = insertUnanchoredActivities(planId);
      final var untouchedActivities = getActivities(planId);

      // Add the activity that will be deleted
      final int baseId = insertActivityWithAnchor(planId, minusTwoDays, parentId, true);

      /* Add three chains of activities, each anchored to the base activity
       *    - In chain 1, all anchors are "start time anchors"
       *    - In chain 2, all anchors are "end time anchors"
       *    - In chain 3, anchors alternate between being "start time" and "end time"
       */
      final int chain1BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      final int chain2BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, false);
      final int chain3BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      insertChains(chain1BaseId, chain2BaseId, chain3BaseId);

      assertEquals(304+untouchedActivities.size(), getActivities(planId).size());

      // Delete the Base activity using the "reanchor chain plan start" strategy
      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_plan_start(%d, %d, '%s'::json)
            """.formatted(baseId, planId, merlinHelper.admin.session()));
      }

      // Only one activity should've been deleted
      final var remainingActivities = getActivities(planId);
      assertEquals(303+untouchedActivities.size(), remainingActivities.size());

      // The unanchored activities should be untouched
      for(int i = 0; i < untouchedActivities.size(); i++){
        assertActivityEquals(untouchedActivities.get(i), remainingActivities.get(i));
      }

      // The chains have been reanchored to plan start, with their start offsets being appropriately adjusted
      // so that they still start at the same time
      assertEquals(sixDays, getActivity(planId, chain1BaseId).startOffset);
      assertNull(getActivity(planId,chain1BaseId).anchorId);
      assertEquals(sixDays, getActivity(planId, chain2BaseId).startOffset);
      assertNull(getActivity(planId,chain2BaseId).anchorId);
      assertEquals(sixDays, getActivity(planId, chain3BaseId).startOffset);
      assertNull(getActivity(planId,chain3BaseId).anchorId);

      // The rest of the chains are unaffected by the change
      for(int i = untouchedActivities.size()+3; i < remainingActivities.size(); i++){
        assertEquals(zeroSeconds, remainingActivities.get(i).startOffset);
        assertNotNull(remainingActivities.get(i).anchorId);
      }
    }

    @Test
    void deleteChain() throws SQLException{
      final PGInterval zeroSeconds = new PGInterval("0 seconds");

      // Set-up: add a bunch of unanchored activities that won't be touched by the delete
      final var parentId = insertUnanchoredActivities(planId);
      final var untouchedActivities = getActivities(planId);

      // Add the activity that will be deleted
      final int baseId = insertActivityWithAnchor(planId, zeroSeconds, parentId, true);

      /* Add three chains of activities, each anchored to the base activity
       *    - In chain 1, all anchors are "start time anchors"
       *    - In chain 2, all anchors are "end time anchors"
       *    - In chain 3, anchors alternate between being "start time" and "end time"
       */
      final int chain1BaseId = insertActivityWithAnchor(planId, zeroSeconds, baseId, true);
      final int chain2BaseId = insertActivityWithAnchor(planId, zeroSeconds, baseId, false);
      final int chain3BaseId = insertActivityWithAnchor(planId, zeroSeconds, baseId, true);
      insertChains(chain1BaseId, chain2BaseId, chain3BaseId);
      assertEquals(304+untouchedActivities.size(), getActivities(planId).size());

      // Delete the base activity using the "delete all activities anchored to this one" strategy
      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_delete_subtree(%d, %d, '%s'::json)
            """.formatted(baseId, planId, merlinHelper.admin.session()));
      }

      // Only the unanchored activities should remain
      final var remainingActivities = getActivities(planId);
      assertEquals(untouchedActivities.size(), remainingActivities.size());

      // The unanchored activities should be untouched
      for(int i = 0; i < untouchedActivities.size(); i++){
        assertActivityEquals(untouchedActivities.get(i), remainingActivities.get(i));
      }
    }
  }
}
