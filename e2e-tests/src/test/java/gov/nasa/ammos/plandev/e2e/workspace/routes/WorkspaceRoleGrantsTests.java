package gov.nasa.ammos.plandev.e2e.workspace.routes;

import com.microsoft.playwright.Playwright;
import gov.nasa.ammos.plandev.e2e.types.User;
import gov.nasa.ammos.plandev.e2e.types.workspaces.HasuraRequestFailure;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import gov.nasa.ammos.plandev.e2e.utils.HasuraRequests;
import gov.nasa.ammos.plandev.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_nonOwner;
import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_owner;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for workspace access granted to roles (sequencing.workspace_role_grants).
 *
 * A grant gives members of the role collaborator-equivalent access, as long as the user's active role
 * permits the operation. Membership is read live from the database, so these tests keep using the
 * JWT issued before a membership or grant changes.
 */
@Tag("workspace")
public class WorkspaceRoleGrantsTests {
  // Roles that are not part of the default set, with the workspace permissions of a role that can write
  private static final String opsRole = "rg_mission_ops";
  private static final String scienceRole = "rg_science_team";

  // Assigned a granted role, active role is "user"
  private static final User opsWriter = new User("rg_ops_writer", "user", new String[]{"user"});
  private static final User scienceWriter = new User("rg_science_writer", "user", new String[]{"user"});
  // A user whose only link to the granted role is that it is their default role
  private static final User opsDefault = new User("rg_ops_default", opsRole, new String[]{"viewer"});
  // Assigned a granted role, but their active role (viewer) cannot write
  private static final User opsViewer = new User("rg_ops_viewer", "viewer", new String[]{"viewer"});
  private static final User outsider = new User("rg_outsider", "user", new String[]{"user"});
  private static final User collaborator = new User("rg_collaborator", "user", new String[]{"user"});
  private static final User[] users = {opsWriter, scienceWriter, opsDefault, opsViewer, outsider, collaborator};

  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  private static int cdictId;
  private static int parcelId;
  private static String ownerToken;
  private static String opsWriterToken;
  private static String scienceWriterToken;
  private static String opsDefaultToken;
  private static String opsViewerToken;
  private static String outsiderToken;
  private static String collaboratorToken;

  private int workspaceId;
  private int fileCount = 0;

  @BeforeAll
  static void beforeAll() throws IOException {
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);

    final var writeAndDelete = Json.createObjectBuilder()
                                   .add("list_workspace_contents", "NO_CHECK")
                                   .add("read_file_directory", "NO_CHECK")
                                   .add("write_file_directory", "OWNER_COLLABORATOR")
                                   .add("delete_file_directory", "OWNER_COLLABORATOR")
                                   // Even where a role lets collaborators delete a workspace, a role grant must not
                                   .add("delete_workspace", "OWNER_COLLABORATOR")
                                   .build();
    hasura.createUserRole(opsRole, writeAndDelete);
    hasura.createUserRole(scienceRole, writeAndDelete);

    for (final var user : users) hasura.createUser(user);
    hasura.addUserAllowedRole(opsWriter, opsRole);
    hasura.addUserAllowedRole(scienceWriter, scienceRole);
    hasura.addUserAllowedRole(opsViewer, opsRole);

    try (final var gateway = new GatewayRequests(playwright)) {
      ownerToken = gateway.login(test_owner);
      opsWriterToken = gateway.login(opsWriter);
      scienceWriterToken = gateway.login(scienceWriter);
      opsDefaultToken = gateway.login(opsDefault);
      opsViewerToken = gateway.login(opsViewer);
      outsiderToken = gateway.login(outsider);
      collaboratorToken = gateway.login(collaborator);
    }

