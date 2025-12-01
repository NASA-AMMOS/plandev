package gov.nasa.jpl.plandev.e2e.utils;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;

import javax.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class WorkspaceRequests implements AutoCloseable {
  private final APIRequestContext request;
  private static final String hasuraAdminSecret = System.getenv("HASURA_GRAPHQL_ADMIN_SECRET");
  private static final Map<String, String> defaultHeaders = Map.of("x-hasura-role", "plandev_admin", "x-hasura-user-id", "PlanDev Legacy");
  public enum RequestType {GET, PUT, POST, DELETE}

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
   * Helper method to create an empty workspace. Parses out the workspaceId from the response.
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
    final var response = request.post("/ws/create", options);

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
    return request.post("/ws/create", options);
  }


  /**
   * Call the 'put file' endpoint in the Workspace server. Does not pass the "overwrite" flag.
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param fileLocation Where to place the plan
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
    return request.put("/ws/%d/%s".formatted(workspaceId, fileLocation), options);
  }

  /**
   * Call the 'put file' endpoint in the Workspace server. Passes the overwrite flag
   * @param token The JWT token for the user making the request
   * @param workspaceId The workspace to insert the file into
   * @param fileLocation Where to place the plan
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
    return request.put("/ws/%d/%s".formatted(workspaceId, fileLocation), options);
  }

  /**
   * Call the `list workspace contents` endpoint in the Workspace server.
   * @param token The JWT token for the user making the request.
   * @param workspaceId The workspace to list the contents of
   * @return the APIResponse from the Workspace Server
   */
  public APIResponse listWorkspaceContents(String token, int workspaceId) {
    final var options = RequestOptions.create().setHeader("Authorization ", "Bearer " +token);
    return request.get("/ws/%d".formatted(workspaceId), options);
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
    return request.get("/ws/%d".formatted(workspaceId), options);
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
    final var response = request.delete("/ws/%d".formatted(workspaceId), options);

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
    final var options = RequestOptions.create()
                                      .setHeader("Authorization", "Bearer "+authToken);
    return request.delete("/ws/%d".formatted(workspaceId), options);
  }


  @Override
  public void close() {
    request.dispose();
  }
}
