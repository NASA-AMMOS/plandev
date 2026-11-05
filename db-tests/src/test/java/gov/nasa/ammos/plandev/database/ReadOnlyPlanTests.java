package gov.nasa.ammos.plandev.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReadOnlyPlanTests {
  private static DatabaseTestHelper helper;
  private static MerlinDatabaseTestHelper merlinHelper;
  private static Connection connection;

  private static final String errorMessage = "ERROR: Plan %d is marked as Read Only and cannot be edited.";

  @BeforeAll
  static void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("readonly_plan_tests", "Read-Only Plan Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
  }

  @AfterAll
  static void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  private static int modelId;
  private static int planId;
  private static int directiveId;

  @BeforeEach
  void beforeEach() throws SQLException {
    modelId = merlinHelper.insertMissionModel(merlinHelper.insertFileUpload());
    planId = merlinHelper.insertPlan(modelId);
    directiveId = merlinHelper.insertActivity(planId);
    merlinHelper.setPlanReadOnly(planId, true);
    setModelExecutable(modelId, false);
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  //region Helper Methods
  private boolean modelExists(final int modelId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          select
          from merlin.mission_model
          where id = %d;
          """.formatted(modelId));
      return res.next();
    }
  }

  private void setModelExecutable(final int modelId, final boolean executable) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          update merlin.mission_model
          set is_executable = %b
          where id = %d;
          """.formatted(executable, modelId));
    }
  }

  private void updatePlanName(final int planId, final String newName) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          update merlin.plan
          set name = '%s'
          where id = %d;
          """.formatted(newName, planId));
    }
  }

  private void updatePlanDescription(final int planId, final String newDescription) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=sql
          """
          update merlin.plan
          set description = '%s'
          where id = %d;
          """.formatted(newDescription, planId));
    }
  }

  private void updateSimulationArgs(final int planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(
          //language=SQL
          """
          update merlin.simulation
          set arguments = '{}'::jsonb
          where plan_id = %d;
          """.formatted(planId));
    }
  }
  //endregion

  /**
   * Read-only plans cannot have their simulation arguments updated
   */
  @Test
  void cannotChangeSimulationArgs() {
    final var sqlEx = assertThrows(SQLException.class, () -> updateSimulationArgs(planId));
    assertTrue(sqlEx.getMessage().startsWith(errorMessage.formatted(planId)));
  }

  @Nested
  class Directives {
    /**
     * Directives cannot be added to a read-only plan
     */
    @Test
    void cannotAddDirective() {
      final var sqlEx = assertThrows(SQLException.class, () -> merlinHelper.insertActivity(planId));
      assertTrue(sqlEx.getMessage().startsWith(errorMessage.formatted(planId)));
    }

    /**
     * Directives on a read-only plan cannot be modified
     */
    @Test
    void cannotMutateDirective() {
      final var sqlEx = assertThrows(SQLException.class, () -> merlinHelper.updateActivityName("New Name", directiveId, planId));
      assertTrue(sqlEx.getMessage().startsWith(errorMessage.formatted(planId)));
    }

    /**
     * Directives cannot be deleted from a read-only plan
     */
    @Test
    void cannotDeleteDirective() {
      final var sqlEx = assertThrows(SQLException.class, () -> merlinHelper.deleteActivityDirective(planId, directiveId));
      assertTrue(sqlEx.getMessage().startsWith(errorMessage.formatted(planId)));
    }
  }

  @Nested
  class PlanMetadata{
    /**
     * Read-only plans cannot have their start times adjusted
     */
    @Test
    void cannotChangePlanStart() {
      final var sqlEx = assertThrows(SQLException.class, () -> merlinHelper.updatePlanStartTime(planId, "2026-01-01T00:00:00+00"));
      assertTrue(sqlEx.getMessage().startsWith(errorMessage.formatted(planId)));
    }

    /**
     * Read-only plans cannot have their durations adjusted
     */
    @Test
    void cannotChangePlanDuration() {
      final var sqlEx = assertThrows(SQLException.class, () -> merlinHelper.updatePlanDuration(planId, "24:00:00"));
      assertTrue(sqlEx.getMessage().startsWith(errorMessage.formatted(planId)));
    }

    /**
     * Read-only plans can have their names adjusted
     */
    @Test
    void canChangeName() {
      assertDoesNotThrow(() -> updatePlanName(planId, "new plan name"));
    }

    @Test
    void canChangeDescription() {
      assertDoesNotThrow(() -> updatePlanDescription(planId, "new plan description"));
    }
  }

  /**
   * These tests confirm that a plan's mission model is not incorrectly deleted when the plan is.
   */
  @Nested
  class ModelCleanupDelete {
    /**
     * The mission model is NOT deleted if the plan was editable at the time of deletion,
     * regardless of whether the model is executable
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void planEditable(boolean modelExecutable) throws SQLException {
      // Set up test conditions
      setModelExecutable(modelId, modelExecutable);
      merlinHelper.setPlanReadOnly(planId, false);

      // Delete plan
      merlinHelper.deletePlan(planId);

      // Model should still be present
      assertTrue(modelExists(modelId));
    }

    /**
     * The mission model for a read-only plan is NOT deleted if another plan is using it,
     * regardless of whether the model is executable
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedModel(boolean modelExecutable) throws SQLException {
      // Set up test conditions
      merlinHelper.insertPlan(modelId);
      setModelExecutable(modelId, modelExecutable);

      // Delete plan
      merlinHelper.deletePlan(planId);

      // Model should still be present
      assertTrue(modelExists(modelId));
    }

    /**
     * The mission model for a read-only plan is NOT deleted if the model is executable
     */
    @Test
    void executableModel() throws SQLException {
      // Set up test conditions
      setModelExecutable(modelId, true);

      // Delete plan
      merlinHelper.deletePlan(planId);

      // Model should still be present
      assertTrue(modelExists(modelId));
    }

    /**
     * The mission model for a read-only plan is deleted IFF:
     *  - the model is only used by the plan being deleted
     *  - the model isn't executable
     */
    @Test
    void nonExecutableModel() throws SQLException {
      // Delete plan
      merlinHelper.deletePlan(planId);

      // Model should be deleted
      assertFalse(modelExists(modelId));
    }
  }
}
