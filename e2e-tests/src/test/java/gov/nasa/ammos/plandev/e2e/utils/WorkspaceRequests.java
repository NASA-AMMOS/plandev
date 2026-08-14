package gov.nasa.ammos.plandev.e2e.utils;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.ammos.plandev.e2e.types.User;
import gov.nasa.ammos.plandev.e2e.types.workspaces.BulkPutItem;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.e2e.E2ETestSuite.test_admin;

public class WorkspaceRequests implements AutoCloseable {
  private final APIRequestContext request;
  private static final String hasuraAdminSecret = System.getenv("HASURA_GRAPHQL_ADMIN_SECRET");
  private static final Map<String, String> defaultHeaders = test_admin.session();
  public enum RequestType {GET, PUT, POST, DELETE}
  public enum MetadataMergeBehavior {deep, deepMerge, shallow, shallowMerge, overwrite}

  // Workspace URLS
  public static final String CREATE_WS_URL = "/ws/create";
  public static final String WS_URL = "/ws/%d";
  public static final String SINGLE_ITEM_URL = "/ws/%d/%s";
  public static final String BULK_URL = "/ws/bulk/%d";
  public static final String METADATA_URL = "/metadata/%d/%s";
  public static final String UNSET_METADATA_URL = "/metadata/unset/%d/%s";

  public WorkspaceRequests(Playwright playwright){
    request = playwright.request().newContext(new APIRequest.NewContextOptions()
                                                  .setBaseURL(BaseURL.WORKSPACE_SERVER.url));
  }

  /**
   * Generic 'makeRequest' method for sending a request to the workspace server
   * that is not covered by any of the helper methods
   */
  public APIResponse makeRequest(String endpoint, RequestOptions options, RequestType type) {
    switch (type) {
      case GET -> {
        return request.get(endpoint, options);
      }
      case PUT -> {
        return request.put(endpoint, options);
      }
      case POST -> {
        return request.post(endpoint, options);
      }
      case DELETE -> {
        return request.delete(endpoint, options);
      }
      default -> throw new IllegalArgumentException("Unrecognized Request Type: "+type);
    }
  }

  /**
   * Helper method to create an empty workspace owned by test admin user. Parses out the workspaceId from the response.
   * @param workspaceLocation the name of the folder to be created
   * @param parcelId the parcel id to use for the workspace
   * @return the created workspace's id
   */
  public int createWorkspace(String workspaceLocation, int parcelId) throws IOException {
    final String body = Json.createObjectBuilder()
                            .add("workspaceLocation", workspaceLocation)
                            .add("parcelId", parcelId)
                            .build()
                            .toString();
    final var options = RequestOptions.create()
                                      .setHeader("x-hasura-admin-secret", hasuraAdminSecret);
    defaultHeaders.forEach(options::setHeader);
    options.setData(body);
    final var response = request.post(CREATE_WS_URL, options);

    if(!response.ok()){
      throw new IOException(response.statusText());
    }

    return Integer.parseInt(response.text());
  }

  /**
   * Helper method to create an empty workspace owned by a particular user. Parses out the workspaceId from the response.
   *
   * Due to Workspace Creation being admin-only, this method uses the admin secret to grant otherwise non-admin users access.
   *
   * @param owner the User who will own the new workspace
   * @param workspaceLocation where to place the workspace
   * @param parcelId the parcel to use
   * @return the workspace server's response
   */
  public int createWorkspace(User owner, String workspaceLocation, int parcelId) throws IOException {
    final var body = Json.createObjectBuilder()
                         .add("workspaceLocation", workspaceLocation)
                         .add("parcelId", parcelId);

    final var options = RequestOptions.create()
                                      .setHeader("x-hasura-admin-secret", hasuraAdminSecret)
                                      .setHeader("x-hasura-role", "aerie_admin")
                                      .setHeader("x-hasura-user-id", owner.name())
                                      .setData(body.build().toString());
    final var response = request.post(CREATE_WS_URL, options);

    if(!response.ok()){
      throw new IOException(response.statusText());
    }
    return Integer.parseInt(response.text());
  }

