package gov.nasa.jpl.aerie.database;

import gov.nasa.jpl.aerie.database.TagsTests.Tag;
import org.junit.jupiter.api.*;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PlanMigrationTests {
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;
  int missionModelId;
  int planId;
  int modelFileId;
  int modelId;
  int newModelFileId;
  int newModelId;

  @BeforeEach
  void beforeEach() throws SQLException {
    modelFileId = merlinHelper.insertFileUpload();
    modelId = merlinHelper.insertMissionModel(modelFileId);
    planId = merlinHelper.insertPlan(modelId);
    newModelFileId = merlinHelper.insertFileUpload();
    newModelId = merlinHelper.insertMissionModel(newModelFileId);
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_merlin_test", "Plan Migration Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
    merlinHelper.insertUser("PlanMigrationTests");
    merlinHelper.insertUser("PlanMigrationTests Requester");
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  //region Helper Methods

  int duplicatePlan(final int planId, final String newPlanName) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          select merlin.duplicate_plan(%s, '%s', 'PlanMigrationTests') as id;
          """.formatted(planId, newPlanName));
      res.next();
      return res.getInt("id");
    }
  }


  private record ImpactedDirective(String directiveType, String issue) {};

  // This function parses the impacted_directives returned by checkModelCompatibilityForPlan and converts those results
  // to an Array of ImpactedDirectives, in order to compare reasons two models may differ.
  // Expected input is something like:
  // [{"activity_directive": {..."type": "Type1"}, "issue": "removed"}, {...}]
  private List<ImpactedDirective> parseImpactedDirectivesJson(String str) {
    ArrayList<ImpactedDirective> impactedDirectives = new ArrayList<>();
    try (JsonReader reader = Json.createReader(new StringReader(str))) {
      JsonArray jsonArray = reader.readArray();
      for (JsonObject element: jsonArray.getValuesAs(JsonObject.class)) {
        JsonObject directiveInfo  = element.getJsonObject("activity_directive");
        impactedDirectives.add(new ImpactedDirective(directiveInfo.getString("type"), element.getString("issue")));
      }
    }
    return impactedDirectives;
  }


  private List<Integer> getLatestSnapshots(final int planId) throws SQLException {
    try(final var statement = connection.createStatement()){
        final var results = statement.executeQuery(
            //language=sql
            """
            SELECT snapshot_id
            FROM merlin.plan_latest_snapshot
            WHERE plan_id = %d
            ORDER BY snapshot_id DESC
            """.formatted(planId)
        );
        final List<Integer> latestSnapshots = new ArrayList<>();
        while (results.next()) {
          latestSnapshots.add(results.getInt(1));
        }
        return latestSnapshots;
      }
  }

  private String getSnapshotName(final int snapshotId) throws SQLException {
    try(final var statement = connection.createStatement()){
      final var results = statement.executeQuery(
          //language=sql
          """
          SELECT snapshot_name
          FROM merlin.plan_snapshot
          WHERE snapshot_id = %d
          """.formatted(snapshotId)
      );
      if (results.next()) {
        return results.getString(1);
      }
    }
    return "SnapshotNotFound";
  }


  private String getModelName(final int modelId) throws SQLException {
    try(final var statement = connection.createStatement()){
      final var results = statement.executeQuery(
          //language=sql
          """
          SELECT name
          FROM merlin.mission_model
          WHERE id = %d
          """.formatted(modelId)
      );
      if (results.next()) {
        return results.getString(1);
      }
    }
    return "ModelNotFound";
  }



  private int createMergeRequest(final int planId_receiving, final int planId_supplying) throws SQLException{
    try(final var statement = connection.createStatement()){
      final var res = statement.executeQuery(
          //language=sql
          """
          select merlin.create_merge_request(%d, %d, 'PlanMigrationTests Requester');
          """.formatted(planId_supplying, planId_receiving)
      );
      res.next();
      return res.getInt(1);
    }
  }

  private Map<String, String> checkModelCompatibilityForPlan(final int planId, final int newModelId) throws SQLException{
    try(final var statement = connection.createStatement()){
      final var res = statement.executeQuery(
          //language=sql
          """
          select
          (result -> 'removed_activity_types')::text as removed_activity_types,
          (result -> 'modified_activity_types')::text as modified_activity_types,
          (result -> 'impacted_directives')::text as impacted_directives
          from hasura.check_model_compatibility_for_plan(%d, %d)
          """.formatted(planId, newModelId)
      );

      if (res.next()) {
        final var resultMap = new HashMap<String, String>();
        resultMap.put("removed_activity_types", res.getString("removed_activity_types"));
        resultMap.put("modified_activity_types", res.getString("modified_activity_types"));
        resultMap.put("impacted_directives", res.getString("impacted_directives"));
        return resultMap;
      } else {
        throw new SQLException("No result returned from check_model_compatibility");
      }
    }
  }

  private boolean migratePlanToModel(final int planId, final int newModelId) throws SQLException{
    try(final var statement = connection.createStatement()){
      final var res = statement.executeQuery(
          //language=sql
          """
          select hasura.migrate_plan_to_model(%d, %d, '%s'::json);
          """.formatted(planId, newModelId, merlinHelper.admin.session())
      );
      return res.next();
    }
  }

  private int getPlanModel(final int planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          select model_id
          from merlin.plan
          where id = %d;
          """.formatted(planId));
      assertTrue(res.next());
      return res.getInt(1);
    }
  }


  /**
   * Check that migration fails if:
   *  (a) _plan_id is invalid and _new_model_id is valid
   *  (b) _plan_id is valid and _new_model_id is invalid
   *  (c) _plan_id and _new_model_id are both invalid
   *
   */
  @Test
  void checkFailsForNonexistentPlansOrModels() throws SQLException {
    final var sqlEx = assertThrows(
      SQLException.class,
      () -> migratePlanToModel(-1, missionModelId)
    );
    assertTrue(sqlEx.getMessage().contains("Plan -1 does not exist"), "bad error message, got " + sqlEx.getMessage());


    final var sqlEx2 = assertThrows(
      SQLException.class,
      () -> migratePlanToModel(planId, -1)
    );
    assertTrue(sqlEx2.getMessage().contains("Model -1 does not exist"), "bad error message, got " + sqlEx2.getMessage());


    final var sqlEx3 = assertThrows(
      SQLException.class,
      () -> migratePlanToModel(-1, -1)
    );
    assertTrue(sqlEx3.getMessage().contains("Plan -1 does not exist"), "bad error message, got " + sqlEx3.getMessage());
  }


  /**
   * Check that migration fails if there exist open merge requests on _plan_id.
   */
  @Test
  void checkFailsForOpenMergeRequests() throws SQLException {
    final int branchId = duplicatePlan(planId, "MultiTags Test");
    createMergeRequest(planId, branchId);

    final var sqlEx = assertThrows(
      SQLException.class,
      () -> migratePlanToModel(planId, modelId)
    );
    assertTrue(
        sqlEx.getMessage().contains("Cannot migrate plan "+planId+": it has open merge requests"),
        "bad error message, got " + sqlEx.getMessage()
    );
  }

  /**
   * Check that migration creates a snapshot.
   */
  @Test
  void verifySnapshotCreated() throws SQLException {

    migratePlanToModel(planId, newModelId);
    final List<Integer> snapshotIds = getLatestSnapshots(planId);
    assertEquals(snapshotIds.size(), 1);

    final String oldModelName = getModelName(modelId);
    final String newModelName = getModelName(newModelId);

    final String snapshotName = getSnapshotName(snapshotIds.get(0));
    final String expected = "Migration from model %s (id %d) to model %s (id %d) on".formatted(oldModelName, modelId, newModelName, newModelId);
    assertTrue(snapshotName.startsWith(expected), "Expected snapshot name to start with \"%s\" but snapshot name is \"%s\"".formatted(expected, snapshotName));
  }

  /**
   * Check that migration changes the mission model of a given plan.
   */
  @Test
  void verifyMigrationWorks() throws SQLException {
    migratePlanToModel(planId, newModelId);
    final int modelIdInDb = getPlanModel(planId);
    assertEquals(modelIdInDb, newModelId);
  }


  /**
   * Verify that a compatability check will not show any differences for identical models.
   */
  @Test
  void verifyCompatibilityCheckWorksForIdenticalModels() throws SQLException {
    Map<String, String> result = checkModelCompatibilityForPlan(planId, newModelId);
    System.out.println(result);
    assertEquals("[]", result.get("removed_activity_types"));
    assertEquals("{}", result.get("modified_activity_types"));
    assertEquals("[]", result.get("impacted_directives"));
  }

  /**
   * Verify that a compatability check will show differences for dissimilar models.
   */
  @Test
  void verifyCompatibilityCheckWorksForDissimilarModels() throws SQLException {
    // Add ActivityToBeModified to both models, and modify its parameter schema in the second model
    merlinHelper.insertActivityType(modelId, "ActivityToBeModified", "{\"counter\": {\"order\": 0, \"schema\": {\"type\": \"int\"}}}");
    merlinHelper.insertActivityType(newModelId, "ActivityToBeModified", "{\"newName\": {\"order\": 0, \"schema\": {\"type\": \"int\"}}}");

    // Only add ActivityToBeRemoved to the first model
    merlinHelper.insertActivityType(modelId, "ActivityToBeRemoved", "{}");

    // Insert both activity types into the plan
    merlinHelper.insertActivity(planId, "00:00:00", "ActivityToBeModified", "{\"counter\": 1}");
    merlinHelper.insertActivity(planId, "00:00:00", "ActivityToBeRemoved", "{}");

    Map<String, String> result = checkModelCompatibilityForPlan(planId, newModelId);

    // Expecting to see ActivityToBeRemoved in the removed_activity_types, and ActivityToBeModified in the modified_activity_types
    assertEquals("[\"ActivityToBeRemoved\"]", result.get("removed_activity_types"));
    assertEquals("{ \"ActivityToBeModified\" : {\"old_parameter_schema\" : {\"counter\": {\"order\": 0, \"schema\": {\"type\": \"int\"}}}, \"new_parameter_schema\" : {\"newName\": {\"order\": 0, \"schema\": {\"type\": \"int\"}}}} }", result.get("modified_activity_types"));

    // Expecting to see two elements in the list of impacted directives corresponding to the two added above
    List<ImpactedDirective> impacted = parseImpactedDirectivesJson(result.get("impacted_directives"));
    assertEquals(2, impacted.size());
    // verify ActivityToBeRemoved exists in array, and has reason "removed"
    assertTrue(impacted.contains(new ImpactedDirective("ActivityToBeRemoved", "removed")));
    // verify ActivityToBeModified exists in array, and has reason "altered"
    assertTrue(impacted.contains(new ImpactedDirective("ActivityToBeModified", "altered")));
  }
  
}
