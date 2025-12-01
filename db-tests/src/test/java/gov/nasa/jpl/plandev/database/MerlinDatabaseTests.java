package gov.nasa.jpl.plandev.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PGInterval;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

record SimulationDatasetRecord(int simulation_id, int dataset_id){}
record PlanDatasetRecord(int plan_id, int dataset_id) {}
record ProfileSegmentAtATimeRecord(int datasetId, int profileId, String name, String type, String startOffset, String dynamics, boolean isGap) {}


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerlinDatabaseTests {
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("plandev_merlin_test", "Merlin Database Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  //region Helper Methods
  int getMissionModelRevision(final int modelId) throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT revision
          FROM merlin.mission_model
          WHERE id = %d;
          """.formatted(modelId));
      res.next();
      return res.getInt("revision");
    }
  }
  int getPlanRevision(final int planId) throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT revision
          FROM merlin.plan
          WHERE id = %d;
          """.formatted(planId));
      res.next();
      return res.getInt("revision");
    }
  }
  int getSimulationTemplateRevision(final int simulationTemplateId) throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT revision
          FROM merlin.simulation_template
          WHERE id = %d;
          """.formatted(simulationTemplateId));
      res.next();
      return res.getInt("revision");
    }
  }
  int getSimulationRevision(final int simulationId) throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT revision
          FROM merlin.simulation
          WHERE id = %d;
          """.formatted(simulationId));
      res.next();
      return res.getInt("revision");
    }
  }

  int insertSimulationTemplate(final int modelId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement
          .executeQuery(
              //language=sql
              """
              INSERT INTO merlin.simulation_template (model_id, description, arguments)
              VALUES ('%s', 'test-description', '{}')
              RETURNING id;
              """.formatted(modelId)
          );
      res.next();
      return res.getInt("id");
    }
  }

  int getSimulationId(final int planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id
          FROM merlin.simulation s
          WHERE s.plan_id = '%s';
          """.formatted(planId)
      );
      res.next();
      return res.getInt("id");
    }
  }

  void addTemplateIdToSimulation(final int simulationTemplateId, final int simulationId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          UPDATE merlin.simulation
          SET simulation_template_id = %d,
              arguments = '{}'
          WHERE id = %d;
          """.formatted(simulationTemplateId, simulationId));
    }
  }

  int getDatasetId(final int planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT dataset_id
          FROM merlin.plan_dataset
          WHERE plan_id = %d
          """.formatted(planId));
      res.next();
      return res.getInt("dataset_id");
    }
  }

  PlanDatasetRecord insertPlanDataset(final int planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO merlin.plan_dataset (plan_id, offset_from_plan_start)
          VALUES ('%s', '0')
          RETURNING plan_id, dataset_id;
          """.formatted(planId));
      res.next();
      return new PlanDatasetRecord(res.getInt("plan_id"), res.getInt("dataset_id"));
    }
  }

  int getDatasetCount(final int datasetId) throws SQLException {
    try (final var statement = connection.createStatement();
        final var res = statement.executeQuery(
            //language=sql
            """
            SELECT COUNT(*)
            FROM merlin.dataset
            WHERE id = %s;
            """.formatted(datasetId)
    )) {
      res.next();
      return res.getInt("count");
    }
  }

  SimulationDatasetRecord insertSimulationDataset(final int simulationId, final int datasetId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement
          .executeQuery(
              //language=sql
              """
              INSERT INTO merlin.simulation_dataset (simulation_id, dataset_id, arguments, simulation_start_time, simulation_end_time)
              VALUES ('%s', '%s', '{}', '2020-1-1 00:00:00', '2020-1-2 00:00:00')
              RETURNING simulation_id, dataset_id;
              """.formatted(simulationId, datasetId)
          );
      res.next();
      return new SimulationDatasetRecord(res.getInt("simulation_id"), res.getInt("dataset_id"));
    }
  }

  //endregion

  int fileId;
  int missionModelId;
  int planId;
  int activityId;
  int simulationTemplateId;
  int simulationId;
  int datasetId;
  SimulationDatasetRecord simulationDatasetRecord;
  PlanDatasetRecord planDatasetRecord;

  @BeforeEach
  void beforeEach() throws SQLException {
    fileId = merlinHelper.insertFileUpload();
    missionModelId = merlinHelper.insertMissionModel(fileId);
    planId = merlinHelper.insertPlan(missionModelId);
    activityId = merlinHelper.insertActivity(planId);
    simulationTemplateId = insertSimulationTemplate(missionModelId);
    simulationId = getSimulationId(planId);
    addTemplateIdToSimulation(simulationTemplateId, simulationId);
    planDatasetRecord = insertPlanDataset(planId);
    datasetId = getDatasetId(planId);
    simulationDatasetRecord = insertSimulationDataset(simulationId, datasetId);
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  @Nested
  class MissionModelTriggers {
    @Test
    void shouldIncrementMissionModelRevisionOnMissionModelUpdate() throws SQLException {
      final var revision = getMissionModelRevision(missionModelId);
      try(final var statement = connection.createStatement()){
        statement.executeUpdate(
            //language=sql
            """
            UPDATE merlin.mission_model
            SET name = 'updated-name-%s'
            WHERE id = %d;
            """.formatted(UUID.randomUUID().toString(), missionModelId));
      }

      final var updatedRevision = getMissionModelRevision(missionModelId);
      assertEquals(revision + 1, updatedRevision);
    }

    @Test
    void shouldIncrementMissionModelRevisionOnMissionModelJarIdUpdate() throws SQLException {
      final var revision = getMissionModelRevision(missionModelId);

      try(final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            UPDATE merlin.uploaded_file
            SET path = 'test-path-updated'
            WHERE id = %d;
            """.formatted(fileId));
      }

      final var updatedRevision = getMissionModelRevision(missionModelId);
      assertEquals(revision + 1, updatedRevision);
    }
  }

  @Nested
  class PlanTriggers {
    @Test
    void shouldIncrementPlanRevisionOnPlanUpdate() throws SQLException {
      final var initialRevision = getPlanRevision(planId);

      try(final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            UPDATE merlin.plan
            SET name = 'test-plan-updated-%s'
            WHERE id = %d;
            """.formatted(UUID.randomUUID().toString(), planId));
      }

      final var updatedRevision = getPlanRevision(planId);
      assertEquals(initialRevision + 1, updatedRevision);
    }

    @Test
    void shouldIncrementPlanRevisionOnActivityInsert() throws SQLException {
      final var initialRevision = getPlanRevision(planId);
      merlinHelper.insertActivity(planId);
      final var updatedRevision = getPlanRevision(planId);
      assertEquals(initialRevision + 1, updatedRevision);
    }

    @Test
    void shouldIncrementPlanRevisionOnActivityUpdate() throws SQLException {
      final var activityId = merlinHelper.insertActivity(planId);
      final var initialRevision = getPlanRevision(planId);
      merlinHelper.updateActivityName("test-activity-updated", activityId, planId);
      final var updatedRevision = getPlanRevision(planId);
      assertEquals(initialRevision + 1, updatedRevision);
    }

    @Test
    void shouldIncrementPlanRevisionOnActivityDelete() throws SQLException {
      final var activityId = merlinHelper.insertActivity(planId);
      final var initialRevision = getPlanRevision(planId);
      merlinHelper.deleteActivityDirective(planId, activityId);
      final var updatedRevision = getPlanRevision(planId);
      assertEquals(initialRevision + 1, updatedRevision);
    }
  }

  @Nested
  class SimulationTemplateTriggers {
    @Test
    void shouldIncrementSimulationTemplateRevisionOnSimulationTemplateUpdate() throws SQLException {
      final var initialRevision = getSimulationTemplateRevision(simulationTemplateId);

      try(final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            UPDATE merlin.simulation_template
            SET description = 'test-description-updated'
            WHERE id = %s;
            """.formatted(simulationTemplateId));
      }

      final var updatedRevision = getSimulationTemplateRevision(simulationTemplateId);
      assertEquals(initialRevision + 1, updatedRevision);
    }
  }

  @Nested
  class SimulationTriggers {
    @Test
    void shouldIncrementSimulationRevisionOnSimulationUpdate() throws SQLException {
      final var initialRevision = getSimulationRevision(simulationId);

      try(final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            UPDATE merlin.simulation
            SET arguments = '{}'
            WHERE id = %s;
            """.formatted(simulationId));
      }

      final var updatedRevision = getSimulationRevision(simulationId);
      assertEquals(initialRevision + 1, updatedRevision);
    }
  }

  @Nested
  class PlanDatasetTriggers {
    @Test
    void shouldCreateDefaultDatasetOnPlanDatasetInsertWithNullDatasetId() throws SQLException {
      final var newDatasetId = insertPlanDataset(planId).dataset_id();
      try(final var statement = connection.createStatement()) {
        final var datasetRes = statement.executeQuery(
            //language=sql
            """
            SELECT *
            FROM merlin.dataset
            WHERE id = %d;
            """.formatted(newDatasetId));

        datasetRes.next();
        assertEquals(newDatasetId, datasetRes.getInt("id"));
        assertEquals(0, datasetRes.getInt("revision"));
      }
    }

    @Test
    void shouldCalculatePlanDatasetOffsetOnPlanDatasetInsertWithNonNullDatasetId() throws SQLException {
      // ASSUMPTION: The plan to which `planDatasetRecord` is associated must start at 2020-1-1 00:00:00+00, so that
      // this new plan starts exactly 1 hour later.
      final var newPlanId = merlinHelper.insertPlan(missionModelId, merlinHelper.admin.name(), "test-plan-"+UUID.randomUUID(), "2020-1-1 01:00:00+00");

      try(final var statement = connection.createStatement()) {
        final var planDatasetInsertRes = statement.executeQuery(
            //language=sql
            """
            INSERT INTO merlin.plan_dataset (plan_id, dataset_id)
            VALUES (%s, %s)
            RETURNING *;
            """.formatted(newPlanId, planDatasetRecord.dataset_id()));
        planDatasetInsertRes.next();
        final var newOffsetFromPlanStart = new PGInterval(planDatasetInsertRes.getString("offset_from_plan_start"));
        assertEquals(new PGInterval("-1 hours"), newOffsetFromPlanStart);
      }
    }

    @Test
    void shouldDeleteDatasetWithNoAssociatedPlansOnPlanDatasetDelete() throws SQLException {
      assertEquals(1, getDatasetCount(planDatasetRecord.dataset_id()));

      try (final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            DELETE FROM merlin.plan_dataset
            WHERE plan_id = %d and dataset_id = %d;
            """.formatted(planDatasetRecord.plan_id(), planDatasetRecord.dataset_id()));
      }

      assertEquals(0, getDatasetCount(planDatasetRecord.dataset_id()));
    }
  }

  @Nested
  class SimulationDatasetTriggers {
    @Test
    void shouldInitializeDatasetOnInsert() throws SQLException {
      try (final var statement = connection.createStatement();
           final var res = statement.executeQuery(
            //language=sql
            """
            SELECT plan_revision, model_revision, simulation_revision, simulation_template_revision
            FROM merlin.simulation_dataset
            WHERE simulation_id = %d AND dataset_id = %d;
            """.formatted(simulationDatasetRecord.simulation_id(), simulationDatasetRecord.dataset_id()))
      ) {
        res.next();
        assertEquals(1, res.getInt("plan_revision"));
        assertEquals(0, res.getInt("model_revision"));
        assertEquals(1, res.getInt("simulation_revision")); //1, as we add a template in the BeforeEach
        assertEquals(0, res.getInt("simulation_template_revision"));
      }
    }

    @Test
    void shouldDeleteDatasetOnSimulationDatasetDelete() throws SQLException {
      try (final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            DELETE FROM merlin.simulation_dataset
            WHERE simulation_id = %d AND dataset_id = %d;
            """.formatted(simulationDatasetRecord.simulation_id(), simulationDatasetRecord.dataset_id()));
      }
      assertEquals(0, getDatasetCount(simulationDatasetRecord.dataset_id()));
    }
  }

  @Test
  void shouldRejectInsertProfileSegmentWithNonExistentProfile() throws SQLException {
    final var datasetId = allocateDataset();
    try {
      insertProfileSegment(datasetId, 1256);
      fail();
    } catch (SQLException e) {
      if (!e.getMessage().contains("foreign key violation: there is no profile with id 1256 in dataset %d".formatted(
          datasetId))) {
        throw e;
      }
    }
  }

  @Test
  void shouldRejectInsertEventWithNonExistentTopic() throws SQLException {
    final var datasetId = allocateDataset();
    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          INSERT INTO merlin.event (dataset_id, real_time, transaction_index, causal_time, value, topic_index)
          VALUES (%d, '0 seconds', 0, '.1', '{}', 1234);
          """.formatted(datasetId));
      fail();
    } catch (SQLException e) {
      if (!e
          .getMessage()
          .contains("foreign key violation: there is no topic with topic_index 1234 in dataset %d".formatted(datasetId))) {
        throw e;
      }
    }
  }

  @Test
  void shouldRejectUpdateProfileSegmentToNonExistentProfile() throws SQLException {
    final var datasetId = allocateDataset();
    final int profileId = insertProfile(datasetId);

    insertProfileSegment(datasetId, profileId);

    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          update merlin.profile_segment
          set profile_id = %d
          where dataset_id=%d and profile_id=%d;
          """.formatted(profileId + 1, datasetId, profileId));
      fail();
    } catch (SQLException e) {
      if (!e.getMessage().contains("foreign key violation: there is no profile with id %d in dataset %d".formatted(
          profileId + 1,
          datasetId))) {
        throw e;
      }
    }

  }

  @Test
  void shouldRejectUpdateEventToNonExistentTopic() throws SQLException {
    final var datasetId = allocateDataset();
    final var topicIndex = 42;

    insertTopic(datasetId, topicIndex);
    insertEvent(datasetId, topicIndex);

    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          update merlin.event
          set topic_index = %d
          where dataset_id=%d and topic_index=%d;
          """.formatted(topicIndex + 1, datasetId, topicIndex));
      fail();
    } catch (SQLException e) {
      if (!e
          .getMessage()
          .contains("foreign key violation: there is no topic with topic_index %d in dataset %d".formatted(
              topicIndex
              + 1,
              datasetId))) {
        throw e;
      }
    }
  }

  @Test
  void shouldCascadeWhenDeletingProfile() throws SQLException {
    final var datasetId = allocateDataset();
    final int profileId = insertProfile(datasetId);
    insertProfileSegment(datasetId, profileId);

    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          DELETE FROM merlin.profile
          WHERE dataset_id=%d and id=%d
          """.formatted(datasetId, profileId));
    }

    assertEquals(0, getProfileSegmentCount(datasetId, profileId));
  }

  @Test
  void shouldCascadeWhenDeletingTopic() throws SQLException {
    final var datasetId = allocateDataset();
    final var topicIndex = 42;

    insertTopic(datasetId, topicIndex);
    insertEvent(datasetId, topicIndex);

    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          DELETE FROM merlin.topic
          WHERE dataset_id=%d and topic_index=%d
          """.formatted(datasetId, topicIndex));

      try (final var res = statement.executeQuery(
          //language=sql
          """
          SELECT count(1) FROM merlin.event WHERE dataset_id=%d and topic_index=%d
          """.formatted(datasetId, topicIndex))
      ) {
        res.next();
        assertEquals(0, res.getInt("count"));
      }
    }
  }

  @Test
  void shouldCascadeWhenUpdatingProfile() throws SQLException {
    final var datasetId = allocateDataset();
    final int profileId = insertProfile(datasetId);
    insertProfileSegment(datasetId, profileId);

    final int newProfileId;
    try (final var statement = connection.createStatement();
         final var res = statement.executeQuery(
             //language=sql
             """
             UPDATE merlin.profile
             SET id=default
             WHERE dataset_id=%d and id=%d
             RETURNING id;
             """.formatted(datasetId, profileId))
    ) {
      res.next();
      newProfileId = res.getInt("id");
    }

    assertNotEquals(profileId, newProfileId);
    assertEquals(1, getProfileSegmentCount(datasetId, newProfileId));
  }

  @Test
  void shouldCascadeWhenUpdatingTopicIndex() throws SQLException {
    final var datasetId = allocateDataset();
    final var topicIndex = 42;

    insertTopic(datasetId, topicIndex);
    insertEvent(datasetId, topicIndex);

    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          UPDATE merlin.topic
          SET topic_index = %d
          WHERE dataset_id = %d and topic_index = %d
          """.formatted(topicIndex + 1, datasetId, topicIndex));

      try (final var res = statement.executeQuery(
          //language=sql
          """
          SELECT count(1) FROM merlin.event WHERE dataset_id = %d and topic_index = %d
          """.formatted(datasetId, topicIndex + 1))
      ) {
        res.next();
        assertEquals(1, res.getInt("count"));
      }
    }
  }

  @Test
  void getResourcesAtTimeFetchesLatestData() throws SQLException {
    final var datasetId = allocateDataset();

    final var contestantName = "contestantCount";
    final var contestantType = "{\"type\": \"discrete\", \"schema\": {\"type\": \"int\"}}";

    final var winnerName = "winner";
    final var winnerType = "{\"type\": \"discrete\", \"schema\": {\"type\": \"string\"}}";

    final var contestantCountId = insertProfile(datasetId, contestantName, contestantType, "12 hours");
    final var winnerId = insertProfile(datasetId, winnerName, winnerType, "12 hours");

    // Contestant Count Segments:
    insertProfileSegment(datasetId, contestantCountId, "0 seconds", "20", false);
    insertProfileSegment(datasetId, contestantCountId, "2 hours", "12", false);
    insertProfileSegment(datasetId, contestantCountId, "12 hours", "1", false);

    // Winner Segments:
    insertProfileSegment(datasetId, winnerId, "6 hours", "\"Bob or Alice\"", false);
    insertProfileSegment(datasetId, winnerId, "10 hours", "\"Alice\"", false);

    connection.prepareStatement("set intervalstyle = 'iso_8601';").execute();

    final var segmentsAtStart = getResourcesAtStartOffset(datasetId, "00:00:00");
    final var segmentsAtOneHour = getResourcesAtStartOffset(datasetId, "06:00:00");
    final var segmentsAtTwelveHours = getResourcesAtStartOffset(datasetId, "12:00:00");

    assertEquals(1, segmentsAtStart.size());
    assertEquals(2, segmentsAtOneHour.size());
    assertEquals(2, segmentsAtTwelveHours.size());

    final var atStartSegment0 = new ProfileSegmentAtATimeRecord(
        datasetId,
        contestantCountId,
        contestantName,
        contestantType,
        "PT0S",
        "20",
        false);
    assertEquals(atStartSegment0, segmentsAtStart.get(0));

    final var atOneSegment0 = new ProfileSegmentAtATimeRecord(
        datasetId,
        contestantCountId,
        contestantName,
        contestantType,
        "PT2H",
        "12",
        false);
    final var atOneSegment1 = new ProfileSegmentAtATimeRecord(
        datasetId,
        winnerId,
        winnerName,
        winnerType,
        "PT6H",
        "\"Bob or Alice\"",
        false);

    assertEquals(atOneSegment0, segmentsAtOneHour.get(0));
    assertEquals(atOneSegment1, segmentsAtOneHour.get(1));

    final var atTwelveSegment0 = new ProfileSegmentAtATimeRecord(
        datasetId,
        contestantCountId,
        contestantName,
        contestantType,
        "PT12H",
        "1",
        false);
    final var atTwelveSegment1 = new ProfileSegmentAtATimeRecord(
        datasetId,
        winnerId,
        winnerName,
        winnerType,
        "PT10H",
        "\"Alice\"",
        false);

    assertEquals(atTwelveSegment0, segmentsAtTwelveHours.get(0));
    assertEquals(atTwelveSegment1, segmentsAtTwelveHours.get(1));
  }

  private int insertProfile(final int datasetId) throws SQLException {
    return insertProfile(datasetId, "fred", "{}", "0 seconds");
  }

  private int insertProfile(final int datasetId, final String name, final String type, final String duration)
  throws SQLException
  {
    try (final var statement = connection.createStatement()) {
      final var results = statement.executeQuery(
          //language=sql
          """
          INSERT INTO merlin.profile(dataset_id, name, type, duration)
          VALUES (%d, '%s', '%s', '%s')
          RETURNING id;
          """.formatted(datasetId, name, type, duration));
      assertTrue(results.next());
      return results.getInt("id");
    }
  }

  private void insertProfileSegment(final int datasetId, final int profileId) throws SQLException {
    insertProfileSegment(datasetId, profileId, "0 seconds", "{}", false);
  }

  private void insertProfileSegment(
      final int datasetId,
      final int profileId,
      final String startOffset,
      final String dynamics,
      final boolean isGap
  ) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          INSERT INTO merlin.profile_segment(dataset_id, profile_id, start_offset, dynamics, is_gap)
          VALUES (%d, %d, '%s'::interval, '%s'::jsonb, %b);
          """.formatted(datasetId, profileId, startOffset, dynamics, isGap));
    }
  }

  private int getProfileSegmentCount(final int datasetId, final int profileId) throws SQLException {
    try (final Statement statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT count(1) FROM merlin.profile_segment WHERE dataset_id=%d and profile_id=%d
          """.formatted(datasetId, profileId));
      assertTrue(res.next());
      return res.getInt("count");
    }
  }

  private void insertTopic(final int datasetId, final int topicIndex) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          INSERT INTO merlin.topic (dataset_id, topic_index, name, value_schema)
          VALUES (%d, %d, 'fred', '{}');
          """.formatted(datasetId, topicIndex));
    }
  }

  private void insertEvent(final int datasetId, final int topicIndex) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          INSERT INTO merlin.event (dataset_id, real_time, transaction_index, causal_time, value, topic_index)
          VALUES (%d, '0 seconds', 0, '.1', '{}', %d);
          """.formatted(datasetId, topicIndex));
    }
  }

  private ArrayList<ProfileSegmentAtATimeRecord> getResourcesAtStartOffset(
      final int datasetId,
      final String startOffset) throws SQLException
  {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          select * from hasura.get_resources_at_start_offset(%d, '%s');
          """.formatted(datasetId, startOffset));

      final var segments = new ArrayList<ProfileSegmentAtATimeRecord>();
      while (res.next()) {
        segments.add(new ProfileSegmentAtATimeRecord(
            res.getInt("dataset_id"),
            res.getInt("id"),
            res.getString("name"),
            res.getString("type"),
            res.getString("start_offset"),
            res.getString("dynamics"),
            res.getBoolean("is_gap")
        ));
      }
      return segments;
    }
  }

  private int allocateDataset() throws SQLException {
    final int datasetId;
    try (final Statement statement = connection.createStatement()) {
      try (final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO merlin.dataset
          DEFAULT VALUES
          RETURNING id;
          """)) {
      res.next();
      datasetId = res.getInt("id");
    }
    return datasetId;
  }
}
}
