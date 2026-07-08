package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static gov.nasa.jpl.aerie.e2e.types.User.admin;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class WorkspaceETagTests {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  // Class-Wide Data
  private static int cdictId;
  private static int parcelId;

  private static String adminToken;

  private int workspaceId;

  @BeforeAll
  static void beforeAll() throws IOException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);

    // Get valid JWT tokens for the users
    try (final var gateway = new GatewayRequests(playwright)) {
      adminToken = gateway.login(admin);
    }

    // Set up parcel and dictionary to use across the tests
    cdictId = hasura.createMockCommandDictionary("WorkspaceETagTest", "Workspace E2E Test");
    parcelId = hasura.createMockParcel("Workspace ETag Parcel", cdictId);
  }

  @AfterAll
  static void afterAll() throws IOException {
    // Cleanup parcel and dictionary
    hasura.deleteMockCommandDictionary(cdictId);
    hasura.deleteMockParcel(parcelId);

    // Cleanup Requests
    wsServer.close();
    hasura.close();
    playwright.close();
  }

  @BeforeEach
  void beforeEach() throws IOException {
    workspaceId = wsServer.createWorkspace("ETagWSTests", parcelId);
  }

  @AfterEach
  void afterEach() throws IOException {
    wsServer.deleteWorkspace(workspaceId);
  }

  /**
   * When generating Entity Tags for a file, the WS server reads the file in 1MB chunks.
   * This test uploads two 1.2MB files that are the exact same size and differ solely after the 1MB mark,
   * then compares the generated ETags.
   */
  @Test
  void chunkingDoesNotAffectETags() throws IOException {
    final var versionAPath = Path.of("versionA.txt");
    final var versionBPath = Path.of("versionB.txt");

    // Upload the two files
    wsServer.putFile(
        adminToken,
        workspaceId,
        versionAPath,
        Path.of("workspaces", "workspace_etag_test_original.txt"));
    wsServer.putFile(
        adminToken,
        workspaceId,
        versionBPath,
        Path.of("workspaces", "workspace_etag_test_modified.txt"));

    // GET the files
    final var versionA = wsServer.get(adminToken, workspaceId, versionAPath);
    final var versionB = wsServer.get(adminToken, workspaceId, versionBPath);

    // Compare the eTag headers
    final var versionAETag = versionA.headers().get("etag");
    final var versionBETag = versionB.headers().get("etag");
    assertNotEquals(versionAETag, versionBETag);
  }
}
