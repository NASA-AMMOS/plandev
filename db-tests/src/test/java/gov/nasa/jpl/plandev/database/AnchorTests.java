package gov.nasa.jpl.plandev.database;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  @BeforeEach
  void beforeEach() throws SQLException {
    fileId = merlinHelper.insertFileUpload();
    missionModelId = merlinHelper.insertMissionModel(fileId);
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
      String anchorId,  // Since anchor_id allows for null values, this is a String to avoid confusion over what the number means.
      boolean anchoredToStart,
      String approximateStartTime
  ) {}

  private record AnchorValidationStatus(int activityId, int planId, String reasonInvalid) {}
  //endregion

  @Nested
  class AnchorCreationAndExceptions {
    @Test
    void createAnchor() throws SQLException {
      final PGInterval oneDay = new PGInterval("1 day");
      final PGInterval tenMinutes = new PGInterval("10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int anchorActId = merlinHelper.insertActivity(planId);
      final int otherActId = merlinHelper.insertActivity(planId, oneDay.toString());

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
      assertEquals(anchorActId, Integer.valueOf(otherActivity.anchorId));
      assertFalse(otherActivity.anchoredToStart);
      assertEquals(tenMinutes, otherActivity.startOffset);
      assertEquals("2020-01-01 00:10:00+00", otherActivity.approximateStartTime);

      // Anchor activity has the correct offset
      Activity anchorActivity = getActivity(planId, anchorActId);
      assertEquals("2020-01-01 00:00:00+00", anchorActivity.approximateStartTime);
    }

    @Test
    void cantAnchorToSelf() throws SQLException {
      final int planId = merlinHelper.insertPlan(missionModelId);
      final int activityId = merlinHelper.insertActivity(planId);

      try {
        merlinHelper.setAnchor(activityId, true, activityId, planId);
        fail();
      } catch (SQLException ex) {
        if (!ex.getMessage().contains("Cannot anchor activity " + activityId + " to itself.")) {
          throw ex;
        }
      }
    }

    @Test
    void noCyclesInAnchors() throws SQLException {
      final int planId = merlinHelper.insertPlan(missionModelId);
      final int actAId = merlinHelper.insertActivity(planId);
      final int actBId = merlinHelper.insertActivity(planId);

      merlinHelper.setAnchor(actAId, true, actBId, planId);

      try {
        merlinHelper.setAnchor(actBId, true, actAId, planId);
        fail();
      } catch (SQLException ex) {
        if (!ex.getMessage().contains("Cycle detected. Cannot apply changes.")) {
          throw ex;
        }
      }
    }

    // This additionally tests that invalid anchor ids fail
    @Test
    void cannotAnchorToActivityNotInPlan() throws SQLException {
      final int planId = merlinHelper.insertPlan(missionModelId);
      final int activityId = merlinHelper.insertActivity(planId);

      try {
        merlinHelper.setAnchor(-10, true, activityId, planId);
        fail();
      } catch (SQLException ex) {
        if (!ex.getMessage().contains(
            "insert or update on table \"activity_directive\" violates foreign key constraint \"anchor_in_plan\"")) {
          throw ex;
        }
      }
    }
  }

  @Nested
  class NetNegativeEndTimeStatus {
    @Test
    void negativeEndTimeOffsetWritesToStatus() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int grandparentActId = merlinHelper.insertActivity(planId);
      final int parentActId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), grandparentActId, false);
      final int negOffsetActId = merlinHelper.insertActivity(planId, minusTenMinutes.toString());
      final int childActId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), negOffsetActId, true);
      final int unrelatedActId = merlinHelper.insertActivity(planId);

      // Invalid regarding Plan Start
      final AnchorValidationStatus negOffsetStatus = getValidationStatus(planId, negOffsetActId);
      final AnchorValidationStatus childStatus = getValidationStatus(planId, childActId);
      final AnchorValidationStatus parentStatus = getValidationStatus(planId, parentActId);
      final AnchorValidationStatus grandparentStatus = getValidationStatus(planId, grandparentActId);
      final AnchorValidationStatus unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      assertEquals("Activity Directive "+negOffsetActId +" has a net negative offset relative to Plan Start.", negOffsetStatus.reasonInvalid);
      assertTrue(childStatus.reasonInvalid.isEmpty());
      assertTrue(parentStatus.reasonInvalid.isEmpty());
      assertTrue(grandparentStatus.reasonInvalid.isEmpty());
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());

      // Invalid relative to parent
      merlinHelper.setAnchor(parentActId, false, negOffsetActId, planId);
      assertEquals("Activity Directive " +negOffsetActId +" has a net negative offset relative to an end-time"
                   + " anchor on Activity Directive " +parentActId +".", refresh(negOffsetStatus).reasonInvalid);
      assertEquals("Activity Directive " +childActId +" has a net negative offset relative to an end-time"
                   + " anchor on Activity Directive " +parentActId +".", refresh(childStatus).reasonInvalid);
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(grandparentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Valid relative to parent, invalid relative to grandparent
      merlinHelper.setAnchor(parentActId, true, negOffsetActId, planId);
      assertEquals("Activity Directive " +negOffsetActId +" has a net negative offset relative to an end-time"
                   + " anchor on Activity Directive " +grandparentActId +".", refresh(negOffsetStatus).reasonInvalid);
      assertEquals("Activity Directive " +childActId +" has a net negative offset relative to an end-time"
                   + " anchor on Activity Directive " +grandparentActId +".", refresh(childStatus).reasonInvalid);
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(grandparentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Valid regrading Plan End
      // This also validates that the validation status is cleared when updated to a valid value
      merlinHelper.setAnchor(-1, false, negOffsetActId, planId );
      assertTrue(refresh(negOffsetStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(grandparentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
    }

    @Test
    void immediateDescendentBecomesInvalid() throws SQLException {
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId); // Should always be valid.

      final int parentActId = merlinHelper.insertActivity(planId);
      final int baseActId = merlinHelper.insertActivity(planId, fifteenMinutes.toString());

      // Create a chain
      final int childActId = insertActivityWithAnchor(planId, minusTenMinutes, baseActId, true);

      final AnchorValidationStatus unrelatedValidation = getValidationStatus(planId, unrelatedActId);
      final AnchorValidationStatus baseValidation = getValidationStatus(planId, baseActId);
      final AnchorValidationStatus childValidation = getValidationStatus(planId, childActId);
      assertTrue(unrelatedValidation.reasonInvalid.isEmpty());
      assertTrue(baseValidation.reasonInvalid.isEmpty());
      assertTrue(childValidation.reasonInvalid.isEmpty());

      // Anchoring base to the end of parent does not invalidate anything.
      merlinHelper.setAnchor(parentActId, false, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());

      // Shortening base's offset makes child invalid
      updateOffsetFromAnchor(fiveMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +childActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", refresh(childValidation).reasonInvalid);

      // Updating base back makes child valid.
      updateOffsetFromAnchor(fifteenMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
    }

    @Test
    void childAndGrandchildBecomeInvalid() throws SQLException {
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId); // Should always be valid.

      final int parentActId = merlinHelper.insertActivity(planId);
      final int baseActId = merlinHelper.insertActivity(planId, fifteenMinutes.toString());

      // Create a chain
      final int childActId = insertActivityWithAnchor(planId, minusTenMinutes, baseActId, true);
      final int grandchildActId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), childActId, true);

      final AnchorValidationStatus unrelatedValidation = getValidationStatus(planId, unrelatedActId);
      final AnchorValidationStatus baseValidation = getValidationStatus(planId, baseActId);
      final AnchorValidationStatus childValidation = getValidationStatus(planId, childActId);
      final AnchorValidationStatus grandchildValidation = getValidationStatus(planId, grandchildActId);
      assertTrue(unrelatedValidation.reasonInvalid.isEmpty());
      assertTrue(baseValidation.reasonInvalid.isEmpty());
      assertTrue(childValidation.reasonInvalid.isEmpty());
      assertTrue(grandchildValidation.reasonInvalid.isEmpty());

      // Anchoring base to the end of parent does not invalidate anything due to size of base's offset.
      merlinHelper.setAnchor(parentActId, false, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertEquals("", refresh(baseValidation).reasonInvalid);
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(grandchildValidation).reasonInvalid.isEmpty());

      // Shortening base's offset makes child and grandchild invalid
      updateOffsetFromAnchor(fiveMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +childActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", refresh(childValidation).reasonInvalid);
      assertEquals("Activity Directive " +grandchildActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", refresh(grandchildValidation).reasonInvalid);

      // Restoring base makes child and grandchild valid.
      updateOffsetFromAnchor(fifteenMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(grandchildValidation).reasonInvalid.isEmpty());
    }

    /*
     * Similar test to childAndGrandchildBecomeInvalid, except grandchild having an end-time anchor will prevent it from
     * being checked (as whether it's invalid relative to parent's end time depends on the duration of child and base, which is unknowable at anchoring time)
     */
    @Test
    void grandchildEndTimeAnchorIsIgnored() throws SQLException {
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId); // Should always be valid.

      final int parentActId = merlinHelper.insertActivity(planId);
      final int baseActId = merlinHelper.insertActivity(planId, fifteenMinutes.toString());

      // Create a chain
      final int childActId = insertActivityWithAnchor(planId, minusTenMinutes, baseActId, true);
      final int grandchildActId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), childActId, false);

      final AnchorValidationStatus unrelatedValidation = getValidationStatus(planId, unrelatedActId);
      final AnchorValidationStatus baseValidation = getValidationStatus(planId, baseActId);
      final AnchorValidationStatus childValidation = getValidationStatus(planId, childActId);
      final AnchorValidationStatus grandchildValidation = getValidationStatus(planId, grandchildActId);
      assertTrue(unrelatedValidation.reasonInvalid.isEmpty());
      assertTrue(baseValidation.reasonInvalid.isEmpty());
      assertTrue(childValidation.reasonInvalid.isEmpty());
      assertTrue(grandchildValidation.reasonInvalid.isEmpty());

      // Anchoring base to the end of parent does not invalidate anything due to size of base's offset.
      merlinHelper.setAnchor(parentActId, false, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(grandchildValidation).reasonInvalid.isEmpty());

      // Shortening base's offset makes child invalid and does not affect grandchild.
      updateOffsetFromAnchor(fiveMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +childActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", refresh(childValidation).reasonInvalid);
      assertTrue(refresh(grandchildValidation).reasonInvalid.isEmpty());

      // Restoring base makes child valid.
      updateOffsetFromAnchor(fifteenMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(grandchildValidation).reasonInvalid.isEmpty());
    }

    @Test
    void onlyGrandchildBecomesInvalid() throws SQLException {
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId); // Should always be empty.

      final int parentActId = merlinHelper.insertActivity(planId);
      final int baseActId = merlinHelper.insertActivity(planId, fifteenMinutes.toString());

      // Create a chain
      final int childActId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), baseActId, true);
      final int grandchildActId = insertActivityWithAnchor(planId, minusTenMinutes, childActId, true);

      final AnchorValidationStatus unrelatedValidation = getValidationStatus(planId, unrelatedActId);
      final AnchorValidationStatus baseValidation = getValidationStatus(planId, baseActId);
      final AnchorValidationStatus childValidation = getValidationStatus(planId, childActId);
      final AnchorValidationStatus grandchildValidation = getValidationStatus(planId, grandchildActId);
      assertTrue(unrelatedValidation.reasonInvalid.isEmpty());
      assertTrue(baseValidation.reasonInvalid.isEmpty());
      assertTrue(childValidation.reasonInvalid.isEmpty());
      assertTrue(grandchildValidation.reasonInvalid.isEmpty());

      // Anchoring base to the end of parent does not invalidate anything due to size of base's offset.
      merlinHelper.setAnchor(parentActId, false, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(grandchildValidation).reasonInvalid.isEmpty());

      // Shortening base's offset makes grandchild invalid
      updateOffsetFromAnchor(fiveMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +grandchildActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", refresh(grandchildValidation).reasonInvalid);

      // Restoring base makes grandchild valid.
      updateOffsetFromAnchor(fifteenMinutes, baseActId, planId);
      assertTrue(refresh(unrelatedValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(baseValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(childValidation).reasonInvalid.isEmpty());
      assertTrue(refresh(grandchildValidation).reasonInvalid.isEmpty());
    }

    @Test
    void childIsInvalid() throws SQLException {
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId); // Should always be empty.

      final int parentActId = merlinHelper.insertActivity(planId, fifteenMinutes.toString());

      // Create a chain
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);
      final int childActId = insertActivityWithAnchor(planId, fiveMinutes, baseActId, true);

      final AnchorValidationStatus unrelatedValidation = getValidationStatus(planId, unrelatedActId);
      final AnchorValidationStatus parentValidation = getValidationStatus(planId, parentActId);
      final AnchorValidationStatus baseValidation = getValidationStatus(planId, baseActId);
      final AnchorValidationStatus childValidation = getValidationStatus(planId, childActId);

      assertTrue(unrelatedValidation.reasonInvalid.isEmpty());
      assertTrue(parentValidation.reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", baseValidation.reasonInvalid);
      assertEquals("Activity Directive " +childActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", childValidation.reasonInvalid);
    }

    @Test
    void farDescendantIsInvalid() throws SQLException {
      // Parent, base is anchored to end with negative offset, 100 anchors to start of base with 0 offset. Both parent and child should be invalid
      final PGInterval fiveMinutes = new PGInterval("5 minutes");
      final PGInterval fifteenMinutes = new PGInterval("15 minutes");
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId); // Should always be valid.

      final int parentActId = merlinHelper.insertActivity(planId, fifteenMinutes.toString());

      // Create a chain
      final int baseActId = insertActivityWithAnchor(planId, minusTenMinutes, parentActId, false);
      final int[] interimActIds = new int[100];
      interimActIds[0] = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), baseActId, true);
      for(int i = 1; i < 100; i++){
        interimActIds[i] = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), interimActIds[i-1], true);
      }
      final int childActId = insertActivityWithAnchor(planId, fiveMinutes, interimActIds[99], true);

      final AnchorValidationStatus unrelatedValidation = getValidationStatus(planId, unrelatedActId);
      final AnchorValidationStatus parentValidation = getValidationStatus(planId, parentActId);
      final AnchorValidationStatus baseValidation = getValidationStatus(planId, baseActId);
      final AnchorValidationStatus[] interimValidations = new AnchorValidationStatus[100];
      for(int i = 0; i < 100; i++){
        interimValidations[i] = getValidationStatus(planId, interimActIds[i]);
      }
      final AnchorValidationStatus childValidation = getValidationStatus(planId, childActId);

      assertTrue(unrelatedValidation.reasonInvalid.isEmpty());
      assertTrue(parentValidation.reasonInvalid.isEmpty());
      assertEquals("Activity Directive " +baseActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", baseValidation.reasonInvalid);
      for(int i = 0; i < 100; i++){
        assertEquals("Activity Directive " +interimActIds[i] +" has a net negative offset "
                     + "relative to an end-time anchor on Activity Directive " +parentActId +".", interimValidations[i].reasonInvalid);
      }
      assertEquals("Activity Directive " +childActId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +parentActId +".", childValidation.reasonInvalid);
    }
  }

  @Nested
  class NetNegativePlanStartStatus {
    @Test
    void negativeToPlanStart() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval tenMinutes = new PGInterval("10 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int activityId = merlinHelper.insertActivity(planId, tenMinutes.toString());
      final int unrelatedId = merlinHelper.insertActivity(planId);

      // Valid regarding Plan Start
      final AnchorValidationStatus activityStatus = getValidationStatus(planId, activityId);
      final AnchorValidationStatus unrelatedStatus = getValidationStatus(planId, unrelatedId);
      assertTrue(activityStatus.reasonInvalid.isEmpty());
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());

      // Make Invalid Relative to Plan Start
      updateOffsetFromAnchor(minusTenMinutes, activityId, planId);
      assertEquals("Activity Directive " +activityId +" has a net negative offset relative to Plan Start.", refresh(activityStatus).reasonInvalid);
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Restoring base clears the warning
      updateOffsetFromAnchor(tenMinutes, activityId, planId);
      assertTrue(refresh(activityStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
    }

    @Test
    void negativeToPlanStartDownChain() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval elevenMinutes = new PGInterval("11 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId);
      final int grandparentActId = merlinHelper.insertActivity(planId, elevenMinutes.toString());
      final int parentActId = insertActivityWithAnchor(planId, minusTenMinutes, grandparentActId, true);
      final int baseId = insertActivityWithAnchor(planId, elevenMinutes, parentActId, true);
      final int childId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), baseId, true);

      // Base is currently valid regarding Plan Start
      final AnchorValidationStatus baseStatus = getValidationStatus(planId, baseId);
      final AnchorValidationStatus childStatus = getValidationStatus(planId, childId);
      final AnchorValidationStatus parentStatus = getValidationStatus(planId, parentActId);
      final AnchorValidationStatus grandparentStatus = getValidationStatus(planId, grandparentActId);
      final AnchorValidationStatus unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      assertTrue(baseStatus.reasonInvalid.isEmpty());
      assertTrue(childStatus.reasonInvalid.isEmpty());
      assertTrue(parentStatus.reasonInvalid.isEmpty());
      assertTrue(grandparentStatus.reasonInvalid.isEmpty());
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());

      // Update makes base and child invalid relative to Plan Start
      updateOffsetFromAnchor(minusTenMinutes, baseId, planId);
      assertEquals("Activity Directive " +baseId +" has a net negative offset relative to Plan Start.", refresh(baseStatus).reasonInvalid);
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", refresh(childStatus).reasonInvalid);
      //assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(grandparentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Restoring the anchor clears the warnings
      updateOffsetFromAnchor(elevenMinutes, baseId, planId);
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(grandparentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
    }

    @Test
    void immediateDescendentBecomesInvalid() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval minusFifteenMinutes = new PGInterval("-15 minutes");
      final PGInterval twentyMinutes = new PGInterval("20 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId);

      final int parentId = merlinHelper.insertActivity(planId, twentyMinutes.toString());
      final int baseId = insertActivityWithAnchor(planId, twentyMinutes, parentId, true);
      final int childId = insertActivityWithAnchor(planId, minusTenMinutes, baseId, true);

      // Everything is currently valid regarding Plan Start
      final AnchorValidationStatus childStatus = getValidationStatus(planId, childId);
      final AnchorValidationStatus baseStatus = getValidationStatus(planId, baseId);
      final AnchorValidationStatus parentStatus = getValidationStatus(planId, parentId);
      final AnchorValidationStatus unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      assertTrue(childStatus.reasonInvalid.isEmpty());
      assertTrue(baseStatus.reasonInvalid.isEmpty());
      assertTrue(parentStatus.reasonInvalid.isEmpty());
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());

      // Update to base makes child and grandchild invalid relative to Plan Start
      updateOffsetFromAnchor(minusFifteenMinutes, baseId, planId);
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", refresh(childStatus).reasonInvalid);
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Restoring base clears the warning on child
      updateOffsetFromAnchor(twentyMinutes, baseId, planId);
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
    }

    @Test
    void childAndGrandchildBecomeInvalid() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval minusFifteenMinutes = new PGInterval("-15 minutes");
      final PGInterval twentyMinutes = new PGInterval("20 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId);

      final int parentId = merlinHelper.insertActivity(planId, twentyMinutes.toString());
      final int baseId = insertActivityWithAnchor(planId, twentyMinutes, parentId, true);
      final int childId = insertActivityWithAnchor(planId, minusTenMinutes, baseId, true);
      final int grandchildId = insertActivityWithAnchor(planId, minusTenMinutes, childId, true);

      // Everything is currently valid regarding Plan Start
      final AnchorValidationStatus grandChildStatus = getValidationStatus(planId, grandchildId);
      final AnchorValidationStatus childStatus = getValidationStatus(planId, childId);
      final AnchorValidationStatus baseStatus = getValidationStatus(planId, baseId);
      final AnchorValidationStatus parentStatus = getValidationStatus(planId, parentId);
      final AnchorValidationStatus unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      assertTrue(grandChildStatus.reasonInvalid.isEmpty());
      assertTrue(childStatus.reasonInvalid.isEmpty());
      assertTrue(baseStatus.reasonInvalid.isEmpty());
      assertTrue(parentStatus.reasonInvalid.isEmpty());
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());

      // Update to base makes child and grandchild invalid relative to Plan Start
      updateOffsetFromAnchor(minusFifteenMinutes, baseId, planId);
      assertEquals("Activity Directive " +grandchildId +" has a net negative offset relative to Plan Start.", refresh(grandChildStatus).reasonInvalid);
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", refresh(childStatus).reasonInvalid);
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Restoring base clears the warnings on child and grandchild
      updateOffsetFromAnchor(twentyMinutes, baseId, planId);
      assertTrue(refresh(grandChildStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
    }

    @Test
    void grandchildEndTimeAnchorIsIgnored() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval minusFifteenMinutes = new PGInterval("-15 minutes");
      final PGInterval twentyMinutes = new PGInterval("20 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId);

      final int parentId = merlinHelper.insertActivity(planId, twentyMinutes.toString());
      final int baseId = insertActivityWithAnchor(planId, twentyMinutes, parentId, true);
      final int childId = insertActivityWithAnchor(planId, minusTenMinutes, baseId, true);
      final int grandchildId = insertActivityWithAnchor(planId, minusTenMinutes, childId, false);

      // Everything is currently valid regarding Plan Start
      // Except grandchild, which is invalid regarding child's end time
      final AnchorValidationStatus grandChildStatus = getValidationStatus(planId, grandchildId);
      final AnchorValidationStatus childStatus = getValidationStatus(planId, childId);
      final AnchorValidationStatus baseStatus = getValidationStatus(planId, baseId);
      final AnchorValidationStatus parentStatus = getValidationStatus(planId, parentId);
      final AnchorValidationStatus unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      assertEquals("Activity Directive " +grandchildId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +childId +".", grandChildStatus.reasonInvalid);
      assertTrue(childStatus.reasonInvalid.isEmpty());
      assertTrue(baseStatus.reasonInvalid.isEmpty());
      assertTrue(parentStatus.reasonInvalid.isEmpty());
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());

      // Update to base makes child invalid relative to Plan Start, and does not affect grandchild
      updateOffsetFromAnchor(minusFifteenMinutes, baseId, planId);
      assertEquals("Activity Directive " +grandchildId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +childId +".", refresh(grandChildStatus).reasonInvalid);
      assertEquals("Activity Directive " +childId +" has a net negative offset relative to Plan Start.", refresh(childStatus).reasonInvalid);
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Restoring base clears the warning on child, but not on grandchild
      updateOffsetFromAnchor(twentyMinutes, baseId, planId);
      assertEquals("Activity Directive " +grandchildId +" has a net negative offset "
                   + "relative to an end-time anchor on Activity Directive " +childId +".", refresh(grandChildStatus).reasonInvalid);
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
    }

    // This test is the indirect form of negativeToPlanStartDownChain
    @Test
    void onlyGrandchildBecomesInvalid() throws SQLException {
      final PGInterval minusTenMinutes = new PGInterval("-10 minutes");
      final PGInterval twentyMinutes = new PGInterval("20 minutes");

      final int planId = merlinHelper.insertPlan(missionModelId);
      final int unrelatedActId = merlinHelper.insertActivity(planId);

      final int parentId = merlinHelper.insertActivity(planId, twentyMinutes.toString());
      final int baseId = insertActivityWithAnchor(planId, twentyMinutes, parentId, true);
      final int childId = insertActivityWithAnchor(planId, minusTenMinutes, baseId, true);
      final int grandchildId = insertActivityWithAnchor(planId, minusTenMinutes, childId, true);

      // Everything is currently valid regarding Plan Start
      final AnchorValidationStatus grandChildStatus = getValidationStatus(planId, grandchildId);
      final AnchorValidationStatus childStatus = getValidationStatus(planId, childId);
      final AnchorValidationStatus baseStatus = getValidationStatus(planId, baseId);
      final AnchorValidationStatus parentStatus = getValidationStatus(planId, parentId);
      final AnchorValidationStatus unrelatedStatus = getValidationStatus(planId, unrelatedActId);
      assertTrue(grandChildStatus.reasonInvalid.isEmpty());
      assertTrue(childStatus.reasonInvalid.isEmpty());
      assertTrue(baseStatus.reasonInvalid.isEmpty());
      assertTrue(parentStatus.reasonInvalid.isEmpty());
      assertTrue(unrelatedStatus.reasonInvalid.isEmpty());

      // Update to base makes grandchild invalid relative to Plan Start
      updateOffsetFromAnchor(minusTenMinutes, baseId, planId);
      assertEquals("Activity Directive " +grandchildId +" has a net negative offset relative to Plan Start.", refresh(grandChildStatus).reasonInvalid);
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());

      // Restoring base clears the warnings
      updateOffsetFromAnchor(twentyMinutes, baseId, planId);
      assertTrue(refresh(grandChildStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(childStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(baseStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(parentStatus).reasonInvalid.isEmpty());
      assertTrue(refresh(unrelatedStatus).reasonInvalid.isEmpty());
    }
  }

  @Nested
  class AnchorDeletion {

    @Test
    void cantDeleteActivityWithAnchors() throws SQLException {
      final int planId = merlinHelper.insertPlan(missionModelId);
      final int anchorId = merlinHelper.insertActivity(planId);
      insertActivityWithAnchor(planId, new PGInterval("0 seconds"), anchorId, true);

      try {
        merlinHelper.deleteActivityDirective(planId, anchorId);
        fail();
      } catch (SQLException ex){
        if(!ex.getMessage().contains(
            "update or delete on table \"activity_directive\" violates foreign key constraint \"anchor_in_plan\" on table \"activity_directive\"")){
          throw ex;
        }
      }
    }

    // The Hasura functions are defined as 'STRICT', meaning they immediately return NULL if a parameter is NULL rather than raising an exception
    @Test
    void rebasesDoNotRunOnNullParameters() throws SQLException {
      final int planId = merlinHelper.insertPlan(missionModelId);
      final int activityId = merlinHelper.insertActivity(planId);

      try (final var statement = connection.createStatement()) {
        // Reanchor to Plan Start
        var results = statement.executeQuery(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_plan_start(%d, null, '%s'::json)
            """.formatted(activityId, merlinHelper.admin.session()));
        if (results.next()) {
          fail();
        }

        results = statement.executeQuery(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_plan_start(null, %d, '%s'::json)
            """.formatted(planId, merlinHelper.admin.session()));
        if (results.next()) {
          fail();
        }

        // Reanchor to ascendant anchor
        results = statement.executeQuery(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_to_anchor(%d, null, '%s'::json)
            """.formatted(activityId, merlinHelper.admin.session()));
        if (results.next()) {
          fail();
        }

        results = statement.executeQuery(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_to_anchor(null, %d, '%s'::json)
            """.formatted(planId, merlinHelper.admin.session()));
        if (results.next()) {
          fail();
        }

        // Delete Remaining Chain
        results = statement.executeQuery(
            //language=sql
            """
            select hasura.delete_activity_by_pk_delete_subtree(%d, null, '%s'::json)
            """.formatted(activityId, merlinHelper.admin.session()));
        if (results.next()) {
          fail();
        }

        results = statement.executeQuery(
            //language=sql
            """
            select hasura.delete_activity_by_pk_delete_subtree(null, %d, '%s'::json)
            """.formatted(planId, merlinHelper.admin.session()));
        if (results.next()) {
          fail();
        }
      }
    }

    @Test
    void cannotRebaseActivityThatDoesNotExist() throws SQLException{
      final int planId = merlinHelper.insertPlan(missionModelId);

      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_plan_start(-1, %d, '%s'::json)
            """.formatted(planId, merlinHelper.admin.session()));
        fail();
      } catch (SQLException ex){
        if(!ex.getMessage().contains("Activity Directive -1 does not exist in Plan "+planId)){
          throw ex;
        }
      }

      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_to_anchor(-1, %d, '%s'::json)
            """.formatted(planId, merlinHelper.admin.session()));
        fail();
      } catch (SQLException ex){
        if(!ex.getMessage().contains("Activity Directive -1 does not exist in Plan "+planId)){
          throw ex;
        }
      }

      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_delete_subtree(-1, %d, '%s'::json)
            """.formatted(planId, merlinHelper.admin.session()));
        fail();
      } catch (SQLException ex){
        if(!ex.getMessage().contains("Activity Directive -1 does not exist in Plan "+planId)){
          throw ex;
        }
      }
    }

    @Test
    void rebaseToAscendantAnchor() throws SQLException{
      final int planId = merlinHelper.insertPlan(missionModelId);
      final PGInterval oneDay = new PGInterval("1 day");
      final PGInterval minusTwoDays = new PGInterval("-2 days");
      final PGInterval minusFourDays = new PGInterval("-4 days");

      int lastInsertedId = merlinHelper.insertActivity(planId);
      for(int i = 0; i<10; i++){
        lastInsertedId = insertActivityWithAnchor(planId, oneDay, lastInsertedId, true);
      }

      final var untouchedActivities = getActivities(planId);

      final int baseId = insertActivityWithAnchor(planId, minusTwoDays, lastInsertedId, true);

      final int chain1BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      final int chain2BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, false);
      final int chain3BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      int mostRecentChain1Id = chain1BaseId;
      int mostRecentChain2Id = chain2BaseId;
      int mostRecentChain3Id = chain3BaseId;

      for(int i = 0; i < 100; i++) {
        mostRecentChain1Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain1Id, true);
        mostRecentChain2Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain2Id, false);
        mostRecentChain3Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain3Id, (i & 1) == 0); // alternates true and false
      }

      assertEquals(304+untouchedActivities.size(), getActivities(planId).size());

      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_to_anchor(%d, %d, '%s'::json)
            """.formatted(baseId, planId, merlinHelper.admin.session()));
      }

      final var remainingActivities = getActivities(planId);
      assertEquals(314, remainingActivities.size());

      for(int i = 0; i < untouchedActivities.size(); i++){
        assertActivityEquals(untouchedActivities.get(i), remainingActivities.get(i));
      }

      assertEquals(minusFourDays, getActivity(planId, chain1BaseId).startOffset);
      assertEquals(""+lastInsertedId, getActivity(planId,chain1BaseId).anchorId);
      assertEquals(minusFourDays, getActivity(planId, chain2BaseId).startOffset);
      assertEquals(""+lastInsertedId, getActivity(planId,chain2BaseId).anchorId);
      assertEquals(minusFourDays, getActivity(planId, chain3BaseId).startOffset);
      assertEquals(""+lastInsertedId, getActivity(planId,chain3BaseId).anchorId);

      for(int i = untouchedActivities.size()+3; i<remainingActivities.size(); i++){
        assertEquals(new PGInterval("0 seconds"), remainingActivities.get(i).startOffset);
        assertNotNull(remainingActivities.get(i).anchorId);
      }
    }

    @Test
    void rebaseChainsToPlanStart() throws SQLException{
      final int planId = merlinHelper.insertPlan(missionModelId);
      final PGInterval oneDay = new PGInterval("1 day");
      final PGInterval minusTwoDays = new PGInterval("-2 days");
      final PGInterval sixDays = new PGInterval("6 days");

      int lastInsertedId = merlinHelper.insertActivity(planId);
      for(int i = 0; i<10; i++){
        lastInsertedId = insertActivityWithAnchor(planId, oneDay, lastInsertedId, true);
      }

      final var untouchedActivities = getActivities(planId);

      final int baseId = insertActivityWithAnchor(planId, minusTwoDays, lastInsertedId, true);

      final int chain1BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      final int chain2BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, false);
      final int chain3BaseId = insertActivityWithAnchor(planId, minusTwoDays, baseId, true);
      int mostRecentChain1Id = chain1BaseId;
      int mostRecentChain2Id = chain2BaseId;
      int mostRecentChain3Id = chain3BaseId;

      for(int i = 0; i < 100; i++) {
        mostRecentChain1Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain1Id, true);
        mostRecentChain2Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain2Id, false);
        mostRecentChain3Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain3Id, (i & 1) == 0); // alternates true and false
      }

      assertEquals(304+untouchedActivities.size(), getActivities(planId).size());

      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_reanchor_plan_start(%d, %d, '%s'::json)
            """.formatted(baseId, planId, merlinHelper.admin.session()));
      }

      final var remainingActivities = getActivities(planId);
      assertEquals(314, remainingActivities.size());

      for(int i = 0; i < untouchedActivities.size(); i++){
        assertActivityEquals(untouchedActivities.get(i), remainingActivities.get(i));
      }

      assertEquals(sixDays, getActivity(planId, chain1BaseId).startOffset);
      assertNull(getActivity(planId,chain1BaseId).anchorId);
      assertEquals(sixDays, getActivity(planId, chain2BaseId).startOffset);
      assertNull(getActivity(planId,chain2BaseId).anchorId);
      assertEquals(sixDays, getActivity(planId, chain3BaseId).startOffset);
      assertNull(getActivity(planId,chain3BaseId).anchorId);

      for(int i = untouchedActivities.size()+3; i<remainingActivities.size(); i++){
        assertEquals(new PGInterval("0 seconds"), remainingActivities.get(i).startOffset);
        assertNotNull(remainingActivities.get(i).anchorId);
      }
    }

    @Test
    void deleteChain() throws SQLException{
      final int planId = merlinHelper.insertPlan(missionModelId);
      final int grandparentId = merlinHelper.insertActivity(planId);
      final int parentId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), grandparentId, true);
      final int baseId = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), parentId, true);

      int mostRecentChain1Id = baseId;
      int mostRecentChain2Id = baseId;
      int mostRecentChain3Id = baseId;
      for(int i = 0; i < 100; i++) {
        mostRecentChain1Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain1Id, true);
        mostRecentChain2Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain2Id, false);
        mostRecentChain3Id = insertActivityWithAnchor(planId, new PGInterval("0 seconds"), mostRecentChain3Id, (i & 1) == 0); // alternates true and false
      }

      assertEquals(303, getActivities(planId).size());

      final Activity grandparentActivity = getActivity(planId, grandparentId);
      final Activity parentActivity = getActivity(planId, parentId);

      try(final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            select hasura.delete_activity_by_pk_delete_subtree(%d, %d, '%s'::json)
            """.formatted(baseId, planId, merlinHelper.admin.session()));
      }

      final var remainingActivities = getActivities(planId);
      assertEquals(2, remainingActivities.size());
      assertActivityEquals(grandparentActivity, remainingActivities.get(0));
      assertActivityEquals(parentActivity, remainingActivities.get(1));
    }
  }
}
