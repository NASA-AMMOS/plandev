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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UserSequenceTests {
  private final String sequenceDefinition = """
        C BAKE_BREAD
        C PEEL_BANANA "fromStem"
        C GROW_BANANA 0 0
      """;
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;
  private int commandDictionaryId;
  private int parcelId;
  private int workspaceId;
  private int sequenceId;
  private MerlinDatabaseTestHelper.User sequenceUser;

  void setConnection(DatabaseTestHelper helper) {
    connection = helper.connection();
  }


  @BeforeEach
  void beforeEach() throws SQLException {
    commandDictionaryId = createCommandDictionary("/path/to/dictionary.ts", "foo", "1.0.0", "{}");
    parcelId = createParcel("Test Parcel");
    workspaceId = createWorkspace("Test Workspace", sequenceUser.name());
    sequenceId = createUserSequence("Test Sequence", parcelId, workspaceId, sequenceDefinition, sequenceUser.name());
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
    helper.clearSchema("sequencing");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_user_sequence_test", "User Sequence Tests");
    setConnection(helper);
    merlinHelper = new MerlinDatabaseTestHelper(connection);
    sequenceUser = merlinHelper.insertUser("SequenceTest");
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.clearSchema("merlin");
    helper.clearSchema("sequencing");
    helper.close();
  }

  // region Helper Functions
  private int createCommandDictionary(String dictionaryPath, String mission, String version, String dictionaryJSON) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO sequencing.command_dictionary (dictionary_path, mission, version, parsed_json)
          VALUES ('%s', '%s', '%s', '%s')
          RETURNING id;
          """.formatted(dictionaryPath, mission, version, dictionaryJSON));

      res.next();
      return res.getInt("id");
    }
  }

  private int createParcel(String name) throws SQLException {
    return createParcel(name, commandDictionaryId, sequenceUser.name());
  }

  private int createParcel(String name, int dictionaryId, String username) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO sequencing.parcel (name, command_dictionary_id, owner)
          VALUES ('%s', '%d', '%s')
          RETURNING id;
          """.formatted(name, dictionaryId, username));

      res.next();
      return res.getInt("id");
    }
  }

  private int createWorkspace(String name, String username) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO sequencing.workspace (name, owner)
          VALUES ('%s', '%s')
          RETURNING id;
          """.formatted(name, username));

      res.next();
      return res.getInt("id");
    }
  }

  private int createUserSequence(String name, int parcelId, int workspaceId, String definition, String username) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          INSERT INTO sequencing.user_sequence (name, parcel_id, workspace_id, definition, owner)
          VALUES ('%s', '%d', '%d', '%s', '%s')
          RETURNING id;
          """.formatted(name, parcelId, workspaceId, definition, username)
      );

      res.next();
      return res.getInt("id");
    }
  }


  private void assignParcelToSequence(int parcelId, int sequenceId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          UPDATE sequencing.user_sequence
          SET parcel_id = '%d'
          WHERE id = %d
          """.formatted(parcelId, sequenceId)
      );
    }
  }

  private void updateUserSequenceIsLocked(int sequenceId, boolean isLocked) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          UPDATE sequencing.user_sequence
          SET is_locked = '%b'
          WHERE id = %d
          """.formatted(isLocked, sequenceId)
      );
    }
  }

  private void updateUserSequenceName(int sequenceId, String sequenceName) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          UPDATE sequencing.user_sequence
          SET name = '%s'
          WHERE id = %d
          """.formatted(sequenceName, sequenceId)
      );
    }
  }

  private void deleteUserSequence(int sequenceId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          DELETE FROM sequencing.user_sequence
          WHERE id = %d;
          """.formatted(sequenceId)
      );
    }
  }

  private ArrayList<UserSequence> getUserSequences(int sequenceId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var sequences = new ArrayList<UserSequence>();
      final var res = statement.executeQuery(
          //language=sql
          """
          SELECT id, name, definition, parcel_id, is_locked, workspace_id, owner
          FROM sequencing.user_sequence
          WHERE id = %d
          """.formatted(sequenceId)
      );

      while (res.next()) {
        sequences.add(new UserSequence(
            res.getInt("id"),
            res.getString("name"),
            res.getString("definition"),
            res.getInt("parcel_id"),
            res.getBoolean("is_locked"),
            res.getInt("workspace_id"),
            res.getString("owner")));
      }
      return sequences;
    }
  }
  // endregion

  //region Records
  private record UserSequence(int id, String name, String definition, int parcel_id, boolean is_locked, int workspace_id, String owner) {}
  //endregion

  @Test
  void addUserSequence() throws SQLException {
    final var userSequence = getUserSequences(sequenceId);

    final var expectedUserSequence = new ArrayList<UserSequence>(1);
    expectedUserSequence.add(new UserSequence(sequenceId, "Test Sequence", sequenceDefinition, parcelId, false, workspaceId, sequenceUser.name()));

    assertEquals(expectedUserSequence, userSequence);
  }

  @Test
  void updateUserSequenceParcel() throws SQLException {
    final var testParcelId = createParcel("Test Parcel 2");
    assertDoesNotThrow(()->assignParcelToSequence(testParcelId, sequenceId));

    assignParcelToSequence(parcelId, sequenceId);
  }

  @Test
  void lockUserSequence() throws SQLException {
    updateUserSequenceIsLocked(sequenceId, true);

    try {
      updateUserSequenceName(sequenceId, "Foo Sequence");
    } catch (SQLException ex) {
      if (!ex.getMessage().contains("Cannot update locked user sequence.")) {
        throw ex;
      }
    }
  }

  @Test
  void lockDoesNotAffectOtherSequence() throws SQLException {
    final var otherSequenceId = createUserSequence("Test Sequence 2", parcelId, workspaceId, sequenceDefinition, sequenceUser.name());

    assertDoesNotThrow(()->updateUserSequenceName(otherSequenceId, "Bar Sequence"));
  }

  @Test
  void unlockUserSequence() throws SQLException {
    updateUserSequenceIsLocked(sequenceId, false);

    assertDoesNotThrow(()->updateUserSequenceName(sequenceId, "Foo Sequence"));

    final var userSequence = getUserSequences(sequenceId);
    assertEquals(false, userSequence.get(0).is_locked);
  }

  @Test
  void cannotDeleteLockedSequence() throws SQLException {
    updateUserSequenceIsLocked(sequenceId, true);

    try {
      deleteUserSequence(sequenceId);
    } catch (SQLException ex) {
      if (!ex.getMessage().contains("Cannot delete locked user sequence.")) {
        throw ex;
      }
    }
  }

  @Test
  void canDeleteUnlockedSequence() throws SQLException {
    final var otherSequenceId = createUserSequence("Test Sequence 3", parcelId, workspaceId, sequenceDefinition, sequenceUser.name());

    assertDoesNotThrow(()->deleteUserSequence(otherSequenceId));
  }
}
