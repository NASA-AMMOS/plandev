package gov.nasa.jpl.aerie.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandExpansionDatabaseTests {
  private DatabaseTestHelper helper;
  private Connection connection;

  private int commandDictionaryId;
  private int parcelId;

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("command_expansion_test", "Command Expansion Database Tests");
    connection = helper.connection();

    try (final var statement = connection.createStatement()) {
      final var insertDictionary = statement.executeQuery(
          //language=sql
          """
        insert into sequencing.command_dictionary (dictionary_path, mission,version)
        values ('test-path','test-mission','1')
        returning id, created_at, updated_at
        """);
      insertDictionary.next();
      commandDictionaryId = insertDictionary.getInt("id");

      final var insertParcel = statement.executeQuery(
          //language=sql
          """
          insert into sequencing.parcel (name, command_dictionary_id)
          values ('test-parcel-name','%s')
          returning id, created_at, updated_at
          """.formatted(Integer.toString(commandDictionaryId)));
      insertParcel.next();
      parcelId = insertParcel.getInt("id");
      }
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("sequencing");
  }

  @Nested
  class ExpansionRuleTriggers {
    @Test
    void shouldModifyUpdatedAtTimeOnUpdate() throws SQLException {
      try (final var statement = connection.createStatement()) {

        final var insertRes = statement.executeQuery(
            //language=sql
            """
            insert into sequencing.expansion_rule (activity_type, expansion_logic,parcel_id)
            values ('test-activity-type', 'test-activity-logic','%s')
            returning id, created_at, updated_at
            """.formatted(Integer.toString(parcelId)));
        insertRes.next();
        final var id = insertRes.getInt("id");
        final var created_at = insertRes.getTimestamp("created_at");
        final var updated_at = insertRes.getTimestamp("updated_at");

        assertEquals(created_at, updated_at);

        final var updateRes = statement.executeQuery(
            //language=sql
            """
            update sequencing.expansion_rule
            set expansion_logic = 'updated-logic'
            where id = %d
            returning created_at, updated_at
            """.formatted(id));
        updateRes.next();
        final var created_at2 = updateRes.getTimestamp("created_at");
        final var updated_at2 = updateRes.getTimestamp("updated_at");

        assertEquals(created_at, created_at2);
        assertNotEquals(updated_at, updated_at2);
        assertNotEquals(created_at2, updated_at2);
      }
    }
  }
}
