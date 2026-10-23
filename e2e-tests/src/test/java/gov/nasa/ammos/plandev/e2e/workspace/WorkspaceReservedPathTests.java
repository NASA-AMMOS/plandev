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
import org.junit.jupiter.params.provider.FieldSource;

import javax.json.Json;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_admin;
import static gov.nasa.ammos.plandev.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The workspace's Git repository (.git), internal scratch directory (.seqdev) and Git control files are not reachable
 * through any workspace file API.
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

  private static final String[] reservedPaths = {
      ".git",
      ".git/HEAD",
      "dir/.git/HEAD",
      ".git/config",
      "dir/.git/config",
      ".git/refs/heads/main",
      ".gitignore",
      ".gitattributes",
      ".gitmodules",
      ".seqdev",
      ".seqdev/state.json",
      "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
      "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
  };

  private static final Path fileA = Path.of("a.txt");

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
    assertEquals(200, wsServer.putFile(adminToken, workspaceId, fileA, "command A;").status());
  }

  @AfterEach
  void afterEach() throws IOException {
    wsServer.deleteWorkspace(workspaceId);
  }

  /**
   * Attempting to GET a reserved file or folder results in a SECURITY_EXCEPTION
   */
  @ParameterizedTest
  @FieldSource("reservedPaths")
  void reservedPathsCannotBeFetched(String path) {
    final var resp = wsServer.get(adminToken, workspaceId, Path.of(path));
    assertEquals(500, resp.status());
    final var body = getBody(resp);
    assertEquals("SECURITY_EXCEPTION", body.getString("type"));
  }

  /**
   * Attempting to write to a reserved file or folder results in a SECURITY_EXCEPTION
   */
  @ParameterizedTest
  @FieldSource("reservedPaths")
  void reservedPathsCannotBeWritten(String path) {
    final var resp = wsServer.putFile(adminToken, workspaceId, Path.of(path), "evil", true);
    assertEquals(500, resp.status(), resp.text());
    final var body = getBody(resp);
    assertEquals("SECURITY_EXCEPTION", body.getString("type"));
  }

  /**
   * Attempting to move a reserved file or folder results in a SECURITY_EXCEPTION
   */
  @ParameterizedTest
  @FieldSource("reservedPaths")
  void reservedPathSourceMove(String path) {
    final var resp = wsServer.moveFileDirectory(adminToken, workspaceId, Path.of(path), fileA, true);
    assertEquals(500, resp.status(), resp.text());
    final var body = getBody(resp);
    assertEquals("SECURITY_EXCEPTION", body.getString("type"));
  }

  /**
   * Attempting to move a file to a reserved filepath results in a SECURITY_EXCEPTION
   */
  @ParameterizedTest
  @FieldSource("reservedPaths")
  void reservedPathTargetMove(String path) {
    final var resp = wsServer.moveFileDirectory(adminToken, workspaceId, fileA, Path.of(path), true);
    assertEquals(500, resp.status(), resp.text());
    final var body = getBody(resp);
    assertEquals("SECURITY_EXCEPTION", body.getString("type"));
  }

  /**
   * Attempting to copy a reserved file or folder results in a SECURITY_EXCEPTION
   */
  @ParameterizedTest
  @FieldSource("reservedPaths")
  void reservedPathSourceCopy(String path) {
    final var resp = wsServer.copyFileDirectory(adminToken, workspaceId, Path.of(path), fileA, true);
    assertEquals(500, resp.status(), resp.text());
    final var body = getBody(resp);
    assertEquals("SECURITY_EXCEPTION", body.getString("type"));
  }

  /**
   * Attempting to copy a file to a reserved filepath results in a SECURITY_EXCEPTION
   */
  @ParameterizedTest
  @FieldSource("reservedPaths")
  void reservedPathTargetCopy(String path) {
    final var resp = wsServer.copyFileDirectory(adminToken, workspaceId, fileA, Path.of(path), true);
    assertEquals(500, resp.status(), resp.text());
    final var body = getBody(resp);
    assertEquals("SECURITY_EXCEPTION", body.getString("type"));
  }

  /**
   * Attempting to delete a reserved file or folder results in a SECURITY_EXCEPTION
   */
  @ParameterizedTest
  @FieldSource("reservedPaths")
  void cannotDeleteReservedPaths(String path) {
    final var resp = wsServer.deleteFileDirectory(adminToken, workspaceId, Path.of(path));
    assertEquals(500, resp.status(), resp.text());
    final var body = getBody(resp);
    assertEquals("SECURITY_EXCEPTION", body.getString("type"));
  }

  /** In a bulk request a reserved path fails only its own item. */
  @Test
  void bulkDeleteReportsReservedPathsPerItem() {
    final var resp = wsServer.bulkDelete(adminToken, workspaceId, List.of(Path.of(".git"), fileA));
    assertEquals(207, resp.status());
    try (final var reader = Json.createReader(new StringReader(resp.text()))) {
      final var items = reader.readArray();
      assertEquals(500, items.getJsonObject(0).getInt("status"));
      assertEquals(200, items.getJsonObject(1).getInt("status"));
    }
  }

  /**
   * The `.git` and `.seqdev` directories, as well as any files within them,
   * are excluded from the results of `GET /ws/{workspaceId}`
   */
  @Test
  void listWorkspaceExcludesInternalDirectories() {
    final var resp = wsServer.listWorkspaceContents(adminToken, workspaceId);
    assertEquals(200, resp.status());
    assertFalse(resp.text().contains(".git"), resp.text());
    assertFalse(resp.text().contains(".seqdev"), resp.text());
  }
}
