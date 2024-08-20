package gov.nasa.jpl.aerie.database;

import org.junit.jupiter.api.*;
import org.postgresql.util.PSQLException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerDatabaseTests {
  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_scheduler_test", "Scheduler Database Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
    merlinHelper.insertUser("scheduler db tests");
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  int getSpecificationId(final long planId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          select id from scheduler.scheduling_specification
          where plan_id = %d;
          """.formatted(planId));
      res.next();
      return res.getInt("id");
    }
  }

  List<SpecificationEntry> getPlanSpecification(final int planSpecId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var spec = new ArrayList<SpecificationEntry>();
      final var res = statement.executeQuery(
          //language=sql
          """
          select goal_id, priority
          from scheduler.scheduling_specification_goals
          where specification_id = %d
          order by priority;
          """.formatted(planSpecId));
      while(res.next()){
        spec.add(new SpecificationEntry(res.getInt("goal_id"), res.getInt("priority")));
      }
      return spec;
    }
  }

    List<SpecificationEntry> getModelSpecification(final int modelId) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var spec = new ArrayList<SpecificationEntry>();
      final var res = statement.executeQuery(
          //language=sql
          """
          select goal_id, priority
          from scheduler.scheduling_model_specification_goals
          where model_id = %d
          order by priority;
          """.formatted(modelId));
      while(res.next()){
        spec.add(new SpecificationEntry(res.getInt("goal_id"), res.getInt("priority")));
      }
      return spec;
    }
  }

  int insertGoal() throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          with metadata(id, owner) as (
            insert into scheduler.scheduling_goal_metadata(name, description, owner, updated_by)
            values ('test goal', 'no-op', 'scheduler db tests', 'scheduler db tests')
            returning id, owner
          )
          insert into scheduler.scheduling_goal_definition(goal_id, definition, author)
          select m.id, 'nothing', m.owner
          from metadata m
          returning goal_id as id;
          """);
      res.next();
      return res.getInt("id");
    }
  }

  int insertSchedulingProcedure() throws SQLException {
    var jarId = merlinHelper.insertFileUpload();

    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          with metadata(id, owner) as (
            insert into scheduler.scheduling_goal_metadata(name, description, owner, updated_by)
            values ('test procedure', 'no-op', 'scheduler db tests', 'scheduler db tests')
            returning id, owner
          )
          insert into scheduler.scheduling_goal_definition(goal_id, uploaded_jar_id, type, author)
          select m.id, %d, 'JAR', m.owner
          from metadata m
          returning goal_id as id;
          """.formatted(jarId));
      res.next();
      return res.getInt("id");
    }
  }

  void addGoalToModelSpec(int modelId, int goalId, int priority) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          insert into scheduler.scheduling_model_specification_goals(model_id, goal_id, priority)
          values (%d, %d, %d);
          """.formatted(modelId, goalId, priority));
    }
  }

  void addGoalToPlanSpec(int planSpecId, int goalId, int priority) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.executeUpdate(
          //language=sql
          """
          insert into scheduler.scheduling_specification_goals(specification_id, goal_id, priority)
          values (%d, %d, %d);
          """.formatted(planSpecId, goalId, priority));
    }
  }

  private record SpecificationEntry(int goalId, int priority) {}

  /**
   * Tests the delete trigger on specifications against bulk deletes
   */
  @Nested
  class BulkDeleteFromSpecifications {
    private int modelId;
    private int planSpecId;
    private int[] goalIds;

    @BeforeEach
    void beforeEach() throws SQLException {
      modelId = merlinHelper.insertMissionModel(merlinHelper.insertFileUpload());
      planSpecId = getSpecificationId(merlinHelper.insertPlan(modelId));
      goalIds = new int[20];
      for(int i = 0; i < 20; i++){
        goalIds[i] = insertGoal();
        addGoalToModelSpec(modelId, goalIds[i], i);
        addGoalToPlanSpec(planSpecId, goalIds[i], i);
      }
    }

    @AfterEach
    void afterEach() throws SQLException {
      helper.clearSchema("merlin");
      helper.clearSchema("scheduler");
    }

    // Delete every goal with a priority between [5 and 15], inclusive
    @Test
    void deleteConsecutivePlan() throws SQLException {
      try (final var statement = connection.createStatement()) {
        assertDoesNotThrow(() -> statement.executeUpdate(
          //language=sql
          """
          delete from scheduler.scheduling_specification_goals
          where specification_id = %d
          and priority between 5 and 15;
          """.formatted(planSpecId)));
      }
      final var spec = getPlanSpecification(planSpecId);
      final var expectedSpec = List.of(
          new SpecificationEntry(goalIds[0], 0),
          new SpecificationEntry(goalIds[1], 1),
          new SpecificationEntry(goalIds[2], 2),
          new SpecificationEntry(goalIds[3], 3),
          new SpecificationEntry(goalIds[4], 4),
          new SpecificationEntry(goalIds[16], 5),
          new SpecificationEntry(goalIds[17], 6),
          new SpecificationEntry(goalIds[18], 7),
          new SpecificationEntry(goalIds[19], 8)
      );
      assertEquals(9, spec.size());
      assertEquals(expectedSpec, spec);
    }

    @Test
    void deleteConsecutiveModel() throws SQLException {
      try (final var statement = connection.createStatement()) {
        assertDoesNotThrow(() -> statement.executeUpdate(
          //language=sql
          """
          delete from scheduler.scheduling_model_specification_goals
          where model_id = %d
          and priority between 5 and 15;
          """.formatted(modelId)));
      }
      final var spec = getModelSpecification(modelId);
      final var expectedSpec = List.of(
          new SpecificationEntry(goalIds[0], 0),
          new SpecificationEntry(goalIds[1], 1),
          new SpecificationEntry(goalIds[2], 2),
          new SpecificationEntry(goalIds[3], 3),
          new SpecificationEntry(goalIds[4], 4),
          new SpecificationEntry(goalIds[16], 5),
          new SpecificationEntry(goalIds[17], 6),
          new SpecificationEntry(goalIds[18], 7),
          new SpecificationEntry(goalIds[19], 8)
      );
      assertEquals(9, spec.size());
      assertEquals(expectedSpec, spec);
    }

    // Delete every goal with a priority < 10 and > 15
    @Test
    void deleteGapPlan() throws SQLException {
      try (final var statement = connection.createStatement()) {
        assertDoesNotThrow(() -> statement.executeUpdate(
          //language=sql
          """
          delete from scheduler.scheduling_specification_goals
          where specification_id = %d
          and (priority < 10 or priority > 15);
          """.formatted(planSpecId)));
      }
      final var spec = getPlanSpecification(planSpecId);
      final var expectedSpec = List.of(
          new SpecificationEntry(goalIds[10], 0),
          new SpecificationEntry(goalIds[11], 1),
          new SpecificationEntry(goalIds[12], 2),
          new SpecificationEntry(goalIds[13], 3),
          new SpecificationEntry(goalIds[14], 4),
          new SpecificationEntry(goalIds[15], 5)
      );
      assertEquals(6, spec.size());
      assertEquals(expectedSpec, spec);
    }

    @Test
    void deleteGapModel() throws SQLException {
      try (final var statement = connection.createStatement()) {
        assertDoesNotThrow(() -> statement.executeUpdate(
          //language=sql
          """
          delete from scheduler.scheduling_model_specification_goals
          where model_id = %d
          and (priority < 10 or priority > 15);
          """.formatted(modelId)));
      }
      final var spec = getModelSpecification(modelId);
      final var expectedSpec = List.of(
          new SpecificationEntry(goalIds[10], 0),
          new SpecificationEntry(goalIds[11], 1),
          new SpecificationEntry(goalIds[12], 2),
          new SpecificationEntry(goalIds[13], 3),
          new SpecificationEntry(goalIds[14], 4),
          new SpecificationEntry(goalIds[15], 5)
      );
      assertEquals(6, spec.size());
      assertEquals(expectedSpec, spec);
    }

    // Delete every goal with an odd priority
    @Test
    void deleteAlternatingPlan() throws SQLException {
      try (final var statement = connection.createStatement()) {
        assertDoesNotThrow(() -> statement.executeUpdate(
          //language=sql
          """
          delete from scheduler.scheduling_specification_goals
          where specification_id = %d
          and mod(priority, 2) = 1;
          """.formatted(planSpecId)));
      }
      final var spec = getPlanSpecification(planSpecId);
      final var expectedSpec = List.of(
          new SpecificationEntry(goalIds[0], 0),
          new SpecificationEntry(goalIds[2], 1),
          new SpecificationEntry(goalIds[4], 2),
          new SpecificationEntry(goalIds[6], 3),
          new SpecificationEntry(goalIds[8], 4),
          new SpecificationEntry(goalIds[10], 5),
          new SpecificationEntry(goalIds[12], 6),
          new SpecificationEntry(goalIds[14], 7),
          new SpecificationEntry(goalIds[16], 8),
          new SpecificationEntry(goalIds[18], 9)
      );
      assertEquals(10, spec.size());
      assertEquals(expectedSpec, spec);
    }

    @Test
    void deleteAlternatingModel() throws SQLException {
      try (final var statement = connection.createStatement()) {
        assertDoesNotThrow(() -> statement.executeUpdate(
          //language=sql
          """
          delete from scheduler.scheduling_model_specification_goals
          where model_id = %d
          and mod(priority, 2) = 1;
          """.formatted(modelId)));
      }
      final var spec = getModelSpecification(modelId);
      final var expectedSpec = List.of(
          new SpecificationEntry(goalIds[0], 0),
          new SpecificationEntry(goalIds[2], 1),
          new SpecificationEntry(goalIds[4], 2),
          new SpecificationEntry(goalIds[6], 3),
          new SpecificationEntry(goalIds[8], 4),
          new SpecificationEntry(goalIds[10], 5),
          new SpecificationEntry(goalIds[12], 6),
          new SpecificationEntry(goalIds[14], 7),
          new SpecificationEntry(goalIds[16], 8),
          new SpecificationEntry(goalIds[18], 9)
      );
      assertEquals(10, spec.size());
      assertEquals(expectedSpec, spec);
    }
  }


  @Nested
  class TestSpecificationAndTemplateGoalTriggers {
    int[] specificationIds;
    int[] goalIds;

    @BeforeEach
    void beforeEach() throws SQLException {
      final int modelId = merlinHelper.insertMissionModel(merlinHelper.insertFileUpload());
      specificationIds = new int[]{
          getSpecificationId(merlinHelper.insertPlan(modelId)),
          getSpecificationId(merlinHelper.insertPlan(modelId))
      };
      goalIds = new int[]{insertGoal(), insertGoal(), insertGoal(), insertGoal(), insertGoal(), insertGoal()};
    }

    @AfterEach
    void afterEach() throws SQLException {
      helper.clearSchema("merlin");
      helper.clearSchema("scheduler");
    }

    void insertGoalPriorities(int specOrTemplateIndex, final int[] goalIndices, int[] priorities) throws SQLException {
      for (int i = 0; i < priorities.length; i++) {
        connection.createStatement().executeUpdate(
            //language=sql
            """
            insert into scheduler.scheduling_specification_goals(specification_id, goal_id, priority)
            values (%d, %d, %d);
            """.formatted(specificationIds[specOrTemplateIndex], goalIds[goalIndices[i]], priorities[i]));
      }
    }

    void checkPriorities(int specOrTemplateIndex, int[] goalIdIndices, int[] priorities) throws SQLException {
      assertEquals(goalIdIndices.length, priorities.length);
      for (int i = 0; i < priorities.length; i++) {
        final var res = connection.createStatement().executeQuery(
            //language=sql
            """
            select priority from scheduler.scheduling_specification_goals
            where goal_id = %d and specification_id = %d;
            """.formatted(goalIds[goalIdIndices[i]], specificationIds[specOrTemplateIndex]));
        res.next();
        assertEquals(priorities[i], res.getInt("priority"));
        res.close();
      }
    }

    @Test
    void shouldIncrementPrioritiesOnCollision() throws SQLException {
      // untouched values in table, should be unchanged
      insertGoalPriorities(1, new int[] {0, 1, 2}, new int[]{0, 1, 2});
      checkPriorities(1, new int[]{0, 1, 2}, new int[]{0, 1, 2});

      helper.clearTable("scheduler.scheduling_specification_goals");
      // should cause increments
      insertGoalPriorities(0, new int[] {0, 1, 2}, new int[]{0, 0, 0});
      checkPriorities(0, new int[]{0, 1, 2}, new int[]{2, 1, 0});
    }

    @Test
    void shouldErrorWhenInsertingNegativePriority() {
      assertThrows(SQLException.class, () -> insertGoalPriorities(
          0, new int[] {0, 1, 2}, new int[]{-1}
      ));
    }

    @Test
    void shouldErrorWhenInsertingNonConsecutivePriority() throws SQLException {
      insertGoalPriorities(1, new int[] {0, 1, 2}, new int[]{0, 1, 2});
      assertThrows(SQLException.class, () -> insertGoalPriorities(
          0, new int[] {0, 1, 2}, new int[]{1}
      ));
    }

    private int getSpecificationRevision(int specificationId) throws SQLException {
      final var res = connection.createStatement().executeQuery(
          //language=sql
          """
          select revision from scheduler.scheduling_specification
          where id = %d;
          """.formatted(specificationId));
      res.next();
      return res.getInt("revision");
    }

    @Test
    void shouldIncrementSpecRevisionAfterModifyingGoalSpec() throws SQLException {
      insertGoalPriorities(0, new int[] {0, 1, 2, 3, 4}, new int[]{0, 1, 2, 3, 4});
      final var revisionBefore  = getSpecificationRevision(specificationIds[0]);
      try(final var statement = connection.createStatement()){
        statement.executeUpdate(
            //language=sql
            """
            update scheduler.scheduling_specification_goals
            set goal_revision = 0
            where goal_id = %d;
            """.formatted(goalIds[3]));
      }
      final var revisionAfter  = getSpecificationRevision(specificationIds[0]);
      assertEquals(revisionBefore + 1, revisionAfter);
    }

    @Test
    void shouldReorderPrioritiesOnUpdate() throws SQLException {
      insertGoalPriorities(0, new int[] {0, 1, 2}, new int[]{0, 1, 2});
      insertGoalPriorities(1, new int[] {3, 4, 5}, new int[]{0, 1, 2});

      try(final var statement = connection.createStatement()) {
        // First test lowering a priority
        statement.executeUpdate(
            //language=sql
            """
            update scheduler.scheduling_specification_goals
            set priority = 0
            where specification_id = %d
            and goal_id = %d;
            """.formatted(specificationIds[0], goalIds[2]));
        checkPriorities(0, new int[]{0, 1, 2}, new int[]{1, 2, 0});
        checkPriorities(1, new int[]{3, 4, 5}, new int[]{0, 1, 2});

        /// Next test raising a priority
        statement.executeUpdate(
            //language=sql
            """
            update scheduler.scheduling_specification_goals
            set priority = 2
            where specification_id = %d
            and goal_id = %d;
            """.formatted(specificationIds[0], goalIds[2]));
      }
      checkPriorities(0, new int[] {0, 1, 2}, new int[] {0, 1, 2});
      checkPriorities(1, new int[] {3, 4, 5}, new int[] {0, 1, 2});
    }

    @Test
    void shouldDecrementPrioritiesOnDelete() throws SQLException {
      insertGoalPriorities(0, new int[] {0, 1, 2}, new int[]{0, 1, 2});
      insertGoalPriorities(1, new int[] {3, 4, 5}, new int[]{0, 1, 2});

      try(final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            delete from scheduler.scheduling_specification_goals
            where specification_id = %d
            and goal_id = %d;
            """.formatted(specificationIds[0], goalIds[1]));
      }
      checkPriorities(0, new int[]{0, 2}, new int[]{0, 1});
      checkPriorities(1, new int[]{3, 4, 5}, new int[]{0, 1, 2});
    }

    @Test
    void shouldTriggerMultipleReorders() throws SQLException {
      insertGoalPriorities(0, new int[] {0, 1, 2}, new int[]{0, 1, 2});
      insertGoalPriorities(1, new int[] {3, 4, 5}, new int[]{0, 1, 2});

      try(final var statement=connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            delete from scheduler.scheduling_specification_goals
            where goal_id = %d;
            """.formatted(goalIds[1]));
        statement.executeUpdate(
            //language=sql
            """
            delete from scheduler.scheduling_specification_goals
            where goal_id = %d;
            """.formatted(goalIds[4]));
      }
      checkPriorities( 0, new int[] {0, 2}, new int[] {0, 1});
      checkPriorities(1, new int[] {3, 5}, new int[] {0, 1});
    }

    @Test
    void shouldNotTriggerWhenPriorityIsUnchanged() throws SQLException {
      insertGoalPriorities(1, new int[] {0, 1, 2}, new int[]{0, 1, 2});
      try(final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            update scheduler.scheduling_specification_goals
            set specification_id = %d
            where specification_id = %d;
            """.formatted(specificationIds[0], specificationIds[1]));
      }
      checkPriorities(0, new int[]{0, 1, 2}, new int[]{0, 1, 2});
    }

    @Test
    void shouldGeneratePriorityWhenNull() throws SQLException {
      try(final var statement = connection.createStatement()) {
        statement.executeUpdate(
            //language=sql
            """
            insert into scheduler.scheduling_specification_goals(specification_id, goal_id)
            values (%d, %d);
            """.formatted(specificationIds[0], goalIds[0]));
        checkPriorities(0, new int[] {0}, new int[] {0});
        statement.executeUpdate(
            //language=sql
            """
            insert into scheduler.scheduling_specification_goals(specification_id, goal_id, priority)
            values (%d, %d, null);
            """.formatted(specificationIds[0], goalIds[2]));
        checkPriorities(0, new int[] {0, 2}, new int[] {0, 1});
        statement.executeUpdate(
            //language=sql
            """
            insert into scheduler.scheduling_specification_goals(specification_id, goal_id)
            values (%d, %d);
            """.formatted(specificationIds[0], goalIds[1]));
        checkPriorities(0, new int[] {0, 2, 1}, new int[] {0, 1, 2});
      }
    }
  }

  @Nested
  class ProceduralSchedulingTests {
    int specId;
    int[] goalIds;
    @BeforeEach
    void beforeEach() throws SQLException {
      final int modelId = merlinHelper.insertMissionModel(merlinHelper.insertFileUpload());
      specId = getSpecificationId(merlinHelper.insertPlan(modelId));
      goalIds = new int[]{insertGoal(), insertSchedulingProcedure()};
    }

    @AfterEach
    void afterEach() throws SQLException {
      helper.clearSchema("merlin");
      helper.clearSchema("scheduler");
    }

    @Test
    void testCantPartiallyChangeProcedureToEDSLGoal() throws SQLException {
      try (final var statement = connection.createStatement()) {
        final var exception = assertThrowsExactly(
            PSQLException.class,
            () -> statement.executeUpdate(
              //language=sql
              """
              update scheduler.scheduling_goal_definition
              set type = 'EDSL'
              where goal_id = %d
              """.formatted(goalIds[1])
            )
        );

        assertTrue(exception.getMessage().contains(
            "new row for relation \"scheduling_goal_definition\" violates check constraint \"check_goal_definition_type_consistency\"")
        );
      }
    }

    @Test
    void testCantPartiallyChangeEDSLGoalToProcedure() throws SQLException {
      try (final var statement = connection.createStatement()) {
        final var exception = assertThrowsExactly(
            PSQLException.class,
            () -> statement.executeUpdate(
              //language=sql
              """
              update scheduler.scheduling_goal_definition
              set type = 'JAR'
              where goal_id = %d
              """.formatted(goalIds[0])
            )
        );

        assertTrue(exception.getMessage().contains(
            "new row for relation \"scheduling_goal_definition\" violates check constraint \"check_goal_definition_type_consistency\"")
        );
      }
    }
  }
}
