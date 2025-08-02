package gov.nasa.jpl.aerie.database;

import gov.nasa.jpl.aerie.database.PlanCollaborationTests.Activity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ActivityDirectiveChangelogTests {
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;
  private Connection connection;
  private int planId;

  @BeforeEach
  void beforeEach() throws SQLException {
    int fileId = merlinHelper.insertFileUpload();
    int missionModelId = merlinHelper.insertMissionModel(fileId);
    planId = merlinHelper.insertPlan(missionModelId);
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_activity_changelog_test", "Activity Directive Changelog Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
    connection = null;
    helper = null;
  }

  //region Helper Methods
  private Activity getActivity(final int planId, final int activityId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          // language=sql
          """
          SELECT *
          FROM merlin.activity_directive
          WHERE id = %d
          AND plan_id = %d;
          """.formatted(activityId, planId));

      res.next();

      return new Activity(
          res.getInt("id"),
          res.getInt("plan_id"),
          res.getString("name"),
          res.getInt("source_scheduling_goal_id"),
          res.getInt("source_scheduling_goal_invocation_id"),
          res.getString("created_at"),
          res.getString("created_by"),
          res.getString("last_modified_at"),
          res.getString("last_modified_by"),
          res.getString("start_offset"),
          res.getString("type"),
          res.getString("arguments"),
          res.getString("last_modified_arguments_at"),
          res.getString("metadata"),
          res.getString("anchor_id"),
          res.getBoolean("anchored_to_start")
      );
    }
  }
  private void updateActivityStartOffset(int planId, int activityDirectiveId, String newOffset) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var updatedRows = statement.executeQuery(
          // language=sql
          """
          UPDATE merlin.activity_directive
          SET start_offset = '%s'
          WHERE plan_id = %d and id = %d
          RETURNING id;
          """.formatted(newOffset, planId, activityDirectiveId));
      updatedRows.next();
      assertEquals(activityDirectiveId, updatedRows.getInt(1));
    }
  }
  private void revertActivityDirectiveToChangelog(int planId, int activityDirectiveId, int revision) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.executeQuery(
          // language=sql
          """
          SELECT hasura.restore_activity_changelog(
            _plan_id => %d,
            _activity_directive_id => %d,
            _revision => %d,
            hasura_session => '%s'::json);
          """.formatted(planId, activityDirectiveId, revision, merlinHelper.admin.session())
      );
    }
  }
  private int getChangelogRevisionCount(int planId, int activityDirectiveId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          // language=sql
          """
          SELECT count(revision)
          FROM merlin.activity_directive_changelog
          WHERE plan_id = %d and activity_directive_id = %d;
          """.formatted(planId, activityDirectiveId)
      );
      res.next();
      return res.getInt(1);
    }
  }
  //endregion

  @Test
  void shouldHaveNoChangelogsForEmptyPlan() throws SQLException {
    try (final var statement = connection.createStatement()) {
       final var res = statement.executeQuery(
          // language=sql
          """
          SELECT count(revision)
          FROM merlin.activity_directive_changelog
          WHERE plan_id = %d;
          """.formatted(planId)
      );
      res.next();
      final var count = res.getInt(1);
      assertEquals(0, count);
    }
  }

  @Test
  void shouldCreateChangelogForInsertedActDir() throws SQLException {
    final var activityId = merlinHelper.insertActivity(planId);
    assertEquals(1, getChangelogRevisionCount(planId, activityId));
  }

  @Test
  void shouldCreateChangelogForUpdatedActDir() throws SQLException {
    final var activityId = merlinHelper.insertActivity(planId);
    assertEquals(1, getChangelogRevisionCount(planId, activityId));
    updateActivityStartOffset(planId, activityId, "01:01:01");
    assertEquals(2, getChangelogRevisionCount(planId, activityId));
}
    @Test
    void changelogRevisionHasCorrectValues() throws SQLException {
      final var activityId = merlinHelper.insertActivity(planId);

      try (final var statement = connection.createStatement()) {
        final var res = statement.executeQuery(
            // language=sql
            """
            SELECT *
            FROM merlin.activity_directive_changelog
            WHERE plan_id = %d and activity_directive_id = %d;
            """.formatted(planId, activityId));

        res.next();
        final var current = getActivity(planId, activityId);

        assertEquals(current.activityId(), res.getInt("activity_directive_id"));
        assertEquals(current.planId(), res.getInt("plan_id"));
        assertEquals(current.name(), res.getString("name"));
        assertEquals(current.sourceSchedulingGoalId(), res.getInt("source_scheduling_goal_id"));
        assertEquals(current.sourceSchedulingGoalInvocationId(), res.getInt("source_scheduling_goal_invocation_id"));
        assertEquals(current.lastModifiedAt(), res.getString("changed_at"));
        assertEquals(current.lastModifiedBy(), res.getString("changed_by"));
        assertEquals(current.startOffset(), res.getString("start_offset"));
        assertEquals(current.type(), res.getString("type"));
        assertEquals(current.arguments(), res.getString("arguments"));
        assertEquals(current.lastModifiedArgumentsAt(), res.getString("changed_arguments_at"));
        assertEquals(current.metadata(), res.getString("metadata"));
        assertEquals(current.anchorId(), res.getString("anchor_id"));
        assertEquals(current.anchoredToStart(), res.getBoolean("anchored_to_start"));
    }
  }

  @Test
  void shouldDeleteChangelogsOverRevisionLimit() throws SQLException {
    final var maxRevisionsLimit = 11;
    final var activityId = merlinHelper.insertActivity(planId);
    // randomly update activity directive > maxRevisionsLimit times
    for (int i = 0; i < maxRevisionsLimit * 2; i++) {
      updateActivityStartOffset(planId, activityId, "%02d");
    }
    assertEquals(maxRevisionsLimit, getChangelogRevisionCount(planId, activityId));
  }

  @Test
  void revertNonExistentPlanThrowsError() throws SQLException {
    try {
      revertActivityDirectiveToChangelog(-1, -1, -1);
    } catch (SQLException ex) {
      if (!ex.getMessage().contains("Plan %d does not exist".formatted(-1))) {
        throw ex;
      }
    }
  }

  @Test
  void revertNonExistentDirectiveThrowsError() throws SQLException {
    try {
      revertActivityDirectiveToChangelog(planId, -1, -1);
    } catch (SQLException ex) {
      if (!ex.getMessage().contains("Activity Directive %d does not exist in Plan %d".formatted(-1, planId))) {
        throw ex;
      }
    }
  }

  @Test
  void revertNonExistentRevisionThrowsError() throws SQLException {
    final var activityId = merlinHelper.insertActivity(planId);
    try {
      revertActivityDirectiveToChangelog(planId, activityId, -1);
    } catch (SQLException ex) {
      if (!ex.getMessage().contains("Changelog Revision %d does not exist for Plan %d and Activity Directive %d".formatted(-1, planId, activityId))) {
        throw ex;
      }
    }
  }

  @Test
  void shouldRevertActDirToChangelogEntry() throws SQLException {
    final var activityId = merlinHelper.insertActivity(planId);

    final var actDirBefore = getActivity(planId, activityId);

    try (final var statement = connection.createStatement()) {
      statement.executeQuery(
          // language=sql
          """
          UPDATE merlin.activity_directive
          SET
            name = 'changed',
            start_offset = '01:01:01',
            arguments = '{"biteSize": 2}'
          WHERE plan_id = %d and id = %d
          RETURNING *;
          """.formatted(planId, activityId));
    }
    final var actDirMid = getActivity(planId, activityId);

    revertActivityDirectiveToChangelog(planId, activityId, 0);
    final var actDirAfter = getActivity(planId, activityId);

    assertNotEquals(actDirBefore.name(), actDirMid.name());
    assertNotEquals(actDirBefore.startOffset(), actDirMid.startOffset());
    assertNotEquals(actDirBefore.arguments(), actDirMid.arguments());

    assertEquals(actDirBefore.name(), actDirAfter.name());
    assertEquals(actDirBefore.startOffset(), actDirAfter.startOffset());
    assertEquals(actDirBefore.arguments(), actDirAfter.arguments());
  }
}