  /**
   * Call the workspace creation endpoint using JWT authorization
   * @param userToken the JWT token to use
   * @param workspaceLocation where to place the workspace
   * @param workspaceName if set, what to name the workspace instead
   * @param parcelId the parcel to use
   * @return the workspace server's response
   */
  public APIResponse createWorkspace(String userToken, String workspaceLocation, Optional<String> workspaceName, int parcelId) {
    final var body = Json.createObjectBuilder()
                         .add("workspaceLocation", workspaceLocation)
                         .add("parcelId", parcelId);
    workspaceName.ifPresent(n -> body.add("workspaceName", n));

    final var options = RequestOptions.create()
                                      .setHeader("Authorization", "Bearer "+userToken)
                                      .setData(body.build().toString());
    return request.post(CREATE_WS_URL, options);
  }

  /**
   * Call the 'put file' endpoint in the Workspace server. Does not pass the "overwrite" flag.
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param fileLocation Where to place the file
   * @param fileContents The contents of the file to be inserted
   * @return The APIResponse from the server
   */
  public APIResponse putFile(String token, int workspaceId, Path fileLocation, String fileContents) {
    final var filePayload = new FilePayload(
        fileLocation.getFileName().toString(),
        "text/plain",
        fileContents.getBytes(StandardCharsets.UTF_8));
    final var options = RequestOptions
        .create()
        .setQueryParam("type", "file")
        .setHeader("Authorization", "Bearer "+token)
        .setMultipart(FormData.create().set("file", filePayload));
    return request.put(SINGLE_ITEM_URL.formatted(workspaceId, fileLocation), options);
  }

  /**
   * Upload a file located in the test resources to the Workspace Server using the 'PUT file' endpoint.
   * Does not pass the "overwrite" flag.
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param fileLocation Where to place the file in the workspace
   * @param resourcePath The Path to the file within the test resources folder (src/test/resources)
   * @return The APIResponse from the server
   */
  public APIResponse putFile(String token, int workspaceId, Path fileLocation, Path resourcePath) throws IOException
  {
    byte[] buffer = Files.readAllBytes(Path.of("src/test/resources/", resourcePath.toString()));
    FilePayload filePayload = new FilePayload(
        fileLocation.getFileName().toString(),
        "text/plain",
        buffer);
    final var options = RequestOptions
        .create()
        .setQueryParam("type", "file")
        .setHeader("Authorization", "Bearer "+token)
        .setMultipart(FormData.create().set("file", filePayload));
    return request.put(SINGLE_ITEM_URL.formatted(workspaceId, fileLocation), options);
  }

  /**
   * Call the 'File PUT' endpoint in the Workspace server. Passes the overwrite flag
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param fileLocation Where to place the file
   * @param fileContents The contents of the file to be inserted
   * @param overwrite whether to overwrite the file should it exist
   * @return The APIResponse from the server
   */
  public APIResponse putFile(String token, int workspaceId, Path fileLocation, String fileContents, boolean overwrite) {
    final var filePayload = new FilePayload(
        fileLocation.getFileName().toString(),
        "text/plain",
        fileContents.getBytes(StandardCharsets.UTF_8));
    final var options = RequestOptions
        .create()
        .setQueryParam("type", "file")
        .setQueryParam("overwrite", overwrite)
        .setHeader("Authorization", "Bearer "+token)
        .setMultipart(FormData.create().set("file", filePayload));
    return request.put(SINGLE_ITEM_URL.formatted(workspaceId, fileLocation), options);
  }

  /**
   * Call the 'Directory PUT' endpoint in the Workspace Server.
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param folderLocation Where to place the folder
   * @return The APIResponse from the server
   */
  public APIResponse putDirectory(String token, int workspaceId, Path folderLocation) {
    final var options = RequestOptions
        .create()
        .setQueryParam("type", "folder")
        .setHeader("Authorization", "Bearer "+token);
    return request.put(SINGLE_ITEM_URL.formatted(workspaceId, folderLocation), options);
  }

  /**
   * Call the 'File DELETE' endpoint in the Workspace server.
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param itemLocation The Path to the item to be deleted
   * @return The APIResponse from the server
   */
  public APIResponse deleteFileDirectory(String token, int workspaceId, Path itemLocation) {
    final var options = RequestOptions.create().setHeader("Authorization", "Bearer "+token);
    return request.delete(SINGLE_ITEM_URL.formatted(workspaceId, itemLocation), options);
  }