    cdictId = hasura.createMockCommandDictionary("Workspace Role Grants Test", "Workspace E2E Test");
    parcelId = hasura.createMockParcel("Workspace Role Grants Parcel", cdictId);
  }

  @AfterAll
  static void afterAll() throws IOException {
    hasura.deleteMockParcel(parcelId);
    hasura.deleteMockCommandDictionary(cdictId);
    for (final var user : users) hasura.deleteUser(user);
    hasura.deleteUserRole(opsRole);
    hasura.deleteUserRole(scienceRole);

    wsServer.close();
    hasura.close();
    playwright.close();
  }

  @BeforeEach
  void createWorkspace() throws IOException {
    workspaceId = wsServer.createWorkspace(test_owner, "wsRoleGrants" + UUID.randomUUID(), parcelId);
  }

  @AfterEach
  void deleteWorkspace() throws IOException {
    wsServer.deleteWorkspace(workspaceId);
  }

  /** Attempt to write a new file, returning the response's status */
  private int write(String token) {
    return wsServer.putFile(token, workspaceId, Path.of("file" + fileCount++ + ".txt"), "contents").status();
  }

  @Test
  void ownerAndCollaboratorKeepAccess() throws IOException {
    hasura.addWorkspaceCollaborator(collaborator, workspaceId);
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(200, write(ownerToken));
    assertEquals(200, write(collaboratorToken));
  }

  @Test
  void noGrantsMeansOnlyOwnerAndCollaborators() throws IOException {
    assertEquals(403, write(opsWriterToken));
    assertEquals(403, write(opsDefaultToken));
    assertEquals(403, write(outsiderToken));
  }

  @Test
  void assignedRoleGrantsAccessWhenNotTheActiveRole() throws IOException {
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(200, write(opsWriterToken));

    final var file = Path.of("deletable.txt");
    assertEquals(200, wsServer.putFile(opsWriterToken, workspaceId, file, "contents").status());
    assertEquals(200, wsServer.deleteFileDirectory(opsWriterToken, workspaceId, file).status());

    assertEquals(403, write(outsiderToken));
  }

  /** A user's default role does not have to be listed in their allowed roles to count as a membership */
  @Test
  void defaultRoleCountsAsMembership() throws IOException {
    assertEquals(403, write(opsDefaultToken));
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(200, write(opsDefaultToken));
  }

  @Test
  void anyGrantedRoleGrantsAccess() throws IOException {
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    hasura.addWorkspaceRoleGrant(workspaceId, scienceRole);
    assertEquals(200, write(opsWriterToken));
    assertEquals(200, write(scienceWriterToken));

    hasura.removeWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(403, write(opsWriterToken));
    assertEquals(200, write(scienceWriterToken));
  }

  @Test
  void readOnlyActiveRoleCannotBypassItsRestrictions() throws IOException {
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(403, write(opsViewerToken));
    assertEquals(200, wsServer.listWorkspaceContents(opsViewerToken, workspaceId).status());
  }

  /** Changes to role membership apply immediately, even to a session started before the change */
  @Test
  void roleMembershipIsLive() throws IOException {
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(403, write(outsiderToken));

    hasura.addUserAllowedRole(outsider, opsRole);
    assertEquals(200, write(outsiderToken));

    hasura.removeUserAllowedRole(outsider, opsRole);
    assertEquals(403, write(outsiderToken));
  }

  @Test
  void revokingTheGrantRevokesAccess() throws IOException {
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(200, write(opsWriterToken));

    hasura.removeWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(403, write(opsWriterToken));
  }

  @Test
  void roleGrantedWriterCannotAdministerTheWorkspace() throws IOException {
    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    assertEquals(200, write(opsWriterToken));

    assertEquals(403, wsServer.deleteWorkspace(opsWriterToken, workspaceId).status());
    assertEquals(403, wsServer.deleteWorkspace(opsDefaultToken, workspaceId).status()); // active role is opsRole
    assertThrows(HasuraRequestFailure.class, () -> hasura.addWorkspaceRoleGrant(opsWriter, workspaceId, scienceRole));
    assertEquals(0, hasura.removeWorkspaceRoleGrant(opsWriter, workspaceId, opsRole));
    assertThrows(HasuraRequestFailure.class, () -> hasura.addWorkspaceCollaborator(opsWriter, outsider, workspaceId));
    assertEquals(200, write(opsWriterToken)); // grant is intact
  }

  @Test
  void onlyOwnersAndAdminsManageGrants() {
    assertDoesNotThrow(() -> hasura.addWorkspaceRoleGrant(test_owner, workspaceId, opsRole));
    assertThrows(HasuraRequestFailure.class, () -> hasura.addWorkspaceRoleGrant(test_nonOwner, workspaceId, scienceRole));
    assertEquals(0, assertDoesNotThrow(() -> hasura.removeWorkspaceRoleGrant(test_nonOwner, workspaceId, opsRole)));
    assertEquals(1, assertDoesNotThrow(() -> hasura.removeWorkspaceRoleGrant(test_owner, workspaceId, opsRole)));
  }

  @Test
  void actionRunsFollowWorkspaceAccess() throws IOException {
    final var actionId = hasura.createActionDefinition("rg-action-" + UUID.randomUUID(), workspaceId);
    assertThrows(HasuraRequestFailure.class, () -> hasura.createActionRun(opsWriter, actionId));

    hasura.addWorkspaceRoleGrant(workspaceId, opsRole);
    final var cancelableRun = assertDoesNotThrow(() -> hasura.createActionRun(opsWriter, actionId));
    final var runAfterRevoke = assertDoesNotThrow(() -> hasura.createActionRun(opsWriter, actionId));
    assertThrows(HasuraRequestFailure.class, () -> hasura.createActionRun(outsider, actionId));

    // Cancelling a run requires the same access as requesting one
    assertEquals(0, assertDoesNotThrow(() -> hasura.cancelActionRun(outsider, cancelableRun)));
    assertEquals(1, assertDoesNotThrow(() -> hasura.cancelActionRun(opsWriter, cancelableRun)));

    hasura.removeWorkspaceRoleGrant(workspaceId, opsRole);
    assertThrows(HasuraRequestFailure.class, () -> hasura.createActionRun(opsWriter, actionId));
    assertEquals(0, assertDoesNotThrow(() -> hasura.cancelActionRun(opsWriter, runAfterRevoke)));
  }
}
