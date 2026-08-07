package gov.nasa.jpl.aerie.e2e.workspace.routes;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.e2e.E2ETestSuite.test_nonOwner;
import static gov.nasa.jpl.aerie.e2e.E2ETestSuite.test_viewer;
import static gov.nasa.jpl.aerie.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Named.named;

/**
 * Tests for the /ws/create and /ws/{workspace_id} routes
 *
 * Tests that have yet to be implemented are disabled
 */
@Tag("workspace")
public class WorkspaceManagementRoutesTests {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  // Class-Wide Data
  private static int cdictId;
  private static int parcelId;

  private static String nonOwnerToken;
  private static String viewerToken;

  @BeforeAll
  static void beforeAllWorkspaceTests() throws IOException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);

    // Get valid JWT tokens for the users
    try (final var gateway = new GatewayRequests(playwright)) {
      nonOwnerToken = gateway.login(test_nonOwner);
      viewerToken = gateway.login(test_viewer);
    }

    // Set up parcel and dictionary to use across the tests
    cdictId = hasura.createMockCommandDictionary("WorkspaceBindingsTest", "Workspace E2E Test");
    parcelId = hasura.createMockParcel("Workspace Bindings Parcel", cdictId);
  }

  @AfterAll
  static void afterAllWorkspaceTests() throws IOException {
    // Cleanup parcel and dictionary
    hasura.deleteMockCommandDictionary(cdictId);
    hasura.deleteMockParcel(parcelId);

    // Cleanup Requests
    wsServer.close();
    hasura.close();
    playwright.close();
  }

  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @Nested
  class CreateWorkspace {
    /**
     * The workspace server returns a 403 Forbidden response when a user with insufficient role privileges
     * attempts to create a workspace
     */
    @Test
    void forbiddenInsufficientPrivileges() {
      final var response = wsServer.createWorkspace(viewerToken, "Should Fail", Optional.empty(), parcelId);
      assertEquals(403, response.status());
      final var body = getBody(response);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals("Role 'viewer' is not allowed to perform action 'create_workspace'", body.getString("message"));
      assertEquals("aerie_permissions", body.getString("service"));
    }

    @ParameterizedTest
    @MethodSource("improperlyFormattedBodyArgs")
    @Disabled
    void improperlyFormattedBody(JsonObject jsonBodyString) {
      // TODO: make this a parametrized test that includes:
      //  no body,
      //  empty body,
      //  missing workspace location,
      //  missing parcel id,
      //  missing both
      //  (extra params is excluded bc we don't check from them currently)
      //  In all cases, request should be rejected
    }

    Stream<Arguments> improperlyFormattedBodyArgs() {
      return Stream.of(
          Arguments.arguments(named("no body", null)),
          Arguments.arguments(named("empty body", JsonValue.EMPTY_JSON_OBJECT)),
          Arguments.arguments(named("array", JsonValue.EMPTY_JSON_ARRAY)),
          Arguments.arguments(named("no workspace location", Json.createObjectBuilder()
                                                                 .add("parcelId", parcelId)
                                                                 .build())),
          Arguments.arguments(named("no parcel id", Json.createObjectBuilder()
                                                        .add("workspaceLocation", "improperBodyArgsWs")
                                                        .build())),
          Arguments.arguments(named("no workspace location or parcel id", Json.createObjectBuilder()
                                                                              .add("workspaceName", "Improper Body WS")
                                                                              .build()))
      );
    }


    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/", "~", ".", "~/.", "~/usr/src/worspaces.myworkspace.txt"})
    @Disabled
    void invalidCharactersWorkspaceString(String workspaceLocation) {
      // TODO: make this a parametrized test w/ the 'NullAndEmptySource' annotation that includes:
      //  empty string,
      //  null,
      //  "/",
      //  ".",
      //  "~",
      //  all three bad symbols
      //  In all cases, the request should be rejected.
    }

    @Test
    @Disabled
    void validInputsNoWorkspaceName() {
      // TODO: expected success with the resulting workspace's name equalling its path (use a GQL query to check this)
    }

    @ParameterizedTest
    @NullAndEmptySource
    @Disabled
    void invalidWorkspaceName(String workspaceName) {
          /*
          TODO: WS server should reject both a null and empty workspace name
           */
    }

    @ParameterizedTest
    @ValueSource(strings = {"nameTestWS", "Workspace Names Test WS", "/", "~", ".", "~/.", "~/fakepath.ext"})
    @Disabled
    void validWorkspaceName(String workspaceName) {
          /*
           TODO: Make this a parametrized tests that includes a custom workspace name:
            - That matches the workspace name
            - "/"
            - "."
            - "~"
            - all three bad symbols
            In all cases, the custom name should be accepted and set as the workspace's name (use a GQL query to check this)
           */
    }
  }

  @Nested
  class DeleteWorkspace {
    // Per-Test Data
    private int workspaceId;

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace("deleteWSTests", parcelId);
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    /**
     * The workspace server returns a 403 Forbidden response when a user with insufficient role privileges
     * attempts to delete a workspace
     */
    @Test
    void forbiddenInsufficientPrivileges() {
      final var response = wsServer.deleteWorkspace(nonOwnerToken, workspaceId);
      assertEquals(403, response.status());
      final var body = getBody(response);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals(("User 'test_not_owner_user' with role 'user' cannot perform 'delete_workspace' "
                    + "because they are not a 'OWNER' for workspace with id '%d'").formatted(workspaceId),
                   body.getString("message"));
      assertEquals("aerie_permissions", body.getString("service"));
    }

    /**
     * The workspace server returns a 403 Forbidden response when a user with the `user` role who isn't the
     * OWNER attempts to delete a workspace
     */
    @Test
    void forbiddenNotOwner() {
      final var response = wsServer.deleteWorkspace(nonOwnerToken, workspaceId);
      assertEquals(403, response.status());
      final var body = getBody(response);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals(("User 'test_not_owner_user' with role 'user' cannot perform 'delete_workspace' "
                    + "because they are not a 'OWNER' for workspace with id '%d'").formatted(workspaceId),
                   body.getString("message"));
      assertEquals("aerie_permissions", body.getString("service"));
    }

    /**
     * The workspace server returns a 404 Resource Not Found response when an invalid id is provided.
     *
     * Disabled because test is not implemented
     */
    @Test
    @Disabled
    void invalidId() {
      // TODO: test:
      //  - not a number (may return a 500 instead b/c of a parsing error),
      //  - negative number
    }

    @Test
    void ownerCanDelete() throws IOException {
      final var wsId = wsServer.createWorkspace(nonOwnerToken, "OwnerCanDelete", parcelId);
      final var deleteResp = wsServer.deleteWorkspace(nonOwnerToken, wsId);
      assertEquals(200, deleteResp.status());
      assertEquals("Workspace deleted.", deleteResp.text());
    }
  }

  // Suite disabled until tests are written
  @Nested
  @Disabled
  class ListWorkspaceContents {
    /*
     * No auth tests because
     * 1. This endpoint is used in "WSAuthorizeTests", meaning it auth is pretty thoroughly tested
     * 2. There are no default roles that cannot access this method
     *
     * No input validation because this endpoint defers its behavior to `listContents`,
     * which is tested in WSRoutes.GET
     */
    @Test
    void emptyWorkspace() {
      // TODO: Expected results: success with an empty JSON
    }

    @Test
    void nonEmptyWorkspaceNoDepth() {
      // TODO: Place some non-empty nested folders in the workspace
      //  Expected results: success with the workspace contents
    }

    @Test
    void nonEmptyWorkspaceWithDepth() {
      // TODO: Place some non-empty nested folders in the workspace and provide the depth query variable
      //  Expected results: success with the workspace contents up to the specified depth
    }
  }
}
