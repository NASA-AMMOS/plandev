package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.nio.file.Path;

import static gov.nasa.jpl.aerie.e2e.types.User.admin;
import static gov.nasa.jpl.aerie.e2e.types.User.owner;
import static gov.nasa.jpl.aerie.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkspaceReadOnlyTests {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  // Class-Wide Data
  private static int cdictId;
  private static int parcelId;

  private static String adminToken;
  private static String ownerToken;

  private int workspaceId;
  private static final Path folder = Path.of("folder");
  private static final Path fileName = Path.of("readOnlyFile.txt");
  private static final Path file = folder.resolve(fileName);
  private static final String fileContents = "ReadOnly file";

  @BeforeAll
  static void beforeAll() throws IOException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);

    // Get valid JWT tokens for the users
    try (final var gateway = new GatewayRequests(playwright)) {
      adminToken = gateway.login(admin);
      ownerToken = gateway.login(owner);
    }

    // Set up parcel and dictionary to use across the tests
    cdictId = hasura.createMockCommandDictionary("WorkspaceReadOnlyTest", "Workspace E2E Test");
    parcelId = hasura.createMockParcel("Workspace ReadOnly Parcel", cdictId);
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
    workspaceId = wsServer.createWorkspace("ReadOnlyWSTests", parcelId);
    hasura.changeWorkspaceOwner(workspaceId, owner);

    // Add and set up ReadOnly file
    wsServer.putFile(ownerToken, workspaceId, file, fileContents);
    wsServer.setReadOnly(ownerToken, workspaceId, file, true);
  }

  @AfterEach
  void afterEach() throws IOException {
    wsServer.deleteWorkspace(workspaceId);
  }

  /**
   * If a file is marked as "readOnly", its contents cannot be updated
   */
  @Test
  void readOnlyRestrictsFileEdit() {
    final var resp = wsServer.putFile(ownerToken, workspaceId, file, "New File Contents", true);
    final var body = getBody(resp);

    assertEquals(423, resp.status());
    assertEquals("FILE_LOCKED", body.getString("type"));
    assertEquals("File %s is currently marked as readOnly.".formatted(file), body.getString("cause"));

    // File still contains original contents
    final var getResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(200, getResp.status());
    assertEquals(fileContents, getResp.text());
  }

  @Test
  void readOnlyPermitsMetadataUpdate() {
    final var originalMetadataGetResp = wsServer.getMetadata(ownerToken, workspaceId, file);
    assertEquals(200, originalMetadataGetResp.status());
    final var originalMetadataFile = getBody(originalMetadataGetResp);

    final var newUserMetadata = Json.createObjectBuilder().add("sampleKey", "value").build();
    final var resp = wsServer.setUserMetadata(ownerToken, workspaceId, file, newUserMetadata);
    assertEquals(200, resp.status());

    // Confirm that the file was updated
    final var updatedMetadataGet = wsServer.getMetadata(ownerToken, workspaceId, file);
    assertEquals(200, updatedMetadataGet.status());
    final var updatedMetadataFile = getBody(updatedMetadataGet);

    assertEquals(7, updatedMetadataFile.size());
    assertEquals("1", updatedMetadataFile.getString("version"));
    assertEquals(owner.name(), updatedMetadataFile.getString("createdBy"));
    assertEquals(owner.name(), updatedMetadataFile.getString("lastEditedBy"));
    assertEquals(originalMetadataFile.getString("createdAt"), updatedMetadataFile.getString("createdAt"));
    assertEquals(originalMetadataFile.getString("lastEditedAt"), updatedMetadataFile.getString("lastEditedAt"));
    // This should still be set to "true"
    assertTrue(updatedMetadataFile.getBoolean("readOnly"));
    // This should now be set to the new value
    assertEquals(newUserMetadata, updatedMetadataFile.getJsonObject("user"));
  }

  @Test
  void readOnlyRestrictsDelete() {
    final var resp = wsServer.deleteFileDirectory(ownerToken, workspaceId, file);
    final var body = getBody(resp);

    assertEquals(423, resp.status());
    assertEquals("FILE_LOCKED", body.getString("type"));
    assertEquals("File %s is currently marked as readOnly.".formatted(file), body.getString("cause"));

    // File is still present after delete
    final var getResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(200, getResp.status());
  }

  /**
   * A file that is marked as "readOnly" prevents its containing folder from being deleted
   */
  @Test
  void readOnlyRestrictsFolderDelete() {
    final var resp = wsServer.deleteFileDirectory(ownerToken, workspaceId, folder);
    final var body = getBody(resp);

    assertEquals(423, resp.status());
    assertEquals("FILE_LOCKED", body.getString("type"));
    assertEquals("The following files in %s are currently marked as readOnly:\n\t - /usr/src/ws/ReadOnlyWSTests/%s".formatted(folder, file), body.getString("cause"));

    final var getResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(200, getResp.status());
    assertEquals(fileContents, getResp.text());
  }

  /**
   * A file that is marked as "readOnly" cannot be moved
   */
  @Test
  void readOnlyRestrictsMoveSource() {
    final var resp = wsServer.moveFileDirectory(ownerToken, workspaceId, file, fileName, false);
    final var body = getBody(resp);

    assertEquals(423, resp.status());
    assertEquals("FILE_LOCKED", body.getString("type"));
    assertEquals("File %s is currently marked as readOnly.".formatted(file), body.getString("cause"));

    // File is still present after move
    final var getResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(200, getResp.status());
  }

  /**
   * A file that is marked as "readOnly" cannot be overwritten by a move
   */
  @Test
  void readOnlyRestrictsMoveDest() {
    // Add a file to be the source of the move
    final var sourceFile = Path.of("other_folder", fileName.toString());
    final var sourceFileContents = "Non readonly file";
    final var putFileResp = wsServer.putFile(ownerToken, workspaceId, sourceFile, sourceFileContents);
    assertEquals(200, putFileResp.status());

    final var moveResp = wsServer.moveFileDirectory(ownerToken, workspaceId, sourceFile, file, true);
    final var moveBody = getBody(moveResp);

    assertEquals(423, moveResp.status());
    assertEquals("FILE_LOCKED", moveBody.getString("type"));
    assertEquals("File %s is currently marked as readOnly.".formatted(file), moveBody.getString("cause"));

    // File was not overwritten by move
    final var getResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(200, getResp.status());
    assertEquals(fileContents, getResp.text());
  }

  /**
   * A file that is marked as "readOnly" prevents its containing folder from being moved
   */
  @Test
  void readOnlyRestrictsFolderMoveSource() {
    final var newPath = Path.of("newfolder", "subfolder");
    final var resp = wsServer.moveFileDirectory(ownerToken, workspaceId, folder, newPath, false);
    final var body = getBody(resp);

    assertEquals(423, resp.status());
    assertEquals("FILE_LOCKED", body.getString("type"));
    assertEquals("The following files in %s are currently marked as readOnly:\n\t - /usr/src/ws/ReadOnlyWSTests/%s".formatted(folder, file), body.getString("cause"));

    // File is still present in original location after move
    final var getResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(200, getResp.status());
  }

  /**
   * A file that is marked as "readOnly" does not restrict its containing folder from being renamed
   */
  @Test
  void readOnlyPermitsFolderRename() {
    final var newPath = Path.of("newfolder");
    final var resp = wsServer.moveFileDirectory(ownerToken, workspaceId, folder, newPath, false);
    assertEquals(200, resp.status());

    // File was successfully moved
    final var oldGetResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(404, oldGetResp.status());
    assertEquals("NO_SUCH_FILE", getBody(oldGetResp).getString("type"));

    final var newGetResp = wsServer.get(ownerToken, workspaceId, newPath.resolve(fileName));
    assertEquals(200, newGetResp.status());
    assertEquals(fileContents, newGetResp.text());
  }

  /**
   * A file that is marked as "readOnly" CAN be copied
   */
  @Test
  void readOnlyPermitsCopySource() {
    final var copyResp = wsServer.copyFileDirectory(ownerToken, workspaceId, file, fileName, false);
    assertEquals(200, copyResp.status());

    // File was copied
    final var getResp = wsServer.get(ownerToken, workspaceId, fileName);
    assertEquals(200, getResp.status());
    assertEquals(fileContents, getResp.text());
  }

  /**
   * A file that is marked as "readOnly" cannot be overwritten by copy.
   */
  @Test
  void readOnlyRestrictsCopyDest() {
    // Add a file to be the source of the copy
    final var sourceFile = Path.of("other_folder", fileName.toString());
    final var sourceFileContents = "Non readonly file";
    final var putFileResp = wsServer.putFile(ownerToken, workspaceId, sourceFile, sourceFileContents);
    assertEquals(200, putFileResp.status());

    final var copyResp = wsServer.copyFileDirectory(ownerToken, workspaceId, sourceFile, file, true);
    final var copyBody = getBody(copyResp);

    assertEquals(423, copyResp.status());
    assertEquals("FILE_LOCKED", copyBody.getString("type"));
    assertEquals("File %s is currently marked as readOnly.".formatted(file), copyBody.getString("cause"));

    // File was not overwritten by copy
    final var getResp = wsServer.get(ownerToken, workspaceId, file);
    assertEquals(200, getResp.status());
    assertEquals(fileContents, getResp.text());
  }

  /**
   * If a file is marked as "readOnly", that does not prevent the workspace from being deleted.
   */
  @Test
  void readOnlyPermitsWSDelete() throws IOException {
    final var wsId = wsServer.createWorkspace("ReadOnlyNotRestrictDelete", parcelId);
    wsServer.putFile(adminToken, wsId, file, "ReadOnly file");
    wsServer.setReadOnly(adminToken, wsId, file, true);
    assertDoesNotThrow(() -> wsServer.deleteWorkspace(wsId));

    // Attempt to get the file to show the WS is deleted
    final var getResp = wsServer.get(adminToken, wsId, file);
    assertEquals(404, getResp.status());
    final var body = getBody(getResp);
    assertEquals("NO_SUCH_WORKSPACE", body.getString("type"));
    assertEquals("No such workspace exists with id %d.".formatted(wsId), body.getString("message"));
  }

  /**
   * When ReadOnly is present but set to "false", it does not apply any restrictions
   */
  @Nested
  class ReadOnlyFalse {
    private int workspaceId;
    private static final String fileContents = "Read Only False file";

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace("ReadOnlyFalseWSTests", parcelId);
      hasura.changeWorkspaceOwner(workspaceId, owner);

      // Add and set up ReadOnly file
      wsServer.putFile(ownerToken, workspaceId, file, fileContents);
      wsServer.setReadOnly(ownerToken, workspaceId, file, false);
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    @Test
    void canEditFile() {
      final var resp = wsServer.putFile(ownerToken, workspaceId, file, "New File Contents", true);
      assertEquals(200, resp.status());

      final var getResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      assertEquals("New File Contents", getResp.text());
    }

    @Test
    void canUpdateMetadata() {
      // Check the initial metadata file contents
      final var initialMetadataResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, initialMetadataResp.status());
      final var initialMetadataFile = getBody(initialMetadataResp);

      assertEquals(6, initialMetadataFile.size());
      assertEquals("1", initialMetadataFile.getString("version"));
      assertEquals(owner.name(), initialMetadataFile.getString("createdBy"));
      assertEquals(owner.name(), initialMetadataFile.getString("lastEditedBy"));
      // These fields should be set to some instant
      assertTrue(initialMetadataFile.containsKey("createdAt"));
      assertTrue(initialMetadataFile.containsKey("lastEditedAt"));
      // This should be set to "false" currently
      assertFalse(initialMetadataFile.getBoolean("readOnly"));
      // User should not be set yet
      assertFalse(initialMetadataFile.containsKey("user"));

      // Update the metadata
      final var newUserMetadata = Json.createObjectBuilder().add("sampleKey", "value").build();
      final var resp = wsServer.setUserMetadata(ownerToken, workspaceId, file, newUserMetadata);
      assertEquals(200, resp.status());

      // Get the new metadata file
      final var getResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      final var metadataFile = getBody(getResp);
      assertEquals(200, getResp.status());
      // Compare the new file contents
      assertEquals(7, metadataFile.size());
      assertEquals("1", metadataFile.getString("version"));
      assertEquals(owner.name(), metadataFile.getString("createdBy"));
      assertEquals(owner.name(), metadataFile.getString("lastEditedBy"));
      assertEquals(initialMetadataFile.getString("createdAt"), metadataFile.getString("createdAt"));
      assertEquals(initialMetadataFile.getString("lastEditedAt"), metadataFile.getString("lastEditedAt"));
      // This should still be set to "false"
      assertFalse(metadataFile.getBoolean("readOnly"));
      // This should now be set to the new value
      assertEquals(newUserMetadata, metadataFile.getJsonObject("user"));
    }

    @Test
    void canDeleteFile() {
      final var resp = wsServer.deleteFileDirectory(ownerToken, workspaceId, file);
      assertEquals(200, resp.status());

      final var getResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(404, getResp.status());
      assertEquals("NO_SUCH_FILE", getBody(getResp).getString("type"));
    }

    @Test
    void canDeleteContainingFolder() {
      final var resp = wsServer.deleteFileDirectory(ownerToken, workspaceId, folder);
      assertEquals(200, resp.status());

      final var getResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(404, getResp.status());
      assertEquals("NO_SUCH_FILE", getBody(getResp).getString("type"));
    }

    @Test
    void canMoveFileSource() {
      final var resp = wsServer.moveFileDirectory(ownerToken, workspaceId, file, fileName, false);
      assertEquals(200, resp.status());

      // File has been moved
      final var getResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(404, getResp.status());
      assertEquals("NO_SUCH_FILE", getBody(getResp).getString("type"));

      final var getDestResp = wsServer.get(ownerToken, workspaceId, fileName);
      assertEquals(200, getDestResp.status());
      assertEquals(fileContents, getDestResp.text());
    }

    @Test
    void canMoveFileDest() {
      // Add a file to be the source of the move
      final var sourceFile = Path.of("other_folder", fileName.toString());
      final var sourceFileContents = "Non readonly file";
      final var putFileResp = wsServer.putFile(ownerToken, workspaceId, sourceFile, sourceFileContents);
      assertEquals(200, putFileResp.status());

      final var moveResp = wsServer.moveFileDirectory(ownerToken, workspaceId, sourceFile, file, true);
      assertEquals(200, moveResp.status());

      // File was overwritten by move
      final var getResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      assertEquals(sourceFileContents, getResp.text());

      final var getSourceResp = wsServer.get(ownerToken, workspaceId, sourceFile);
      assertEquals(404, getSourceResp.status());
      assertEquals("NO_SUCH_FILE", getBody(getSourceResp).getString("type"));
    }

    @Test
    void canMoveFolderSource() {
      final var newPath = Path.of("newfolder", "subfolder");
      assertEquals(200, wsServer.putDirectory(ownerToken, workspaceId, Path.of("newfolder")).status());
      final var resp = wsServer.moveFileDirectory(ownerToken, workspaceId, folder, newPath, false);
      assertEquals(200, resp.status());

      // File was successfully moved
      final var oldGetResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(404, oldGetResp.status());
      assertEquals("NO_SUCH_FILE", getBody(oldGetResp).getString("type"));

      final var newGetResp = wsServer.get(ownerToken, workspaceId, newPath.resolve(fileName));
      assertEquals(200, newGetResp.status());
      assertEquals(fileContents, newGetResp.text());
    }

    @Test
    void canRenameFolder() {
      final var newPath = Path.of("newfolder");
      final var resp = wsServer.moveFileDirectory(ownerToken, workspaceId, folder, newPath, false);
      assertEquals(200, resp.status());

      // File was successfully moved
      final var oldGetResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(404, oldGetResp.status());
      assertEquals("NO_SUCH_FILE", getBody(oldGetResp).getString("type"));

      final var newGetResp = wsServer.get(ownerToken, workspaceId, newPath.resolve(fileName));
      assertEquals(200, newGetResp.status());
      assertEquals(fileContents, newGetResp.text());
    }

    @Test
    void canCopyDest() {
      // Add a file to be the source of the copy
      final var sourceFile = Path.of("other_folder", fileName.toString());
      final var sourceFileContents = "Non readonly file";
      final var putFileResp = wsServer.putFile(ownerToken, workspaceId, sourceFile, sourceFileContents);
      assertEquals(200, putFileResp.status());

      final var copyResp = wsServer.copyFileDirectory(ownerToken, workspaceId, sourceFile, file, true);
      assertEquals(200, copyResp.status());

      // File was overwritten by copy
      final var getResp = wsServer.get(ownerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      assertEquals(sourceFileContents, getResp.text());

      final var getSourceResp = wsServer.get(ownerToken, workspaceId, sourceFile);
      assertEquals(200, getSourceResp.status());
      assertEquals(sourceFileContents, getSourceResp.text());
    }

    @Test
    void canDeleteWS() throws IOException {
      final var wsId = wsServer.createWorkspace("ReadOnlyNotRestrictDelete", parcelId);
      wsServer.putFile(adminToken, wsId, file, "ReadOnly file");
      wsServer.setReadOnly(adminToken, wsId, file, true);
      assertDoesNotThrow(() -> wsServer.deleteWorkspace(wsId));

      // Attempt to get the file to show the WS is deleted
      final var getResp = wsServer.get(adminToken, wsId, file);
      assertEquals(404, getResp.status());
      final var body = getBody(getResp);
      assertEquals("NO_SUCH_WORKSPACE", body.getString("type"));
      assertEquals("No such workspace exists with id %d.".formatted(wsId), body.getString("message"));
    }
  }
}
