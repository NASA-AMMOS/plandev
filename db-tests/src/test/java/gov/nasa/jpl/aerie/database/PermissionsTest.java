package gov.nasa.jpl.aerie.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PermissionsTest {
  private static final String testRole = "testRole";
  private enum FunctionPermissionKey {
    apply_preset,
    begin_merge,
    branch_plan,
    cancel_merge,
    create_merge_rq,
    create_snapshot,
    commit_merge,
    delete_activity_reanchor,
    delete_activity_reanchor_bulk,
    delete_activity_reanchor_plan,
    delete_activity_reanchor_plan_bulk,
    delete_activity_subtree,
    delete_activity_subtree_bulk,
    deny_merge,
    get_conflicting_activities,
    get_non_conflicting_activities,
    get_plan_history,
    restore_activity_changelog,
    restore_snapshot,
    set_resolution,
    set_resolution_bulk,
    withdraw_merge_rq
  }
  private enum GeneralPermission {
    OWNER, MISSION_MODEL_OWNER, PLAN_OWNER, PLAN_COLLABORATOR, PLAN_OWNER_COLLABORATOR
  }
  private enum MergePermission {
    PLAN_OWNER_SOURCE, PLAN_COLLABORATOR_SOURCE, PLAN_OWNER_COLLABORATOR_SOURCE,
    PLAN_OWNER_TARGET, PLAN_COLLABORATOR_TARGET, PLAN_OWNER_COLLABORATOR_TARGET
  }

  private DatabaseTestHelper helper;
  private MerlinDatabaseTestHelper merlinHelper;

  private Connection connection;
  int fileId;
  int missionModelId;

  @BeforeEach
  void beforeEach() throws SQLException {
    fileId = merlinHelper.insertFileUpload();
    missionModelId = merlinHelper.insertMissionModel(fileId);
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("merlin");
  }

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("aerie_permissions_test", "Database Permissions Tests");
    connection = helper.connection();
    merlinHelper = new MerlinDatabaseTestHelper(connection);
    insertUserRole(testRole);
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  //region Helper Methods
  private String getRole(String session) throws SQLException {
    try(final var statement = connection.createStatement()){
      final var resp = statement.executeQuery(
          //language=sql
          """
          select permissions.get_role('%s'::json)
          """.formatted(session));
      resp.next();
      return resp.getString(1);
    }
  }

  private String getFunctionPermission(String function, String session) throws SQLException {
    try(final var statement = connection.createStatement()){
      final var resp = statement.executeQuery(
          //language=sql
          """
          select permissions.get_function_permissions('%s', '%s'::json)
          """.formatted(function, session));
      resp.next();
      return resp.getString(1);
    }
  }

  private void checkGeneralPermissions(String permission, int planId, String username) throws SQLException {
    checkGeneralPermissions("get_plan_history", permission,planId, username);
  }

  private void checkGeneralPermissions(String function, String permission, int planId, String username) throws SQLException {
    try(final var statement = connection.createStatement()){
      statement.execute(
          //language=sql
          """
          call permissions.check_general_permissions('%s', '%s', %d, '%s')
          """.formatted(function, permission, planId, username));
    }
  }

  private void raiseIfPlanMergePermission(String permission) throws SQLException {
    try(final var statement = connection.createStatement()){
      statement.execute(
          //language=sql
          """
          select permissions.raise_if_plan_merge_permission('get_plan_history', '%s');
          """.formatted(permission));
    }
  }

  private void checkMergePermissions(
      String permission,
      int planIdReceiving,
      int planIdSupplying,
      String username)
  throws SQLException {
    try(final var statement = connection.createStatement()){
      statement.execute(
          //language=sql
          """
          call permissions.check_merge_permissions('create_merge_rq', '%s', %d, %d, '%s')
          """.formatted(permission, planIdReceiving, planIdSupplying, username));
    }
  }

  private void insertUserRole(String role) throws SQLException {
    try(final var statement = connection.createStatement()){
      statement.execute(
        //language=sql
        """
        insert into permissions.user_roles(role, description)
        values ('%s', 'A role created during DBTests');
        """.formatted(role));
    }
  }

  private void updateUserRolePermissions(
      String role,
      String actionPermissionsJson,
      String functionPermissionsJson)
  throws SQLException {
    try(final var statement = connection.createStatement()){
      if(actionPermissionsJson == null){
        statement.execute(
        //language=sql
        """
        update permissions.user_role_permission
        set function_permissions = '%s'::jsonb
        where role = '%s';
        """.formatted(functionPermissionsJson, role));
      } else if (functionPermissionsJson == null) {
        statement.execute(
        //language=sql
        """
        update permissions.user_role_permission
        set action_permissions = '%s'::jsonb
        where role = '%s';
        """.formatted(actionPermissionsJson, role));
      }
      else {
        statement.execute(
        //language=sql
        """
        update permissions.user_role_permission
        set action_permissions = '%s'::jsonb,
            function_permissions = '%s'::jsonb
        where role = '%s';
        """.formatted(actionPermissionsJson, functionPermissionsJson, role));
      }

    }
  }
  //endregion

  @Test
  void testGetRole() throws SQLException {
    // Test empty JSON
    SQLException ex = assertThrows(SQLException.class, () -> getRole("{}"));
    if(!ex.getMessage().contains("Invalid username: <NULL>"))
      throw ex;
    // Test invalid username
    ex = assertThrows(SQLException.class, () -> getRole("{\"x-hasura-user-id\":\"invalid_user\"}"));
        if(!ex.getMessage().contains("Invalid username: invalid_user"))
      throw ex;
    // Test when there is no 'role' header
    assertEquals("aerie_admin", getRole(merlinHelper.admin.session()));
    assertEquals("user", getRole(merlinHelper.user.session()));
    assertEquals("viewer", getRole(merlinHelper.viewer.session()));

    // Test when there is a 'role' header
    assertEquals("demo_role", getRole("{\"x-hasura-role\":\"demo_role\"}"));
  }

  @Nested
  class GetFunctionPermissions {
    @ParameterizedTest
    @EnumSource(FunctionPermissionKey.class)
    void aerieAdminAlwaysReturnsNoCheck(FunctionPermissionKey function) throws SQLException{
      assertEquals("NO_CHECK", getFunctionPermission(function.name(), merlinHelper.admin.session()));
    }

    @Test
    void getFunctionReturnsAssignedValueUser() throws SQLException {
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("apply_preset", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_TARGET", getFunctionPermission("begin_merge", merlinHelper.user.session()));
      assertEquals("NO_CHECK", getFunctionPermission("branch_plan", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_TARGET", getFunctionPermission("cancel_merge", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_SOURCE", getFunctionPermission("create_merge_rq", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("create_snapshot", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_TARGET", getFunctionPermission("commit_merge", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("delete_activity_reanchor", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("delete_activity_reanchor_bulk", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("delete_activity_reanchor_plan", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("delete_activity_reanchor_plan_bulk", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("delete_activity_subtree", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("delete_activity_subtree_bulk", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_TARGET", getFunctionPermission("deny_merge", merlinHelper.user.session()));
      assertEquals("NO_CHECK", getFunctionPermission("get_conflicting_activities", merlinHelper.user.session()));
      assertEquals("NO_CHECK", getFunctionPermission("get_non_conflicting_activities", merlinHelper.user.session()));
      assertEquals("NO_CHECK", getFunctionPermission("get_plan_history", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("restore_activity_changelog", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_COLLABORATOR", getFunctionPermission("restore_snapshot", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_TARGET", getFunctionPermission("set_resolution", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_TARGET", getFunctionPermission("set_resolution_bulk", merlinHelper.user.session()));
      assertEquals("PLAN_OWNER_SOURCE", getFunctionPermission("withdraw_merge_rq", merlinHelper.user.session()));
    }

    @ParameterizedTest
    @EnumSource(FunctionPermissionKey.class)
    void getFunctionReturnsAssignedValuesViewer(FunctionPermissionKey function) throws SQLException {
      if (function.equals(FunctionPermissionKey.get_conflicting_activities)
          || function.equals(FunctionPermissionKey.get_non_conflicting_activities)
          || function.equals(FunctionPermissionKey.get_plan_history)) {
        assertEquals("NO_CHECK", getFunctionPermission(function.name(), merlinHelper.viewer.session()));
      } else {
        // This test bundles subsumes that Missing Keys Throw an InsufficientPrivilege SQL Error
        final SQLException ex = assertThrows(
            SQLException.class,
            () -> getFunctionPermission(function.name(), merlinHelper.viewer.session()));
        if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
            "User with role 'viewer' is not permitted to run '" +function + "'"))
          throw ex;
      }
    }

    @Test
    void invalidFunctionThrowsException() throws SQLException {
      final SQLException ex = assertThrows(SQLException.class, () -> getFunctionPermission("any", merlinHelper.viewer.session()));
      if(!ex.getMessage().contains("invalid input value for enum permissions.function_permission_key: \"any\""))
        throw ex;
    }
  }

  @Nested
  class CheckGeneralPermissions {
    @Test
    void invalidPermissionFails() throws SQLException {
      final int planId = merlinHelper.insertPlan(missionModelId, merlinHelper.admin.name());
      final SQLException ex = assertThrows(SQLException.class, () -> checkGeneralPermissions("any", planId, merlinHelper.admin.name()));
      if(!ex.getMessage().contains("invalid input value for enum permissions.permission: \"any\""))
        throw ex;
    }

    @ParameterizedTest
    @EnumSource(GeneralPermission.class)
    void insufficientPrivilege(GeneralPermission permission) throws SQLException {
      final int planId = merlinHelper.insertPlan(missionModelId, merlinHelper.admin.name());
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkGeneralPermissions(permission.name(), planId, merlinHelper.user.name()));
      if (!ex.getSQLState().equals("42501")) {
        throw ex;
      }
      if (permission.equals(GeneralPermission.MISSION_MODEL_OWNER)) {
        if (!ex.getMessage().contains("Cannot run 'get_plan_history': 'MerlinUser' is not "
                                      + permission + " on Model " + missionModelId)) {
          throw ex;
        }
      } else if (!ex.getMessage().contains("Cannot run 'get_plan_history': 'MerlinUser' is not "
                                           + permission + " on Plan " + planId)) {
        throw ex;
      }
    }

    @ParameterizedTest
    @EnumSource(GeneralPermission.class)
    void sufficientPrivilege(GeneralPermission permission) throws SQLException {
      final int planId;

      // Perform special setup in two cases to ensure minimum permissions
      switch(permission){
        case MISSION_MODEL_OWNER -> {
          final int modelId = merlinHelper.insertMissionModel(fileId, merlinHelper.user.name());
          planId = merlinHelper.insertPlan(modelId, merlinHelper.admin.name());
        }
        case PLAN_COLLABORATOR -> {
          planId = merlinHelper.insertPlan(missionModelId, merlinHelper.admin.name());
          merlinHelper.insertPlanCollaborator(planId, merlinHelper.user.name());
        }
        default -> planId = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      }

      assertDoesNotThrow(() -> checkGeneralPermissions(permission.name(), planId, merlinHelper.user.name()));

      // Check the "both Owner and Collaborator" and "only Collaborator" branches for PLAN_OWNER_COLLABORATOR
      if (permission.equals(GeneralPermission.PLAN_OWNER_COLLABORATOR)){
        merlinHelper.insertPlanCollaborator(planId, merlinHelper.user.name());
        merlinHelper.insertPlanCollaborator(planId, merlinHelper.viewer.name());

        assertDoesNotThrow(() -> checkGeneralPermissions(permission.name(), planId, merlinHelper.user.name()));
        assertDoesNotThrow(() -> checkGeneralPermissions(permission.name(), planId, merlinHelper.viewer.name()));
      }
    }

    @Test
    void specialApplyPresetOwnerPermission() throws SQLException {
      // Set up a custom role and two users with it
      try (final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            insert into permissions.user_roles(role) values ('applyPresetOwner');
            """);
        statement.execute(
            //language=sql
            """
            update permissions.user_role_permission
            set function_permissions = '{"apply_preset": "OWNER"}'
            where role = 'applyPresetOwner';
          """);
      }
      final var userMerlin = merlinHelper.insertUser("Merlin", "applyPresetOwner");
      final var userFalcon = merlinHelper.insertUser("Falcon", "applyPresetOwner");

      merlinHelper.insertActivityType(missionModelId, "test-activity");
      final int merlinPlanId = merlinHelper.insertPlan(missionModelId, userMerlin.name());
      final int falconPlanId = merlinHelper.insertPlan(missionModelId, userFalcon.name());
      final int falconPresetId = merlinHelper.insertPreset(missionModelId, "test_preset", "test-activity", userFalcon.name());

      final int merlinActivityId = merlinHelper.insertActivity(merlinPlanId);
      final int falconActivityId = merlinHelper.insertActivity(falconPlanId);

      // Invalid - Plan Owner, but Not Preset Owner
      SQLException ex = assertThrows(
          SQLException.class,
          () -> merlinHelper.assignPreset(falconPresetId, merlinActivityId, merlinPlanId, userMerlin.session()));
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'apply_preset': '"+ userMerlin.name() +"' is not OWNER on Activity Preset " + falconPresetId)) {
        throw ex;
      }
      // Invalid - Preset Owner, but Not Plan Owner
      ex = assertThrows(
          SQLException.class,
          () -> merlinHelper.assignPreset(falconPresetId, merlinActivityId, merlinPlanId, userFalcon.session()));
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'apply_preset': '" + userFalcon.name() +"' is not OWNER on Plan " + merlinPlanId)) {
        throw ex;
      }
      // Valid - Owner of both Preset and Plan
      assertDoesNotThrow(() -> merlinHelper.assignPreset(falconPresetId, falconActivityId, falconPlanId, userFalcon.session()));

      // Cleanup added role and users
      try (final var statement = connection.createStatement()) {
        statement.execute(
            //language=sql
            """
            delete from permissions.users
            where username = 'Merlin'
            or username = 'Falcon';
            """);
        statement.execute(
            //language=sql
            """
            delete from permissions.user_roles
            where role = 'applyPresetOwner';
            """);
      }
    }
  }

  @Nested
  class RaiseIfPlanMergePermission {
    @Test
    void invalidValueThrows() throws SQLException {
      final SQLException exception = assertThrows(SQLException.class, () -> raiseIfPlanMergePermission("any"));
      if(!exception.getMessage().contains("invalid input value for enum permissions.permission: \"any\""))
        throw exception;
    }

    @Test
    void noCheckDoesntThrow() {
      assertDoesNotThrow(() -> raiseIfPlanMergePermission("NO_CHECK"));
    }

    @ParameterizedTest
    @EnumSource(GeneralPermission.class)
    void nonMergePermissionsDontThrow(GeneralPermission permission) {
      assertDoesNotThrow(() -> raiseIfPlanMergePermission(permission.name()));
    }

    @ParameterizedTest
    @EnumSource(MergePermission.class)
    void mergePermissionsThrow(MergePermission permission) throws SQLException {
      final SQLException exception = assertThrows(SQLException.class, () -> raiseIfPlanMergePermission(permission.name()));
      if(!exception.getMessage().contains(
          "Invalid Permission: The Permission '"+permission+"' may not be applied to function 'get_plan_history'"))
        throw exception;
    }
  }

  @Nested
  class CheckMergePermissions {
    @Test
    void invalidPermissionFails() throws SQLException {
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final SQLException exception = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("any", basePlan, viewerPlan, merlinHelper.user.name()));
      if(!exception.getMessage().contains("invalid input value for enum permissions.permission: \"any\""))
        throw exception;
    }

    // OWNER: The user must be the Owner of both Plans
    @Test
    void testOwner() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());

      // Check - Sufficient Privilege
      assertDoesNotThrow(() -> checkMergePermissions("OWNER", basePlan, userPlan, merlinHelper.user.name()));

      // Check - Insufficient Privilege
      // Only owns Source
      SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("OWNER", basePlan, viewerPlan, merlinHelper.user.name()));
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" +merlinHelper.user.name() + "' is not OWNER on Plan " + viewerPlan)) {
        throw ex;
      }
      // Only owns Target
      ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("OWNER", viewerPlan, basePlan, merlinHelper.user.name()));
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" +merlinHelper.user.name() + "' is not OWNER on Plan " + viewerPlan)) {
        throw ex;
      }
      // Owns neither
      ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("OWNER", basePlan, userPlan, merlinHelper.viewer.name()));
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" +merlinHelper.viewer.name() + "' is not OWNER on Plan " + basePlan)) {
        throw ex;
      }
    }

    // MISSION_MODEL_OWNER: The user must be Owner of the model
    @Test
    void testMissionModelOwner() throws SQLException {
      // Setup
      final int missionModelId = merlinHelper.insertMissionModel(fileId, merlinHelper.user.name());
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());

      // Check - Sufficient Privilege
      assertDoesNotThrow(() -> checkMergePermissions("MISSION_MODEL_OWNER", basePlan, viewerPlan, merlinHelper.user.name()));

      // Check - Insufficient Privilege
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("MISSION_MODEL_OWNER", basePlan, viewerPlan, merlinHelper.viewer.name()));
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
            "Cannot run 'create_merge_rq': '" + merlinHelper.viewer.name() + "' is not MISSION_MODEL_OWNER on Model " +missionModelId))
          throw ex;
    }

    // PLAN_OWNER: The user must be the Owner of either Plan
    @Test
    void testPlanOwner() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "Base Plan");
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name(), "Viewer Plan");
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "User Plan");

      // Check - Sufficient Privilege
      // Owner on Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER", userPlan, viewerPlan, merlinHelper.user.name()));
      // Owner on Target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER", viewerPlan, userPlan, merlinHelper.user.name()));
      // Owner on Both
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER", basePlan, userPlan, merlinHelper.user.name()));

      // Check - Insufficient Privilege
      // Owner on Neither
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_OWNER", basePlan, userPlan, merlinHelper.viewer.name()));
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '"+ merlinHelper.viewer.name() +"' is not PLAN_OWNER on either Plan "
          + basePlan+" (Base Plan) or Plan "+userPlan+" (User Plan)")) {
        throw ex;
      }
    }

    // PLAN_COLLABORATOR:	The user must be a Collaborator of either Plan. The Plan Owner is NOT considered a Collaborator of the Plan
    @Test
    void testPlanCollaborator() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "Base Plan");
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name(), "Viewer Plan");
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "User Plan");

      merlinHelper.insertPlanCollaborator(basePlan, merlinHelper.admin.name());
      merlinHelper.insertPlanCollaborator(viewerPlan, merlinHelper.admin.name());
      merlinHelper.insertPlanCollaborator(viewerPlan, merlinHelper.user.name());

      // Check - Sufficient Privilege
      // Collaborator on Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_COLLABORATOR", viewerPlan, basePlan, merlinHelper.user.name()));
      // Collaborator on Target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_COLLABORATOR", basePlan, viewerPlan, merlinHelper.user.name()));
      // Collaborator on Both
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_COLLABORATOR", basePlan, viewerPlan, merlinHelper.admin.name()));

      // Check - Insufficient Privilege
      // Collaborator on Neither
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_COLLABORATOR", basePlan, userPlan, merlinHelper.user.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.user.name() + "' is not PLAN_COLLABORATOR on either Plan "
          + basePlan + " (Base Plan) or Plan " + userPlan + " (User Plan)")) {
        throw ex;
      }
    }

    // PLAN_OWNER_COLLABORATOR:	The user must be either the Owner or a Collaborator of either Plan
    @Test
    void testPlanOwnerCollaborator() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "Base Plan");
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name(), "Viewer Plan");
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name(), "User Plan");

      merlinHelper.insertPlanCollaborator(basePlan, merlinHelper.user.name());
      merlinHelper.insertPlanCollaborator(basePlan, merlinHelper.viewer.name());

      // Check - Sufficient Privilege
      // Owner on Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR", userPlan, viewerPlan, merlinHelper.user.name()));
      // Collaborator on Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR", basePlan, userPlan, merlinHelper.viewer.name()));
      // Both on Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR", basePlan, viewerPlan, merlinHelper.user.name()));

      // Owner on Target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR", viewerPlan, userPlan, merlinHelper.user.name()));
      // Collaborator on Target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR", userPlan, basePlan, merlinHelper.viewer.name()));
      // Both on Target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR", viewerPlan, basePlan, merlinHelper.user.name()));


      // Check - Insufficient Privilege
      // Neither on neither
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_OWNER_COLLABORATOR", basePlan, userPlan, merlinHelper.admin.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.admin.name() +
          "' is not PLAN_OWNER_COLLABORATOR on either Plan " + basePlan + " (Base Plan) or Plan " + userPlan)) {
        throw ex;
      }
    }

    // PLAN_OWNER_SOURCE:	The user must be the Owner of the Supplying Plan
    @Test
    void testPlanOwnerSource() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());

      // Check - Sufficient Privilege
      // Owns Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_SOURCE", viewerPlan, basePlan, merlinHelper.user.name()));
      // Owns Both
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_SOURCE", userPlan, basePlan, merlinHelper.user.name()));

      // Check - Insufficient Privilege
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_OWNER_SOURCE", viewerPlan, basePlan, merlinHelper.viewer.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.viewer.name() + "' is not PLAN_OWNER on Source Plan " + basePlan)) {
        throw ex;
      }
    }

    // PLAN_COLLABORATOR_SOURCE: The user must be a Collaborator of the Supplying Plan.
    @Test
    void testPlanCollaboratorSource() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      merlinHelper.insertPlanCollaborator(basePlan, merlinHelper.viewer.name());
      merlinHelper.insertPlanCollaborator(viewerPlan, merlinHelper.user.name());

      // Check - Sufficient Privilege
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_COLLABORATOR_SOURCE", viewerPlan, basePlan, merlinHelper.viewer.name()));

      // Check - Insufficient Privilege
      SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_COLLABORATOR_SOURCE", viewerPlan, basePlan, merlinHelper.user.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.user.name()
          + "' is not PLAN_COLLABORATOR on Source Plan " + basePlan)) {
        throw ex;
      }
      ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_COLLABORATOR_SOURCE", userPlan, basePlan, merlinHelper.user.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.user.name()
          + "' is not PLAN_COLLABORATOR on Source Plan " + basePlan)) {
        throw ex;
      }
    }

    // PLAN_OWNER_COLLABORATOR_SOURCE:	The user must be either the Owner or a Collaborator of the Supplying Plan.
    @Test
    void testPlanOwnerCollaboratorSource() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int vPlan2 = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      merlinHelper.insertPlanCollaborator(basePlan, merlinHelper.viewer.name());
      merlinHelper.insertPlanCollaborator(viewerPlan, merlinHelper.user.name());
      merlinHelper.insertPlanCollaborator(userPlan, merlinHelper.user.name());

      // Check - Sufficient Privilege
      // Own Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_SOURCE", viewerPlan, basePlan, merlinHelper.user.name()));
      // Collaborator on Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_SOURCE", viewerPlan, basePlan, merlinHelper.viewer.name()));
      // Both Owner and Collaborator on Source
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_SOURCE", vPlan2, userPlan, merlinHelper.user.name()));

      // Check - Insufficient Privilege
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_SOURCE", userPlan, vPlan2, merlinHelper.user.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.user.name()
          + "' is not PLAN_OWNER_COLLABORATOR on Source Plan " + vPlan2)) {
        throw ex;
      }
    }

    // PLAN_OWNER_TARGET: The user must be the Owner of the Receiving Plan.
    @Test
    void testPlanOwnerTarget() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());

      // Check - Sufficient Privilege
      // Owns Target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_TARGET", viewerPlan, basePlan, merlinHelper.viewer.name()));
      // Owns Both
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_TARGET", userPlan, basePlan, merlinHelper.user.name()));

      // Check - Insufficient Privilege
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_OWNER_TARGET", userPlan, basePlan, merlinHelper.viewer.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.viewer.name()
          + "' is not PLAN_OWNER on Target Plan " + userPlan)) {
        throw ex;
      }
    }

    // PLAN_COLLABORATOR_TARGET: The user must be a Collaborator of the Receiving Plan.
    @Test
    void testPlanCollaboratorTarget() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      merlinHelper.insertPlanCollaborator(basePlan, merlinHelper.viewer.name());
      merlinHelper.insertPlanCollaborator(viewerPlan, merlinHelper.user.name());


      // Check - Sufficient Privilege
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_COLLABORATOR_TARGET", viewerPlan, basePlan, merlinHelper.user.name()));

      // Check - Insufficient Privilege
      SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_COLLABORATOR_TARGET", viewerPlan, basePlan, merlinHelper.viewer.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.viewer.name()
          + "' is not PLAN_COLLABORATOR on Target Plan " + viewerPlan)) {
        throw ex;
      }
      ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_COLLABORATOR_TARGET", userPlan, basePlan, merlinHelper.user.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
           "Cannot run 'create_merge_rq': '" + merlinHelper.user.name()
          + "' is not PLAN_COLLABORATOR on Target Plan " + userPlan)) {
        throw ex;
      }
    }

    // PLAN_OWNER_COLLABORATOR_TARGET: The user must be either the Owner or a Collaborator of the Receiving Plan.
    @Test
    void testPlanOwnerCollaboratorTarget() throws SQLException {
      // Setup
      final int basePlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int viewerPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      final int userPlan = merlinHelper.insertPlan(missionModelId, merlinHelper.user.name());
      final int vPlan2 = merlinHelper.insertPlan(missionModelId, merlinHelper.viewer.name());
      merlinHelper.insertPlanCollaborator(viewerPlan, merlinHelper.user.name());
      merlinHelper.insertPlanCollaborator(vPlan2, merlinHelper.viewer.name());


      // Check - Sufficient Privilege
      // Own target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_TARGET", viewerPlan, basePlan, merlinHelper.viewer.name()));
      // Collaborate on Target
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_TARGET", viewerPlan, basePlan, merlinHelper.user.name()));
      // Both
      assertDoesNotThrow(() -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_TARGET", vPlan2, viewerPlan, merlinHelper.viewer.name()));

      // Check - Insufficient Privilege
      final SQLException ex = assertThrows(
          SQLException.class,
          () -> checkMergePermissions("PLAN_OWNER_COLLABORATOR_TARGET", userPlan, basePlan, merlinHelper.viewer.name())
      );
      if (!ex.getSQLState().equals("42501") || !ex.getMessage().contains(
          "Cannot run 'create_merge_rq': '" + merlinHelper.viewer.name()
          + "' is not PLAN_OWNER_COLLABORATOR on Target Plan " + userPlan)) {
        throw ex;
      }
    }
  }

  @Nested
  class UserRolePermissionsValidation{
    private final String invalidJsonTextErrCode = "22032";
    @Test
    void emptyIsValid() {
      // Action
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, "{}", null));
      // Function
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, null, "{}"));
      // Both
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, "{}", "{}"));
    }

    @Test
    void nonsenseKeys() throws SQLException {
      // Nonsense Values for Keys are caught
      final String actionPermissions = "{\"fake_action_key\": \"NO_CHECK\"}";
      final String functionPermissions = "{\"fake_function_key\": \"NO_CHECK\"}";
      // Action
      final String actionExceptionMsg =
          """
          ERROR: invalid keys in supplied row
            Detail: The following action keys are not valid: fake_action_key
            Hint:
          """.trim();
      final var actionException = assertThrows(SQLException.class,
                                               () -> updateUserRolePermissions(testRole, actionPermissions, null));
      if( !actionException.getMessage().contains(actionExceptionMsg)
         || !actionException.getSQLState().equals(invalidJsonTextErrCode)){
        throw actionException;
      }

      // Function
      final String fnExceptionMsg =
          """
          ERROR: invalid keys in supplied row
            Detail: The following function keys are not valid: fake_function_key
            Hint:
          """.trim();
      final var fnException = assertThrows(SQLException.class,
                                           () -> updateUserRolePermissions(testRole, null, functionPermissions));
      if(!fnException.getMessage().contains(fnExceptionMsg)
         || !fnException.getSQLState().equals(invalidJsonTextErrCode)){
        throw fnException;
      }
      // Both
      final String bothExceptionMsg =
          """
          ERROR: invalid keys in supplied row
            Detail: The following action keys are not valid: fake_action_key
          The following function keys are not valid: fake_function_key
            Hint:
          """.trim();
      final var bothException = assertThrows(SQLException.class,
                                             () -> updateUserRolePermissions(testRole, actionPermissions, functionPermissions));
      if(!bothException.getMessage().contains(bothExceptionMsg)
         || !bothException.getSQLState().equals(invalidJsonTextErrCode)){
        throw bothException;
      }
    }

    @Test
    void nonsensePermissions() throws SQLException {
      // Nonsense Values for Permissions are caught
      final String actionPermissions = "{\"simulate\": \"NONSENSE_PERMISSION\"}";
      final String functionPermissions = "{\"begin_merge\": \"NONSENSE_PERMISSION\"}";
      // Action
      final String actionExceptionMsg =
          """
          ERROR: invalid permissions in supplied row
            Detail: The following action keys have invalid permissions: {simulate: NONSENSE_PERMISSION}
            Hint:
          """.trim();
      final var actionException = assertThrows(SQLException.class,
                                               () -> updateUserRolePermissions(testRole, actionPermissions, null));
      if( !actionException.getMessage().contains(actionExceptionMsg)
         || !actionException.getSQLState().equals(invalidJsonTextErrCode)){
        throw actionException;
      }

      // Function
      final String fnExceptionMsg =
          """
          ERROR: invalid permissions in supplied row
            Detail: The following function keys have invalid permissions: {begin_merge: NONSENSE_PERMISSION}
            Hint:
          """.trim();
      final var fnException = assertThrows(SQLException.class,
                                           () -> updateUserRolePermissions(testRole, null, functionPermissions));
      if(!fnException.getMessage().contains(fnExceptionMsg)
         || !fnException.getSQLState().equals(invalidJsonTextErrCode)){
        throw fnException;
      }
      // Both
      final String bothExceptionMsg =
          """
          ERROR: invalid permissions in supplied row
            Detail: The following action keys have invalid permissions: {simulate: NONSENSE_PERMISSION}
          The following function keys have invalid permissions: {begin_merge: NONSENSE_PERMISSION}
            Hint:
          """.trim();
      final var bothException = assertThrows(SQLException.class,
                                             () -> updateUserRolePermissions(testRole, actionPermissions, functionPermissions));
      if(!bothException.getMessage().contains(bothExceptionMsg)
         || !bothException.getSQLState().equals(invalidJsonTextErrCode)){
        throw bothException;
      }
    }

    @Test
    void invalidPermissions() throws SQLException {
      // Improperly applied Plan Merge Permissions are caught
      final String actionPermissions = "{\"simulate\": \"PLAN_OWNER_SOURCE\"}";
      final String functionPermissions = "{\"apply_preset\": \"PLAN_OWNER_TARGET\"}";
      // Action
      final String actionExceptionMsg =
          """
          ERROR: invalid permissions in supplied row
            Detail: The following action keys may not take plan merge permissions: {simulate: PLAN_OWNER_SOURCE}
            Hint:
          """.trim();
      final var actionException = assertThrows(SQLException.class,
                                               () -> updateUserRolePermissions(testRole, actionPermissions, null));
      if( !actionException.getMessage().contains(actionExceptionMsg)
         || !actionException.getSQLState().equals(invalidJsonTextErrCode)){
        throw actionException;
      }

      // Function
      final String fnExceptionMsg =
          """
          ERROR: invalid permissions in supplied row
            Detail: The following function keys may not take plan merge permissions: {apply_preset: PLAN_OWNER_TARGET}
            Hint:
          """.trim();
      final var fnException = assertThrows(SQLException.class,
                                           () -> updateUserRolePermissions(testRole, null, functionPermissions));
      if(!fnException.getMessage().contains(fnExceptionMsg)
         || !fnException.getSQLState().equals(invalidJsonTextErrCode)){
        throw fnException;
      }
      // Both
      final String bothExceptionMsg =
          """
          ERROR: invalid permissions in supplied row
            Detail: The following action keys may not take plan merge permissions: {simulate: PLAN_OWNER_SOURCE}
          The following function keys may not take plan merge permissions: {apply_preset: PLAN_OWNER_TARGET}
            Hint:
          """.trim();
      final var bothException = assertThrows(SQLException.class,
                                             () -> updateUserRolePermissions(testRole, actionPermissions, functionPermissions));
      if(!bothException.getMessage().contains(bothExceptionMsg)
         || !bothException.getSQLState().equals(invalidJsonTextErrCode)){
        throw bothException;
      }
    }

    @Test
    void incompleteIsValid() {
      // Updates Viewer Role Permissions, which is an incomplete set
      final String actionPermissions = """
      {
        "sequence_seq_json_bulk": "NO_CHECK",
        "resource_samples": "NO_CHECK"
      }
      """;
      final String functionPermissions = """
      {
        "get_conflicting_activities": "NO_CHECK",
        "get_non_conflicting_activities": "NO_CHECK",
        "get_plan_history": "NO_CHECK"
      }
      """;
      // Action
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, actionPermissions, null));
      // Function
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, null, functionPermissions));
      // Both
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, actionPermissions, functionPermissions));
    }

    @Test
    void completeIsValid() {
      // Uses User Role Permissions to check that a complete set is valid
      // and that plan merge permissions on plan merge functions is valid
      final String actionPermissions = """
          {
            "check_constraints": "PLAN_OWNER_COLLABORATOR",
            "create_expansion_rule": "NO_CHECK",
            "create_expansion_set": "NO_CHECK",
            "expand_all_activities": "NO_CHECK",
            "expand_all_templates": "NO_CHECK",
            "assign_activities_by_filter": "NO_CHECK",
            "insert_ext_dataset": "PLAN_OWNER",
            "resource_samples": "NO_CHECK",
            "schedule":"PLAN_OWNER_COLLABORATOR",
            "sequence_seq_json_bulk": "NO_CHECK",
            "simulate":"PLAN_OWNER_COLLABORATOR"
          }
          """;
      final String functionPermissions = """
          {
            "apply_preset": "PLAN_OWNER_COLLABORATOR",
            "begin_merge": "PLAN_OWNER_TARGET",
            "branch_plan": "NO_CHECK",
            "cancel_merge": "PLAN_OWNER_TARGET",
            "commit_merge": "PLAN_OWNER_TARGET",
            "create_merge_rq": "PLAN_OWNER_SOURCE",
            "create_snapshot": "PLAN_OWNER_COLLABORATOR",
            "delete_activity_reanchor": "PLAN_OWNER_COLLABORATOR",
            "delete_activity_reanchor_bulk": "PLAN_OWNER_COLLABORATOR",
            "delete_activity_reanchor_plan": "PLAN_OWNER_COLLABORATOR",
            "delete_activity_reanchor_plan_bulk": "PLAN_OWNER_COLLABORATOR",
            "delete_activity_subtree": "PLAN_OWNER_COLLABORATOR",
            "delete_activity_subtree_bulk": "PLAN_OWNER_COLLABORATOR",
            "deny_merge": "PLAN_OWNER_TARGET",
            "get_conflicting_activities": "NO_CHECK",
            "get_non_conflicting_activities": "NO_CHECK",
            "get_plan_history": "NO_CHECK",
            "restore_activity_changelog": "PLAN_OWNER_COLLABORATOR",
            "restore_snapshot": "PLAN_OWNER_COLLABORATOR",
            "set_resolution": "PLAN_OWNER_TARGET",
            "set_resolution_bulk": "PLAN_OWNER_TARGET",
            "withdraw_merge_rq": "PLAN_OWNER_SOURCE"
          }
          """;
      // Action
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, actionPermissions, null));
      // Function
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, null, functionPermissions));
      // Both
      assertDoesNotThrow(() -> updateUserRolePermissions(testRole, actionPermissions, functionPermissions));
    }
  }
}
