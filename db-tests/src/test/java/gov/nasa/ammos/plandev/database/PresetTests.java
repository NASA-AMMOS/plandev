package gov.nasa.jpl.aerie.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PresetTests {
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;
  int fileId;
  int missionModelId;

  void setConnection(DatabaseTestHelper helper) {
    connection = helper.connection();
  }

  @BeforeEach
  void beforeEach() throws SQLException {
    fileId = merlinHelper.insertFileUpload();
    missionModelId = merlinHelper.insertMissionModel(fileId);
    // Insert the "test-activity" activity types to avoid a foreign key conflict
    merlinHelper.insertActivityType(missionModelId, "test-activity");
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_preset_test", "Aerie Preset Tests");
    setConnection(helper);
    merlinHelper = new MerlinDatabaseTestHelper(connection);
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  //region Helper Methods
  Activity assignPreset(int presetId, int activityId, int planId) throws SQLException {
    merlinHelper.assignPreset(presetId, activityId, planId, merlinHelper.admin.session());
    return getActivity(planId, activityId);
  }

  Activity getActivity(final int planId, final int activityId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
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
          res.getString("type"),
          res.getString("arguments")
      );
    }
  }

  ArrayList<Activity> getActivities(final int planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT *
          FROM merlin.activity_directive
          WHERE plan_id = %d
          ORDER BY id;
          """.formatted(planId));

      final var activities = new ArrayList<Activity>();
      while (res.next()){
        activities.add(new Activity(
            res.getInt("id"),
            res.getInt("plan_id"),
            res.getString("name"),
            res.getString("type"),
            res.getString("arguments")
        ));
      }
      return activities;
    }
  }

  static void assertActivityEqualsAsideFromArgs(final Activity expected, final Activity actual) {
    assertEquals(expected.activityId, actual.activityId);
    assertEquals(expected.planId, actual.planId);
    assertEquals(expected.name, actual.name);
    assertEquals(expected.type, actual.type);
  }

  void deletePreset(int presetId) throws SQLException {
    try (final var statement = connection.createStatement()){
      statement.execute(
          //language=sql
          """
          DELETE FROM merlin.activity_presets
          WHERE id = %d
          """.formatted(presetId));
    }
  }

  ArrayList<Activity> getActivitiesWithPreset(final int presetId) throws SQLException{
    try (final var statement = connection.createStatement()) {
      // Select from act dirs using the list of ids gotten from the join table preset to dirs
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT ad.id, ad.plan_id, ad.name, ad.type, ad.arguments
          FROM merlin.activity_directive ad,
            (SELECT activity_id, plan_id
             FROM merlin.preset_to_directive
             WHERE preset_id = %d) presets
          WHERE (ad.id, ad.plan_id) = (presets.activity_id, presets.plan_id);
          """.formatted(presetId));

      final var activities = new ArrayList<Activity>();
      while (res.next()){
        activities.add(new Activity(
            res.getInt("id"),
            res.getInt("plan_id"),
            res.getString("name"),
            res.getString("type"),
            res.getString("arguments")
        ));
      }
      return activities;
    }
  }

  Preset getPresetAssignedToActivity(final int activityId, final int planId) throws SQLException{
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT ap.id, ap.model_id, ap.name, ap.associated_activity_type, ap.arguments
          FROM merlin.activity_presets ap,
            (SELECT preset_id
             FROM merlin.preset_to_directive
             WHERE (activity_id, plan_id) = (%d, %d)) o
          WHERE ap.id = o.preset_id;
          """.formatted(activityId, planId));
      return res.next() ?
       new Preset(
          res.getInt("id"),
          res.getInt("model_id"),
          res.getString("name"),
          res.getString("associated_activity_type"),
          res.getString("arguments")
      ) : null;
    }
  }
  //endregion

  //region Records
  record Activity(int activityId, int planId, String name, String type, String arguments) {}
  record Preset(int id, int modelId, String name, String associatedActivityType, String arguments) {}
  //endregion

  @Test
  void presetAppliesCorrectly() throws SQLException {
    final int planId = merlinHelper.insertPlan(missionModelId);
    final String activityArgs = "{\"fruitCount\": 40}";
    final int activityId = merlinHelper.insertActivity(planId, "00:00:00", activityArgs);
    final String simplePresetArgs = "{\"fruitCount\": 80}";
    final int simplePresetId = merlinHelper.insertPreset(missionModelId, "simple preset", "test-activity", merlinHelper.admin.name(), simplePresetArgs);
    final String extendedPresetArgs = "{\"coreCount\": 120, \"destination\": \"Mars\"}";
    final int extendedPresetId = merlinHelper.insertPreset(missionModelId, "extended preset", "test-activity", merlinHelper.admin.name(), extendedPresetArgs);

    var simplePresetActivities = getActivitiesWithPreset(simplePresetId);
    var extendedPresetActivities = getActivitiesWithPreset(extendedPresetId);
    assertTrue(simplePresetActivities.isEmpty());
    assertTrue(extendedPresetActivities.isEmpty());

    // Simple Update
    final Activity originalActivity = getActivity(planId, activityId);
    final Activity simpleActivity = assignPreset(simplePresetId, activityId, planId);
    assertEquals(1, getActivities(planId).size());
    assertActivityEqualsAsideFromArgs(originalActivity, simpleActivity);
    assertNotEquals(originalActivity.arguments, simpleActivity.arguments);
    assertEquals(simplePresetArgs, simpleActivity.arguments);

    simplePresetActivities = getActivitiesWithPreset(simplePresetId);
    extendedPresetActivities = getActivitiesWithPreset(extendedPresetId);
    assertEquals(1, simplePresetActivities.size());
    assertEquals(simpleActivity, simplePresetActivities.get(0));
    assertTrue(extendedPresetActivities.isEmpty());

    // Extended Update
    final Activity extendedActivity = assignPreset(extendedPresetId, activityId, planId);
    assertEquals(1, getActivities(planId).size());
    assertActivityEqualsAsideFromArgs(originalActivity, extendedActivity);
    assertNotEquals(originalActivity.arguments, extendedActivity.arguments);
    assertEquals(extendedPresetArgs, extendedActivity.arguments);

    simplePresetActivities = getActivitiesWithPreset(simplePresetId);
    extendedPresetActivities = getActivitiesWithPreset(extendedPresetId);
    assertTrue(simplePresetActivities.isEmpty());
    assertEquals(1, extendedPresetActivities.size());
    assertEquals(extendedActivity, extendedPresetActivities.get(0));
  }


  @Test
  void cannotApplyPresetOfIncorrectType() throws SQLException {
    // Insert 'fake-type' to avoid an FK conflict
    merlinHelper.insertActivityType(missionModelId, "fake-type");
    final int planId = merlinHelper.insertPlan(missionModelId);
    final int activityId = merlinHelper.insertActivity(planId);
    final int presetId = merlinHelper.insertPreset(missionModelId, "test preset", "fake-type");

    final Activity activity = getActivity(planId, activityId);
    assertEquals("test-activity", activity.type);

    try{
      assignPreset(presetId, activityId, planId);
      fail();
    } catch (SQLException ex){
      if(!ex.getMessage().contains("Cannot apply preset for activity type \"fake-type\" onto an activity of type \"test-activity\".")){
        throw ex;
      }
    }
  }

  @Test
  void cannotApplyNonexistentPreset() throws SQLException {
    final int planId = merlinHelper.insertPlan(missionModelId);
    final int activityId = merlinHelper.insertActivity(planId);

    try{
      assignPreset(-1, activityId, planId);
      fail();
    } catch (SQLException ex){
      if(!ex.getMessage().contains("Activity preset -1 does not exist")){
        throw ex;
      }
    }
  }

  @Test
  void cannotApplyPresetToNonexistentActivity() throws SQLException {
    final int planId = merlinHelper.insertPlan(missionModelId);
    final int presetId = merlinHelper.insertPreset(missionModelId, "test preset", "test-activity");

    try{
      assignPreset(presetId, -1, planId);
      fail();
    } catch (SQLException ex){
      if(!ex.getMessage().contains("Activity directive -1 does not exist in plan "+planId)){
        throw ex;
      }
    }
  }

  // Tests that two presets with the same name can be uploaded as long as the model or associated activity type differs
  // and that two presets applied to the same activity type and model but with different names can be uploaded
  // but that two presets with the same name, model, and associated activity type cannot be uploaded
  @Test
  void presetUniquenessConstraint() throws SQLException {
    final int otherModelId = merlinHelper.insertMissionModel(fileId);
    // Insert the used activity types to avoid an FK conflict
    merlinHelper.insertActivityType(missionModelId, "Shared Type");
    merlinHelper.insertActivityType(missionModelId, "Different Type");
    merlinHelper.insertActivityType(otherModelId, "Shared Type");
    merlinHelper.insertActivityType(otherModelId, "Unique Type");

    merlinHelper.insertPreset(missionModelId, "Shared Name", "Shared Type");
    merlinHelper.insertPreset(missionModelId, "Shared Name", "Different Type");
    merlinHelper.insertPreset(otherModelId, "Shared Name", "Shared Type");
    merlinHelper.insertPreset(missionModelId, "Different Name", "Shared Type");
    merlinHelper.insertPreset(otherModelId, "Unique Name", "Unique Type");
    try{
      merlinHelper.insertPreset(missionModelId, "Shared Name", "Shared Type");
      fail();
    } catch (SQLException ex){
      if(!ex.getMessage().contains("duplicate key value violates unique constraint \"activity_presets_model_id_associated_activity_type_name_key\"")){
        throw ex;
      }
    }
  }
}
