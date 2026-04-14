package gov.nasa.jpl.aerie.e2e.bindings.workspace;

import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.e2e.types.User.nonOwner;
import static gov.nasa.jpl.aerie.e2e.types.User.owner;
import static gov.nasa.jpl.aerie.e2e.types.User.viewer;
import static gov.nasa.jpl.aerie.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

/**
 * Tests for the /metadata/unset/{workspaceId}/<path> and /metadata/{workspaceId}/<path> routes.
 */
public class MetadataWorkspaceRoutesTests {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  // Class-Wide Data
  private static int cdictId;
  private static int parcelId;
  private static final Path fakeFile = Path.of("fake_file.txt");

  private static String ownerToken;
  private static String nonOwnerToken;
  private static String viewerToken;

  private static final JsonObject initialUserObject = Json.createObjectBuilder()
                                                          .add("textField", "default")
                                                          .add("booleanField", false)
                                                          .add("intField", 1)
                                                          .add("arrayField", Json.createArrayBuilder()
                                                                                 .add(1)
                                                                                 .add(2)
                                                                                 .add(3)
                                                                                 .add(4)
                                                                                 .add(5))
                                                          .add("subObject", Json.createObjectBuilder()
                                                                                .add("status", "test")
                                                                                .add("nestedSubObject", Json.createObjectBuilder()
                                                                                                            .add("a", 1)
                                                                                                            .add("b", 2)))
                                                          .build();

