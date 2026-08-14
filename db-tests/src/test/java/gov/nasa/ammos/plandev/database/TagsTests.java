package gov.nasa.jpl.aerie.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.awt.*;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TagsTests {
  private final String constraintDefinition = "export default (): Constraint => Real.Resource(\"/fruit\").equal(Real.Resource(\"/peel\"))";
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;
  int fileId;
  int missionModelId;
  int tagId;
  MerlinDatabaseTestHelper.User tagsUser;

  void setConnection(DatabaseTestHelper helper) {
    connection = helper.connection();
  }

  @BeforeEach
  void beforeEach() throws SQLException {
    fileId = merlinHelper.insertFileUpload();
    missionModelId = merlinHelper.insertMissionModel(fileId);
    tagId = insertTag("Farm");
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
    helper.clearSchema("tags");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_tags_test", "Tags Tests");
    setConnection(helper);
    merlinHelper = new MerlinDatabaseTestHelper(connection);
    tagsUser = merlinHelper.insertUser("TagsTest");
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  //region Helper Functions
  private int insertTag(String name) throws SQLException {
    return insertTag(name, tagsUser.name());
  }
  int insertTag(String name, String username) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO tags.tags (name, owner)
          VALUES ('%s', '%s')
          RETURNING id;
          """.formatted(name, username)
      );
      res.next();
      return res.getInt("id");
    }
  }

  int insertTag(String name, String username, String color) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO tags.tags (name, color, owner)
          VALUES ('%s', '%s', '%s')
          RETURNING id;
          """.formatted(name, color, username)
      );
      res.next();
      return res.getInt("id");
    }
  }

  Tag updateTagColor(int tagId, String color) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          UPDATE tags.tags
          SET color = '%s'
          WHERE id = %d
          RETURNING id, name, color, owner
          """.formatted(color, tagId)
      );
      res.next();
      return new Tag(
          res.getInt("id"),
          res.getString("name"),
          res.getString("color"),
          res.getString("owner")
      );
    }
  }

  Tag updateTagName(int tagId, String name) throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          UPDATE tags.tags
          SET name = '%s'
          WHERE id = %d
          RETURNING id, name, color, owner
          """.formatted(name, tagId));
      res.next();
      return new Tag(
          res.getInt("id"),
          res.getString("name"),
          res.getString("color"),
          res.getString("owner"));
    }
  }

  Tag getTag(int id) throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, color, owner
          FROM tags.tags
          WHERE id = %d;
          """.formatted(id)
      );
      res.next();
      return new Tag(
        res.getInt("id"),
        res.getString("name"),
        res.getString("color"),
        res.getString("owner"));
    }
  }

  ArrayList<Tag> getAllTags() throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var tags = new ArrayList<Tag>();
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, color, owner
          FROM tags.tags;
          """
      );
      while(res.next()) {
        tags.add(new Tag(
            res.getInt("id"),
            res.getString("name"),
            res.getString("color"),
            res.getString("owner")));
      }
      return tags;
    }
  }

  void deleteTag(int id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          DELETE FROM tags.tags
          WHERE id = %d;
          """.formatted(id));
    }
  }

  void assignTagToActivity(int directive_id, int plan_id, int tag_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          INSERT INTO tags.activity_directive_tags (plan_id, directive_id, tag_id)
          VALUES (%d, %d, %d)
          """.formatted(plan_id,directive_id,tag_id));
    }
  }

  void removeTagFromActivity(int directive_id, int plan_id, int tag_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          DELETE FROM tags.activity_directive_tags
          WHERE plan_id = %d
            AND directive_id = %d
            AND tag_id = %d;
          """.formatted(plan_id, directive_id, tag_id));
    }
  }

  ArrayList<Tag> getTagsOnActivity(int directive_id, int plan_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var tags = new ArrayList<Tag>();
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, color, owner
          FROM tags.tags t, tags.activity_directive_tags adt
          WHERE adt.tag_id = t.id
            AND adt.plan_id = %d
            AND adt.directive_id = %d
          ORDER BY id;
          """.formatted(plan_id, directive_id));
      while (res.next()) {
        tags.add(new Tag(
            res.getInt("id"),
            res.getString("name"),
            res.getString("color"),
            res.getString("owner")));
      }
      return tags;
    }
  }

  ArrayList<Tag> getTagsOnActivitySnapshot(int directive_id, int snapshot_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var tags = new ArrayList<Tag>();
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, color, owner
          FROM tags.tags t, tags.snapshot_activity_tags sat
          WHERE sat.tag_id = t.id
            AND sat.snapshot_id = %d
            AND sat.directive_id = %d
          ORDER BY id;
          """.formatted(snapshot_id, directive_id));
      while (res.next()) {
        tags.add(new Tag(
            res.getInt("id"),
            res.getString("name"),
            res.getString("color"),
            res.getString("owner")));
      }
      return tags;
    }
  }

  void assignTagToPlan(int plan_id, int tag_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          INSERT INTO tags.plan_tags (plan_id, tag_id)
          VALUES (%d, %d)
          """.formatted(plan_id,tag_id));
    }
  }

  void removeTagFromPlan(int plan_id, int tag_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          DELETE FROM tags.plan_tags
          WHERE plan_id = %d
            AND tag_id = %d;
          """.formatted(plan_id, tag_id));
    }
  }

  ArrayList<Tag> getTagsOnPlan(int plan_id) throws SQLException {
    try(final var statement = connection.createStatement()) {
      final var tags = new ArrayList<Tag>();
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, color, owner
          FROM tags.tags t, tags.plan_tags pt
          WHERE pt.tag_id = t.id
            AND pt.plan_id = %d
          ORDER BY id;
          """.formatted(plan_id));
      while(res.next()) {
        tags.add(new Tag(
            res.getInt("id"),
            res.getString("name"),
            res.getString("color"),
            res.getString("owner")));
      }
      return tags;
    }
  }

  void assignTagToConstraint(int constraint_id, int tag_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          INSERT INTO tags.constraint_tags (constraint_id, tag_id)
          VALUES (%d, %d)
          """.formatted(constraint_id, tag_id));
    }
  }

  void assignTagToConstraintRevision(int constraint_id, int constraint_revision, int tag_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          INSERT INTO tags.constraint_definition_tags (constraint_id, constraint_revision, tag_id)
          VALUES (%d, %d, %d)
          """.formatted(constraint_id, constraint_revision, tag_id));
    }
  }

  void removeTagFromConstraint(int constraint_id, int tag_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          DELETE FROM tags.constraint_tags
          WHERE constraint_id = %d
            AND tag_id = %d;
          """.formatted(constraint_id, tag_id));
    }
  }

  ArrayList<Tag> getTagsOnConstraint(int constraint_id) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var tags = new ArrayList<Tag>();
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, color, owner
          FROM tags.tags t, tags.constraint_tags ct
          WHERE ct.tag_id = t.id
            AND ct.constraint_id = %d
          ORDER BY id;
          """.formatted(constraint_id));
      while (res.next()) {
        tags.add(new Tag(
            res.getInt("id"),
            res.getString("name"),
            res.getString("color"),
            res.getString("owner")));
      }
      return tags;
    }
  }

  ArrayList<Tag> getTagsOnConstraintRevision(int constraint_id, int constraint_revision) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var tags = new ArrayList<Tag>();
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, color, owner
          FROM tags.tags t, tags.constraint_definition_tags ct
          WHERE ct.tag_id = t.id
            AND ct.constraint_id = %d
            AND ct.constraint_revision = %d
          ORDER BY id;
          """.formatted(constraint_id, constraint_revision));
      while (res.next()) {
        tags.add(new Tag(
            res.getInt("id"),
            res.getString("name"),
            res.getString("color"),
            res.getString("owner")));
      }
      return tags;
    }
  }

  int assignTagToActivityType(int modelId, String name, int tagId) throws SQLException{
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          UPDATE merlin.activity_type at
          SET subsystem = %d
          WHERE at.model_id = %d
            AND at.name = '%s'
          RETURNING subsystem;
          """.formatted(tagId, modelId, name));
      assertTrue(res.next());
      final int subsystem = res.getInt("subsystem");
      assertFalse(res.next());
      return subsystem;
    }
  }

  void removeTagFromActivityType(int modelId, String name) throws SQLException {
     try (final var statement = connection.createStatement()) {
       final var res = statement.executeQuery(
           //language=sql
           """
           UPDATE merlin.activity_type
           SET subsystem = null
           WHERE model_id = %d
           AND name = '%s'
           RETURNING subsystem
           """.formatted(modelId, name));
       assertTrue(res.next());
       assertNull(res.getObject("subsystem"));
       assertFalse(res.next());
     }
  }
  //endregion

  //region Records
  record Tag(int id, String name, String color, String owner) {}
  //endregion

  @Test
  void tagsAssociateCorrectly() throws SQLException {
    final var secondTagId = insertTag("Banana");
    final var planId = merlinHelper.insertPlan(missionModelId);
    final var activityId = merlinHelper.insertActivity(planId);
    final var constraintId = merlinHelper.insertConstraint("Test Constraint", constraintDefinition, tagsUser);

    assignTagToPlan(planId, tagId);
    assignTagToActivity(activityId, planId, tagId);
    assignTagToConstraint(constraintId, tagId);
    assignTagToConstraintRevision(constraintId, 0, tagId);

    assignTagToPlan(planId, secondTagId);

    final var planTags = getTagsOnPlan(planId);
    final var activityTags = getTagsOnActivity(activityId, planId);
    final var constraintTags = getTagsOnConstraint(constraintId);
    final var constraintRevisionTags = getTagsOnConstraintRevision(constraintId, 0);

    final ArrayList<Tag> expected = new ArrayList<>();
    expected.add(new Tag(tagId, "Farm", null, "TagsTest"));

    final ArrayList<Tag> expectedPlan = new ArrayList<>(expected);
    expectedPlan.add(new Tag(secondTagId, "Banana", null, "TagsTest"));


    assertEquals(expectedPlan, planTags);
    assertEquals(expected, activityTags);
    assertEquals(expected, constraintTags);
    assertEquals(expected, constraintRevisionTags);
  }

  @Test
  void tagsDissociateCorrectly() throws SQLException {
    final var secondTagId = insertTag("Banana");
    final var planId = merlinHelper.insertPlan(missionModelId);
    final var activityId = merlinHelper.insertActivity(planId);
    final var constraintId = merlinHelper.insertConstraint("Test Constraint", constraintDefinition, tagsUser);

    assignTagToPlan(planId, tagId);
    assignTagToActivity(activityId, planId, tagId);
    assignTagToConstraint(constraintId, tagId);
    assignTagToConstraintRevision(constraintId, 0, tagId);

    assignTagToPlan(planId, secondTagId);

    removeTagFromPlan(planId, tagId);
    assertDoesNotThrow(() -> getTag(secondTagId));
    final var planTags = getTagsOnPlan(planId);
    final var activityTags = getTagsOnActivity(activityId, planId);
    final var constraintTags = getTagsOnActivity(activityId, planId);
    final var constraintRevisionTags = getTagsOnConstraintRevision(constraintId, 0);

    final var expected = new ArrayList<Tag>(1);
    expected.add(new Tag(tagId, "Farm", null, tagsUser.name()));

    final var expectedPlan = new ArrayList<Tag>(1);
    expectedPlan.add(new Tag(secondTagId, "Banana", null, tagsUser.name()));

    assertEquals(expectedPlan, planTags);
    assertEquals(expected, activityTags);
    assertEquals(expected, constraintTags);
    assertEquals(expected, constraintRevisionTags);
  }

  @Test
  void cannotApplyNonexistentTag() throws SQLException {
    // Plan
    final var planId = merlinHelper.insertPlan(missionModelId);
    try {
      assignTagToPlan(planId, -1);
    } catch (SQLException e) {
      if(!e.getMessage().contains("insert or update on table \"plan_tags\" violates foreign key constraint \"plan_tags_tag_id_fkey\"")) {
        throw e;
      }
    }
    // Activity
    final var activityId = merlinHelper.insertActivity(planId);
    try {
      assignTagToActivity(activityId, planId, -1);
    } catch (SQLException e) {
      if(!e.getMessage().contains("insert or update on table \"activity_directive_tags\" violates foreign key constraint \"activity_directive_tags_tag_id_fkey\"")) {
        throw e;
      }
    }
    // Constraint
    final var constraintId = merlinHelper.insertConstraint("Test Constraint", constraintDefinition, tagsUser);
    try {
      assignTagToConstraint(constraintId, -1);
    } catch (SQLException e) {
      if(!e.getMessage().contains("insert or update on table \"constraint_tags\" violates foreign key constraint \"constraint_tags_tag_id_fkey\"")) {
        throw e;
      }
    }
    // Constraint Revision
    try {
      assignTagToConstraintRevision(constraintId, 0, -1);
    } catch (SQLException e) {
      if(!e.getMessage().contains("insert or update on table \"constraint_definition_tags\" violates foreign key constraint \"constraint_definition_tags_tag_id_fkey\"")) {
        throw e;
      }
    }
    // Activity Type
    merlinHelper.insertActivityType(missionModelId, "GrowBanana");
    try {
      assignTagToActivityType(missionModelId, "GrowBanana", -1);
    } catch (SQLException e) {
      if(!e.getMessage().contains("insert or update on table \"activity_type\" violates foreign key constraint \"activity_type_subsystem_fkey\"")) {
        throw e;
      }
    }
  }

  @Test
  void tagDeleteCascadesInMerlin() throws SQLException {
    final var secondTagId = insertTag("Banana");
    final var planId = merlinHelper.insertPlan(missionModelId);
    final var activityId = merlinHelper.insertActivity(planId);
    final var constraintId = merlinHelper.insertConstraint("Test Constraint", constraintDefinition, tagsUser);

    assignTagToPlan(planId, tagId);
    assignTagToActivity(activityId, planId, tagId);
    assignTagToConstraint(constraintId, tagId);
    assignTagToConstraintRevision(constraintId, 0, tagId);

    assignTagToPlan(planId, secondTagId);
    assignTagToActivity(activityId, planId, secondTagId);
    assignTagToConstraint(constraintId, secondTagId);
    assignTagToConstraintRevision(constraintId, 0, secondTagId);

    deleteTag(tagId);

    final var planTags = getTagsOnPlan(planId);
    final var activityTags = getTagsOnActivity(activityId, planId);
    final var constraintTags = getTagsOnConstraint(constraintId);
    final var constraintRevisionTags = getTagsOnConstraintRevision(constraintId, 0);

    final ArrayList<Tag> expected = new ArrayList<>();
    expected.add(new Tag(secondTagId, "Banana", null, tagsUser.name()));

    assertEquals(expected, planTags);
    assertEquals(expected, activityTags);
    assertEquals(expected, constraintTags);
    assertEquals(expected, constraintRevisionTags);
  }

  @Test
  void tagNamesAreUnique() throws SQLException {
    final Tag insertedTag = getTag(tagId);
    try {
      insertTag(insertedTag.name);
    } catch (SQLException e) {
      if(!e.getMessage().contains("duplicate key value violates unique constraint \"tags_name_key\"")){
        throw e;
      }
    }
    final int secondTagId = insertTag("Banana");
    try {
      updateTagName(secondTagId, insertedTag.name);
    } catch (SQLException e) {
      if(!e.getMessage().contains("duplicate key value violates unique constraint \"tags_name_key\"")){
        throw e;
      }
    }
  }

  @Test
  void tagColorMustBeHexOrNull() throws SQLException {
    assertDoesNotThrow(()->insertTag("Null Color Tag"));
    assertDoesNotThrow(()->insertTag("Hex Color Tag", tagsUser.name(), "#ad6ef5"));
    assertDoesNotThrow(()->insertTag("Uppercase Hex Color Tag", tagsUser.name(), "#AD6EF5"));
    final var javaColorString = String.format("#%02x%02x%02x", Color.PINK.getRed(), Color.PINK.getBlue(), Color.PINK.getGreen());
    assertDoesNotThrow(()->insertTag("Java Colors Tag", tagsUser.name(), javaColorString));
    try {
      insertTag("Invalid Color", tagsUser.name(), "bad color :(");
    } catch (SQLException e) {
      if (!e.getMessage()
          .contains("new row for relation \"tags\" violates check constraint \"color_is_hex_format\"\n")) {
        throw e;
      }
    }
    try {
      updateTagColor(tagId, "#######");
    } catch (SQLException e) {
      if (!e.getMessage()
          .contains("new row for relation \"tags\" violates check constraint \"color_is_hex_format\"\n")) {
        throw e;
      }
    }

  }

  @Test
  void subsystemTagRestrictsDelete() throws SQLException {
    merlinHelper.insertActivityType(missionModelId, "GrowBanana");
    // Assert Association
    final var subsystem = assignTagToActivityType(missionModelId, "GrowBanana", tagId);
    assertEquals(tagId, subsystem);

    // Attempt Delete
    try {
      deleteTag(tagId);
    } catch (SQLException e) {
      if(!e.getMessage().contains("update or delete on table \"tags\" violates foreign key constraint "
                                  + "\"activity_type_subsystem_fkey\" on table \"activity_type\"")) {
        throw e;
      }
    }

    // Clear Association
    removeTagFromActivityType(missionModelId, "GrowBanana");

    deleteTag(tagId);
  }
}