  /**
   * Calls the 'File POST' endpoint to move a file within a workspace. Passes the 'overwrite' flag
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param itemLocation The Path to the item to be moved
   * @param destination Where to move the file to
   * @param overwrite If a file with the same name exists at the destination, whether to overwrite it
   * @return The APIResponse from the server
   */
  public APIResponse moveFileDirectory(String token, int workspaceId, Path itemLocation, Path destination, boolean overwrite) {
    final var body = Json.createObjectBuilder()
                         .add("moveTo", destination.toString())
                         .add("overwrite", overwrite)
                         .build();
    final var options = RequestOptions.create()
                                      .setHeader("Authorization", "Bearer "+token)
                                      .setData(body.toString());
    return request.post(SINGLE_ITEM_URL.formatted(workspaceId, itemLocation), options);
  }

  /**
   * Calls the 'File POST' endpoint to copy a file within a workspace. Passes the 'overwrite' flag
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param itemLocation The Path to the item to be copied
   * @param destination Where to copy the file to
   * @param overwrite If a file with the same name exists at the destination, whether to overwrite it
   * @return The APIResponse from the server
   */
  public APIResponse copyFileDirectory(String token, int workspaceId, Path itemLocation, Path destination, boolean overwrite) {
    final var body = Json.createObjectBuilder()
                         .add("copyTo", destination.toString())
                         .add("overwrite", overwrite)
                         .build();
    final var options = RequestOptions.create()
                                      .setHeader("Authorization", "Bearer "+token)
                                      .setData(body.toString());
    return request.post(SINGLE_ITEM_URL.formatted(workspaceId, itemLocation), options);
  }

  /**
   * Call the `list workspace contents` endpoint in the Workspace server.
   * @param token The JWT token for the user making the request.
   * @param workspaceId The workspace to list the contents of
   * @return the APIResponse from the Workspace Server
   */
  public APIResponse listWorkspaceContents(String token, int workspaceId) {
    final var options = RequestOptions.create().setHeader("Authorization ", "Bearer " +token);
    return request.get(WS_URL.formatted(workspaceId), options);
  }

  /**
   * Call the `list workspace contents` endpoint in the Workspace server with the given headers.
   * Any authorization headers must be put inside the `headers` parameter
   * @param headers The set of headers to be used in the request
   * @param workspaceId The workspace to list the contents of
   * @return the APIResponse from the Workspace Server
   */
  public APIResponse listWorkspaceContents(Map<String, String> headers, int workspaceId) {
    final var options = RequestOptions.create();
    headers.forEach(options::setHeader);
    return request.get(WS_URL.formatted(workspaceId), options);
  }

  /**
   * Helper method to delete a workspace during test environment cleanup.
   * @param workspaceId the id of the workspace to be deleted
   * @throws IOException if the response code is not 200
   */
  public void deleteWorkspace(int workspaceId) throws IOException {
    final var options = RequestOptions.create()
                                      .setHeader("x-hasura-admin-secret", hasuraAdminSecret);
    defaultHeaders.forEach(options::setHeader);
    final var response = request.delete(WS_URL.formatted(workspaceId), options);

    if(!response.ok()){
      throw new IOException(response.statusText());
    }
  }

  /**
   * Call the `deleteWorkspace` endpoint while using a JWT
   * @param authToken the user's JWT (received from logging them into the Gateway)
   * @param workspaceId the workspace to be deleted
   * @return the APIResponse from the Workspace Server
  */
  public APIResponse deleteWorkspace(String authToken, int workspaceId) {
    final var options = RequestOptions.create().setHeader("Authorization", "Bearer "+authToken);
    return request.delete(WS_URL.formatted(workspaceId), options);
  }

  /**
   * Call the GET endpoint in the Workspace Server
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace the item is in
   * @param itemPath The Path within the workspace where the item is
   * @return The APIResponse from the server
   */
  public APIResponse get(String token, int workspaceId, Path itemPath) {
    final var options = RequestOptions.create().setHeader("Authorization", "Bearer " + token);
    return request.get(SINGLE_ITEM_URL.formatted(workspaceId, itemPath.toString()), options);
  }

