package gov.nasa.ammos.plandev.database;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings("SqlSourceToSinkFlow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WorkspaceRoleGrantsTests {
  private DatabaseTestHelper helper;
  private Connection connection;
  private int workspaceId;

  @BeforeAll
  void beforeAll() throws SQLException, IOException, InterruptedException {
    helper = new DatabaseTestHelper("workspace_role_grants_tests", "Workspace Role Grants Tests");
    connection = helper.connection();
  }

  @AfterAll
  void afterAll() throws SQLException, IOException, InterruptedException {
    helper.close();
  }

  @BeforeEach
  void beforeEach() throws SQLException {
    execute("insert into sequencing.command_dictionary (dictionary_path, mission, version) values ('p', 'm', 'v')");
    execute("insert into sequencing.parcel (name, command_dictionary_id) values ('parcel', (select max(id) from sequencing.command_dictionary))");
    execute("insert into sequencing.workspace (name, disk_location, parcel_id) values ('ws', '/tmp/ws', (select max(id) from sequencing.parcel))");
    workspaceId = Integer.parseInt(query("select max(id) from sequencing.workspace").getFirst());
  }

  @AfterEach
  void afterEach() throws SQLException {
    helper.clearSchema("sequencing");
    execute("delete from permissions.users");
    execute("delete from permissions.user_roles where role not in ('aerie_admin', 'user', 'viewer')");
  }

  private void execute(String sql) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private List<String> query(String sql) throws SQLException {
    final var results = new ArrayList<String>();
    try (final var statement = connection.createStatement(); final var res = statement.executeQuery(sql)) {
      while (res.next()) results.add(res.getString(1));
    }
    return results;
  }

  private List<String> grantedRoles() throws SQLException {
    return query("select role from sequencing.workspace_role_grants where workspace_id = %d order by role".formatted(workspaceId));
  }

  /**
   * A user belongs to their default role even if it is not one of their allowed roles
   */
  @Test
  void roleMembersIncludeDefaultAndAllowedRoles() throws SQLException {
    execute("insert into permissions.user_roles(role) values ('custom')");
    execute("insert into permissions.users values ('default_only', 'custom'), ('allowed_only', 'user')");
    execute("insert into permissions.users_allowed_roles values ('allowed_only', 'custom'), ('allowed_only', 'user')");

    assertEquals(List.of("default_only"), query("select username from permissions.role_members where role = 'custom' and username like 'default%'"));
    assertEquals(List.of("custom", "user"), query("select role from permissions.role_members where username = 'allowed_only' order by role"));
  }

  @Test
  void grantsMustReferenceAnExistingRole() throws SQLException {
    assertThrows(SQLException.class, () -> execute("insert into sequencing.workspace_role_grants values (%d, 'no_such_role')".formatted(workspaceId)));
  }

  @Test
  void grantsAreRemovedWithTheirRoleOrWorkspace() throws SQLException {
    execute("insert into permissions.user_roles(role) values ('custom')");
    execute("insert into sequencing.workspace_role_grants values (%d, 'custom'), (%d, 'user')".formatted(workspaceId, workspaceId));
    assertEquals(List.of("custom", "user"), grantedRoles());

    execute("delete from permissions.user_roles where role = 'custom'");
    assertEquals(List.of("user"), grantedRoles());

    execute("delete from sequencing.workspace where id = %d".formatted(workspaceId));
    assertEquals(List.of(), grantedRoles());
  }
}
