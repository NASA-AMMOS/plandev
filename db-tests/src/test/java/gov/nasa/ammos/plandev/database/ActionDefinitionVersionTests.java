package gov.nasa.jpl.aerie.database;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ActionDefinitionVersionTests {
  private DatabaseTestHelper helper;
  private Connection connection;

  // Shared prerequisite IDs set up in beforeEach
  private int commandDictionaryId;
  private int parcelId;
  private int workspaceId;
  private int actionFileId;

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_action_version_test", "Action Definition Version Tests");
    connection = helper.connection();
    insertUser("TestAdmin");
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
    connection = null;
    helper = null;
  }

  @BeforeEach
  void beforeEach() throws SQLException {
    // Build the dependency chain: user -> command_dictionary -> parcel -> workspace -> action_definition
    commandDictionaryId = insertCommandDictionary();
    parcelId = insertParcel(commandDictionaryId);
    workspaceId = insertWorkspace(parcelId);
    actionFileId = insertFileUpload();
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("actions");
    helper.clearSchema("sequencing");
    helper.clearSchema("merlin");
  }

  //region Helper Methods
  private void insertUser(String username) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          INSERT INTO permissions.users (username, default_role)
          VALUES ('%s', 'aerie_admin')
          ON CONFLICT DO NOTHING;
          """.formatted(username));
    }
  }

  private int insertCommandDictionary() throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO sequencing.command_dictionary (dictionary_path, mission, version)
          VALUES ('test-path', 'test-mission', '%s')
          RETURNING id;
          """.formatted(UUID.randomUUID().toString()));
      res.next();
      return res.getInt("id");
    }
  }

  private int insertParcel(int cmdDictId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO sequencing.parcel (name, command_dictionary_id)
          VALUES ('test-parcel', %d)
          RETURNING id;
          """.formatted(cmdDictId));
      res.next();
      return res.getInt("id");
    }
  }

  private int insertWorkspace(int parcelId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO sequencing.workspace (name, disk_location, parcel_id, owner)
          VALUES ('test-workspace', '/tmp/test-%s', %d, 'TestAdmin')
          RETURNING id;
          """.formatted(UUID.randomUUID().toString(), parcelId));
      res.next();
      return res.getInt("id");
    }
  }

  private int insertFileUpload() throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var uuid = UUID.randomUUID().toString();
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO merlin.uploaded_file (path, name)
          VALUES ('test-action-path-%s', 'test-action-file-%s')
          RETURNING id;
          """.formatted(uuid, uuid));
      res.next();
      return res.getInt("id");
    }
  }

  private int insertActionDefinition(int workspaceId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO actions.action_definition (name, description, workspace_id, owner)
          VALUES ('test-action', 'A test action', %d, 'TestAdmin')
          RETURNING id;
          """.formatted(workspaceId));
      res.next();
      return res.getInt("id");
    }
  }

  private int insertVersion(int actionDefinitionId, int fileId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO actions.action_definition_version (action_definition_id, action_file_id, author)
          VALUES (%d, %d, 'TestAdmin')
          RETURNING revision;
          """.formatted(actionDefinitionId, fileId));
      res.next();
      return res.getInt("revision");
    }
  }

  private int getVersionCount(int actionDefinitionId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT count(*) FROM actions.action_definition_version
          WHERE action_definition_id = %d;
          """.formatted(actionDefinitionId));
      res.next();
      return res.getInt(1);
    }
  }
  //endregion

  @Nested
  class RevisionAutoIncrement {
    @Test
    void firstVersionGetsRevisionZero() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      final var revision = insertVersion(defId, actionFileId);
      assertEquals(0, revision);
    }

    @Test
    void secondVersionGetsRevisionOne() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      final var fileId2 = insertFileUpload();

      final var rev0 = insertVersion(defId, actionFileId);
      final var rev1 = insertVersion(defId, fileId2);

      assertEquals(0, rev0);
      assertEquals(1, rev1);
    }

    @Test
    void revisionsIncrementIndependentlyPerDefinition() throws SQLException {
      final var defA = insertActionDefinition(workspaceId);
      final var defB = insertActionDefinition(workspaceId);
      final var fileId2 = insertFileUpload();

      // Each definition should start its own revision sequence at 0
      assertEquals(0, insertVersion(defA, actionFileId));
      assertEquals(0, insertVersion(defB, actionFileId));
      assertEquals(1, insertVersion(defA, fileId2));
      assertEquals(1, insertVersion(defB, fileId2));
    }
  }

  @Nested
  class CascadeDelete {
    @Test
    void deletingDefinitionDeletesVersions() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      insertVersion(defId, actionFileId);
      insertVersion(defId, insertFileUpload());
      assertEquals(2, getVersionCount(defId));

      try (final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            DELETE FROM actions.action_definition WHERE id = %d;
            """.formatted(defId));
      }

      assertEquals(0, getVersionCount(defId));
    }

    @Test
    void deletingVersionDoesNotDeleteDefinition() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      insertVersion(defId, actionFileId);

      try (final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            DELETE FROM actions.action_definition_version
            WHERE action_definition_id = %d AND revision = 0;
            """.formatted(defId));
      }

      // The parent definition should still exist
      try (final var statement = connection.createStatement()) {
        final var res = statement.executeQuery(
            //language=sql
            """
            SELECT count(*) FROM actions.action_definition WHERE id = %d;
            """.formatted(defId));
        res.next();
        assertEquals(1, res.getInt(1));
      }
    }
  }

  @Nested
  class ActionRunDefaultRevision {
    @Test
    void runDefaultsToLatestRevision() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      insertVersion(defId, actionFileId);
      insertVersion(defId, insertFileUpload());
      // Latest revision is 1

      try (final var statement = connection.createStatement()) {
        // Insert a run WITHOUT specifying action_definition_revision;
        // the trigger should auto-populate it with the latest (1)
        final var res = statement.executeQuery(
            //language=sql
            """
            INSERT INTO actions.action_run (settings, parameters, action_definition_id, requested_by)
            VALUES ('{}', '{}', %d, 'TestAdmin')
            RETURNING action_definition_revision;
            """.formatted(defId));
        res.next();
        assertEquals(1, res.getInt("action_definition_revision"));
      }
    }

    @Test
    void runRespectsExplicitRevision() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      insertVersion(defId, actionFileId);
      insertVersion(defId, insertFileUpload());

      try (final var statement = connection.createStatement()) {
        // Explicitly request revision 0 (not the latest)
        final var res = statement.executeQuery(
            //language=sql
            """
            INSERT INTO actions.action_run (settings, parameters, action_definition_id, action_definition_revision, requested_by)
            VALUES ('{}', '{}', %d, 0, 'TestAdmin')
            RETURNING action_definition_revision;
            """.formatted(defId));
        res.next();
        assertEquals(0, res.getInt("action_definition_revision"));
      }
    }
  }

  @Nested
  class Archiving {
    @Test
    void definitionDefaultsToNotArchived() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);

      try (final var statement = connection.createStatement()) {
        final var res = statement.executeQuery(
            //language=sql
            """
            SELECT archived FROM actions.action_definition WHERE id = %d;
            """.formatted(defId));
        res.next();
        assertEquals(false, res.getBoolean("archived"));
      }
    }

    @Test
    void versionDefaultsToNotArchived() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      insertVersion(defId, actionFileId);

      try (final var statement = connection.createStatement()) {
        final var res = statement.executeQuery(
            //language=sql
            """
            SELECT archived FROM actions.action_definition_version
            WHERE action_definition_id = %d AND revision = 0;
            """.formatted(defId));
        res.next();
        assertEquals(false, res.getBoolean("archived"));
      }
    }

    @Test
    void canArchiveDefinition() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);

      try (final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            UPDATE actions.action_definition SET archived = true WHERE id = %d;
            """.formatted(defId));

        final var res = statement.executeQuery(
            //language=sql
            """
            SELECT archived FROM actions.action_definition WHERE id = %d;
            """.formatted(defId));
        res.next();
        assertTrue(res.getBoolean("archived"));
      }
    }

    @Test
    void canArchiveVersion() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      insertVersion(defId, actionFileId);

      try (final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            UPDATE actions.action_definition_version SET archived = true
            WHERE action_definition_id = %d AND revision = 0;
            """.formatted(defId));

        final var res = statement.executeQuery(
            //language=sql
            """
            SELECT archived FROM actions.action_definition_version
            WHERE action_definition_id = %d AND revision = 0;
            """.formatted(defId));
        res.next();
        assertTrue(res.getBoolean("archived"));
      }
    }
  }

  @Nested
  class ForeignKeyConstraints {
    @Test
    void cannotInsertVersionForNonexistentDefinition() {
      assertThrows(SQLException.class, () -> {
        try (final var statement = connection.createStatement()) {
          statement.executeQuery(
              //language=sql
              """
              INSERT INTO actions.action_definition_version (action_definition_id, action_file_id, author)
              VALUES (-999, %d, 'TestAdmin')
              RETURNING revision;
              """.formatted(actionFileId));
        }
      });
    }

    @Test
    void cannotDeleteFileReferencedByVersion() throws SQLException {
      final var defId = insertActionDefinition(workspaceId);
      insertVersion(defId, actionFileId);

      assertThrows(SQLException.class, () -> {
        try (final var statement = connection.createStatement()) {
          statement.executeUpdate(
              //language=sql
              """
              DELETE FROM merlin.uploaded_file WHERE id = %d;
              """.formatted(actionFileId));
        }
      });
    }
  }
}