  /**
   * Call the 'Bulk PUT' endpoint in the Workspace server.
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param toPut List of things to be placed on the server. If there are file contents, it will be uploaded as file.
   *              If the Optional is empty, it will be uploaded as a directory.
   * @return The APIResponse from the server
   */
  public APIResponse bulkPut(String token, int workspaceId, List<BulkPutItem> toPut) {
    final var formData = FormData.create();
    final var bodyArray = Json.createArrayBuilder();

    // Generate the request body
    for(final var putItem : toPut) {
      bodyArray.add(putItem.toJson());
      if(putItem instanceof BulkPutItem.FileBulkPutItem fileInput) {
        formData.append("files", fileInput.generateFilePayload());
      }
    }

    // Generate the request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setMultipart(formData.set("body", bodyArray.build().toString()));

    return request.put(BULK_URL.formatted(workspaceId), options);
  }

  /**
   * Call the 'Bulk POST' endpoint in the Workspace server to move items.
   *
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param paths The list of items to be affected by the request
   * @param destination The destination folder to place the items in
   * @param destinationWorkspaceId If present, the destination workspace.
   * @param overwrite If present, the value of the 'overwrite' flag
   * @return The APIResponse from the server
   */
  public APIResponse bulkMove(
      String token,
      int workspaceId,
      List<Path> paths,
      Path destination,
      Optional<Integer> destinationWorkspaceId,
      Optional<Boolean> overwrite
  ) {
    // Generate the request body
    final var body = Json.createObjectBuilder().add("moveTo", destination.toString());

    final var itemsArray = Json.createArrayBuilder();
    paths.forEach(p -> itemsArray.add(Json.createObjectBuilder().add("path", p.toString())));
    body.add("items", itemsArray);

    destinationWorkspaceId.ifPresent(wid -> body.add("toWorkspace", wid));

    overwrite.ifPresent(o -> body.add("overwrite", o));

    // Generate request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setHeader("Content-type", "application/json")
        .setData(body.build().toString());

    return request.post(BULK_URL.formatted(workspaceId), options);
  }

  /**
   * Call the 'Bulk POST' endpoint in the Workspace server to copy items.
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param paths The list of items to be affected by the request
   * @param destination The destination folder to place the items in
   * @param destinationWorkspaceId If present, the destination workspace.
   * @param overwrite If present, the value of the 'overwrite' flag
   * @return The APIResponse from the server
   */
  public APIResponse bulkCopy(
      String token,
      int workspaceId,
      List<Path> paths,
      Path destination,
      Optional<Integer> destinationWorkspaceId,
      Optional<Boolean> overwrite
  ) {
    // Generate the request body
    final var body = Json.createObjectBuilder().add("copyTo", destination.toString());

    final var itemsArray = Json.createArrayBuilder();
    paths.forEach(p -> itemsArray.add(Json.createObjectBuilder().add("path", p.toString())));
    body.add("items", itemsArray);

    destinationWorkspaceId.ifPresent(wid -> body.add("toWorkspace", wid));

    overwrite.ifPresent(o -> body.add("overwrite", o));

    // Generate request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setHeader("Content-type", "application/json")
        .setData(body.build().toString());

    return request.post(BULK_URL.formatted(workspaceId), options);
  }


  /**
   * Call the 'Bulk DELETE' endpoint in the Workspace server.
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param paths The list of items to be deleted
   * @return The APIResponse from the server
   */
  public APIResponse bulkDelete(String token, int workspaceId, List<Path> paths) {
    // Generate the request body
    final var body = Json.createArrayBuilder();
    paths.forEach(p -> body.add(p.toString()));

    // Generate request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setHeader("Content-type", "application/json")
        .setData(body.build().toString());

    return request.delete(BULK_URL.formatted(workspaceId), options);
  }

  /**
   * Call the "Metadata GET" endpoint in the Workspace server.
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @return The APIResponse from the server
   */
  public APIResponse getMetadata(String token, int workspaceId, Path filePath) {
    // Generate request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setHeader("Content-type", "application/json");

    return request.get(METADATA_URL.formatted(workspaceId, filePath.toString()), options);
  }

