package gov.nasa.ammos.plandev.e2e.workspace;

import com.microsoft.playwright.Playwright;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import gov.nasa.ammos.plandev.e2e.utils.HasuraRequests;
import gov.nasa.ammos.plandev.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.Json;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The workspace's Git repository (.git), runtime state (.seqdev) and Git control files are not reachable through any
 * workspace file API.
 */
@Tag("workspace")
public class WorkspaceReservedPathTests {
  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  private static int cdictId;
  private static int parcelId;
  private static String adminToken;

  private int workspaceId;

  @BeforeAll
  static void beforeAll() throws IOException {
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);
    try (final var gateway = new GatewayRequests(playwright)) {
      adminToken = gateway.login(test_admin);
    }
    cdictId = hasura.createMockCommandDictionary("WorkspaceReservedPathTest", "Workspace E2E Test");
    parcelId = hasura.createMockParcel("Workspace Reserved Path Parcel", cdictId);
  }

  @AfterAll
  static void afterAll() throws IOException {
    hasura.deleteMockCommandDictionary(cdictId);
    hasura.deleteMockParcel(parcelId);
    wsServer.close();
    hasura.close();
    playwright.close();
  }

  @BeforeEach
  void beforeEach() throws IOException {
    workspaceId = wsServer.createWorkspace("ReservedPathWSTests", parcelId);
    // The first mutation puts the workspace under history, creating .git and .seqdev
    assertEquals(200, wsServer.putFile(adminToken, workspaceId, Path.of("a.txt"), "command A;").status());
  }

  @AfterEach
  void afterEach() throws IOException {
    wsServer.deleteWorkspace(workspaceId);
  }

  @ParameterizedTest
  @ValueSource(strings = {".git/HEAD", ".git/config", ".git/refs/heads/main", "dir/.git/config", ".seqdev/state.json"})
  void reservedPathsCannotBeRead(String path) {
    final var resp = wsServer.get(adminToken, workspaceId, Path.of(path));
    assertEquals(400, resp.status(), resp.text());
  }

  @ParameterizedTest
  @ValueSource(strings = {".git/config", "dir/.git/HEAD", ".gitignore", ".gitattributes", ".seqdev/state.json"})
  void reservedPathsCannotBeWritten(String path) {
    final var resp = wsServer.putFile(adminToken, workspaceId, Path.of(path), "evil", true);
    assertEquals(400, resp.status(), resp.text());
  }

  @Test
  void reservedPathsCannotBeMovedCopiedOrDeleted() {
    assertEquals(400, wsServer.moveFileDirectory(adminToken, workspaceId, Path.of(".git/HEAD"), Path.of("HEAD"), false).status());
    assertEquals(400, wsServer.moveFileDirectory(adminToken, workspaceId, Path.of("a.txt"), Path.of(".git/a.txt"), true).status());
    assertEquals(400, wsServer.copyFileDirectory(adminToken, workspaceId, Path.of(".git"), Path.of("leak"), false).status());
    assertEquals(400, wsServer.deleteFileDirectory(adminToken, workspaceId, Path.of(".git")).status());
    assertEquals(400, wsServer.deleteFileDirectory(adminToken, workspaceId, Path.of(".seqdev")).status());
    assertEquals(200, wsServer.get(adminToken, workspaceId, Path.of("a.txt")).status());
  }

  /** In a bulk request a reserved path fails only its own item. */
  @Test
  void bulkDeleteReportsReservedPathsPerItem() {
    final var resp = wsServer.bulkDelete(adminToken, workspaceId, List.of(Path.of(".git"), Path.of("a.txt")));
    assertEquals(207, resp.status());
    try (final var reader = Json.createReader(new StringReader(resp.text()))) {
      final var items = reader.readArray();
      assertEquals(400, items.getJsonObject(0).getInt("status"));
      assertEquals(200, items.getJsonObject(1).getInt("status"));
    }
  }

  @Test
  void listingsDoNotIncludeInternalDirectories() {
    final var resp = wsServer.listWorkspaceContents(adminToken, workspaceId);
    assertEquals(200, resp.status());
    assertFalse(resp.text().contains(".git"), resp.text());
    assertFalse(resp.text().contains(".seqdev\""), resp.text());
  }
}