  @BeforeAll
  static void beforeAll() throws IOException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);

    // Get valid JWT tokens for the users
    try (final var gateway = new GatewayRequests(playwright)) {
      ownerToken = gateway.login(owner);
      nonOwnerToken = gateway.login(nonOwner);
      viewerToken = gateway.login(viewer);
    }

    // Set up parcel and dictionary to use across the tests
    cdictId = hasura.createMockCommandDictionary("Bulk Workspace Routes Test", "Workspace E2E Test");
    parcelId = hasura.createMockParcel("Bulk Workspace Routes Parcel", cdictId);
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

  @Nested
  class Get {
    private static int workspaceId;
    private static final Path file = Path.of("get_file_test.txt");
    private static final Path noMetadataFile = Path.of("no_metadata_file.txt");

    @BeforeAll
    static void beforeAll() throws IOException {
      workspaceId = wsServer.createWorkspace(ownerToken, "Metadata_GET_Tests", parcelId);
      wsServer.putFile(ownerToken, workspaceId, file, "Get File tests for Metadata endpoints");

      // Set up a file without metadata
      wsServer.putFile(ownerToken, workspaceId, noMetadataFile, "File without metadata");
      assertEquals(200, wsServer.deleteMetadata(ownerToken, workspaceId, noMetadataFile).status());
    }

    @AfterAll
    static void afterAll() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    @Test
    @Disabled
    void forbidden() {
      // TODO: Returns a 403 if Forbidden. Will need to temporarily update permissions to actually get this effect
    }

    /**
     * If the workspace doesn't exist, the server returns a "404 NO_SUCH_WORKSPACE" response
     */
    @Test
    void noSuchWorkspace() {
      final var resp = wsServer.getMetadata(ownerToken, -1, file);
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_WORKSPACE", body.getString("type"));
      assertEquals("No such workspace exists with id -1.", body.getString("message"));
    }

    /**
     * If the base file doesn't exist, the server returns a "404 NO_SUCH_FILE" response
     */
    @Test
    void noSuchFile() {
      final var resp = wsServer.getMetadata(ownerToken, workspaceId, fakeFile);
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_FILE", body.getString("type"));
      assertEquals("No such file exists in workspace "+ workspaceId +": "+fakeFile, body.getString("message"));
    }

    /**
     * If the metadata file doesn't exist, the server returns a 200 and a default response.
     */
    @Test
    void noSuchMetadataFile() {
      final var resp = wsServer.getMetadata(ownerToken, workspaceId, noMetadataFile);
      assertEquals(200, resp.status());
      assertEquals("METADATA", resp.headers().get("x-render-type"));

      final var metadataFile = getBody(resp); // Metadata files are specifically JSON
      assertEquals(1, metadataFile.size());
      assertEquals("1", metadataFile.getString("version"));
    }

    /**
     * If the metadata file exists, it is fetched
     */
    @Test
    void getMetadataFile() {
      final var resp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, resp.status());
      assertEquals("METADATA", resp.headers().get("x-render-type"));

      final var metadataFile = getBody(resp); // Metadata files are specifically JSON
      assertEquals(5, metadataFile.size());
      assertEquals("1", metadataFile.getString("version"));
      assertEquals(owner.name(), metadataFile.getString("createdBy"));
      assertEquals(owner.name(), metadataFile.getString("lastEditedBy"));
      assertDoesNotThrow(() -> Instant.parse(metadataFile.getString("createdAt")));
      assertDoesNotThrow(() -> Instant.parse(metadataFile.getString("lastEditedAt")));
    }
  }

  @Nested
  class SetKey {
    private int workspaceId;
    private static final Path file = Path.of("set_file_test.txt");
    private static final Path noMetadataFile = Path.of("no_metadata_file.txt");

    private static final JsonObject updateObject = Json.createObjectBuilder()
                                                       .add("intField", 0)
                                                       .add("arrayField", Json.createArrayBuilder()
                                                                              .add(6)
                                                                              .add(7))
                                                       .add("newField", "new value")
                                                       .add("booleanField", "hello")
                                                       .add("subObject", Json.createObjectBuilder()
                                                                             .add("nestedSubObject", Json.createObjectBuilder()
                                                                                                         .add("c", 3)))
                                                       .build();

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace(ownerToken, "Metadata_SET_Tests", parcelId);
      wsServer.putFile(ownerToken, workspaceId, file, "Set File tests for Metadata endpoints");

      // Set up a file without metadata
      wsServer.putFile(ownerToken, workspaceId, noMetadataFile, "File without metadata");
      assertEquals(200, wsServer.deleteMetadata(ownerToken, workspaceId, noMetadataFile).status());
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    /**
     * The Workspace Server returns a 403 Forbidden response when a user with insufficient role privileges
     * attempts to modify a metadata file
     */
    @Test
    void forbiddenInsufficientPrivileges() {
      final var resp = wsServer.setReadOnly(viewerToken, workspaceId, file, true);
      assertEquals(403, resp.status());
      final var body = getBody(resp);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals("Role 'viewer' is not allowed to perform action 'write_file_directory'",
                   body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * The workspace server returns a 403 Forbidden response when a user with the `user` role who isn't the
     * OWNER attempts to modify a metadata file
     */
    @Test
    void forbiddenNotOwner() {
      final var resp = wsServer.setReadOnly(nonOwnerToken, workspaceId, file, true);
      assertEquals(403, resp.status());
      final var body = getBody(resp);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals(("User '%s' with role 'user' cannot perform 'write_file_directory' "
                    + "because they are not a 'OWNER_COLLABORATOR' for workspace with id '%d'").formatted(nonOwner.name(), workspaceId),
                   body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * If the workspace doesn't exist, the server returns a "404 NO_SUCH_WORKSPACE" response
     */
    @Test
    void noSuchWorkspace() {
      final var resp = wsServer.setReadOnly(ownerToken, -1, file, true);
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_WORKSPACE", body.getString("type"));
      assertEquals("Could not check permissions on Workspace -1.", body.getString("message"));
    }

    /**
     * If the file doesn't exist, the server returns a "404 NO_SUCH_FILE" response
     */
    @Test
    void noSuchFile() {
      final var resp = wsServer.setReadOnly(ownerToken, workspaceId, fakeFile, true);
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_FILE", body.getString("type"));
      assertEquals("No such file exists in workspace "+ workspaceId +": "+fakeFile, body.getString("message"));
    }

    /**
     * If the metadata file doesn't exist, the server constructs a metadata file using the request information
     */
    @Test
    void noSuchMetadataFile() {
      // Get should currently return default response
      final var defaultMetadataFile = getBody(wsServer.getMetadata(ownerToken, workspaceId, noMetadataFile));
      assertEquals(1, defaultMetadataFile.size());
      assertEquals("1", defaultMetadataFile.getString("version"));

      // Edit the metadata file
      assertEquals(200, wsServer.setReadOnly(ownerToken, workspaceId, noMetadataFile, false).status());

      // The metadata file should no longer return default information
      final var updatedMetadataFileResp = wsServer.getMetadata(ownerToken, workspaceId, noMetadataFile);
      assertEquals(200, updatedMetadataFileResp.status());
      final var updatedMetadataFile = getBody(updatedMetadataFileResp);
      assertEquals(6, updatedMetadataFile.size());
      assertEquals("1", updatedMetadataFile.getString("version"));
      assertEquals(owner.name(), updatedMetadataFile.getString("createdBy"));
      assertEquals(owner.name(), updatedMetadataFile.getString("lastEditedBy"));
      assertFalse(updatedMetadataFile.getBoolean("readOnly"));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("createdAt")));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("lastEditedAt")));
    }

    /**
     * The workspace owner can update the metadata for a file
     */
    @Test
    void ownerCanUpdate() {
      final var initialGetResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, initialGetResp.status());
      final var initialMetadata = getBody(initialGetResp);
      assertEquals(5, initialMetadata.size());
      assertFalse(initialMetadata.containsKey("readOnly"));

      final var setResp = wsServer.setReadOnly(ownerToken, workspaceId, file, false);
      assertEquals(200, setResp.status());

      final var getResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(6, updatedMetadata.size());
      assertFalse(updatedMetadata.getBoolean("readOnly"));
    }

    /**
     * A workspace collaborator can update the metadata for a file
     */
    @Test
    void collaboratorCanUpdate() {
      assertDoesNotThrow(() -> hasura.addWorkspaceCollaborator(nonOwner, workspaceId));

      final var initialGetResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, initialGetResp.status());
      final var initialMetadata = getBody(initialGetResp);
      assertEquals(5, initialMetadata.size());
      assertFalse(initialMetadata.containsKey("readOnly"));

      final var setResp = wsServer.setReadOnly(nonOwnerToken, workspaceId, file, false);
      assertEquals(200, setResp.status());

      final var getResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(6, updatedMetadata.size());
      assertFalse(updatedMetadata.getBoolean("readOnly"));
    }

    /**
     * If "mergeBehavior" is set to "deep" or "deepMerge", the user object is deep merged during updates
     */
    @ParameterizedTest
    @ValueSource(strings = {"deep", "deepMerge"})
    void deepMergeUserField(String mergeString) {
      final var mergeBehavior = WorkspaceRequests.MetadataMergeBehavior.valueOf(mergeString);

      // Set the user field to a default value
      assertEquals(200, wsServer.setUserMetadata(ownerToken, workspaceId, file, initialUserObject).status());

      // Send the update
      final var setResp = wsServer.setUserMetadata(ownerToken, workspaceId, file, updateObject, mergeBehavior);
      assertEquals(200, setResp.status());

      // Get the updated metadata
      final var updatedMetadataResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, updatedMetadataResp.status());
      final var user = getBody(updatedMetadataResp).getJsonObject("user");

      // This is what the object should look like after a deep merge
      final var expectedObject = Json.createObjectBuilder()
                                     .add("textField", "default")
                                     .add("intField", 0)
                                     .add("newField", "new value")
                                     .add("booleanField", "hello")
                                     .add("arrayField", Json.createArrayBuilder().add(6).add(7))
                                     .add("subObject", Json.createObjectBuilder()
                                                           .add("status", "test")
                                                           .add("nestedSubObject", Json.createObjectBuilder()
                                                                                       .add("a", 1)
                                                                                       .add("b", 2)
                                                                                       .add("c", 3)))
                                     .build();
      assertEquals(expectedObject, user);
    }

    /**
     * If "mergeBehavior" is set to "shallow" or "shallowMerge", the user object is shallow merged during updates
     */
    @ParameterizedTest
    @ValueSource(strings = {"shallow", "shallowMerge"})
    void shallowMergeUserField(String mergeString) {
      final var mergeBehavior = WorkspaceRequests.MetadataMergeBehavior.valueOf(mergeString);

      // Set the user field to a default value
      assertEquals(200, wsServer.setUserMetadata(ownerToken, workspaceId, file, initialUserObject).status());

      // Send the update
      final var setResp = wsServer.setUserMetadata(ownerToken, workspaceId, file, updateObject, mergeBehavior);
      assertEquals(200, setResp.status());

      // Get the updated metadata
      final var updatedMetadataResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, updatedMetadataResp.status());
      final var user = getBody(updatedMetadataResp).getJsonObject("user");

      // This is what the object should look like after a shallow merge
      final var expectedObject = Json.createObjectBuilder()
                                     .add("textField", "default")
                                     .add("intField", 0)
                                     .add("newField", "new value")
                                     .add("booleanField", "hello")
                                     .add("arrayField", Json.createArrayBuilder().add(6).add(7))
                                     .add("subObject", Json.createObjectBuilder()
                                                           .add("nestedSubObject", Json.createObjectBuilder()
                                                                                       .add("c", 3)))
                                     .build();
      assertEquals(expectedObject, user);
    }

    /**
     * If "mergeBehavior" is set to "overwrite", the user object is replaced with the updated object
     */
    @Test
    void overwriteMergeUserField() {
      final var mergeBehavior = WorkspaceRequests.MetadataMergeBehavior.overwrite;

      // Set the user field to a default value
      assertEquals(200, wsServer.setUserMetadata(ownerToken, workspaceId, file, initialUserObject).status());

      // Send the update
      final var setResp = wsServer.setUserMetadata(ownerToken, workspaceId, file, updateObject, mergeBehavior);
      assertEquals(200, setResp.status());

      // Get the updated metadata
      final var updatedMetadataResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, updatedMetadataResp.status());
      final var user = getBody(updatedMetadataResp).getJsonObject("user");

      // The user object should have been overwritten to the update object
      assertEquals(updateObject, user);
    }

    /**
     * The default algorithm is "shallow merge"
     */
    @Test
    void noParamIsShallowMerge() {
      // Set the user field to a default value
      assertEquals(200, wsServer.setUserMetadata(ownerToken, workspaceId, file, initialUserObject).status());

      // Send the update
      final var setResp = wsServer.setUserMetadata(ownerToken, workspaceId, file, updateObject);
      assertEquals(200, setResp.status());

      // Get the updated metadata
      final var updatedMetadataResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, updatedMetadataResp.status());
      final var user = getBody(updatedMetadataResp).getJsonObject("user");

      // This is what the object should look like after a deep merge
      final var expectedObject = Json.createObjectBuilder()
                                     .add("textField", "default")
                                     .add("intField", 0)
                                     .add("newField", "new value")
                                     .add("booleanField", "hello")
                                     .add("arrayField", Json.createArrayBuilder().add(6).add(7))
                                     .add("subObject", Json.createObjectBuilder()
                                                           .add("nestedSubObject", Json.createObjectBuilder()
                                                                                       .add("c", 3)))
                                     .build();
      assertEquals(expectedObject, user);
    }

    /**
     * Multiple metadata fields can be updated in the same request
     */
    @Test
    void updateAllFieldsAtOnce() {
      final var setResp = wsServer.setMetadata(ownerToken, workspaceId, file, Optional.of(true), Optional.of(initialUserObject));
      assertEquals(200, setResp.status());

      final var getResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var metadata = getBody(getResp);
      assertEquals(7, metadata.size());
      assertTrue(metadata.getBoolean("readOnly"));
      assertEquals(initialUserObject, metadata.getJsonObject("user"));
    }

    @Nested
    class MalformedRequest {
      @Test
      void noBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json");
        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);

        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertEquals("Invalid body format. Expected body format is a JSON object with the set of keys to be updated.", body.getString("message"));
      }

      @Test
      void nonJsonBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "text/plain")
            .setData("Set some metadata");

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);

        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Body must be type application/json", body.getString("message"));
      }

      @Test
      void incorrectContentTypeHeader() {
        final var reqBody = Json.createObjectBuilder().add("readOnly", true).build();
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/octet-stream")
            .setData(reqBody.toString());

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Body must be type application/json", body.getString("message"));
      }

      @Test
      void emptyBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer " + ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("{}");
        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Cannot process request: at least one key must be specified.", body.getString("message"));
      }

      @Test
      void invalidQueryParam() {
        final var reqBody = Json.createObjectBuilder().add("readOnly", true).build();
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/octet-stream")
            .setQueryParam("mergeBehavior", "none")
            .setData(reqBody.toString());

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("ILLEGAL_ARGUMENT", body.getString("type"));
        assertEquals("Invalid type provided: none. 'mergeType' must be one of 'deep', 'shallow', or 'overwrite'", body.getString("message"));
      }

      @Test
      void nullReadOnly() {
        final var reqBody = Json.createObjectBuilder().addNull("readOnly").build();
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData(reqBody.toString());

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Key 'readOnly' must be a boolean", body.getString("message"));
      }

      @Test
      void nullUser() {
        final var reqBody = Json.createObjectBuilder().addNull("user").build();
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData(reqBody.toString());

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Key 'user' must be a JSON Object.", body.getString("message"));
      }

      @Test
      void nonJsonObjectUser() {
        final var reqBody = Json.createObjectBuilder().add("user", 5).build();
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData(reqBody.toString());

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Key 'user' must be a JSON Object.", body.getString("message"));
      }

      @Test
      void invalidKeyNamesInUserObject() {
        final var reqBody = Json.createObjectBuilder()
                                .add("user", Json.createObjectBuilder().add("invalid.key", 0))
                                .build();
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData(reqBody.toString());

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Keys within the 'user' object contain forbidden character '.'", body.getString("message"));
      }


      @ParameterizedTest
      @MethodSource("nonWhiteListKeyArgs")
      void nonWhiteListKeyProvided(JsonObject reqBody) {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData(reqBody.toString());

        final var url = WorkspaceRequests.METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertTrue(body.getString("message").startsWith("Request body contains unpermitted keys. "
                                                        + "Only the following keys may be updated:"));
      }

      /**
       * Generate arguments to test whitelist.
       */
      private static Stream<Arguments> nonWhiteListKeyArgs() {
        return Stream.of(
            Arguments.arguments(named("Single Invalid", Json.createObjectBuilder()
                                                            .add("version", "3")
                                                            .build())),
            Arguments.arguments(named("Multiple Invalid", Json.createObjectBuilder()
                                                              .add("version", "3")
                                                              .add("createdAt", "1")
                                                              .build())),
            Arguments.arguments(named("Invalid and Valid", Json.createObjectBuilder()
                                                                .add("version", "3")
                                                                .add("readOnly", true)
                                                                .build())),
            Arguments.arguments(named("Single Nonexistent", Json.createObjectBuilder()
                                                                .add("fake", 5)
                                                                .build())),
            Arguments.arguments(named("Nonexistent and Invalid", Json.createObjectBuilder()
                                                                     .add("fake", 5)
                                                                     .add("version", "3")
                                                                     .build())));
      }
    }
  }

  @Nested
  class UnsetKey {
    private int workspaceId;
    private static final Path file = Path.of("unset_file_test.txt");
    private static final Path noMetadataFile = Path.of("no_metadata_file.txt");

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace(ownerToken, "Metadata_SET_Tests", parcelId);
      wsServer.putFile(ownerToken, workspaceId, file, "Set File tests for Metadata endpoints");
      assertEquals(200, wsServer.setUserMetadata(ownerToken, workspaceId, file, initialUserObject).status());

      // Set up a file without metadata
      wsServer.putFile(ownerToken, workspaceId, noMetadataFile, "File without metadata");
      assertEquals(200, wsServer.deleteMetadata(ownerToken, workspaceId, noMetadataFile).status());
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    /**
     * The Workspace Server returns a 403 Forbidden response when a user with insufficient role privileges
     * attempts to modify a metadata file
     */
    @Test
    void forbiddenInsufficientPrivileges() {
      final var resp = wsServer.unsetMetadata(viewerToken, workspaceId, file, List.of("user"));
      assertEquals(403, resp.status());
      final var body = getBody(resp);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals("Role 'viewer' is not allowed to perform action 'write_file_directory'",
                   body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * The workspace server returns a 403 Forbidden response when a user with the `user` role who isn't the
     * OWNER attempts to modify a metadata file
     */
    @Test
    void forbiddenNotOwner() {
      final var resp = wsServer.unsetMetadata(nonOwnerToken, workspaceId, file, List.of("user"));
      assertEquals(403, resp.status());
      final var body = getBody(resp);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals(("User '%s' with role 'user' cannot perform 'write_file_directory' "
                    + "because they are not a 'OWNER_COLLABORATOR' for workspace with id '%d'").formatted(nonOwner.name(), workspaceId),
                   body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * If the workspace doesn't exist, the server returns a "404 NO_SUCH_WORKSPACE" response
     */
    @Test
    void noSuchWorkspace() {
      final var resp = wsServer.unsetMetadata(ownerToken, -1, file, List.of("user"));
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_WORKSPACE", body.getString("type"));
      assertEquals("Could not check permissions on Workspace -1.", body.getString("message"));
    }

    /**
     * If the file doesn't exist, the server returns a "404 NO_SUCH_FILE" response
     */
    @Test
    void noSuchFile() {
      final var resp = wsServer.unsetMetadata(ownerToken, workspaceId, fakeFile, List.of("user"));
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_FILE", body.getString("type"));
      assertEquals("No such file exists in workspace "+ workspaceId +": "+fakeFile, body.getString("message"));
    }

    /**
     * If the metadata file doesn't exist, the server constructs a metadata file using the request information
     */
    @Test
    void noSuchMetadataFile() {
      // Get should currently return default response
      final var defaultMetadataFile = getBody(wsServer.getMetadata(ownerToken, workspaceId, noMetadataFile));
      assertEquals(1, defaultMetadataFile.size());
      assertEquals("1", defaultMetadataFile.getString("version"));

      // Edit the metadata file
      assertEquals(200, wsServer.unsetMetadata(ownerToken, workspaceId, noMetadataFile, List.of("readOnly")).status());

      // The metadata file should no longer return default information
      final var updatedMetadataFileResp = wsServer.getMetadata(ownerToken, workspaceId, noMetadataFile);
      assertEquals(200, updatedMetadataFileResp.status());
      final var updatedMetadataFile = getBody(updatedMetadataFileResp);
      assertEquals(5, updatedMetadataFile.size());
      assertEquals("1", updatedMetadataFile.getString("version"));
      assertEquals(owner.name(), updatedMetadataFile.getString("createdBy"));
      assertEquals(owner.name(), updatedMetadataFile.getString("lastEditedBy"));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("createdAt")));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("lastEditedAt")));
    }

    /**
     * The workspace owner can unset keys from the metadata for a file
     */
    @Test
    void ownerCanUpdate() {
      final var initialGetResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, initialGetResp.status());
      final var initialMetadata = getBody(initialGetResp);
      assertEquals(6, initialMetadata.size());
      assertTrue(initialMetadata.containsKey("user"));

      final var unsetResp = wsServer.unsetMetadata(ownerToken, workspaceId, file, List.of("user"));
      assertEquals(200, unsetResp.status());

      final var getResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(5, updatedMetadata.size());
      assertFalse(updatedMetadata.containsKey("user"));
    }

    /**
     * A workspace collaborator can unset keys the metadata for a file
     */
    @Test
    void collaboratorCanUpdate() {
      assertDoesNotThrow(() -> hasura.addWorkspaceCollaborator(nonOwner, workspaceId));

      final var initialGetResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, initialGetResp.status());
      final var initialMetadata = getBody(initialGetResp);
      assertEquals(6, initialMetadata.size());
      assertTrue(initialMetadata.containsKey("user"));

      final var unsetResp = wsServer.unsetMetadata(nonOwnerToken, workspaceId, file, List.of("user"));
      assertEquals(200, unsetResp.status());

      final var getResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(5, updatedMetadata.size());
      assertFalse(updatedMetadata.containsKey("user"));
    }

    /**
     * ReadOnly can be unset via the unsetMetadata endpoint
     */
    @Test
    void unsetReadOnly() {
      assertEquals(200, wsServer.setReadOnly(ownerToken, workspaceId, file, true).status());

      final var unsetResp = wsServer.unsetMetadata(ownerToken, workspaceId, file, List.of("readOnly"));
      assertEquals(200, unsetResp.status());

      final var getResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(6, updatedMetadata.size());
      assertTrue(updatedMetadata.containsKey("user"));
      assertFalse(updatedMetadata.containsKey("readOnly"));
    }

    /**
     * The entire "user" object can be unset via the unsetMetadata endpoint
     */
    @Test
    void unsetEntireUserObject() {
      final var unsetResp = wsServer.unsetMetadata(ownerToken, workspaceId, file, List.of("user"));
      assertEquals(200, unsetResp.status());

      final var getResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(5, updatedMetadata.size());
      assertFalse(updatedMetadata.containsKey("user"));
    }

    /**
     * Keys within the 'user' object can be unset by using dot-path syntax
     */
    @Test
    void unsetNestedUserObject() {
      final var unsetResp = wsServer.unsetMetadata(
          ownerToken,
          workspaceId,
          file,
          List.of("user.arrayField", "user.subObject.status", "user.subObject.nestedSubObject.b"));
      assertEquals(200, unsetResp.status());

      final var getResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(6, updatedMetadata.size());
      assertTrue(updatedMetadata.containsKey("user"));

      final var expectedUserObject = Json.createObjectBuilder()
                                         .add("textField", "default")
                                         .add("booleanField", false)
                                         .add("intField", 1)
                                         .add("subObject", Json.createObjectBuilder()
                                                               .add("nestedSubObject", Json.createObjectBuilder()
                                                                                           .add("a", 1)))
                                         .build();

      assertEquals(expectedUserObject, updatedMetadata.getJsonObject("user"));
    }

    /**
     * Attempting to unset a top-level field that isn't set results in a 200 OK status with a no-op
     */
    @Test
    void unsetUnsetField() {
      final var initialMetadata = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));

      final var unsetResp = wsServer.unsetMetadata(ownerToken, workspaceId, file, List.of("readOnly"));
      assertEquals(200, unsetResp.status());

      final var updatedMetadata = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));
      assertEquals(initialMetadata, updatedMetadata);
    }

    /**
     * Attempting to unset a nonexistent field within the user object results in a 200 OK status with a no-op
     */
    @ParameterizedTest
    @ValueSource(strings = {"user.fake_key", "user.fake.nested.key"})
    void unsetNonexistentNestedUserObject(String key) {
      final var initialMetadata = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));

      final var unsetResp = wsServer.unsetMetadata(ownerToken, workspaceId, file, List.of(key));
      assertEquals(200, unsetResp.status());

      final var updatedMetadata = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));
      assertEquals(initialMetadata, updatedMetadata);
    }

    /**
     * Multiple fields can be unset at once
     */
    @Test
    void unsetAllFieldsAtOnce() {
      // Initialize Metadata
      assertEquals(200, wsServer.setMetadata(
          ownerToken,
          workspaceId,
          file,
          Optional.of(true),
          Optional.ofNullable(initialUserObject),
          WorkspaceRequests.MetadataMergeBehavior.overwrite).status());

      final var unsetResp = wsServer.unsetMetadata(ownerToken, workspaceId, file, List.of("readOnly", "user"));
      assertEquals(200, unsetResp.status());

      final var getResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getResp.status());
      final var updatedMetadata = getBody(getResp);
      assertEquals(5, updatedMetadata.size());
      assertFalse(updatedMetadata.containsKey("readOnly"));
      assertFalse(updatedMetadata.containsKey("user"));
    }


    @Nested
    class MalformedRequest {
      @Test
      void noBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json");
        final var url = WorkspaceRequests.UNSET_METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);

        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertEquals("Invalid body format. Expected body format is a JSON array with the set of keys to be removed.", body.getString("message"));
      }

      @Test
      void nonJsonBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "text/plain")
            .setData("Unset some metadata");

        final var url = WorkspaceRequests.UNSET_METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);

        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Body must be type application/json", body.getString("message"));
      }

      @Test
      void incorrectContentTypeHeader() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/octet-stream")
            .setData("[\"readOnly\"]");

        final var url = WorkspaceRequests.UNSET_METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Body must be type application/json", body.getString("message"));
      }

      @Test
      void emptyBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer " + ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("[]");
        final var url = WorkspaceRequests.UNSET_METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Cannot process request: at least one key must be specified.", body.getString("message"));
      }

      @Test
      void nonJsonArrayBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("{}");

        final var url = WorkspaceRequests.UNSET_METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertEquals("Invalid body format. Expected body format is a JSON array with the set of keys to be removed.", body.getString("message"));
      }

      @ParameterizedTest
      @MethodSource("nonWhiteListKeyArgs")
      void nonWhiteListKeyProvided(List<String> keysToUnset) {
        final var reqBody = Json.createArrayBuilder();
        keysToUnset.forEach(reqBody::add);

        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData(reqBody.build().toString());

        final var url = WorkspaceRequests.UNSET_METADATA_URL.formatted(workspaceId, file.toString());
        final var resp = wsServer.makeRequest(url, options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertTrue(body.getString("message").startsWith("Request body contains unpermitted keys. "
                     + "Only the following keys may be updated:"));
      }

      /**
       * Generate arguments to test whitelist.
       */
      private static Stream<Arguments> nonWhiteListKeyArgs() {
        return Stream.of(
            Arguments.arguments(named("Single Invalid", List.of("version"))),
            Arguments.arguments(named("Multiple Invalid", List.of("version", "createdAt"))),
            Arguments.arguments(named("Invalid then Valid", List.of("version", "user"))),
            Arguments.arguments(named("Valid then Invalid", List.of("user", "version"))),
            Arguments.arguments(named("Single Nonexistent", List.of("fake"))),
            Arguments.arguments(named("Nonexistent then Invalid", List.of("fake", "version"))));
      }
    }
  }

  @Nested
  class Delete {
    private int workspaceId;
    private static final Path file = Path.of("delete_file_test.txt");
    private static final Path noMetadataFile = Path.of("no_metadata_file.txt");

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace(ownerToken, "Metadata_DELETE_Tests", parcelId);
      wsServer.putFile(ownerToken, workspaceId, file, "Delete File tests for Metadata endpoints");

      // Set up a file without metadata
      wsServer.putFile(ownerToken, workspaceId, noMetadataFile, "File without metadata");
      assertEquals(200, wsServer.deleteMetadata(ownerToken, workspaceId, noMetadataFile).status());
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    /**
     * The Workspace Server returns a 403 Forbidden response when a user with insufficient role privileges
     * attempts to delete a metadata file
     */
    @Test
    void forbiddenInsufficientPrivileges() {
      final var resp = wsServer.deleteMetadata(viewerToken, workspaceId, file);
      assertEquals(403, resp.status());
      final var body = getBody(resp);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals(("Role 'viewer' is not allowed to perform action 'delete_file_directory'"),
                   body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * The workspace server returns a 403 Forbidden response when a user with the `user` role who isn't the
     * OWNER attempts to delete a metadata file
     */
    @Test
    void forbiddenNotOwner() {
      final var resp = wsServer.deleteMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(403, resp.status());
      final var body = getBody(resp);
      assertEquals("FORBIDDEN", body.getString("type"));
      assertEquals(("User '%s' with role 'user' cannot perform 'delete_file_directory' "
                    + "because they are not a 'OWNER_COLLABORATOR' for workspace with id '%d'").formatted(nonOwner.name(), workspaceId),
                   body.getString("message"));
      assertEquals("aerie_workspace", body.getString("service"));
    }

    /**
     * If the workspace doesn't exist, the server returns a "404 NO_SUCH_WORKSPACE" response
     */
    @Test
    void noSuchWorkspace() {
      final var resp = wsServer.deleteMetadata(ownerToken, -1, file);
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_WORKSPACE", body.getString("type"));
      assertEquals("Could not check permissions on Workspace -1.", body.getString("message"));
    }

    /**
     * If the file doesn't exist, the server returns a "404 NO_SUCH_FILE" response
     */
    @Test
    void noSuchFile() {
      final var resp = wsServer.deleteMetadata(ownerToken, workspaceId, fakeFile);
      assertEquals(404, resp.status());
      final var body = getBody(resp);
      assertEquals("NO_SUCH_FILE", body.getString("type"));
      assertEquals("No such file exists in workspace "+ workspaceId +": "+fakeFile, body.getString("message"));
    }

    /**
     * If the underlying metadata file doesn't exist, the server returns a 200 OK response
     */
    @Test
    void noSuchMetadataFile() {
      final var resp = wsServer.deleteMetadata(ownerToken, workspaceId, noMetadataFile);
      assertEquals(200, resp.status());
      assertEquals("Metadata for file %s deleted.".formatted(noMetadataFile.toString()), resp.text());
    }

    /**
     * The workspace owner can delete the metadata for a file
     */
    @Test
    void ownerCanDeleteFile() {
      // Confirm that the metadata file exists
      final var getOldResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, getOldResp.status());
      final var oldMetadataFile = getBody(getOldResp);
      assertEquals(5, oldMetadataFile.size());

      final var resp = wsServer.deleteMetadata(ownerToken, workspaceId, file);
      assertEquals(200, resp.status());
      assertEquals("Metadata for file %s deleted.".formatted(file.toString()), resp.text());

      // Get metadata for the file should now return the default response
      final var getNewResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, getNewResp.status());
      final var newMetadataFile = getBody(getNewResp);
      assertEquals(1, newMetadataFile.size());
      assertEquals("1", newMetadataFile.getString("version"));
    }

    /**
     * A workspace collaborator can delete the metadata for a file
     */
    @Test
    void collaboratorCanDeleteFile() {
      // Setup Collaborator
      assertDoesNotThrow(() -> hasura.addWorkspaceCollaborator(nonOwner, workspaceId));

      // Confirm that the metadata file exists
      final var getOldResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getOldResp.status());
      final var oldMetadataFile = getBody(getOldResp);
      assertEquals(5, oldMetadataFile.size());

      final var resp = wsServer.deleteMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, resp.status());
      assertEquals("Metadata for file %s deleted.".formatted(file.toString()), resp.text());

      // Get metadata for the file should now return the default response
      final var getNewResp = wsServer.getMetadata(nonOwnerToken, workspaceId, file);
      assertEquals(200, getNewResp.status());
      final var newMetadataFile = getBody(getNewResp);
      assertEquals(1, newMetadataFile.size());
      assertEquals("1", newMetadataFile.getString("version"));
    }

    /**
     * If the metadata for a file is deleted,
     * it is reconstructed as much as possible the next time the file is edited.
     */
    @Test
    void reconstructMetadataAfterDeletionFileEdit() {
      // Setup Collaborator
      assertDoesNotThrow(() -> hasura.addWorkspaceCollaborator(nonOwner, workspaceId));

      // Confirm that the metadata file exists
      final var originalMetadata = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));
      assertEquals(5, originalMetadata.size());
      assertEquals("1", originalMetadata.getString("version"));
      assertEquals(owner.name(), originalMetadata.getString("createdBy"));
      assertEquals(owner.name(), originalMetadata.getString("lastEditedBy"));
      assertDoesNotThrow(() -> Instant.parse(originalMetadata.getString("createdAt")));
      assertDoesNotThrow(() -> Instant.parse(originalMetadata.getString("lastEditedAt")));

      // Delete metadata file
      final var deleteResp = wsServer.deleteMetadata(ownerToken, workspaceId, file);
      assertEquals(200, deleteResp.status());

      // Get metadata for the file should now return the default response
      final var defaultMetadataFile = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));
      assertEquals(1, defaultMetadataFile.size());
      assertEquals("1", defaultMetadataFile.getString("version"));

      // Edit the file with the collaborator
      assertEquals(200, wsServer.putFile(nonOwnerToken, workspaceId, file, "New File contents", true).status());

      // The metadata file should no longer return default information
      final var updatedMetadataFileResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, updatedMetadataFileResp.status());
      final var updatedMetadataFile = getBody(updatedMetadataFileResp);
      assertEquals(5, updatedMetadataFile.size());
      assertEquals("1", updatedMetadataFile.getString("version"));
      assertEquals(nonOwner.name(), updatedMetadataFile.getString("createdBy"));
      assertEquals(nonOwner.name(), updatedMetadataFile.getString("lastEditedBy"));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("createdAt")));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("lastEditedAt")));

      // Confirm that the timestamps are now later
      final var originalCreatedAt = Instant.parse(originalMetadata.getString("createdAt"));
      final var updatedCreatedAt = Instant.parse(updatedMetadataFile.getString("createdAt"));
      final var originalLastEditedAt = Instant.parse(originalMetadata.getString("lastEditedAt"));
      final var updatedLastEditedAt = Instant.parse(updatedMetadataFile.getString("lastEditedAt"));

      assertTrue(updatedCreatedAt.isAfter(originalCreatedAt));
      assertTrue(updatedLastEditedAt.isAfter(originalLastEditedAt));
    }

    /**
     * If the metadata for a file is deleted,
     * it is reconstructed as much as possible the next time the metadata is edited.
     */
    @Test
    void reconstructMetadataAfterDeletionMetadataEdit() {
      // Setup Collaborator
      assertDoesNotThrow(() -> hasura.addWorkspaceCollaborator(nonOwner, workspaceId));

      // Confirm that the metadata file exists
      final var originalMetadata = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));
      assertEquals(5, originalMetadata.size());
      assertEquals("1", originalMetadata.getString("version"));
      assertEquals(owner.name(), originalMetadata.getString("createdBy"));
      assertEquals(owner.name(), originalMetadata.getString("lastEditedBy"));
      assertDoesNotThrow(() -> Instant.parse(originalMetadata.getString("createdAt")));
      assertDoesNotThrow(() -> Instant.parse(originalMetadata.getString("lastEditedAt")));

      // Delete metadata file
      final var deleteResp = wsServer.deleteMetadata(ownerToken, workspaceId, file);
      assertEquals(200, deleteResp.status());

      // Get metadata for the file should now return the default response
      final var defaultMetadataFile = getBody(wsServer.getMetadata(ownerToken, workspaceId, file));
      assertEquals(1, defaultMetadataFile.size());
      assertEquals("1", defaultMetadataFile.getString("version"));

      // Edit the metadata file with the collaborator
      assertEquals(200, wsServer.setReadOnly(nonOwnerToken, workspaceId, file, false).status());

      // The metadata file should no longer return default information
      final var updatedMetadataFileResp = wsServer.getMetadata(ownerToken, workspaceId, file);
      assertEquals(200, updatedMetadataFileResp.status());
      final var updatedMetadataFile = getBody(updatedMetadataFileResp);
      assertEquals(6, updatedMetadataFile.size());
      assertEquals("1", updatedMetadataFile.getString("version"));
      assertEquals(nonOwner.name(), updatedMetadataFile.getString("createdBy"));
      assertEquals(nonOwner.name(), updatedMetadataFile.getString("lastEditedBy"));
      assertFalse(updatedMetadataFile.getBoolean("readOnly"));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("createdAt")));
      assertDoesNotThrow(() -> Instant.parse(updatedMetadataFile.getString("lastEditedAt")));

      // Confirm that the timestamps are now later
      final var originalCreatedAt = Instant.parse(originalMetadata.getString("createdAt"));
      final var updatedCreatedAt = Instant.parse(updatedMetadataFile.getString("createdAt"));
      final var originalLastEditedAt = Instant.parse(originalMetadata.getString("lastEditedAt"));
      final var updatedLastEditedAt = Instant.parse(updatedMetadataFile.getString("lastEditedAt"));

      assertTrue(updatedCreatedAt.isAfter(originalCreatedAt));
      assertTrue(updatedLastEditedAt.isAfter(originalLastEditedAt));
    }
  }
}
