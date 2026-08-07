package gov.nasa.jpl.aerie.e2e.workspace.routes;

import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.types.workspaces.BulkPutItem;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.WorkspaceRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.json.Json;
import javax.json.JsonArray;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.e2e.E2ETestSuite.test_owner;
import static gov.nasa.jpl.aerie.e2e.utils.RequestBodyHelper.getArrayBody;
import static gov.nasa.jpl.aerie.e2e.utils.RequestBodyHelper.getBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Named.named;

/**
 * Tests for the /ws/bulk/{workspaceId}/ routes.
 */
public class BulkWorkspaceRoutesTests {
  // Requests
  private static Playwright playwright;
  private static HasuraRequests hasura;
  private static WorkspaceRequests wsServer;

  // Class-Wide Data
  private static int cdictId;
  private static int parcelId;

  private static String ownerToken;

  @BeforeAll
  static void beforeAll() throws IOException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
    wsServer = new WorkspaceRequests(playwright);

    // Get valid JWT tokens for the users
    try (final var gateway = new GatewayRequests(playwright)) {
      ownerToken = gateway.login(test_owner);
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
  class BulkPut {
    private int workspaceId;

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace(ownerToken, "bulkPutWS", parcelId);
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    /**
     * Basic successful cases.
     * All of these should return a top level status of 207,
     *    and an array of JSON objects with the same length as the input list.
     * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
     *    and a 'result' field that either says "directory created" or "file uploaded",
     *    as appropriate based on the created item's type.
     * Additionally, a GET request for the item should succeed after the PUT.
     */
    @ParameterizedTest
    @MethodSource("bulkPutBasicCasesArgs")
    void bulkPutBasicCases(List<BulkPutItem> inputs) {
      final var resp = wsServer.bulkPut(ownerToken, workspaceId, inputs);

      // Check status code
      assertEquals(207, resp.status());

      // Check details of response
      final var respBody = getArrayBody(resp);
      assertEquals(inputs.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = inputs.get(i);
        final var actual = respBody.get(i).asJsonObject();

        // Check the PUT response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.getPath().toString(), actual.getString("item"));
        if (expected instanceof BulkPutItem.FileBulkPutItem file) {
          assertEquals(
              "File " + expected.getPath().getFileName() + " uploaded to " + expected.getPath(),
              actual.getString("response"));
          // Check that file was uploaded with the correct contents
          final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
          assertEquals(200, getResp.status());
          assertEquals(file.fileContents(), getResp.text());
        } else {
          assertEquals("Directory created.", actual.getString("response"));
          // Simple check that the item was actually uploaded -- does not check directory contents
          final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
          assertEquals(200, getResp.status());
        }
      }
    }

    /**
     * Generate arguments to test basic upload cases.
     */
    private static Stream<Arguments> bulkPutBasicCasesArgs() {
      final var myFileInput = new BulkPutItem.FileBulkPutItem(
          Path.of("myFile.txt"),
          "this is my file contents");
      final var myDirInput = new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir"));
      final var folderDirInput = new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir/subDir"), true);
      final var secondFileInput = new BulkPutItem.FileBulkPutItem(
          Path.of("myDir/otherFile.txt"),
          "this is another file",
          "anotherFile.txt");

      return Stream.of(
          Arguments.arguments(named("Single File Bulk PUT", List.of(myFileInput))),
          Arguments.arguments(named("Single Directory Bulk PUT", List.of(myDirInput))),
          Arguments.arguments(named("Multiple Files Bulk PUT", List.of(myFileInput, secondFileInput))),
          Arguments.arguments(named("Multiple Directories Bulk PUT", List.of(myDirInput, folderDirInput))),
          Arguments.arguments(named(
              "Mixed Files and Directories Bulk PUT",
              List.of(myDirInput, folderDirInput, myFileInput, secondFileInput)))
      );
    }

    /**
     * When only one item upload fails, the overall status is 207, the successful items have a status of 200,
     * and the unsuccessful items have an appropriate error status.
     */
    @Test
    void mixedResults() {
      // Setup: upload a conflicting file using the non-bulk endpoint
      final var putResp = wsServer.putFile(ownerToken, workspaceId, Path.of("file.txt"), "original file contents");
      assertEquals(200, putResp.status());

      // Upload a list of items, including one conflict
      final List<BulkPutItem> toUpload = List.of(
          new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "conflicting file"),
          new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir")),
          new BulkPutItem.FileBulkPutItem(
              Path.of("myDir/file.txt"),
              "file with same name in another folder",
              "otherFile.txt"));

      final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

      // Check Response
      assertEquals(207, resp.status());
      final var respBody = getArrayBody(resp);
      assertEquals(3, respBody.size());

      // First item should be the conflicted file with a 409 Conflicted
      final var conflictFile = respBody.getFirst().asJsonObject();
      assertEquals("file.txt", conflictFile.getString("item"));
      assertEquals(409, conflictFile.getInt("status"));
      assertEquals("original file contents", wsServer.get(ownerToken, workspaceId, Path.of("file.txt")).text());

      // Second item should be the unconflicted directory
      final var dir = respBody.get(1).asJsonObject();
      assertEquals("myDir", dir.getString("item"));
      assertEquals(200, dir.getInt("status"));
      assertEquals(
          "[{\"name\":\"file.txt\",\"type\":\"TEXT\"}]",
          wsServer.get(ownerToken, workspaceId, Path.of("myDir")).text());

      // Third item should be the unconflicted file
      final var otherFile = respBody.getLast().asJsonObject();
      assertEquals("myDir/file.txt", otherFile.getString("item"));
      assertEquals(200, otherFile.getInt("status"));
      assertEquals(
          "file with same name in another folder",
          wsServer.get(ownerToken, workspaceId, Path.of("myDir/file.txt")).text());
    }

    /**
     * File contents can be attached under a name other than the file's uploaded name using the input_file_name field
     */
    @Test
    void customInputName() {
      // Upload two items with the same name to different folders.
      final List<BulkPutItem> toUpload = List.of(
          new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file in one folder"),
          new BulkPutItem.FileBulkPutItem(
              Path.of("myDir/file.txt"),
              "file with same name in another folder",
              "otherFile.txt"));

      final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

      // Check Response
      assertEquals(207, resp.status());
      final var respBody = getArrayBody(resp);
      assertEquals(toUpload.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = toUpload.get(i);
        final var actual = respBody.get(i).asJsonObject();

        // Check the PUT response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.getPath().toString(), actual.getString("item"));
        if (expected instanceof BulkPutItem.FileBulkPutItem file) {
          assertEquals(
              "File " + expected.getPath().getFileName() + " uploaded to " + expected.getPath(),
              actual.getString("response"));
          // Check that file was uploaded with the correct contents
          final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
          assertEquals(200, getResp.status());
          assertEquals(file.fileContents(), getResp.text());
        } else {
          fail();
        }
      }
    }

    /**
     * File contents can be attached under a name other than the file's uploaded name using the input_file_name field
     */
    @Test
    void bulkUploadCreate() {
      // Upload a list of items, including one conflict
      final List<BulkPutItem> toUpload = List.of(
          new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file in one folder"),
          new BulkPutItem.FileBulkPutItem(
              Path.of("myDir/file.txt"),
              "file with same name in another folder",
              "otherFile.txt"));

      final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

      // Check Response
      assertEquals(207, resp.status());
      final var respBody = getArrayBody(resp);
      assertEquals(toUpload.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = toUpload.get(i);
        final var actual = respBody.get(i).asJsonObject();

        // Check the PUT response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.getPath().toString(), actual.getString("item"));
        if (expected instanceof BulkPutItem.FileBulkPutItem file) {
          assertEquals(
              "File " + expected.getPath().getFileName() + " uploaded to " + expected.getPath(),
              actual.getString("response"));
          // Check that file was uploaded with the correct contents
          final var getResp = wsServer.get(ownerToken, workspaceId, expected.getPath());
          assertEquals(200, getResp.status());
          assertEquals(file.fileContents(), getResp.text());
        } else {
          fail();
        }
      }
    }

    @Nested
    class MalformedRequest {
      private final static String endpoint = "/ws/bulk/%d";

      /**
       * A PUT request with no "body" component fails with a 400
       */
      @Test
      void noBodyRejected() {
        final var formData = FormData.create();
        final var fileContents = new FilePayload(
            "myFile.txt",
            "text/plain",
            "example file contents".getBytes(StandardCharsets.UTF_8));

        // Generate the request
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setMultipart(formData.set("files", fileContents));

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
        assertEquals(400, resp.status());
        final var respBody = getBody(resp);
        assertEquals("MALFORMED_REQUEST", respBody.getString("type"));
        assertEquals("Invalid body format.", respBody.getString("message"));
      }

      /**
       * A PUT request attempting to upload files must include a "files" component
       */
      @Test
      void noFilesFileUploadRejected() {
        final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file contents");
        final var formData = FormData.create();

        // Generate the request
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setMultipart(formData.set("body", Json.createArrayBuilder().add(fileUpload.toJson()).build().toString()));

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
        assertEquals(207, resp.status());
        final var fileResp = getArrayBody(resp).getFirst().asJsonObject();
        assertEquals("file.txt", fileResp.getString("item"));
        assertEquals(400, fileResp.getInt("status"));

        final var fileError = fileResp.getJsonObject("response");
        assertEquals("MALFORMED_REQUEST", fileError.getString("type"));
        assertEquals("No file provided with the name file.txt", fileError.getString("message"));
        assertEquals("Attach file contents under the 'files' part of the request.", fileError.getString("cause"));

        assertEquals(404, wsServer.get(ownerToken, workspaceId, fileUpload.getPath()).status());
      }

      /**
       * If multiple files are attached under the same name, the request is rejected.
       */
      @Test
      void attachedFileNameConflictRejected() {
        final List<BulkPutItem> toUpload = List.of(
            new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file in one folder"),
            new BulkPutItem.FileBulkPutItem(Path.of("myDir/file.txt"), "file with same name in another folder"));

        final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

        // Check Response
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Cannot process request: multiple files are attached under the same name.", body.getString("message"));

        // Check that no files were actually uploaded
        for(final var item : toUpload) {
          assertEquals(404, wsServer.get(ownerToken, workspaceId, item.getPath()).status());
        }
      }

      /**
       * Reject the request if multiple files are trying to be uploaded to the same location.
       */
      @ParameterizedTest
      @MethodSource("multipleItemsSameLocationArgs")
      void twoItemsToSameLocationRejected(List<BulkPutItem> toUpload) {
        final var resp = wsServer.bulkPut(ownerToken, workspaceId, toUpload);

        // Check Response
        assertEquals(409, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Multiple items are attempting to be uploaded to the same location. Please give all items unique names.", body.getString("message"));

        // Check that no items were actually created
        for(final var item : toUpload) {
          assertEquals(404, wsServer.get(ownerToken, workspaceId, item.getPath()).status());
        }
      }

      private static Stream<Arguments> multipleItemsSameLocationArgs() {
        final var fileName = Path.of("file.txt");
        final var dirName = Path.of("myDir");
        final List<BulkPutItem> fileExample = List.of(
            new BulkPutItem.FileBulkPutItem(fileName, "file in one folder"),
            new BulkPutItem.FileBulkPutItem(fileName, "file with same name", "otherFile.txt"));

        final List<BulkPutItem> dirExample = List.of(
            new BulkPutItem.DirectoryBulkPutItem(dirName, true),
            new BulkPutItem.DirectoryBulkPutItem(dirName));

        final List<BulkPutItem> mixedExample = List.of(
            new BulkPutItem.DirectoryBulkPutItem(dirName),
            new BulkPutItem.FileBulkPutItem(dirName, "file with directory name"));

        final List<BulkPutItem> nestedExample = List.of(
            new BulkPutItem.FileBulkPutItem(dirName.resolve(fileName), "file in one folder"),
            new BulkPutItem.FileBulkPutItem(dirName.resolve(fileName), "file with same name", "otherFile.txt"));

        return Stream.of(
            Arguments.arguments(named("Two Files", fileExample)),
            Arguments.arguments(named("Two Directories", dirExample)),
            Arguments.arguments(named("File and Directory", mixedExample)),
            Arguments.arguments(named("Two Files in a Directory", nestedExample)));
      }

      /**
       * The input must be a multipart/form-data, even when just creating multiple directories
       */
      @Test
      void nonMultipartFails() {
        final BulkPutItem directoryUpload = new BulkPutItem.DirectoryBulkPutItem(Path.of("myDir"));
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("content-type", "application/json")
            .setData(Json.createArrayBuilder().add(directoryUpload.toJson()).build().toString());

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
        assertEquals(400, resp.status());
        final var respBody = getBody(resp);
        assertEquals("MALFORMED_REQUEST", respBody.getString("type"));
        assertEquals("Invalid body format.", respBody.getString("message"));

        assertEquals(404, wsServer.get(ownerToken, workspaceId, directoryUpload.getPath()).status());
      }

      /**
       * The "body" part of the request must be a JSON
       */
      @Test
      void nonJSONBodyRejected() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setMultipart(FormData.create().set("body", "make a new folder please"));

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
        assertEquals(400, resp.status());
        final var respBody = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", respBody.getString("type"));
        assertTrue(respBody.getString("message").startsWith("Invalid body format. Expected body format is an array of JSON objects with the form:"));
      }

      /**
       * Directories can't have custom input names.
       */
      @Test
      void customInputNameDisallowedDirectory() {
        final var dirInput = Json.createObjectBuilder()
                                 .add("path", "myDir")
                                 .add("type", "directory")
                                 .add("input_file_name", "otherDir")
                                 .build();

        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer " + ownerToken)
            .setMultipart(FormData.create().set("body", dirInput.toString()));

        final var resp = wsServer.makeRequest(
            endpoint.formatted(workspaceId),
            options,
            WorkspaceRequests.RequestType.PUT);
        assertEquals(400, resp.status());
        final var respBody = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", respBody.getString("type"));
        assertTrue(respBody
                       .getString("message")
                       .startsWith(
                           "Invalid body format. Expected body format is an array of JSON objects with the form:"));
      }

      /**
       * The "files" part of the request, if provided, must contain files.
       */
      @Test
      void bodyInFilesRejected() {
        final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(Path.of("file.txt"), "file contents");
        final var body = Json.createArrayBuilder().add(fileUpload.toJson()).build().toString();
        final var formData = FormData.create();

        // Generate the request
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setMultipart(formData.set("body", body).set("files", body));

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
        assertEquals(207, resp.status());
        final var fileResp = getArrayBody(resp).getFirst().asJsonObject();
        assertEquals("file.txt", fileResp.getString("item"));
        assertEquals(400, fileResp.getInt("status"));

        final var fileError = fileResp.getJsonObject("response");
        assertEquals("MALFORMED_REQUEST", fileError.getString("type"));
        assertEquals("No file provided with the name file.txt", fileError.getString("message"));
        assertEquals("Attach file contents under the 'files' part of the request.", fileError.getString("cause"));

        assertEquals(404, wsServer.get(ownerToken, workspaceId, fileUpload.getPath()).status());
      }

      /**
       * The PUT request must specify items to upload
       */
      @Test
      void emptyBodyRejected() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setMultipart(FormData.create().set("body", "[]"));

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.PUT);
        assertEquals(400, resp.status());
        final var respBody = getBody(resp);
        assertEquals("MALFORMED_REQUEST", respBody.getString("type"));
        assertEquals("Cannot process request: at least one item must be specified.", respBody.getString("message"));
      }
    }

    @Nested
    class Overwrite {
      @BeforeEach
      void beforeEach() {
        wsServer.putFile(ownerToken, workspaceId, Path.of("myFile.txt"), "original file contents");
      }

      /**
       * Directories can't use the overwrite flag.
       */
      @Test
      void overwriteDisallowedDirectory() {
        final var dirInput = Json.createObjectBuilder()
                                 .add("path", "myDir")
                                 .add("type", "directory")
                                 .add("overwrite", true)
                                 .build();

        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer " + ownerToken)
            .setMultipart(FormData.create().set("body", dirInput.toString()));

        final var resp = wsServer.makeRequest(
            "/ws/bulk/%d".formatted(workspaceId),
            options,
            WorkspaceRequests.RequestType.PUT);
        assertEquals(400, resp.status());
        final var respBody = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", respBody.getString("type"));
        assertTrue(respBody
                       .getString("message")
                       .startsWith(
                           "Invalid body format. Expected body format is an array of JSON objects with the form:"));
      }

      /**
       * When the "overwrite" flag is not set, it defaults to false
       */
      @Test
      void overwriteUnset() {
        final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(
            Path.of("myFile.txt"),
            "new file contents"
        );

        // The overall response was a 207 Multipart
        final var resp = wsServer.bulkPut(ownerToken, workspaceId, List.of(fileUpload));
        assertEquals(207, resp.status());
        final var body = getArrayBody(resp);
        assertEquals(1, body.size());

        // The item's specific response was a 409
        final var item = body.getFirst().asJsonObject();
        final var itemResp = item.getJsonObject("response");
        assertEquals("myFile.txt", item.getString("item"));
        assertEquals(409, item.getInt("status"));
        assertEquals("INTERNAL_ERROR", itemResp.getString("type"));
        assertEquals("myFile.txt already exists.", itemResp.getString("message"));

        // The file's contents were NOT overwritten
        assertEquals("original file contents", wsServer.get(ownerToken, workspaceId, Path.of("myFile.txt")).text());
      }

      /**
       * When the "overwrite" flag is set to false, the server will not upload a file if it already exists
       */
      @Test
      void overwriteFalse() {
        final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(
            Path.of("myFile.txt"),
            "new file contents",
            false
        );

        // The overall response was a 207 Multipart
        final var resp = wsServer.bulkPut(ownerToken, workspaceId, List.of(fileUpload));
        assertEquals(207, resp.status());
        final var body = getArrayBody(resp);
        assertEquals(1, body.size());

        // The item's specific response was a 409
        final var item = body.getFirst().asJsonObject();
        final var itemResp = item.getJsonObject("response");
        assertEquals("myFile.txt", item.getString("item"));
        assertEquals(409, item.getInt("status"));
        assertEquals("INTERNAL_ERROR", itemResp.getString("type"));
        assertEquals("myFile.txt already exists.", itemResp.getString("message"));

        // The file's contents were NOT overwritten
        assertEquals("original file contents", wsServer.get(ownerToken, workspaceId, Path.of("myFile.txt")).text());
      }

      /**
       * When the "overwrite" flag is set to true, the server will overwrite a file if it is present
       */
      @Test
      void overwriteTrue() {
        final BulkPutItem fileUpload = new BulkPutItem.FileBulkPutItem(
            Path.of("myFile.txt"),
            "new file contents",
            true
        );

        // The overall response was a 207 Multipart
        final var resp = wsServer.bulkPut(ownerToken, workspaceId, List.of(fileUpload));
        assertEquals(207, resp.status());
        final var body = getArrayBody(resp);
        assertEquals(1, body.size());

        // The item's specific response was a 200
        final var item = body.getFirst().asJsonObject();
        assertEquals("myFile.txt", item.getString("item"));
        assertEquals(200, item.getInt("status"));
        assertEquals("File myFile.txt uploaded to myFile.txt", item.getString("response"));

        // The file's contents were overwritten
        assertEquals("new file contents", wsServer.get(ownerToken, workspaceId, Path.of("myFile.txt")).text());
      }
    }
  }

  @Nested
  class BulkPost {
    private int workspaceId;
    private int otherWorkspaceId;
    private final Path destinationPath = Path.of("./destination_dir");

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace(ownerToken, "bulkPostWS", parcelId);
      otherWorkspaceId = wsServer.createWorkspace(ownerToken, "otherBulkPostWs", parcelId);

      // Prepopulate ws with contents
      final List<BulkPutItem> wsContents = List.of(
          new BulkPutItem.DirectoryBulkPutItem("top_dir"),
          new BulkPutItem.DirectoryBulkPutItem("top_dir/nested_dir"),
          new BulkPutItem.DirectoryBulkPutItem("top_dir/other_nested_dir"),
          new BulkPutItem.DirectoryBulkPutItem("other_dir"),
          new BulkPutItem.DirectoryBulkPutItem("other_dir/nested_dir"),
          new BulkPutItem.FileBulkPutItem("top_file.txt", "top level file"),
          new BulkPutItem.FileBulkPutItem("top_dir/sub_file.txt", "file within a directory"),
          new BulkPutItem.FileBulkPutItem("other_dir/other_file.txt", "another file within a directory"),
          new BulkPutItem.FileBulkPutItem("top_dir/nested_dir/nested_file.txt", "file within a nested directory"),
          new BulkPutItem.DirectoryBulkPutItem("destination_dir"),
          new BulkPutItem.DirectoryBulkPutItem("destination")
      );
      wsServer.bulkPut(ownerToken, workspaceId, wsContents);
      wsServer.bulkPut(ownerToken, otherWorkspaceId, List.of(new BulkPutItem.DirectoryBulkPutItem("destination_dir")));
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
      wsServer.deleteWorkspace(otherWorkspaceId);
    }

    /**
     * Basic successful cases of moving files within a workspace.
     * All of these should return a top level status of 207,
     *    and an array of JSON objects with the same length as the input list.
     * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
     *    and a 'response' field that says the item was moved.
     * Additionally, the item should only be findable at its new location
     */
    @ParameterizedTest
    @MethodSource("bulkPostBasicCasesArgs")
    void bulkMoveSameWorkspaceBasicCases(List<Path> inputs) {
      final var resp = wsServer.bulkMove(
          ownerToken,
          workspaceId,
          inputs,
          destinationPath,
          Optional.empty(),
          Optional.empty());


      // Check status code
      assertEquals(207, resp.status());

      // Check details of response
      final var respBody = getArrayBody(resp);
      assertEquals(inputs.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = inputs.get(i);
        final var expectedDestination = destinationPath.resolve(expected.getFileName());
        final var actual = respBody.get(i).asJsonObject();

        // Check the POST response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.toString(), actual.getString("item"));
        assertEquals("'%s' in Workspace %d moved to '%s' in Workspace %d"
                         .formatted(expected, workspaceId, expectedDestination, workspaceId),
                     actual.getString("response"));

        // Simple check that the item was actually moved:
        //  trying to get it at its old location should return a 404 Resource Not Found
        //  while trying to get it at its new location should return a 200
        final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
        assertEquals(404, getOldResp.status());

        final var getNewResp = wsServer.get(ownerToken, workspaceId, expectedDestination);
        assertEquals(200, getNewResp.status());
      }
    }

    /**
     * Basic successful cases of moving files from one workspace to another.
     * All of these should return a top level status of 207,
     *    and an array of JSON objects with the same length as the input list.
     * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
     *    and a 'response' field that says the item was moved.
     * Additionally, the item should only be findable at its new location
     */
    @ParameterizedTest
    @MethodSource("bulkPostBasicCasesArgs")
    void bulkMoveBtwnWorkspaceBasicCases(List<Path> inputs) {
      final var resp = wsServer.bulkMove(
          ownerToken,
          workspaceId,
          inputs,
          destinationPath,
          Optional.of(otherWorkspaceId),
          Optional.empty());


      // Check status code
      assertEquals(207, resp.status());

      // Check details of response
      final var respBody = getArrayBody(resp);
      assertEquals(inputs.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = inputs.get(i);
        final var expectedDestination = destinationPath.resolve(expected.getFileName());
        final var actual = respBody.get(i).asJsonObject();

        // Check the POST response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.toString(), actual.getString("item"));
        assertEquals("'%s' in Workspace %d moved to '%s' in Workspace %d"
                         .formatted(expected, workspaceId, expectedDestination, otherWorkspaceId),
                     actual.getString("response"));

        // Simple check that the item was actually moved:
        //  trying to get it at both its old and new location should return a 200
        //  while trying to get it at its new location should return a 200
        final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
        assertEquals(404, getOldResp.status());

        final var getNewResp = wsServer.get(ownerToken, otherWorkspaceId, expectedDestination);
        assertEquals(200, getNewResp.status());
      }
    }

    /**
     * Basic successful cases of copying files within a workspace.
     * All of these should return a top level status of 207,
     *    and an array of JSON objects with the same length as the input list.
     * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
     *    and a 'response' field that says the item was moved.
     * Additionally, the item should be findable at both its old and new location
     */
    @ParameterizedTest
    @MethodSource("bulkPostBasicCasesArgs")
    void bulkCopySameWorkspaceBasicCases(List<Path> inputs) {
      final var resp = wsServer.bulkCopy(
          ownerToken,
          workspaceId,
          inputs,
          destinationPath,
          Optional.empty(),
          Optional.empty());


      // Check status code
      assertEquals(207, resp.status());

      // Check details of response
      final var respBody = getArrayBody(resp);
      assertEquals(inputs.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = inputs.get(i);
        final var expectedDestination = destinationPath.resolve(expected.getFileName());
        final var actual = respBody.get(i).asJsonObject();

        // Check the POST response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.toString(), actual.getString("item"));
        assertEquals("'%s' in Workspace %d copied to '%s' in Workspace %d"
                         .formatted(expected, workspaceId, expectedDestination, workspaceId),
                     actual.getString("response"));

        // Simple check that the item was actually copied:
        //  trying to get it at both its old and new location should return a 200
        final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
        assertEquals(200, getOldResp.status());

        final var getNewResp = wsServer.get(ownerToken, workspaceId, expectedDestination);
        assertEquals(200, getNewResp.status());

        // The copied file has the same contents
        assertEquals(getOldResp.text(), getNewResp.text());
      }
    }

    /**
     * Basic successful cases of copying files from one workspace to another.
     * All of these should return a top level status of 207,
     *    and an array of JSON objects with the same length as the input list.
     * Each object in the array should have a status of 200, an 'item' field with the uploaded item's name,
     *    and a 'response' field that says the item was moved.
     * Additionally, the item should be findable at both its old and new location
     */
    @ParameterizedTest
    @MethodSource("bulkPostBasicCasesArgs")
    void bulkCopyBtwnWorkspaceBasicCases(List<Path> inputs) {
      final var resp = wsServer.bulkCopy(
          ownerToken,
          workspaceId,
          inputs,
          destinationPath,
          Optional.of(otherWorkspaceId),
          Optional.empty());


      // Check status code
      assertEquals(207, resp.status());

      // Check details of response
      final var respBody = getArrayBody(resp);
      assertEquals(inputs.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = inputs.get(i);
        final var expectedDestination = destinationPath.resolve(expected.getFileName());
        final var actual = respBody.get(i).asJsonObject();

        // Check the POST response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.toString(), actual.getString("item"));
        assertEquals("'%s' in Workspace %d copied to '%s' in Workspace %d"
                         .formatted(expected, workspaceId, expectedDestination, otherWorkspaceId),
                     actual.getString("response"));

        // Simple check that the item was actually copied:
        //  trying to get it at both its old and new location should return a 200
        final var getOldResp = wsServer.get(ownerToken, workspaceId, expected);
        assertEquals(200, getOldResp.status());

        final var getNewResp = wsServer.get(ownerToken, otherWorkspaceId, expectedDestination);
        assertEquals(200, getNewResp.status());

        // The copied file has the same contents
        assertEquals(getOldResp.text(), getNewResp.text());
      }
    }

    /**
     * Generate arguments to test basic upload cases.
     */
    private static Stream<Arguments> bulkPostBasicCasesArgs() {
      final var topFileInput = Path.of("top_file.txt");
      final var nestedFileInput = Path.of("other_dir/other_file.txt");
      final var topDirInput = Path.of("top_dir");
      final var nestedDirInput = Path.of("other_dir/nested_dir");
      final var siblingNameInput = Path.of("destination");

      return Stream.of(
          Arguments.arguments(named("Top Level File Single Bulk POST", List.of(topFileInput))),
          Arguments.arguments(named("Top Level Directory Single Bulk POST", List.of(topDirInput))),
          Arguments.arguments(named("Nested File Single Bulk POST", List.of(nestedFileInput))),
          Arguments.arguments(named("Nested Directory Single Bulk POST", List.of(nestedDirInput))),
          Arguments.arguments(named("Multiple Files Bulk POST", List.of(topFileInput, nestedFileInput))),
          Arguments.arguments(named("Multiple Directories Bulk POST", List.of(topDirInput, nestedDirInput))),
          Arguments.arguments(named(
              "Mixed Files and Directories Bulk POST",
              List.of(topFileInput, nestedFileInput, nestedDirInput, topDirInput))),
          Arguments.arguments(named("Sibling Directory with Subset Name", List.of(siblingNameInput)))
      );
    }

    /**
     * When only one item move fails, the overall status is 207, the successful items have a status of 200,
     * and the unsuccessful items have an appropriate error status.
     */
    @Test
    void mixedResultsMove() {
      final var resp = wsServer.bulkMove(
          ownerToken,
          workspaceId,
          List.of(Path.of("fake_file.seq"), Path.of("top_file.txt"), Path.of("other_dir")),
          Path.of("top_dir/other_nested_dir"),
          Optional.empty(),
          Optional.empty());

      // Check Response
      assertEquals(207, resp.status());
      final var respBody = getArrayBody(resp);
      assertEquals(3, respBody.size());

      // First item should be the nonexistant file with a 404 File Not Found
      final var fakeFile = respBody.getFirst().asJsonObject();
      assertEquals("fake_file.seq", fakeFile.getString("item"));
      assertEquals(404, fakeFile.getInt("status"));

      // Second item should be the file that exists
      final var realFile = respBody.get(1).asJsonObject();
      assertEquals("top_file.txt", realFile.getString("item"));
      assertEquals(200, realFile.getInt("status"));
      // Check the item was moved
      assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("top_file.txt")).status());
      assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/top_file.txt")).status());

      // Third item should be the directory that exists
      final var otherFile = respBody.getLast().asJsonObject();
      assertEquals("other_dir", otherFile.getString("item"));
      assertEquals(200, otherFile.getInt("status"));
      // Check the item was moved
      assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("other_dir")).status());
      assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/other_dir")).status());
    }

    /**
     * When only one item copy fails, the overall status is 207, the successful items have a status of 200,
     * and the unsuccessful items have an appropriate error status.
     */
    @Test
    void mixedResultsCopy() {
      final var resp = wsServer.bulkCopy(
          ownerToken,
          workspaceId,
          List.of(Path.of("fake_file.seq"), Path.of("top_file.txt"), Path.of("other_dir")),
          Path.of("top_dir/other_nested_dir"),
          Optional.empty(),
          Optional.empty());

      // Check Response
      assertEquals(207, resp.status());
      final var respBody = getArrayBody(resp);
      assertEquals(3, respBody.size());

      // First item should be the nonexistant file with a 404 File Not Found
      final var fakeFile = respBody.getFirst().asJsonObject();
      assertEquals("fake_file.seq", fakeFile.getString("item"));
      assertEquals(404, fakeFile.getInt("status"));

      // Second item should be the file that exists
      final var realFile = respBody.get(1).asJsonObject();
      assertEquals("top_file.txt", realFile.getString("item"));
      assertEquals(200, realFile.getInt("status"));
      // Check the item was copied
      assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_file.txt")).status());
      assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/top_file.txt")).status());

      // Third item should be the directory that exists
      final var otherFile = respBody.getLast().asJsonObject();
      assertEquals("other_dir", otherFile.getString("item"));
      assertEquals(200, otherFile.getInt("status"));
      // Check the item was copied
      assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("other_dir")).status());
      assertEquals(200, wsServer.get(ownerToken, workspaceId, Path.of("top_dir/other_nested_dir/other_dir")).status());
    }

    @Nested
    class Overwrite {
      /**
       * Prep for the Overwrite Tests by putting conflict files in the destination directory
       */
      @BeforeEach
      void prepOverwriteTests() {
        final List<BulkPutItem> conflictContents = List.of(
            new BulkPutItem.FileBulkPutItem("destination_dir/top_file.txt", "conflicting top level file"),
            new BulkPutItem.DirectoryBulkPutItem("destination_dir/top_dir"),
            new BulkPutItem.DirectoryBulkPutItem("destination_dir/nested_dir"),
            new BulkPutItem.FileBulkPutItem("destination_dir/other_file.txt", "conflicting file within a directory")
        );

        wsServer.bulkPut(ownerToken, workspaceId, conflictContents);
        wsServer.bulkPut(ownerToken, otherWorkspaceId, conflictContents);
      }

      /**
       * An item that exists in both the original and destination locations
       *
       * @param originalPath the path of the item to be moved or copied
       * @param originalContents the contents of the item at its original location
       * @param conflictContents the contents of the conflicting
       */
      private record ConflictItem(Path originalPath, String originalContents, String conflictContents) {}

      /**
       * Generate arguments to test basic upload cases.
       */
      private static Stream<Arguments> overwriteCasesArgs() {
        final var topFile = new ConflictItem(
            Path.of("top_file.txt"),
            "top level file",
            "conflicting top level file");
        final var nestedFile = new ConflictItem(
            Path.of("other_dir/other_file.txt"),
            "another file within a directory",
            "conflicting file within a directory");
        final var topDir = new ConflictItem(
            Path.of("top_dir"),
            "["
            + "{\"name\":\"nested_dir\",\"type\":\"DIRECTORY\",\"contents\":["
            + "{\"name\":\"nested_file.txt\",\"type\":\"TEXT\"}]},"
            + "{\"name\":\"other_nested_dir\",\"type\":\"DIRECTORY\",\"contents\":[]},"
            + "{\"name\":\"sub_file.txt\",\"type\":\"TEXT\"}]",
            JsonArray.EMPTY_JSON_ARRAY.toString());
        final var nestedDir = new ConflictItem(
            Path.of("other_dir/nested_dir"),
            JsonArray.EMPTY_JSON_ARRAY.toString(),
            JsonArray.EMPTY_JSON_ARRAY.toString());

        return Stream.of(
            Arguments.arguments(named("Top Level File", List.of(topFile))),
            Arguments.arguments(named("Top Level Directory", List.of(topDir))),
            Arguments.arguments(named("Nested File", List.of(nestedFile))),
            Arguments.arguments(named("Nested Directory", List.of(nestedDir))),
            Arguments.arguments(named("Multiple Files", List.of(topFile, nestedFile))),
            Arguments.arguments(named("Multiple Directories", List.of(topDir, nestedDir))),
            Arguments.arguments(named("Mixed Files and Directories", List.of(topFile, nestedFile, nestedDir, topDir)))
        );
      }

      /**
       * With overwrite unset, a conflict is returned. This tests for both within and between workspaces
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkMoveOverwriteUnset(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var withinResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.empty(),
            Optional.empty());

        final var betweenResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.of(otherWorkspaceId),
            Optional.empty());

        // Check Status Code
        assertEquals(207, withinResp.status());
        assertEquals(207, betweenResp.status());

        // Check Details of Responses
        final var withinRespBody = getArrayBody(withinResp);
        final var betweenRespBody = getArrayBody(betweenResp);

        assertEquals(withinRespBody.size(), betweenRespBody.size());

        for (int i = 0; i < withinRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualWithin = withinRespBody.get(i).asJsonObject();
          final var actualBetween = betweenRespBody.get(i).asJsonObject();

          assertEquals(409, actualWithin.getInt("status"));
          assertEquals(409, actualBetween.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

          // Check that the original file was not moved due to the conflict,
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
        }
      }

      /**
       * With overwrite set to false, a conflict is returned. This tests for both within and between workspaces
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkMoveOverwriteFalse(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var withinResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.empty(),
            Optional.of(false));

        final var betweenResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.of(otherWorkspaceId),
            Optional.of(false));

        // Check Status Code
        assertEquals(207, withinResp.status());
        assertEquals(207, betweenResp.status());

        // Check Details of Responses
        final var withinRespBody = getArrayBody(withinResp);
        final var betweenRespBody = getArrayBody(betweenResp);

        assertEquals(withinRespBody.size(), betweenRespBody.size());

        for (int i = 0; i < withinRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualWithin = withinRespBody.get(i).asJsonObject();
          final var actualBetween = betweenRespBody.get(i).asJsonObject();

          assertEquals(409, actualWithin.getInt("status"));
          assertEquals(409, actualBetween.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

          // Check that the original file was not moved due to the conflict,
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
        }
      }

      /**
       * With overwrite set to true, no conflict occurs. This tests for moving within a workspace
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkMoveOverwriteTrueWithinWS(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var withinResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.empty(),
            Optional.of(true));

        // Check Status Code
        assertEquals(207, withinResp.status());

        // Check Details of Responses
        final var withinRespBody = getArrayBody(withinResp);


        for (int i = 0; i < withinRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualWithin = withinRespBody.get(i).asJsonObject();

          assertEquals(200, actualWithin.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());

          // Check that the original file was moved
          assertEquals(404, wsServer.get(ownerToken, workspaceId, expected.originalPath).status());
        }
      }

      /**
       * With overwrite set to true, no conflict occurs. This tests for moving between workspaces
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkMoveOverwriteTrueBetweenWS(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var betweenResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.of(otherWorkspaceId),
            Optional.of(true));

        // Check Status Code
        assertEquals(207, betweenResp.status());

        // Check Details of Responses
        final var betweenRespBody = getArrayBody(betweenResp);

        for (int i = 0; i < betweenRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualBetween = betweenRespBody.get(i).asJsonObject();

          assertEquals(200, actualBetween.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.originalContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

          // Check that the original file was moved
          assertEquals(404, wsServer.get(ownerToken, workspaceId, expected.originalPath).status());
        }
      }

      /**
       * With overwrite unset, a conflict is returned. This tests for both within and between workspaces
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkCopyOverwriteUnset(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var withinResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.empty(),
            Optional.empty());

        final var betweenResp = wsServer.bulkMove(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.of(otherWorkspaceId),
            Optional.empty());

        // Check Status Code
        assertEquals(207, withinResp.status());
        assertEquals(207, betweenResp.status());

        // Check Details of Responses
        final var withinRespBody = getArrayBody(withinResp);
        final var betweenRespBody = getArrayBody(betweenResp);

        assertEquals(withinRespBody.size(), betweenRespBody.size());

        for (int i = 0; i < withinRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualWithin = withinRespBody.get(i).asJsonObject();
          final var actualBetween = betweenRespBody.get(i).asJsonObject();

          assertEquals(409, actualWithin.getInt("status"));
          assertEquals(409, actualBetween.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

          // Check that the original file was untouched.
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
        }
      }

      /**
       * With overwrite set to false, a conflict is returned. This tests for both within and between workspaces
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkCopyOverwriteFalse(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var withinResp = wsServer.bulkCopy(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.empty(),
            Optional.of(false));

        final var betweenResp = wsServer.bulkCopy(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.of(otherWorkspaceId),
            Optional.of(false));

        // Check Status Code
        assertEquals(207, withinResp.status());
        assertEquals(207, betweenResp.status());

        // Check Details of Responses
        final var withinRespBody = getArrayBody(withinResp);
        final var betweenRespBody = getArrayBody(betweenResp);

        assertEquals(withinRespBody.size(), betweenRespBody.size());

        for (int i = 0; i < withinRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualWithin = withinRespBody.get(i).asJsonObject();
          final var actualBetween = betweenRespBody.get(i).asJsonObject();

          assertEquals(409, actualWithin.getInt("status"));
          assertEquals(409, actualBetween.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());
          assertEquals(expected.conflictContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

          // Check that the original file was not touched
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
        }
      }

      /**
       * With overwrite set to true, no conflict occurs. This tests for moving within a workspace
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkCopyOverwriteTrueWithinWS(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var withinResp = wsServer.bulkCopy(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.empty(),
            Optional.of(true));

        // Check Status Code
        assertEquals(207, withinResp.status());

        // Check Details of Responses
        final var withinRespBody = getArrayBody(withinResp);


        for (int i = 0; i < withinRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualWithin = withinRespBody.get(i).asJsonObject();

          assertEquals(200, actualWithin.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, conflictLocation).text());

          // Check that the original file is untouched
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
        }
      }

      /**
       * With overwrite set to true, no conflict occurs. This tests for moving between workspaces
       */
      @ParameterizedTest
      @MethodSource("overwriteCasesArgs")
      void bulkCopyOverwriteTrueBetweenWS(List<ConflictItem> inputs) {
        final var paths = inputs.stream().map(i -> i.originalPath).toList();
        final var destination = Path.of("./destination_dir");

        final var betweenResp = wsServer.bulkCopy(
            ownerToken,
            workspaceId,
            paths,
            destination,
            Optional.of(otherWorkspaceId),
            Optional.of(true));

        // Check Status Code
        assertEquals(207, betweenResp.status());

        // Check Details of Responses
        final var betweenRespBody = getArrayBody(betweenResp);

        for (int i = 0; i < betweenRespBody.size(); ++i) {
          final var expected = inputs.get(i);
          final var actualBetween = betweenRespBody.get(i).asJsonObject();

          assertEquals(200, actualBetween.getInt("status"));

          // Check file contents
          final var conflictLocation = destination.resolve(expected.originalPath.getFileName());
          assertEquals(expected.originalContents, wsServer.get(ownerToken, otherWorkspaceId, conflictLocation).text());

          // Check that the original file is untouched
          assertEquals(expected.originalContents, wsServer.get(ownerToken, workspaceId, expected.originalPath).text());
        }
      }
    }

    @Nested
    class MalformedRequest {
      private static final String endpoint = "/ws/bulk/%d";

      @Test
      void noBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
      }

      @Test
      void nonJsonBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "text/plain")
            .setData("Delete some file please");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
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
            .setData("{}");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Body must be type application/json", body.getString("message"));
      }

      @Test
      void emptyBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("{}");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
      }

      @Test
      void emptyItemsArray() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("{\"items\": [], \"moveTo\": \".\"}");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Cannot process request: at least one item must be specified.", body.getString("message"));
      }

      @Test
      void bothMoveAndCopy() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("{\"items\": [{\"path\": \"top_file.txt\"}], \"moveTo\": \".\", \"copyTo\": \".\"}");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
      }

      /**
       * One of "copyTo" or "moveTo" must be specified
       */
      @Test
      void noPostTypeSpecified() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("{\"items\": [{\"path\": \"top_file.txt\"}]");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
      }

      @Test
      void invalidPostType() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("{\"items\": [{\"path\": \"top_file.txt\"}], \"move\": \".\"}");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.POST);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertTrue(body.getString("message").startsWith("Invalid body format. Expected body format is a JSON object with the form:"));
      }
    }
  }

  @Nested
  class BulkDelete {
    private int workspaceId;

    @BeforeEach
    void beforeEach() throws IOException {
      workspaceId = wsServer.createWorkspace(ownerToken, "bulkDeleteWS", parcelId);

      // Prepopulate ws with contents
      final List<BulkPutItem> wsContents = List.of(
          new BulkPutItem.DirectoryBulkPutItem("top_dir"),
          new BulkPutItem.DirectoryBulkPutItem("top_dir/nested_dir"),
          new BulkPutItem.DirectoryBulkPutItem("top_dir/other_nested_dir"),
          new BulkPutItem.DirectoryBulkPutItem("other_dir"),
          new BulkPutItem.DirectoryBulkPutItem("other_dir/nested_dir"),
          new BulkPutItem.FileBulkPutItem("top_file.txt", "top level file"),
          new BulkPutItem.FileBulkPutItem("top_dir/sub_file.txt", "file within a directory"),
          new BulkPutItem.FileBulkPutItem("other_dir/other_file.txt", "another file within a directory"),
          new BulkPutItem.FileBulkPutItem("top_dir/nested_dir/nested_file.txt", "file within a nested directory")
      );

      wsServer.bulkPut(ownerToken, workspaceId, wsContents);
    }

    @AfterEach
    void afterEach() throws IOException {
      wsServer.deleteWorkspace(workspaceId);
    }

    /**
     * Basic successful cases.
     * All of these should return a top level status of 207,
     *    and an array of JSON objects with the same length as the input list.
     * Each object in the array should have a status of 200, an 'item' field with the deleted item's name,
     *    and a 'result' field that either says "directory created" or "file uploaded",
     *    as appropriate based on the created item's type.
     * Additionally, a GET request for the item should succeed after the PUT.
     */
    @ParameterizedTest
    @MethodSource("bulkDeleteBasicCasesArgs")
    void bulkDeleteBasicCases(List<Path> inputs) {
      final var resp = wsServer.bulkDelete(ownerToken, workspaceId, inputs);

      // Check status code
      assertEquals(207, resp.status());

      // Check details of response
      final var respBody = getArrayBody(resp);
      assertEquals(inputs.size(), respBody.size());

      for (int i = 0; i < respBody.size(); ++i) {
        final var expected = inputs.get(i);
        final var actual = respBody.get(i).asJsonObject();

        // Check the DELETE response
        assertEquals(200, actual.getInt("status"));
        assertEquals(expected.toString(), actual.getString("item"));


        // Simple check that the item was actually deleted -- trying to get it should return a 404 Resource Not Found
        final var getResp = wsServer.get(ownerToken, workspaceId, expected);
        assertEquals(404, getResp.status());
      }
    }

    /**
     * Generate arguments to test basic upload cases.
     */
    private static Stream<Arguments> bulkDeleteBasicCasesArgs() {
      final var topFileInput = Path.of("top_file.txt");
      final var nestedFileInput = Path.of("other_dir/other_file.txt");
      final var topDirInput = Path.of("top_dir");
      final var nestedDirInput = Path.of("other_dir/nested_dir");

      return Stream.of(
          Arguments.arguments(named("Top Level File Single Bulk DELETE", List.of(topFileInput))),
          Arguments.arguments(named("Top Level Directory Single Bulk DELETE", List.of(topDirInput))),
          Arguments.arguments(named("Nested File Single Bulk DELETE", List.of(nestedFileInput))),
          Arguments.arguments(named("Nested Directory Single Bulk DELETE", List.of(nestedDirInput))),
          Arguments.arguments(named("Multiple Files Bulk DELETE", List.of(topFileInput, nestedFileInput))),
          Arguments.arguments(named("Multiple Directories Bulk DELETE", List.of(topDirInput, nestedDirInput))),
          Arguments.arguments(named(
              "Mixed Files and Directories Bulk DELETE",
              List.of(topFileInput, nestedFileInput, nestedDirInput, topDirInput)))
      );
    }

    /**
     * When only one item delete fails, the overall status is 207, the successful items have a status of 200,
     * and the unsuccessful items have an appropriate error status.
     */
    @Test
    void mixedResults() {
      final var resp = wsServer.bulkDelete(
          ownerToken,
          workspaceId,
          List.of(Path.of("fake_file.seq"), Path.of("top_file.txt"), Path.of("other_dir")));

      // Check Response
      assertEquals(207, resp.status());
      final var respBody = getArrayBody(resp);
      assertEquals(3, respBody.size());

      // First item should be the nonexistant file with a 404 File Not Found
      final var fakeFile = respBody.getFirst().asJsonObject();
      assertEquals("fake_file.seq", fakeFile.getString("item"));
      assertEquals(404, fakeFile.getInt("status"));

      // Second item should be the file that exists
      final var realFile = respBody.get(1).asJsonObject();
      assertEquals("top_file.txt", realFile.getString("item"));
      assertEquals(200, realFile.getInt("status"));
      assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("top_file.txt")).status());

      // Third item should be the directory that exists
      final var otherFile = respBody.getLast().asJsonObject();
      assertEquals("other_dir", otherFile.getString("item"));
      assertEquals(200, otherFile.getInt("status"));
      assertEquals(404, wsServer.get(ownerToken, workspaceId, Path.of("other_dir")).status());
    }

    @Nested
    class MalformedRequest {
      private static final String endpoint = "/ws/bulk/%d";

      @Test
      void noBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("JSON_PARSING_EXCEPTION", body.getString("type"));
        assertEquals("Invalid body format. Expected body format is an array of paths.", body.getString("message"));
      }

      @Test
      void nonJsonBody() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "text/plain")
            .setData("Delete some file please");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
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
            .setData("[\"top-file.txt\"]");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Body must be type application/json", body.getString("message"));
      }

      @Test
      void emptyBodyArray() {
        final var options = RequestOptions
            .create()
            .setHeader("Authorization", "Bearer "+ownerToken)
            .setHeader("Content-type", "application/json")
            .setData("[]");

        final var resp = wsServer.makeRequest(endpoint.formatted(workspaceId), options, WorkspaceRequests.RequestType.DELETE);
        assertEquals(400, resp.status());
        final var body = getBody(resp);
        assertEquals("MALFORMED_REQUEST", body.getString("type"));
        assertEquals("Cannot process request: at least one item must be specified.", body.getString("message"));
      }
    }
  }
}
