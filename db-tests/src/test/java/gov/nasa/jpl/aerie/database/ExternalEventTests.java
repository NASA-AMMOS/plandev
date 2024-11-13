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
import java.util.ArrayList;
import java.util.List;

import org.postgresql.util.PSQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExternalEventTests {

  //region Generic Database Testing Setup
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;
  private Connection connection;

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("external_event", "Activity Directive Changelog Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.clearSchema("merlin");
    helper.close();
    connection = null;
    helper = null;
  }
  //endregion


  //region Commonly Repeated Variables
  final static String SOURCE_TYPE = "Test";
  final static String DERIVATION_GROUP = "Test Default";
  final static String EVENT_TYPE = "Test";
  final static String CREATED_AT = "2024-01-01T00:00:00Z";
  final static String EMPTY_ATTRIBUTES = "{}";
  final static String EMPTY_ATTRIBUTE_SCHEMA = "{}";
  //endregion


  //region Records
  protected record ExternalEvent(String key, String event_type_name, String source_key, String derivation_group_name, String start_time, String duration, String attributes) {
    // no attributes
    ExternalEvent(String key, String event_type_name, String source_key, String derivation_group_name, String start_time, String duration) {
      this(key, event_type_name, source_key, derivation_group_name, start_time, duration, "{}");
    }

    ExternalEvent(String key, String start_time, String duration, ExternalSource source) {
      this(key, EVENT_TYPE, source.key(), source.derivation_group_name(), start_time, duration, "{}");
    }
  }
  protected record ExternalSource(String key, String source_type_name, String derivation_group_name, String valid_at, String start_time, String end_time, String created_at){}
  protected record DerivedEvent(String key, String event_type_name, String source_key, String derivation_group_name, String start_time, String duration, String source_range, String valid_at){}
  protected record PlanDerivationGroup(int plan_id, String derivation_group_name, boolean acknowledged, String last_acknowledged_at){}
  //endregion


  //region Helper Functions
  protected void insertExternalEventType(String event_type_name, String attribute_schema) throws SQLException {
    try(final var statement = connection.createStatement()) {
      // create the event type
      statement.executeUpdate(
          // language=sql
          """
          INSERT INTO
            merlin.external_event_type
          VALUES ('%s', '%s');
          """.formatted(event_type_name, attribute_schema)
      );
    }
  }

  protected void insertExternalSourceType(String source_type_name, String attribute_schema) throws SQLException {
    try(final var statement = connection.createStatement()) {
      // create the source type
      statement.executeUpdate(
          // language=sql
          """
          INSERT INTO
            merlin.external_source_type
          VALUES ('%s', '%s');
          """.formatted(source_type_name, attribute_schema)
      );
    }
  }

  protected void insertDerivationGroup(String derivation_group_name, String source_type_name) throws SQLException {
    try(final var statement = connection.createStatement()) {
      // create the derivation group
      statement.executeUpdate(
          // language=sql
          """
          INSERT INTO
            merlin.derivation_group
          VALUES ('%s', '%s');
          """.formatted(derivation_group_name, source_type_name)
      );
    }
  }

  protected void insertExternalSource(ExternalSource externalSource) throws SQLException {
    try(final var statement = connection.createStatement()) {
      System.out.println("STARTING " + externalSource);
      // create the source
      statement.executeUpdate(
          // language=sql
          """
          INSERT INTO
            merlin.external_source
          VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s');
          """.formatted(
              externalSource.key,
              externalSource.source_type_name,
              externalSource.derivation_group_name,
              externalSource.valid_at,
              externalSource.start_time,
              externalSource.end_time,
              externalSource.created_at
          )
      );
      System.out.println("FINISHED " + externalSource);
    }
  }

  protected void insertExternalEvent(ExternalEvent externalEvent) throws SQLException {
    try(final var statement = connection.createStatement()) {
      // create the event
      statement.executeUpdate(
          // language=sql
          """
          INSERT INTO
            merlin.external_event
          VALUES ('%s', '%s', '%s', '%s', '%s', '%s');
          """.formatted(
              externalEvent.key,
              externalEvent.event_type_name,
              externalEvent.source_key,
              externalEvent.derivation_group_name,
              externalEvent.start_time,
              externalEvent.duration
          )
      );
    }
  }

  protected void associateDerivationGroupWithPlan(int planId, String derivationGroupName) throws SQLException {
    try(final var statement = connection.createStatement()) {
      // create the event type
      statement.executeUpdate(
          // language=sql
          """
          INSERT INTO
            merlin.plan_derivation_group
          VALUES ('%s', '%s');
          """.formatted(planId, derivationGroupName)
      );
    }
  }

  /**
   * Get all derived events.
   */
  protected List<DerivedEvent> getDerivedEvents() throws SQLException {
    List<DerivedEvent> results = new ArrayList<>();
    try(final var statement = connection.createStatement()) {
      var res = statement.executeQuery(
          // language=sql
          """
          SELECT * FROM merlin.derived_events;
          """
      );
      while (res.next()) {
        results.add(new DerivedEvent(
            res.getString("event_key"),
            res.getString("event_type_name"),
            res.getString("source_key"),
            res.getString("derivation_group_name"),
            res.getString("start_time"),
            res.getString("DURATION"),
            res.getString("source_range"),
            res.getString("valid_at")
        ));
      }
    }
    return results;
  }

  protected List<DerivedEvent> getDerivedEvents(String dg_name) throws SQLException {
    List<DerivedEvent> results = new ArrayList<>();
    try(final var statement = connection.createStatement()) {
      var res = statement.executeQuery(
          // language=sql
          """
          SELECT * FROM merlin.derived_events
            WHERE derivation_group_name = '%s';
          """.formatted(dg_name)
      );
      while (res.next()) {
        results.add(new DerivedEvent(
            res.getString("event_key"),
            res.getString("event_type_name"),
            res.getString("source_key"),
            res.getString("derivation_group_name"),
            res.getString("start_time"),
            res.getString("DURATION"),
            res.getString("source_range"),
            res.getString("valid_at")
        ));
      }
    }
    return results;
  }

  /**
   * Repeated function that uploads a source and various events, as well as related types.
   * Used in:
   *  - ExternalEventTests.java:
   *    + duplicateSource
   *    + superDerivedEvents
   *    + associateDerivationGroupWithBasePlan
   *
   * Data here is based on the SSMO-MPS/mission-data-sandbox/derivation_test examples.
   *
   * @param dg The derivation group name to use in entries.
   * @param skip_types A boolean that should be set to true to skip uploading event and source types, if this is being
   *                    called twice in a given test.
   */
  protected void upload_source(String dg, boolean skip_types) throws SQLException {
    // First, define the sources.
    ExternalSource sourceOne = new ExternalSource(
        "Derivation_Test_00.json",
        SOURCE_TYPE,
        dg,
        "2024-01-18 00:00:00+00",
        "2024-01-05 00:00:00+00",
        "2024-01-11 00:00:00+00",
        "2024-08-21 22:36:12.858009+00"
    );
    ExternalSource sourceTwo = new ExternalSource(
        "Derivation_Test_01.json",
        SOURCE_TYPE,
        dg,
        "2024-01-19 00:00:00+00",
        "2024-01-01 00:00:00+00",
        "2024-01-07 00:00:00+00",
        "2024-08-21 22:36:19.381275+00"
    );
    ExternalSource sourceThree = new ExternalSource(
        "Derivation_Test_02.json",
        SOURCE_TYPE,
        dg,
        "2024-01-20 00:00:00+00",
        "2024-01-03 00:00:00+00",
        "2024-01-10 00:00:00+00",
        "2024-08-21 22:36:23.340941+00"
    );
    ExternalSource sourceFour = new ExternalSource(
        "Derivation_Test_03.json",
        SOURCE_TYPE,
        dg,
        "2024-01-21 00:00:00+00",
        "2024-01-01 12:00:00+00",
        "2024-01-02 12:00:00+00",
        "2024-08-21 22:36:28.365244+00"
    );

    // Second, define the events, spaced by sources 1-4
    ExternalEvent twoA = new ExternalEvent("2", "DerivationD", "Derivation_Test_00.json", dg, "2024-01-05 23:00:00+00", "01:10:00");
    ExternalEvent seven = new ExternalEvent("7", "DerivationC", "Derivation_Test_00.json", dg, "2024-01-09 23:00:00+00", "02:00:00");
    ExternalEvent eight = new ExternalEvent("8", "DerivationB", "Derivation_Test_00.json", dg, "2024-01-10 11:00:00+00", "01:05:00");

    ExternalEvent one = new ExternalEvent("1", "DerivationA", "Derivation_Test_01.json", dg, "2024-01-01 00:00:00+00", "02:10:00");
    ExternalEvent twoB = new ExternalEvent("2", "DerivationA", "Derivation_Test_01.json", dg, "2024-01-01 12:00:00+00", "02:10:00");
    ExternalEvent three = new ExternalEvent("3", "DerivationB", "Derivation_Test_01.json", dg, "2024-01-02 23:00:00+00", "03:00:00");
    ExternalEvent four = new ExternalEvent("4", "DerivationB", "Derivation_Test_01.json", dg, "2024-01-05 21:00:00+00", "03:00:00");

    ExternalEvent five = new ExternalEvent("5", "DerivationC", "Derivation_Test_02.json", dg, "2024-01-05 23:00:00+00", "01:10:00");
    ExternalEvent six = new ExternalEvent("6", "DerivationC", "Derivation_Test_02.json", dg, "2024-01-06 12:00:00+00", "02:00:00");
    ExternalEvent twoC = new ExternalEvent("2", "DerivationB", "Derivation_Test_02.json", dg, "2024-01-09 11:00:00+00", "01:05:00");

    ExternalEvent nine = new ExternalEvent("9", "DerivationC", "Derivation_Test_03.json", dg, "2024-01-02 00:00:00+00", "01:00:00");

    // Third, insert types; this can be skipped in the case of multiple calls to upload_source in a given test
    if (!skip_types) {
      String[] externalEventTypes = {"DerivationA", "DerivationB", "DerivationC", "DerivationD"};
      for (String eventType : externalEventTypes) {
        insertExternalEventType(eventType, EMPTY_ATTRIBUTE_SCHEMA);
      }
      insertExternalSourceType(SOURCE_TYPE, EMPTY_ATTRIBUTE_SCHEMA);
    }

    // Fourth, insert derivation group
    insertDerivationGroup(dg, SOURCE_TYPE);

    // Then, insert sources
    insertExternalSource(sourceOne);
    insertExternalSource(sourceTwo);
    insertExternalSource(sourceThree);
    insertExternalSource(sourceFour);

    // Finally, insert events
    insertExternalEvent(twoA);
    insertExternalEvent(seven);
    insertExternalEvent(eight);
    insertExternalEvent(one);
    insertExternalEvent(twoB);
    insertExternalEvent(three);
    insertExternalEvent(four);
    insertExternalEvent(five);
    insertExternalEvent(six);
    insertExternalEvent(twoC);
    insertExternalEvent(nine);
  }

  protected void insertStandardTypes() throws SQLException {
    // insert external event type
    insertExternalEventType(EVENT_TYPE, EMPTY_ATTRIBUTE_SCHEMA);

    // insert external source type
    insertExternalSourceType(SOURCE_TYPE, EMPTY_ATTRIBUTE_SCHEMA);

    // insert derivation group
    insertDerivationGroup(DERIVATION_GROUP, SOURCE_TYPE);
  }
  //endregion


  //region Derivation Tests
  /**
   * The focus of this class is to test specifically the derivation function for events. This includes overlapping of
   *    source windows, as well as reconciling differences inside the sources themselves (by testing each of the 4
   *    derivation rules).
   */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class DerivedEventsTests {

    /**
     * This class focuses on testing sources' windows to verify that derivation will work as expected. These are
     *    separate, extracted tests that don't really evaluate events and instead just that sources play together
     *    correctly. As such we test "empty" sources to make sure their overlapped windows work correctly.
     * We add events that span a very short DURATION simply so that the sources show up in derived_events, but we aren't
     *    testing any attributes of said events.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DerivedSourcesTests {

      // Commonly Repeated:
      final static String DURATION = "00:00:00.000001";

      @BeforeEach
      void beforeEach() throws SQLException {
        // insert generic external event type, source type, and derivation group
        insertStandardTypes();
      }

      /**
       * The first test is a basic, non-overlapping case. We must ensure that gaps are preserved:
       *
       *    A:             ++++++++
       *    B:  +++++++
       *        BBBBBBB    AAAAAAAA
       */
      @Test
      void testSparseCoverage() throws SQLException {
        // create our sources and their per-window events
        ExternalSource a = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T03:00:00Z",
            "2024-01-01T04:00:00Z",
            CREATED_AT
        );
        ExternalEvent aE = new ExternalEvent(a.key() + "_event", SOURCE_TYPE, a.key(),
                                             DERIVATION_GROUP, a.start_time(), DURATION);
        ExternalSource b = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T01:00:00Z",
            "2024-01-01T02:00:00Z",
            CREATED_AT
        );
        ExternalEvent bE = new ExternalEvent(b.key() + "_event", SOURCE_TYPE, b.key(),
                                             DERIVATION_GROUP, b.start_time(), DURATION);

        // verify the ranges are as expected
        // insert sources
        insertExternalSource(a);
        insertExternalSource(b);

        // insert events
        insertExternalEvent(aE);
        insertExternalEvent(bE);

        var results = getDerivedEvents();

        // both ranges should only have a single element and be fully present
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "A_event",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 03:00:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 03:00:00+00\",\"2024-01-01 04:00:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "B_event",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 01:00:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 01:00:00+00\",\"2024-01-01 02:00:00+00\")}",
                "2024-01-02 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      /**
       * This test is an overlapping case wherein the source of higher precedence (the more recently valid one - B)
       *    should shorten the range that A applies over. That is, a source is succeeded before in time by a more valid
       *    source.
       *
       *    A:     ++++++++
       *    B:  +++++++
       *        BBBBBBBAAAA
       */
      @Test
      void testForwardOverlap() throws SQLException {
        // create our sources and their per-window events
        ExternalSource a = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            "2024-01-01T02:00:00Z",
            CREATED_AT
        );
        ExternalEvent aE = new ExternalEvent(
            a.key() + "_event",
            SOURCE_TYPE,
            a.key(),
            DERIVATION_GROUP,
            "2024-01-01T1:10:00Z",
            DURATION
        );
        ExternalSource b = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );
        ExternalEvent bE = new ExternalEvent(b.key() + "_event", SOURCE_TYPE, b.key(),
                                             DERIVATION_GROUP, b.start_time(), DURATION);

        // verify the ranges are as expected
        // insert sources
        insertExternalSource(a);
        insertExternalSource(b);

        // insert events
        insertExternalEvent(aE);
        insertExternalEvent(bE);

        var results = getDerivedEvents();

        // both ranges should only have a single element and be fully present
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "A_event",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 01:10:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 01:00:00+00\",\"2024-01-01 02:00:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "B_event",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:00:00+00\")}",
                "2024-01-02 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      /**
       * This test is an overlapping case wherein the source of higher precedence (the more recently valid one - B)
       *    should shorten the range that A applies over. In this case, a source is succeeded after in time by a more
       *    valid source.
       *
       *    A:  +++++++
       *    B:     ++++++++
       *        AAABBBBBBBB
       */
      @Test
      void testBackwardOverlap() throws SQLException {
        // create our sources and their per-window events
        ExternalSource a = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );
        // have to manually pick this
        ExternalEvent aE = new ExternalEvent(
            a.key() + "_event",
            SOURCE_TYPE,
            a.key(),
            DERIVATION_GROUP,
            a.start_time(),
            DURATION
        );
        ExternalSource b = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:30:00Z",
            "2024-01-01T01:30:00Z",
            CREATED_AT
        );
        ExternalEvent bE = new ExternalEvent(b.key() + "_event", SOURCE_TYPE, b.key(),
                                             DERIVATION_GROUP, b.start_time(), DURATION);

        // verify the ranges are as expected
        // insert sources
        insertExternalSource(a);
        insertExternalSource(b);

        // insert events
        insertExternalEvent(aE);
        insertExternalEvent(bE);

        var results = getDerivedEvents();

        // both ranges should only have a single element and be fully present
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "A_event",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 00:30:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "B_event",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:30:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:30:00+00\",\"2024-01-01 01:30:00+00\")}",
                "2024-01-02 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      /**
       * This test is an overlapping case with three sources. The least recent source (of least precedence, source A)
       *    covers a larger, more inclusive interval. A is succeeded by other, smaller sources (B, C), such that the
       *    range over which A applies is chopped into several subintervals:
       *
       *    A:      +++++++++++++++++++++
       *    B:  ++++++
       *    C:                +++++++
       *        BBBBBBAAAAAAAACCCCCCCAAAA
       */
      @Test
      void testBackground() throws SQLException {
        // create our sources and their per-window events
        ExternalSource a = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            "2024-01-01T03:00:00Z",
            CREATED_AT
        );
        // just need 1 that shows up and source range will still show correctly
        ExternalEvent aE = new ExternalEvent(
            a.key() + "_event",
            SOURCE_TYPE,
            a.key(),
            DERIVATION_GROUP,
            "2024-01-01T01:10:00Z",
            DURATION
        );
        ExternalSource b = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );
        ExternalEvent bE = new ExternalEvent(b.key() + "_event", SOURCE_TYPE, b.key(),
                                             DERIVATION_GROUP, b.start_time(), DURATION);
        ExternalSource c = new ExternalSource(
            "C",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-03T00:00:00Z",
            "2024-01-01T01:30:00Z",
            "2024-01-01T02:00:00Z",
            CREATED_AT
        );
        ExternalEvent cE = new ExternalEvent(c.key() + "_event", SOURCE_TYPE, c.key(),
                                             DERIVATION_GROUP, c.start_time(), DURATION);

        // verify the ranges are as expected
        // insert sources
        insertExternalSource(a);
        insertExternalSource(b);
        insertExternalSource(c);

        // insert events
        insertExternalEvent(aE);
        insertExternalEvent(bE);
        insertExternalEvent(cE);

        var results = getDerivedEvents();

        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "A_event",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 01:10:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 01:00:00+00\",\"2024-01-01 01:30:00+00\"),[\"2024-01-01 02:00:00+00\",\"2024-01-01 03:00:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "B_event",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:00:00+00\")}",
                "2024-01-02 00:00:00+00"
            ),
            new DerivedEvent(
                "C_event",
                SOURCE_TYPE,
                "C",
                DERIVATION_GROUP,
                "2024-01-01 01:30:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 01:30:00+00\",\"2024-01-01 02:00:00+00\")}",
                "2024-01-03 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      /**
       * The first 4 tests are exhaustive of source window cases. This final test, then, is included as an overall,
       *    cumulative case:
       *    A:     ++++++++++++
       *    B:  ++++++
       *    C:                   ++++++
       *    D:    +++++++
       *    E:               +++++++
       *    F:      +++
       *    G:             +
       *        BBDDFFFDDAAGAEEEEEEECCC
       */
      @Test
      void testAmalgamation() throws SQLException {
        // create our sources and their per-window events
        ExternalSource a = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:03:00Z",
            "2024-01-01T00:15:00Z",
            CREATED_AT
        );
        ExternalEvent aE = new ExternalEvent(
            a.key() + "_event",
            SOURCE_TYPE,
            a.key(),
            DERIVATION_GROUP,
            "2024-01-01T00:09:10Z",
            DURATION
        );
        ExternalSource b = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:06:00Z",
            CREATED_AT
        );
        ExternalEvent bE = new ExternalEvent(b.key() + "_event", SOURCE_TYPE, b.key(),
                                             DERIVATION_GROUP, b.start_time(), DURATION);
        ExternalSource c = new ExternalSource(
            "C",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-03T00:00:00Z",
            "2024-01-01T00:17:00Z",
            "2024-01-01T00:23:00Z",
            CREATED_AT
        );
        ExternalEvent cE = new ExternalEvent(
            c.key() + "_event",
            SOURCE_TYPE,
            c.key(),
            DERIVATION_GROUP,
            "2024-01-01T00:21:00Z",
            DURATION
        );
        ExternalSource d = new ExternalSource(
            "D",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-04T00:00:00Z",
            "2024-01-01T00:02:00Z",
            "2024-01-01T00:09:00Z",
            CREATED_AT
        );
        ExternalEvent dE = new ExternalEvent(d.key() + "_event", SOURCE_TYPE, d.key(),
                                             DERIVATION_GROUP, d.start_time(), DURATION);
        ExternalSource e = new ExternalSource(
            "E",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-05T00:00:00Z",
            "2024-01-01T00:13:00Z",
            "2024-01-01T00:20:00Z",
            CREATED_AT
        );
        ExternalEvent eE = new ExternalEvent(e.key() + "_event", SOURCE_TYPE, e.key(),
                                             DERIVATION_GROUP, e.start_time(), DURATION);
        ExternalSource f = new ExternalSource(
            "F",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-06T00:00:00Z",
            "2024-01-01T00:04:00Z",
            "2024-01-01T00:07:00Z",
            CREATED_AT
        );
        ExternalEvent fE = new ExternalEvent(f.key() + "_event", SOURCE_TYPE, f.key(),
                                             DERIVATION_GROUP, f.start_time(), DURATION);
        ExternalSource g = new ExternalSource(
            "G",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-07T00:00:00Z",
            "2024-01-01T00:11:00Z",
            "2024-01-01T00:12:00Z",
            CREATED_AT
        );
        ExternalEvent gE = new ExternalEvent(g.key() + "_event", SOURCE_TYPE, g.key(),
                                             DERIVATION_GROUP, g.start_time(), DURATION);

        // verify the ranges are as expected
        // insert sources
        insertExternalSource(a);
        insertExternalSource(b);
        insertExternalSource(c);
        insertExternalSource(d);
        insertExternalSource(e);
        insertExternalSource(f);
        insertExternalSource(g);

        // insert events
        insertExternalEvent(aE);
        insertExternalEvent(bE);
        insertExternalEvent(cE);
        insertExternalEvent(dE);
        insertExternalEvent(eE);
        insertExternalEvent(fE);
        insertExternalEvent(gE);

        var results = getDerivedEvents();

        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "A_event",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 00:09:10+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:09:00+00\",\"2024-01-01 00:11:00+00\"),[\"2024-01-01 00:12:00+00\",\"2024-01-01 00:13:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "B_event",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 00:02:00+00\")}",
                "2024-01-02 00:00:00+00"
            ),
            new DerivedEvent(
                "C_event",
                SOURCE_TYPE,
                "C",
                DERIVATION_GROUP,
                "2024-01-01 00:21:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:20:00+00\",\"2024-01-01 00:23:00+00\")}",
                "2024-01-03 00:00:00+00"
            ),
            new DerivedEvent(
                "D_event",
                SOURCE_TYPE,
                "D",
                DERIVATION_GROUP,
                "2024-01-01 00:02:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:02:00+00\",\"2024-01-01 00:04:00+00\"),[\"2024-01-01 00:07:00+00\",\"2024-01-01 00:09:00+00\")}",
                "2024-01-04 00:00:00+00"
            ),
            new DerivedEvent(
                "E_event",
                SOURCE_TYPE, "E",
                DERIVATION_GROUP, "2024-01-01 00:13:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:13:00+00\",\"2024-01-01 00:20:00+00\")}",
                "2024-01-05 00:00:00+00"
            ),
            new DerivedEvent(
                "F_event",
                SOURCE_TYPE, "F",
                DERIVATION_GROUP, "2024-01-01 00:04:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:04:00+00\",\"2024-01-01 00:07:00+00\")}",
                "2024-01-06 00:00:00+00"
            ),
            new DerivedEvent(
                "G_event",
                SOURCE_TYPE, "G",
                DERIVATION_GROUP, "2024-01-01 00:11:00+00",
                "00:00:00.000001",
                "{[\"2024-01-01 00:11:00+00\",\"2024-01-01 00:12:00+00\")}",
                "2024-01-07 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }
    }

    /**
     * This class focuses on testing each of the four rules involved in the derivation operation. To do so, new sources
     *    and events are created, and their presence and intervals are tested to ensure correctness.
     * The rules are as follows. Refer to the docs for more details on why they are what they are:
     *    1. An External Event superseded by nothing will be present in the final, derived result.
     *    2. An External Event partially superseded by a later External Source, but whose start time occurs before
     *          the start of said External Source(s), will be present in the final, derived result.
     *    3. An External Event whose start is superseded by another External Source, even if its end occurs after the
     *          end of said External Source, will be replaced by the contents of that External Source (whether they are
     *          blank spaces, or other events).
     *    4. An External Event who shares a key with an External Event in a later External Source will always be
     *          replaced.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DerivedEventRuleTests {

      // Commonly Repeated:
      @BeforeEach
      void beforeEach() throws SQLException {
        // insert generic external event type, source type, and derivation group
        insertStandardTypes();
      }

      ////////////////////////// RULE 1 //////////////////////////

      /**
       * A solitary event shouldn't be superseded by anything.
       *
       * A:   aa
       */
      @Test
      void rule1_solitary() throws SQLException {
        // insert the event(s) (and their source(s))
        ExternalSource eS = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );

        ExternalEvent e = new ExternalEvent("A.1", "2024-01-01T00:00:00Z", "01:00:00", eS);


        // insert sources
        insertExternalSource(eS);

        // insert events
        insertExternalEvent(e);

        // ensure the result has the right size and keys
        final var results = getDerivedEvents();

        // the range should only have one element
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "A.1",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "01:00:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:00:00+00\")}",
                "2024-01-01 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      /**
       * An event that is in a partially overlapped source but outside of the overlap is not superseded, even if
       * the other source "bookends" it (i.e. the end of that source meets the event's start time).
       *
       * A:     +++++aa++++++
       * B:  bb+++
       * C:                 +cc++++
       */
      @Test
      void rule1_bookended() throws SQLException {
        // insert the event(s) (and their source(s))
        ExternalSource A = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            "2024-01-01T02:00:00Z",
            CREATED_AT
        );
        ExternalSource B = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );
        ExternalSource C = new ExternalSource(
            "C",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-03T00:00:00Z",
            "2024-01-01T01:30:00Z",
            "2024-01-01T03:00:00Z",
            CREATED_AT
        );

        ExternalEvent e = new ExternalEvent("a", "2024-01-01T01:10:00Z", "00:10:00", A);
        ExternalEvent before = new ExternalEvent("b", "2024-01-01T00:00:00Z", "00:30:00", B);
        ExternalEvent after = new ExternalEvent("c", "2024-01-01T01:30:00Z", "01:00:00", C);


        // insert sources
        insertExternalSource(A);
        insertExternalSource(B);
        insertExternalSource(C);

        // insert events
        insertExternalEvent(e);
        insertExternalEvent(before);
        insertExternalEvent(after);

        // verify the expected keys are included
        final var results = getDerivedEvents();

        // the ranges should only have a single element and be fully present
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "a",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 01:10:00+00",
                "00:10:00",
                "{[\"2024-01-01 01:00:00+00\",\"2024-01-01 01:30:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "b",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:30:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:00:00+00\")}",
                "2024-01-02 00:00:00+00"
            ),
            new DerivedEvent(
                "c",
                SOURCE_TYPE,
                "C",
                DERIVATION_GROUP,
                "2024-01-01 01:30:00+00",
                "01:00:00",
                "{[\"2024-01-01 01:30:00+00\",\"2024-01-01 03:00:00+00\")}",
                "2024-01-03 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      ////////////////////////// RULE 2 //////////////////////////

      /**
       * An event that starts before a source that partially overlaps it is not superseded by the overlapping
       * source.
       *
       * A:  +++aaaaa
       * B:      b+bb++++
       * (a and both b's should be in result)
       */
      @Test
      void rule2() throws SQLException {
        // insert the event(s) (and their source(s))
        ExternalSource A = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );
        ExternalSource B = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:30:00Z",
            "2024-01-01T01:30:00Z",
            CREATED_AT
        );

        // spills into B
        ExternalEvent e = new ExternalEvent("a", "2024-01-01T00:25:00Z", "00:10:00", A);
        ExternalEvent b1 = new ExternalEvent("b1", "2024-01-01T00:30:00Z", "00:10:00", B);
        ExternalEvent b2 = new ExternalEvent("b2", "2024-01-01T00:45:00Z", "00:10:00", B);


        // insert sources
        insertExternalSource(A);
        insertExternalSource(B);

        // insert events
        insertExternalEvent(e);
        insertExternalEvent(b1);
        insertExternalEvent(b2);

        // verify the expected keys
        final var results = getDerivedEvents();

        // all ranges should only have a single element and be fully present
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "a",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 00:25:00+00",
                "00:10:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 00:30:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "b1",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:30:00+00",
                "00:10:00",
                "{[\"2024-01-01 00:30:00+00\",\"2024-01-01 01:30:00+00\")}",
                "2024-01-02 00:00:00+00"
            ),
            new DerivedEvent(
                "b2",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:45:00+00",
                "00:10:00",
                "{[\"2024-01-01 00:30:00+00\",\"2024-01-01 01:30:00+00\")}",
                "2024-01-02 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      ////////////////////////// RULE 3 //////////////////////////

      /**
       * An event whose start time is overlapped by a newer source will be superseded by the newer source.
       * This holds even if the new source is empty at the overlapped event's start time.
       *
       * A:    +a+aaaaa
       * B:  b+bb++++
       * (only b's should be in result)
       */
      @Test
      void rule3_basic() throws SQLException {
        // insert the event(s) (and their source(s))
        ExternalSource A = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            "2024-01-01T01:30:00Z",
            CREATED_AT
        );
        ExternalSource B = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );

        // negated by B, very clearly
        ExternalEvent e1 = new ExternalEvent(
            "a1",
            "2024-01-01T00:40:00Z",
            "00:10:00",
            A
        );
        // even empty space in B neg should negate
        ExternalEvent e2 = new ExternalEvent(
            "a2",
            "2024-01-01T00:55:00Z",
            "00:35:00",
            A
        );
        ExternalEvent b1 = new ExternalEvent("b1", "2024-01-01T00:00:00Z", "00:10:00", B);
        ExternalEvent b2 = new ExternalEvent("b2", "2024-01-01T00:30:00Z", "00:20:00", B);


        // insert sources
        insertExternalSource(A);
        insertExternalSource(B);

        // insert events
        insertExternalEvent(e1);
        insertExternalEvent(e2);
        insertExternalEvent(b1);
        insertExternalEvent(b2);

        // verify the expected keys
        final var results = getDerivedEvents();

        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "b1",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:10:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:00:00+00\")}",
                "2024-01-02 00:00:00+00"
            ),
            new DerivedEvent(
                "b2",
                SOURCE_TYPE,
                "B",
                DERIVATION_GROUP,
                "2024-01-01 00:30:00+00",
                "00:20:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:00:00+00\")}",
                "2024-01-02 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }

      /**
       * A completely empty source will still supercede earlier sources.
       *
       * A:    +a+aaaaa
       * B:  ++++++++
       * (empty result)
       */
      @Test
      void rule3_empty() throws SQLException {
        // insert the event(s) (and their source(s))
        ExternalSource A = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            "2024-01-01T01:30:00Z",
            CREATED_AT
        );
        ExternalSource B = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );

        // negated by empty space
        ExternalEvent e1 = new ExternalEvent(
            "a1",
            "2024-01-01T00:40:00Z",
            "00:10:00",
            A
        );
        // negated by empty space
        ExternalEvent e2 = new ExternalEvent(
            "a2",
            "2024-01-01T00:55:00Z",
            "00:35:00",
            A
        );


        // insert sources
        insertExternalSource(A);

        // insert events
        insertExternalEvent(e1);
        insertExternalEvent(e2);

        // insert B as a source
        insertExternalSource(B);

        // verify expected keys (none)
        final var results = getDerivedEvents();

        assertEquals(0, results.size());
      }

      ////////////////////////// RULE 4 //////////////////////////

      /**
       * An event that appears in a later source will replace its occurrence in an earlier source, even if the sources
       * don't overlap.
       *
       * A:                   ++++aaa+++++
       * B:                                  +++++aaaaa+
       * C:   +++++aaaa+++++
       * (one A, of specific DURATION)
       */
      @Test
      void rule4() throws SQLException {
        // insert the event(s) (and their source(s))
        ExternalSource A = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:30:00Z",
            "2024-01-01T02:30:00Z",
            CREATED_AT
        );
        ExternalSource B = new ExternalSource(
            "B",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-02T00:00:00Z",
            "2024-01-01T03:00:00Z",
            "2024-01-01T04:00:00Z",
            CREATED_AT
        );
        ExternalSource C = new ExternalSource(
            "C",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-03T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:00:00Z",
            CREATED_AT
        );

        // negated by empty space
        ExternalEvent e1 = new ExternalEvent(
            "a",
            "2024-01-01T01:50:00Z",
            "00:10:00",
            A
        );
        // negated by empty space
        ExternalEvent e2 = new ExternalEvent(
            "a",
            "2024-01-01T03:40:00Z",
            "00:15:00",
            B
        );
        // negated by empty space
        ExternalEvent e3 = new ExternalEvent(
            "a",
            "2024-01-01T00:30:00Z",
            "00:20:00",
            C
        );

        // insert sources
        insertExternalSource(A);
        insertExternalSource(B);
        insertExternalSource(C);

        // insert events
        insertExternalEvent(e1);
        insertExternalEvent(e2);
        insertExternalEvent(e3);

        // verify expected keys
        final var results = getDerivedEvents();

        // this range should only have a single element
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "a",
                SOURCE_TYPE,
                "C",
                DERIVATION_GROUP,
                "2024-01-01 00:30:00+00",
                "00:20:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:00:00+00\")}",
                "2024-01-03 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }
    }

    /**
     * This class separates a few tests that don't test a particular rule, but rather general attributes of the derivation
     *    process.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GeneralDerivationTests {
      // GENERAL
      /**
       *  An event that overlaps another event in the same source does not supersede the first event.
       */
      @Test
      void nEventsAtSameTime() throws SQLException {
        // construct the sources and events
        ExternalSource A = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T01:30:00Z",
            CREATED_AT
        );

        ExternalEvent e1 = new ExternalEvent("a", "2024-01-01T00:00:00Z", "00:10:00", A);
        ExternalEvent e2 = new ExternalEvent("b", "2024-01-01T00:00:00Z", "00:05:00", A);
        ExternalEvent e3 = new ExternalEvent("c", "2024-01-01T00:00:00Z", "00:15:00", A);

        // insert generic external event type, source type, and derivation group
        insertStandardTypes();

        // insert sources
        insertExternalSource(A);

        // insert events
        insertExternalEvent(e1);
        insertExternalEvent(e2);
        insertExternalEvent(e3);

        // all 3 keys should be present!
        var results = getDerivedEvents();

        // all ranges should only have a single element and be fully present
        final List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "a",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:10:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:30:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "b",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:05:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:30:00+00\")}",
                "2024-01-01 00:00:00+00"
            ),
            new DerivedEvent(
                "c",
                SOURCE_TYPE,
                "A",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "00:15:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 01:30:00+00\")}",
                "2024-01-01 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }


      /**
       * This is a comprehensive test for derived events that manages several derivation groups (which entails
       *    repeating basicDerivedEvents twice but the second round bears a new DG name. Then, we verify that there is
       *    no overlap! Note that this test is effectively vacuous but is a good sanity check.).
       */
      @Test
      void superDerivedEvents() throws SQLException {
        // upload all source data, but twice (using different dgs, to prove non overlap in derivation)
        String dg2 = DERIVATION_GROUP + "_2";

        // upload the data once for the first derivation group
        upload_source(DERIVATION_GROUP, false);

        // repeat with the second derivation group
        upload_source(dg2, true);

        // check that derived events in our prewritten case has the correct keys
        // verify everything is present
        var results = getDerivedEvents();

        List<DerivedEvent> expectedResults = List.of(
            new DerivedEvent(
                "8",
                "DerivationB",
                "Derivation_Test_00.json", // note - the same source name can be used across different derivation groups
                DERIVATION_GROUP + "_2",
                "2024-01-10 11:00:00+00",
                "01:05:00",
                "{[\"2024-01-10 00:00:00+00\",\"2024-01-11 00:00:00+00\")}",
                "2024-01-18 00:00:00+00"
            ),
            new DerivedEvent(
                "8",
                "DerivationB",
                "Derivation_Test_00.json",
                DERIVATION_GROUP,
                "2024-01-10 11:00:00+00",
                "01:05:00",
                "{[\"2024-01-10 00:00:00+00\",\"2024-01-11 00:00:00+00\")}",
                "2024-01-18 00:00:00+00"
            ),
            new DerivedEvent(
                "3",
                "DerivationB",
                "Derivation_Test_01.json",
                DERIVATION_GROUP + "_2",
                "2024-01-02 23:00:00+00",
                "03:00:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 12:00:00+00\"),[\"2024-01-02 12:00:00+00\",\"2024-01-03 00:00:00+00\")}",
                "2024-01-19 00:00:00+00"
            ),
            new DerivedEvent(
                "1",
                "DerivationA",
                "Derivation_Test_01.json",
                DERIVATION_GROUP + "_2",
                "2024-01-01 00:00:00+00",
                "02:10:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 12:00:00+00\"),[\"2024-01-02 12:00:00+00\",\"2024-01-03 00:00:00+00\")}",
                "2024-01-19 00:00:00+00"
            ),
            new DerivedEvent(
                "1",
                "DerivationA",
                "Derivation_Test_01.json",
                DERIVATION_GROUP,
                "2024-01-01 00:00:00+00",
                "02:10:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 12:00:00+00\"),[\"2024-01-02 12:00:00+00\",\"2024-01-03 00:00:00+00\")}",
                "2024-01-19 00:00:00+00"
            ),
            new DerivedEvent(
                "3",
                "DerivationB",
                "Derivation_Test_01.json",
                DERIVATION_GROUP,
                "2024-01-02 23:00:00+00",
                "03:00:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 12:00:00+00\"),[\"2024-01-02 12:00:00+00\",\"2024-01-03 00:00:00+00\")}",
                "2024-01-19 00:00:00+00"
            ),
            new DerivedEvent(
                "5",
                "DerivationC",
                "Derivation_Test_02.json",
                DERIVATION_GROUP + "_2",
                "2024-01-05 23:00:00+00",
                "01:10:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "6",
                "DerivationC",
                "Derivation_Test_02.json",
                DERIVATION_GROUP + "_2",
                "2024-01-06 12:00:00+00",
                "02:00:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "2",
                "DerivationB",
                "Derivation_Test_02.json",
                DERIVATION_GROUP + "_2",
                "2024-01-09 11:00:00+00",
                "01:05:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "5",
                "DerivationC",
                "Derivation_Test_02.json",
                DERIVATION_GROUP,
                "2024-01-05 23:00:00+00",
                "01:10:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "6",
                "DerivationC",
                "Derivation_Test_02.json",
                DERIVATION_GROUP,
                "2024-01-06 12:00:00+00",
                "02:00:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "2",
                "DerivationB",
                "Derivation_Test_02.json",
                DERIVATION_GROUP,
                "2024-01-09 11:00:00+00",
                "01:05:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "9",
                "DerivationC",
                "Derivation_Test_03.json",
                DERIVATION_GROUP + "_2",
                "2024-01-02 00:00:00+00",
                "01:00:00",
                "{[\"2024-01-01 12:00:00+00\",\"2024-01-02 12:00:00+00\")}",
                "2024-01-21 00:00:00+00"
            ),
            new DerivedEvent(
                "9",
                "DerivationC",
                "Derivation_Test_03.json",
                DERIVATION_GROUP,
                "2024-01-02 00:00:00+00",
                "01:00:00",
                "{[\"2024-01-01 12:00:00+00\",\"2024-01-02 12:00:00+00\")}",
                "2024-01-21 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));

        // verify for a given DERIVATION_GROUP expected keys are correct, no overlap inside DERIVATION_GROUP
        results = getDerivedEvents(dg2);

        expectedResults = List.of(
            new DerivedEvent(
                "1",
                "DerivationA",
                "Derivation_Test_01.json",
                DERIVATION_GROUP + "_2",
                "2024-01-01 00:00:00+00",
                "02:10:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 12:00:00+00\"),[\"2024-01-02 12:00:00+00\",\"2024-01-03 00:00:00+00\")}",
                "2024-01-19 00:00:00+00"
            ),
            new DerivedEvent(
                "9",
                "DerivationC",
                "Derivation_Test_03.json",
                DERIVATION_GROUP + "_2",
                "2024-01-02 00:00:00+00",
                "01:00:00",
                "{[\"2024-01-01 12:00:00+00\",\"2024-01-02 12:00:00+00\")}",
                "2024-01-21 00:00:00+00"
            ),
            new DerivedEvent(
                "3",
                "DerivationB",
                "Derivation_Test_01.json",
                DERIVATION_GROUP + "_2",
                "2024-01-02 23:00:00+00",
                "03:00:00",
                "{[\"2024-01-01 00:00:00+00\",\"2024-01-01 12:00:00+00\"),[\"2024-01-02 12:00:00+00\",\"2024-01-03 00:00:00+00\")}",
                "2024-01-19 00:00:00+00"
            ),
            new DerivedEvent(
                "5",
                "DerivationC",
                "Derivation_Test_02.json",
                DERIVATION_GROUP + "_2",
                "2024-01-05 23:00:00+00",
                "01:10:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "6",
                "DerivationC",
                "Derivation_Test_02.json",
                DERIVATION_GROUP + "_2",
                "2024-01-06 12:00:00+00",
                "02:00:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "2",
                "DerivationB",
                "Derivation_Test_02.json",
                DERIVATION_GROUP + "_2",
                "2024-01-09 11:00:00+00",
                "01:05:00",
                "{[\"2024-01-03 00:00:00+00\",\"2024-01-10 00:00:00+00\")}",
                "2024-01-20 00:00:00+00"
            ),
            new DerivedEvent(
                "8",
                "DerivationB",
                "Derivation_Test_00.json",
                DERIVATION_GROUP + "_2",
                "2024-01-10 11:00:00+00",
                "01:05:00",
                "{[\"2024-01-10 00:00:00+00\",\"2024-01-11 00:00:00+00\")}",
                "2024-01-18 00:00:00+00"
            )
        );
        assertEquals(expectedResults.size(), results.size());
        assertTrue(results.containsAll(expectedResults));
      }
    }
  }
  //endregion


  //region Constraint Tests
  /**
   * The focus of this class is to provide a final set of tests to test primary and significant constraints to prove
   *    that they work and how they work. These constraints are baked into the schema, so extensive tests of foreign or
   *    unique keys are not excluded except for common use cases/easily made mistakes, as PSQL guarantees functionality
   *    for us.
   * We seek to demonstrate in this class, and verify as a sanity check.
   */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class ConstraintTests {

    /**
     * A source's end time must be greater than its start time.
     */
    @Test
    void endTimeGEstartTime() throws SQLException {
      // construct the sources
      ExternalSource endBeforeStart = new ExternalSource(
          "A",
          SOURCE_TYPE,
          DERIVATION_GROUP,
          "2024-01-01T00:00:00Z",
          "2024-01-01T01:00:00Z",
          "2024-01-01T00:30:00Z",
          CREATED_AT
      );
      ExternalSource endMatchesStart = new ExternalSource(
          "A",
          SOURCE_TYPE,
          DERIVATION_GROUP,
          "2024-01-01T00:00:00Z",
          "2024-01-01T01:00:00Z",
          "2024-01-01T01:00:00Z",
          CREATED_AT
      );
      ExternalSource succeeding = new ExternalSource(
          "A",
          SOURCE_TYPE,
          DERIVATION_GROUP,
          "2024-01-01T00:00:00Z",
          "2024-01-01T01:00:00Z",
          "2024-01-01T01:00:00.000001Z",
          CREATED_AT
      );

      // add source type and derivation group
      // create the source type
      insertExternalSourceType(endBeforeStart.source_type_name(), EMPTY_ATTRIBUTE_SCHEMA);

      // create the derivation_group
      insertDerivationGroup(endBeforeStart.derivation_group_name(), endBeforeStart.source_type_name());

      // if start time > end time, error
      final SQLException ex = assertThrows(PSQLException.class, () -> insertExternalSource(endBeforeStart));
      if (!ex.getSQLState().equals("23514")
          && !ex.getMessage().contains("new row for relation \"external_source\" violates check constraint "
                                       + "\"external_source_check\"")) {
        throw ex;
      }

      // if start time = end time, error
      final SQLException ex2 = assertThrows(PSQLException.class, () -> insertExternalSource(endMatchesStart));
      if (!ex2.getSQLState().equals("23514")
          && !ex2.getMessage().contains("new row for relation \"external_source\" violates check constraint "
                                       + "\"external_source_check\"")) {
        throw ex2;
      }

      // else, no error
      assertDoesNotThrow(() -> insertExternalSource(succeeding));
    }

    /**
     * An event must be completely within the bounds of its source.
     *
     *    Source :      +++++++++++++++
     *    Before1: 1111
     *    Before2:    2222
     *    After1 :                   3333
     *    After2 :                      4444
     */
    @Test
    void externalEventSourceBounds() throws SQLException {
      // create sources and events
      ExternalSource A = new ExternalSource(
          "A",
          SOURCE_TYPE,
          DERIVATION_GROUP,
          "2024-01-01T00:00:00Z",
          "2024-01-01T01:00:00Z",
          "2024-01-01T02:00:00Z",
          CREATED_AT);

      // legal.
      ExternalEvent legal = new ExternalEvent("a", "2024-01-01T01:00:00Z", "00:10:00", A);

      // illegal!
      ExternalEvent completelyBefore = new ExternalEvent("completelyBefore", "2024-01-01T00:00:00Z", "00:10:00", A);
      ExternalEvent beforeIntersect = new ExternalEvent("beforeIntersect", "2024-01-01T00:55:00Z", "00:25:00", A);
      ExternalEvent afterIntersect = new ExternalEvent("afterIntersect", "2024-01-01T01:45:00Z", "00:30:00", A);
      ExternalEvent completelyAfter = new ExternalEvent("completelyAfter", "2024-01-01T02:10:00Z", "00:15:00", A);

      // assert the legal event is okay (in the center of the source)
      // insert generic external event type, source type, and derivation group
      insertStandardTypes();

      // insert sources
      insertExternalSource(A);

      // insert events
      insertExternalEvent(legal);

      // assert out of bounds failures
      final SQLException ex = assertThrows(PSQLException.class, () -> insertExternalEvent(completelyBefore));
      if (!ex.getMessage().contains("Event " + completelyBefore.key + " out of bounds of source " + A.key + ".")) {
        throw ex;
      }

      final SQLException ex2 = assertThrows(PSQLException.class, () -> insertExternalEvent(beforeIntersect));
      if (!ex2.getMessage().contains("Event " + beforeIntersect.key + " out of bounds of source " + A.key + ".")) {
        throw ex2;
      }

      final SQLException ex3 = assertThrows(PSQLException.class, () -> insertExternalEvent(afterIntersect));
      if (!ex3.getMessage().contains("Event " + afterIntersect.key + " out of bounds of source " + A.key + ".")) {
        throw ex3;
      }

      final SQLException ex4 = assertThrows(PSQLException.class, () -> insertExternalEvent(completelyAfter));
      if (!ex4.getMessage().contains("Event " + completelyAfter.key + " out of bounds of source " + A.key + ".")) {
        throw ex4;
      }
    }

    /**
     * Source names must be unique within a derivation group; they can repeat across derivation groups.
     */
    @Test
    void duplicateSource() throws SQLException {
      // same name and DERIVATION_GROUP
      ExternalSource failing = new ExternalSource(
          "Derivation_Test_00.json",
          SOURCE_TYPE,
          DERIVATION_GROUP,
          "2024-01-18 00:00:00+00",
          "2024-01-05 00:00:00+00",
          "2024-01-11 00:00:00+00",
          CREATED_AT
      );
      // same name, diff DERIVATION_GROUP
      ExternalSource succeeding = new ExternalSource(
          "Derivation_Test_00.json",
          SOURCE_TYPE,
          DERIVATION_GROUP + "_2",
          "2024-01-18 00:00:00+00",
          "2024-01-05 00:00:00+00",
          "2024-01-11 00:00:00+00",
          CREATED_AT
      );

      // upload general data
      upload_source(DERIVATION_GROUP, false);

      // upload a conflicting source (same name in a given DERIVATION_GROUP)
      final SQLException ex = assertThrows(PSQLException.class, () -> insertExternalSource(failing));
      if (!ex.getSQLState().equals("23505")
          && !ex.getMessage().contains("duplicate key value violates unique constraint \"external_source_pkey\"")) {
        throw ex;
      }

      // upload a non-conflicting source (same name in a different DERIVATION_GROUP)
      insertDerivationGroup(DERIVATION_GROUP + "_2", SOURCE_TYPE);
      insertExternalSource(succeeding);
    }

    /**
     * It is impossible to delete a derivation group if there are any sources that are a part of it.
     */
    @Test
    void deleteDGwithRemainingSource() throws SQLException {
      try(final var statement = connection.createStatement()) {

        // create the source
        ExternalSource src = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            CREATED_AT
        );

        // insert the source and all relevant groups and types
        // create a source type
        insertExternalSourceType(SOURCE_TYPE, EMPTY_ATTRIBUTE_SCHEMA);

        // create a Derivation Group
        insertDerivationGroup(DERIVATION_GROUP, SOURCE_TYPE);

        // create a source
        insertExternalSource(src);

        // delete the DG (expect error)
        final SQLException ex = assertThrows(PSQLException.class,
            () -> statement.executeUpdate(
              // language=sql
              """
              DELETE FROM merlin.derivation_group WHERE name='%s';
              """.formatted(DERIVATION_GROUP)
            )
        );
        if (!ex.getSQLState().equals("23503") &&
            !ex.getMessage().contains(
                "update or delete on table \"derivation_group\" violates foreign key constraint "
                + "\"external_source_type_matches_derivation_group\" on table \"external_source\"")
        ) {
          throw ex;
        }
      }
    }

    /**
     * An external source's type MUST match the derivation group's source type.
     */
    @Test
    void externalSourceTypeMatchDerivationGroup() throws SQLException {
      // create a source that matches the derivation group
      ExternalSource src = new ExternalSource(
          "A",
          SOURCE_TYPE,
          DERIVATION_GROUP,
          "2024-01-01T00:00:00Z",
          "2024-01-01T00:00:00Z",
          "2024-01-01T00:30:00Z",
          CREATED_AT
      );

      // add types
      // create a source type
      insertExternalSourceType(SOURCE_TYPE, EMPTY_ATTRIBUTE_SCHEMA);

      // create a Derivation Group
      insertDerivationGroup(DERIVATION_GROUP, SOURCE_TYPE);

      // insert the source
      insertExternalSource(src);

      // create a source that doesn't match the derivation group (the source type has "_B" appended to it)
      ExternalSource src_2 = new ExternalSource(
          "B",
          SOURCE_TYPE + "_B",
          DERIVATION_GROUP,
          "2024-01-01T00:00:00Z",
          "2024-01-01T00:00:00Z",
          "2024-01-01T00:30:00Z",
          CREATED_AT
      );

      // insert the erroneous source (expect error)
      final SQLException ex = assertThrows(
        SQLException.class,
        () -> insertExternalSource(src_2)
      );
      if (!ex.getSQLState().equals("23503") &&
          !ex.getMessage().contains(
              "ERROR: External source " + src_2.key + " is being added to a derivation group " +
              src_2.derivation_group_name + " where its type " + src_2.source_type_name +
              " does not match the derivation group type " + src.source_type_name + ".")
      ) {
        throw ex;
      }
    }

    /**
     * It is not be possible to delete a source type with a source still associated.
     */
    @Test
    void deleteSourceTypeWithRemainingSource() throws SQLException {
      try(final var statement = connection.createStatement()) {

        // create source
        ExternalSource src = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            CREATED_AT
        );

        // add types
        // create a source type
        insertExternalSourceType(SOURCE_TYPE, EMPTY_ATTRIBUTE_SCHEMA);

        // create a Derivation Group
        insertDerivationGroup(DERIVATION_GROUP, SOURCE_TYPE);

        // create a source
        insertExternalSource(src);

        // delete the source type (expect error)
        final SQLException ex = assertThrows(PSQLException.class, () -> statement.executeUpdate(
            // language=sql
            """
            DELETE FROM merlin.external_source_type WHERE name='%s';
            """.formatted(SOURCE_TYPE)
          )
        );
        if (!ex.getSQLState().equals("23503")
            && !ex.getMessage().contains(
                "update or delete on table \"external_source_type\" violates foreign key constraint "
                + "\"derivation_group_references_external_source_type\" on table \"derivation_group\"")
        ) {
          throw ex;
        }
      }
    }

    /**
     * It is not be possible to delete an event type with an event still associated.
     */
    @Test
    void deleteEventTypeWithRemainingEvent() throws SQLException {
      try (final var statement = connection.createStatement()) {

        // create source and event
        ExternalSource src = new ExternalSource(
            "A",
            SOURCE_TYPE,
            DERIVATION_GROUP,
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:00:00Z",
            "2024-01-01T00:30:00Z",
            CREATED_AT
        );
        ExternalEvent evt = new ExternalEvent("A_1", "2024-01-01T00:00:00Z", "00:05:0", src);

        // insert the event and her types
        // insert generic external event type, source type, and derivation group
        insertStandardTypes();

        // insert sources
        insertExternalSource(src);

        // insert events
        insertExternalEvent(evt);

        // delete the event type (expect error)
        final SQLException ex = assertThrows(PSQLException.class,
          () -> statement.executeUpdate(
              // language=sql
              """
              DELETE FROM merlin.external_event_type WHERE name='%s';
              """.formatted(EVENT_TYPE)
          )
        );
        if (!ex.getSQLState().equals("23503")
            && !ex.getMessage().contains(
                "update or delete on table \"external_event_type\" violates foreign key constraint "
                + "\"external_event_references_event_type_name\" on table \"external_event\"")
        ) {
          throw ex;
        }
      }
    }
  }
  //endregion


  //region Derivation Group Association Tests
  /**
   * The following test Derivation Group/Plan Association behavior.
   */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class PlanDerivationGroupTests {
    List<PlanDerivationGroup> getPlanDerivationGroupAssociations() throws SQLException {
      List<PlanDerivationGroup> results = new ArrayList<>();

      try (final var statement = connection.createStatement()) {
        // create the event type
        final var res = statement.executeQuery(
            // language=sql
            """
                SELECT * FROM merlin.plan_derivation_group ORDER BY plan_id, derivation_group_name;
                """
        );

        while (res.next()) {
          results.add(
              new PlanDerivationGroup(
                  res.getInt("plan_id"),
                  res.getString("derivation_group_name"),
                  res.getBoolean("acknowledged"),
                  res.getString("last_acknowledged_at")
              )
          );
        }
      }

      return results;
    }

    void assertEqualsAsideFromLastAcknowledged(List<PlanDerivationGroup> expected, List<PlanDerivationGroup> actual) {
      assertEquals(expected.size(), actual.size());
      for (int i = 0; i < actual.size(); i++) {
        assertEquals(expected.get(i).plan_id(), actual.get(i).plan_id());
        assertEquals(expected.get(i).derivation_group_name(), actual.get(i).derivation_group_name());
        assertEquals(expected.get(i).acknowledged(), actual.get(i).acknowledged());
      }
    }

    /**
     * This test ensures that a derivation group can be associated with a plan, specifically checking that when the
     *    "acknowledged" field is set, "last_acknowledged_at" is not updated, but no other derivation groups or plans
     *    (and their associations are affected)
     */
    @Test
    void associateDerivationGroupWithBasePlan() throws SQLException {
      // upload a mission model
      final int fileId = merlinHelper.insertFileUpload();
      final int missionModelId = merlinHelper.insertMissionModel(fileId);

      // upload derivation group A and B
      String derivationGroupNameControl = "Control";
      String derivationGroupNameDelta = "Delta";
      upload_source(derivationGroupNameControl, false);
      upload_source(derivationGroupNameDelta, true);

      // create plan "control", as well as one that would face an update to an associated derivation group "delta"
      final int planIdControl = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "control");
      final int planIdDelta = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "delta");

      // associate the two derivation groups with it
      assertDoesNotThrow(() -> associateDerivationGroupWithPlan(planIdControl, derivationGroupNameControl));
      assertDoesNotThrow(() -> associateDerivationGroupWithPlan(planIdDelta, derivationGroupNameControl));
      assertDoesNotThrow(() -> associateDerivationGroupWithPlan(planIdDelta, derivationGroupNameDelta));

      // check that acknowledged is true for all entries, and save the last_acknowledged_at field results
      List<PlanDerivationGroup> expectedResultsInitial = List.of(
          new PlanDerivationGroup(planIdControl, derivationGroupNameControl, true, ""),
          new PlanDerivationGroup(planIdDelta, derivationGroupNameControl, true, ""),
          new PlanDerivationGroup(planIdDelta, derivationGroupNameDelta, true, "")
      );
      final List<PlanDerivationGroup> actualResultsInitial = getPlanDerivationGroupAssociations();

      // first, check other attributes
      assertEqualsAsideFromLastAcknowledged(expectedResultsInitial, actualResultsInitial);

      // insert a source to the changing derivation group
      insertExternalSource(
          new ExternalSource(
              "A.json",
              SOURCE_TYPE,
              derivationGroupNameDelta,
              "2024-01-19 00:00:01+00",
              "2024-01-01 00:00:00+00",
              "2024-01-07 00:00:00+00",
              CREATED_AT
          )
      );

      // check that acknowledged is now false for all non control (delta only) entries, true otherwise
      List<PlanDerivationGroup> expectedResults = List.of(
          new PlanDerivationGroup(planIdControl, derivationGroupNameControl, true, actualResultsInitial.getFirst()
                                                                                                       .last_acknowledged_at()),
          new PlanDerivationGroup(planIdDelta, derivationGroupNameControl, true, actualResultsInitial.get(1)
                                                                                                        .last_acknowledged_at()),
          new PlanDerivationGroup(planIdDelta, derivationGroupNameDelta, false, actualResultsInitial.get(2)
                                                                                                    .last_acknowledged_at())
      );
      final List<PlanDerivationGroup> actualResults = getPlanDerivationGroupAssociations();

      // first, check other attributes
      assertEquals(expectedResults.size(), actualResults.size());
      assertTrue(actualResults.containsAll(expectedResults));

      // final bit - update acknowledged to true for (planIdDelta, derivationGroupNameDelta) pair, see that
      //    last_acknowledged_at updates
      try (final var statement = connection.createStatement()) {
        // update acknowledged field for (planIdDelta, derivationGroupNameDelta) pair
        statement.executeUpdate(
            // language=sql
            """
                UPDATE merlin.plan_derivation_group
                  SET acknowledged = true
                  WHERE (plan_id, derivation_group_name) = (%d, '%s');
                """.formatted(planIdDelta, derivationGroupNameDelta)
        );
      }

      final List<PlanDerivationGroup> postUpdateResults = getPlanDerivationGroupAssociations();

      // check timestamps
      assertEquals(postUpdateResults.getFirst(), actualResultsInitial.getFirst());
      assertEquals(postUpdateResults.get(1), actualResultsInitial.get(1));

      // comparing strings. The post update last_acknowledged_at should be later than the pre update one.
      assertTrue(postUpdateResults.get(2).last_acknowledged_at().compareTo(
          actualResultsInitial.get(2).last_acknowledged_at()) > 0);

      // check the other attributes
      assertEqualsAsideFromLastAcknowledged(actualResultsInitial, postUpdateResults);
    }
  }
  //endregion
}
