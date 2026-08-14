package gov.nasa.ammos.plandev.e2e.workspace.routes;

import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import gov.nasa.ammos.plandev.e2e.utils.HasuraRequests;
import gov.nasa.ammos.plandev.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_admin;
import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_viewer;
import static gov.nasa.ammos.plandev.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Named.named;

/**
 * Tests for the response of the `authorize` before on `/ws/*` routes.
 * Uses GET /ws/{workspaceId} for testing
 *
 * Does not test the case where the Workspace Server doesn't have the HasuraAdminSecret provided,
 * as our test environment has that set.
 */
@Tag("workspace")
public class WorkspaceAuthorizeTests {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  // Class-Wide Data
  private static int cdictId;
  private static int parcelId;

  private static String adminToken;
  private static String viewerToken;

  // Requests
  private static int workspaceId;


  @BeforeAll
  static void beforeAll() throws IOException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);

    // Get valid JWT tokens for the users
    try (final var gateway = new GatewayRequests(playwright)) {
      adminToken = gateway.login(test_admin);
      viewerToken = gateway.login(test_viewer);
    }

    // Set up parcel and dictionary to use across the tests
    cdictId = hasura.createMockCommandDictionary("Workspace Authorize Test", "Workspace E2E Test");
    parcelId = hasura.createMockParcel("Workspace Authorize Parcel", cdictId);
    workspaceId = wsServer.createWorkspace("wsAuthorizeTests", parcelId);
  }

  @AfterAll
  static void afterAll() throws IOException {
    // Cleanup workspace
    wsServer.deleteWorkspace(workspaceId);

    // Cleanup parcel and dictionary
    hasura.deleteMockCommandDictionary(cdictId);
    hasura.deleteMockParcel(parcelId);

    // Cleanup Requests
    wsServer.close();
    hasura.close();
    playwright.close();
  }

  /**
   * The workspace server returns a 401 Unauthorized response when no authorization headers are provided.
   */
  @Test
  void noAuthHeader() {
    final var response = wsServer.listWorkspaceContents(Map.of(), workspaceId);
    assertEquals(401, response.status());
    final var body = getBody(response);
    assertEquals("UNAUTHORIZED", body.getString("type"));
    assertEquals("Invalid Authorization header provided.", body.getString("message"));
    assertEquals("aerie_workspace", body.getString("service"));
  }

  @Nested
  class AdminSecret {
    private static final String adminSecret = System.getenv("HASURA_GRAPHQL_ADMIN_SECRET");

    /**
     * The workspace server returns a 401 Unauthorized response when a request tries to use an invalid admin secret.
     */
    @Test
    void invalidSecret() {
      final String fakeSecret = adminSecret.substring(0, 1);
      final var headers = Map.of("x-hasura-role", "user",
                                 "x-hasura-user-id", "test_not_owner",
                                 "x-hasura-admin-secret", fakeSecret);
      final var response = wsServer.listWorkspaceContents(headers, workspaceId);
      assertEquals(401, response.status());
      final var body = getBody(response);
      assertEquals("UNAUTHORIZED", body.getString("type"));
      assertEquals("Invalid Hasura admin secret", body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * The workspace server returns a 401 Unauthorized response when the admin secret is provided
     * but a user id is not.
     */
    @Test
    void noUserIdProvided() {
      final var headers = Map.of("x-hasura-role", "user",
                                 "x-hasura-admin-secret", adminSecret);
      final var response = wsServer.listWorkspaceContents(headers, workspaceId);
      assertEquals(401, response.status());
      final var body = getBody(response);
      assertEquals("UNAUTHORIZED", body.getString("type"));
      assertEquals("x-hasura-user-id header is required when x-hasura-admin-secret is set", body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * If the workspace server is provided a valid admin secret and user id, but no active role,
     * the server will default to the "aerie_admin" role.
     *
     * This test proves this using the "put file" endpoint with a user who is neither the owner nor collaborator.
     * Only the 'aerie_admin' role is allowed to put files on a workspace they don't own.
     * (see WSRoutes.Put#forbidden for more information)
     */
    @Test
    void noActiveRoleProvided() {
      final var fileName = "sampleFile";
      final var filePayload = new FilePayload(fileName,
                                              "text/plain",
                                              "this is an example file".getBytes(StandardCharsets.UTF_8));
      final var options = RequestOptions.create()
                                        .setQueryParam("type", "file")
                                        .setHeader("x-hasura-admin-secret", adminSecret)
                                        .setHeader("x-hasura-user-id", "not a user")
                                        .setMultipart(FormData.create().set("file", filePayload));
      final var response = wsServer.makeRequest("/ws/%d/%s".formatted(workspaceId, fileName),
                                                options,
                                                WorkspaceRequests.RequestType.PUT);
      assertEquals(200, response.status());
      assertEquals("File %s uploaded to %s".formatted(fileName, fileName), response.text());
    }

    /**
     * The workspace server accepts an admin secret, userid, and active role
     *
     * This test proves this using the "put file" endpoint with a user trying to use the "viewer" role.
     * This role is not allowed to use this endpoint, while "aerie_admin" is
     */
    @Test
    void validSecretUserIdActiveRole() {
      final var fileName = "sampleFile";
      final var filePayload = new FilePayload(fileName,
                                              "text/plain",
                                              "this is an example file".getBytes(StandardCharsets.UTF_8));
      final var options = RequestOptions.create()
                                        .setQueryParam("type", "file")
                                        .setHeader("x-hasura-admin-secret", adminSecret)
                                        .setHeader("x-hasura-user-id", "not a user")
                                        .setHeader("x-hasura-role", "viewer")
                                        .setMultipart(FormData.create().set("file", filePayload));
      final var response = wsServer.makeRequest("/ws/%d/%s".formatted(workspaceId, fileName),
                                                options,
                                                WorkspaceRequests.RequestType.PUT);
      assertEquals(403, response.status());
      final var body = getBody(response);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals("Role 'viewer' is not allowed to perform action 'write_file_directory'", body.getString("message"));
      assertEquals("aerie_permissions", body.getString("service"));
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class JWTAuth {
    /**
     * The workspace server returns a 401 Unauthorized error when the JWT header has an invalid format.
     */
    @ParameterizedTest
    @MethodSource("misformattedJWTHeaderArgs")
    void misformattedJWTHeader(String token) {
      final var headers = Map.of("Authorization", token);
      final var response = wsServer.listWorkspaceContents(headers, workspaceId);
      assertEquals(401, response.status());
      final var body = getBody(response);
      assertEquals("UNAUTHORIZED", body.getString("type"));
      assertEquals("Invalid Authorization header provided.", body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    private Stream<Arguments> misformattedJWTHeaderArgs() {
      return Stream.of(
          Arguments.arguments(named("empty header", "")),
          Arguments.arguments(named("valid token without Bearer", adminToken)),
          Arguments.arguments(named("Bearer but no token", "Bearer ")),
          Arguments.arguments(named("invalid token", "not_a_valid_token"))
      );
    }

    /**
     * The workspace server returns a 401 Unauthorized error when the JWT passed is invalid.
     */
    @Test
    void invalidJWT() {
      final var headers = Map.of("Authorization", "Bearer not_a_valid_token");
      final var response = wsServer.listWorkspaceContents(headers, workspaceId);
      assertEquals(401, response.status());
      final var body = getBody(response);
      assertEquals("UNAUTHORIZED", body.getString("type"));
      assertEquals("The token was expected to have 3 parts, but got 0.", body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * The workspace server accepts a valid JWT.
     */
    @Test
    void validJWT() {
      final var headers = Map.of("Authorization", "Bearer " +viewerToken);
      final var response = wsServer.listWorkspaceContents(headers, workspaceId);
      assertEquals(200, response.status());
    }

    /**
     * The workspace server returns a 401 Unauthorized response when a user passes a valid JWT
     * but attempts to use the x-hasura-role flag for a role they don't have permission to use.
     */
    @Test
    void validJWTInvalidRole() {
      final var headers = Map.of("Authorization", "Bearer "+viewerToken,
                                 "x-hasura-role", "aerie_admin");
      final var response = wsServer.listWorkspaceContents(headers, workspaceId);
      assertEquals(401, response.status());
      final var body = getBody(response);
      assertEquals("UNAUTHORIZED", body.getString("type"));
      assertEquals("Provided active role is not in the set of permitted roles.", body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * The workspace server allows the user to use the x-hasura-role header to swap to one of their permitted roles.
     *
     * This test proves this using the admin token to the "put file" endpoint while setting the current role to "viewer".
     * While "aerie_admin" (the token's default role) is allowed to access this endpoint,
     * the "viewer" role will receive a 403 Forbidden error.
     */
    @Test
    void validJWTValidRole() {
      final var fileName = "sampleFile";
      final var filePayload = new FilePayload(fileName,
                                              "text/plain",
                                              "this is an example file".getBytes(StandardCharsets.UTF_8));
      final var options = RequestOptions.create()
                                        .setQueryParam("type", "file")
                                        .setHeader("Authorization", "Bearer "+adminToken)
                                        .setHeader("x-hasura-role", "viewer")
                                        .setMultipart(FormData.create().set("file", filePayload));
      final var response = wsServer.makeRequest("/ws/%d/%s".formatted(workspaceId, fileName),
                                                options,
                                                WorkspaceRequests.RequestType.PUT);
      assertEquals(403, response.status());
      final var body = getBody(response);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals("Role 'viewer' is not allowed to perform action 'write_file_directory'", body.getString("message"));
      assertEquals("aerie_permissions", body.getString("service"));
    }

    /**
     * The presence of the `x-hasura-admin-secret` header overrides the presence of the `Authorization` header
     *
     * Demonstrated by providing a valid JWT and an invalid admin secret and having the request be rejected
     * because of the invalid admin secret
     */
    @Test
    void secretOverridesJWT() {
      final String fakeSecret = System.getenv("HASURA_GRAPHQL_ADMIN_SECRET").substring(0, 1);
      final var headers = Map.of("Authorization", "Bearer " +viewerToken,
                                 "x-hasura-admin-secret", fakeSecret,
                                 "x-hasura-user-id", "test_viewer");
      final var response = wsServer.listWorkspaceContents(headers, workspaceId);
      assertEquals(401, response.status());
      final var body = getBody(response);
      assertEquals("UNAUTHORIZED", body.getString("type"));
      assertEquals("Invalid Hasura admin secret", body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }
  }
}