  /**
   * Call the "Metadata POST" (aka "setMetadataKeys") endpoint in the Workspace Server,
   * using the specified merge strategy for the "user" object
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @param readOnly If provided, the new value of "readOnly"
   * @param user If provided, the new value of "user"
   * @param mergeBehavior The merge strategy to be used to update "user"
   * @return The APIResponse from the server
   */
  public APIResponse setMetadata(
      String token,
      int workspaceId,
      Path filePath,
      Optional<Boolean> readOnly,
      Optional<JsonObject> user,
      MetadataMergeBehavior mergeBehavior
  ) {
    final var body = Json.createObjectBuilder();
    readOnly.ifPresent(ro -> body.add("readOnly", ro));
    user.ifPresent(u -> body.add("user", u));

    // Generate request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setHeader("Content-type", "application/json")
        .setQueryParam("mergeBehavior", mergeBehavior.name())
        .setData(body.build().toString());

    final var url = METADATA_URL.formatted(workspaceId, filePath.toString());
    return request.post(url, options);
  }

  /**
   * Call the "Metadata POST" (aka "setMetadataKeys") endpoint in the Workspace Server,
   * using the default merge strategy for the "user" object
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @param readOnly If provided, the new value of "readOnly"
   * @param user If provided, the new value of "user"
   * @return The APIResponse from the server
   */
  public APIResponse setMetadata(
      String token,
      int workspaceId,
      Path filePath,
      Optional<Boolean> readOnly,
      Optional<JsonObject> user
  ) {
    final var body = Json.createObjectBuilder();
    readOnly.ifPresent(ro -> body.add("readOnly", ro));
    user.ifPresent(u -> body.add("user", u));

    // Generate request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setHeader("Content-type", "application/json")
        .setData(body.build().toString());

    final var url = METADATA_URL.formatted(workspaceId, filePath.toString());
    return request.post(url, options);
  }

  /**
   * Set the "readOnly" metadata property of a file to the specified value
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @param readOnly The new value of "readOnly"
   * @return The APIResponse from the server
   */
  public APIResponse setReadOnly(
      String token,
      int workspaceId,
      Path filePath,
      boolean readOnly
  ) {
    return setMetadata(token, workspaceId, filePath, Optional.of(readOnly), Optional.empty());
  }

  /**
   * Set the "user" metadata property of the file to the specified value, using the default merge strategy
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @param user The new value of "user"
   * @return The APIResponse from the server
   */
  public APIResponse setUserMetadata(
      String token,
      int workspaceId,
      Path filePath,
      JsonObject user
  ) {
    return setMetadata(token, workspaceId, filePath, Optional.empty(), Optional.of(user));
  }

  /**
   * Set the "user" metadata property of the file to the specified value, using the specified merge strategy
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @param user The new value of "user"
   * @param mergeBehavior The merge strategy the server should use
   * @return The APIResponse from the server
   */
  public APIResponse setUserMetadata(
      String token,
      int workspaceId,
      Path filePath,
      JsonObject user,
      MetadataMergeBehavior mergeBehavior
  ) {
    return setMetadata(token, workspaceId, filePath, Optional.empty(), Optional.of(user), mergeBehavior);
  }

  /**
   * Call the "Unset Metadata POST" (aka "unset metadata keys) endpoint in the Workspace Server
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @param unsetKeys The set of keys to unset
   * @return The APIResponse from the server
   */
  public APIResponse unsetMetadata(String token, int workspaceId, Path filePath, List<String> unsetKeys) {
    final var body = Json.createArrayBuilder(unsetKeys);

    // Generate request
    final var options = RequestOptions
        .create()
        .setHeader("Authorization", "Bearer "+token)
        .setHeader("Content-type", "application/json")
        .setData(body.build().toString());

    return request.post(UNSET_METADATA_URL.formatted(workspaceId, filePath.toString()), options);
  }

  /**
   * Call the "Metadata DELETE" endpoint in the Workspace Server
   * @param token The JWT token for the user making the request
   * @param workspaceId The source workspace
   * @param filePath The path to the base file (NOT the metadata file)
   * @return The APIResponse from the server
   */
  public APIResponse deleteMetadata(String token, int workspaceId, Path filePath) {
    final var options = RequestOptions.create().setHeader("Authorization", "Bearer "+token);
    return request.delete(METADATA_URL.formatted(workspaceId, filePath.toString()), options);
  }

  @Override
  public void close() {
    request.dispose();
  }
}
