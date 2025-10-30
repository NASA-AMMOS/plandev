package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.exceptions.JWTVerificationException;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.WorkspaceAction;
import gov.nasa.jpl.aerie.permissions.exceptions.Forbidden;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;
import gov.nasa.jpl.aerie.permissions.gql.WorkspaceId;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.types.BulkPutItem;
import gov.nasa.jpl.aerie.workspace.server.types.PostActions;
import gov.nasa.jpl.aerie.workspace.server.types.ItemType;
import io.javalin.Javalin;
import io.javalin.apibuilder.ApiBuilder;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.UploadedFile;
import io.javalin.plugin.Plugin;
import io.javalin.validation.ValidationException;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.javalin.apibuilder.ApiBuilder.before;
import static io.javalin.apibuilder.ApiBuilder.path;

public class WorkspaceBindings implements Plugin {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceBindings.class);
  private final JWTService jwtService;
  private final WorkspaceService workspaceService;
  private final PermissionsService permissionsService;
  private final String hasuraAdminSecret;

  public WorkspaceBindings(
      final JWTService jwtService,
      final WorkspaceService workspaceService,
      final PermissionsService permissionsService,
      final String hasuraAdminSecret) {
    this.jwtService = jwtService;
    this.workspaceService = workspaceService;
    this.permissionsService = permissionsService;
    this.hasuraAdminSecret = hasuraAdminSecret;
  }

  private record PathInformation(int workspaceId, Path filePath) {
    static PathInformation of(Context context) {
      final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
      final var filePath = Path.of(context.pathParam("filePath"));

      return new PathInformation(workspaceId, filePath);
    }

    String fileName() {
      return filePath.getFileName().toString();
    }
  }

  @Override
  public void apply(final Javalin javalin) {
    javalin.routes(() -> {
      before("/ws/*", ctx -> {
        // don't force auth on health check
        // skip auth for browser preflight (OPTIONS) requests
        if (ctx.method() != HandlerType.OPTIONS) {
          authorize(ctx);
        }
      });
      // Health check
      path("/health", () -> ApiBuilder.get(ctx -> ctx.status(200)));

      // Bulk CRUD operations for Files and Directories:
      // Placed first to avoid accidentally matching on the individual File/Directory pattern
      path("/ws/bulk/{workspaceId}", () -> {
        ApiBuilder.put(this::bulkPut);
        ApiBuilder.post(this::bulkPost);
        ApiBuilder.delete(this::bulkDelete);
      });

      // CRUD operations for Files and Directories:
      path("/ws/{workspaceId}/<filePath>",
           () -> {
             ApiBuilder.get(this::get);
             ApiBuilder.put(this::put);
             ApiBuilder.delete(this::delete);
             ApiBuilder.post(this::post);
           });

      // CRD operations for Workspaces
      path("/ws/{workspaceId}", () -> {
        ApiBuilder.get(this::listWorkspaceContents);
        ApiBuilder.delete(this::deleteWorkspace);
      });
      path("/ws/create", () -> ApiBuilder.post(this::createWorkspace));
    });

    // Default exception handlers for common endpoint exceptions
    javalin.exception(NoSuchWorkspaceException.class,
                      (ex, ctx) -> ctx.status(404).json(new FormattedError(ex)));
    javalin.exception(IOException.class,
                      (ex, ctx) -> ctx.status(500).json(new FormattedError(ex)));
    javalin.exception(SQLException.class,
                      (ex, ctx) -> ctx.status(500).json(new FormattedError(ex)));
    javalin.exception(UnauthorizedResponse.class, (ex, ctx) -> {
      final var message = ex.getMessage() != null ? ex.getMessage() : "Unauthorized";
      logger.warn("401 Unauthorized: {}", message);
      ctx.status(401).json(new FormattedError(ex));
    });
    javalin.exception(NumberFormatException.class,
                      (ex, ctx) -> ctx.status(400).json(new FormattedError(ex)));
  }

  // region Authorization
  /**
   * Validate that the request has a valid authorization
   */
  private JWTService.UserSession authorize(Context context) {
    final var authHeader = context.header("Authorization");
    final var hasuraAdminSecret = context.header("x-hasura-admin-secret");
    final var activeRole = context.header("x-hasura-role");
    final var userId = context.header("x-hasura-user-id");

    if (hasuraAdminSecret != null) {
      if (this.hasuraAdminSecret.isEmpty()) {
        // If the Hasura admin secret environment variable hasn't been set, fail closed
        throw new UnauthorizedResponse("Hasura admin secret authentication unavailable because HASURA_GRAPHQL_ADMIN_SECRET was not set");
      }

      if (userId == null) {
        throw new UnauthorizedResponse("x-hasura-user-id header is required when x-hasura-admin-secret is set");
      }

      if (!this.hasuraAdminSecret.equals(hasuraAdminSecret)) {
        throw new UnauthorizedResponse("Invalid Hasura admin secret");
      }

      return activeRole == null ? new JWTService.UserSession(userId, "aerie_admin")
                                : new JWTService.UserSession(userId, activeRole);
    } else {
      try {
        return jwtService.validateAuthorization(authHeader, activeRole);
      } catch (JWTVerificationException jve) {
        throw new UnauthorizedResponse(jve.getMessage());
      }
    }
  }

  /**
   * Check that the request meets the permissions to perform the given action on the workspace.
   * If it does not, format the context into an appropriate error state.
   * @return true, if the user passes the permissions check. false otherwise
   */
  private boolean checkPermissions(Context context, int workspaceId, WorkspaceAction action) {
    try {
      final var user = authorize(context);
      permissionsService.check(
          action,
          user.activeRole(),
          user.userId(),
          new WorkspaceId(workspaceId));
      return true;
    } catch (Forbidden ue) {
      context.status(403).json(new FormattedError(ue));
      return false;
    } catch (IOException ioe) {
      context.status(500).json(new FormattedError(ioe, "Could not check permissions."));
      return false;
    } catch (PermissionsServiceException pse) {
      context.status(500).json(new FormattedError(pse, "Could not check permissions."));
      return false;
    }catch (gov.nasa.jpl.aerie.permissions.exceptions.NoSuchWorkspaceException nsw) {
      context.status(404).json(new FormattedError(nsw, "Could not check permissions on Workspace %d.".formatted(nsw.id.id())));
      return false;
    }
  }
  // endregion

  // region Workspace Level Methods
  private void createWorkspace(Context context) {
    // Permissions check
    try {
      final var user = authorize(context);
      permissionsService.checkCoarseGrained(WorkspaceAction.create_workspace, user.activeRole());
    } catch (Forbidden ue) {
      context.status(403).json(new FormattedError(ue));
      return;
    } catch (IOException ioe) {
      context.status(500).json(new FormattedError(ioe, "Could not create workspace."));
      return;
    } catch (PermissionsServiceException pse) {
      context.status(500).json(new FormattedError(pse, "Could not create workspace."));
      return;
    }

    // Message format check
    final String helpText = """
        {
            "workspaceLocation": text     // Name of the folder the workspace will live in
            "parcelId": number            // Id of the workspace's parcel
            "workspaceName": text?        // Optional. If provided, the workspace will be called the specified value (defaults to the value of "workspaceLocation")
        }
        """;
    final Path workspaceLocation;
    final String workspaceName;
    final int parcelId;
    final var user = authorize(context);

    try(final var reader = Json.createReader(new StringReader(context.body()))) {
      final var bodyJson = reader.readObject();
      final String errorMsg = "Mandatory body parameter '%s' is missing or null. Request body format is:\n" + helpText;

      // Parcel Id
      if (!bodyJson.containsKey("parcelId") || bodyJson.isNull("parcelId")) {
        context.status(400).json(new FormattedError(errorMsg.formatted("parcelId")));
        return;
      }
      parcelId = bodyJson.getInt("parcelId");

      // Workspace Location
      if (!bodyJson.containsKey("workspaceLocation") || bodyJson.isNull("workspaceLocation")) {
        context.status(400).json(new FormattedError(errorMsg.formatted("workspaceLocation")));
        return;
      }
      final var workspaceString = bodyJson.getString("workspaceLocation");
      if(workspaceString.contains("/") || workspaceString.contains(".") || workspaceString.contains("~")){
        context.status(400).json(new FormattedError("Workspace location may not contain '/' or '.' or '~'"));
        return;
      }
      if(workspaceString.isBlank()) {
        context.status(400).json(new FormattedError("Workspace location may not be blank."));
        return;
      }
      workspaceLocation = Path.of(workspaceString);

      // Workspace Name
      if(bodyJson.containsKey("workspaceName")) {
        if(bodyJson.isNull("workspaceName")) {
          context.status(400).json(new FormattedError("Workspace name may not be null."));
        }
        workspaceName = bodyJson.getString("workspaceName");
        if(workspaceName.isBlank()) {
          context.status(400).json(new FormattedError("Workspace name may not be blank"));
        }
      } else {
        workspaceName = workspaceString;
      }
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(je, "Request body is malformed. Request body format is:\n" + helpText));
      return;
    }

    final Optional<Integer> workspaceId = workspaceService.createWorkspace(
        workspaceLocation,
        workspaceName,
        user.userId(),
        parcelId);
    if(workspaceId.isPresent()) {
      context.status(200).result(workspaceId.get().toString());
    } else {
      context.status(500).json(new FormattedError("Unable to create workspace."));
    }
  }

  private void deleteWorkspace(Context context) {
    // Permissions Check
    final int workspaceId  = Integer.parseInt(context.pathParam("workspaceId"));
    if(!checkPermissions(context, workspaceId, WorkspaceAction.delete_workspace)) {
      return;
    }

    final var errorMsg = "Unable to delete Workspace %d.".formatted(workspaceId);
    try {
      if (workspaceService.deleteWorkspace(workspaceId)) {
        context.status(200).result("Workspace deleted.");
      } else {
        context.status(500).json(new FormattedError(errorMsg));
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new FormattedError(ex, errorMsg));
    } catch (SQLException e) {
      context.status(500).json(new FormattedError(e, errorMsg));
    }
  }

  private void listWorkspaceContents(Context context) {
    // Permissions Check
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    if(!checkPermissions(context, workspaceId, WorkspaceAction.list_workspace_contents)) {
      return;
    }

    listContents(context);
  }
  // endregion

  // region Single Item Endpoints
  private void listContents(Context context) {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));

    final Optional<Path> directoryPath;
    if(context.pathParamMap().containsKey("filePath")) {
      directoryPath = Optional.of(Path.of(context.pathParam("filePath")));
    } else {
      directoryPath = Optional.empty();
    }

    // Query params
    final var depthString = context.queryParam("depth");
    final int depth = depthString != null ? Integer.parseInt(depthString) : -1;

    try {
      final var fileTree = workspaceService.listFiles(workspaceId, directoryPath, depth);
      if (fileTree == null) {
        context.status(404).json(new FormattedError("No such directory."));
        return;
      }
      context.status(200).json(fileTree.toJson().toString());
    } catch (IOException ioe) {
      context.status(500).json(new FormattedError(ioe));
    } catch (SQLException se) {
      context.status(500).json(new FormattedError(se));
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new FormattedError(ex));
    }
  }

  private void get(Context context) throws NoSuchWorkspaceException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.read_file_directory)) {
      return;
    }

    if (workspaceService.isDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
      listContents(context);
    } else {
      if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(404).json(new FormattedError("No such file exists in the workspace: " + pathInfo.filePath));
        return;
      }

      try {
        final var fileStream = workspaceService.loadFile(pathInfo.workspaceId, pathInfo.filePath());
        final var inputStream = fileStream.readingStream();
        context.header("x-render-type", workspaceService.getFileType(pathInfo.filePath).name());
        context.contentType(ContentType.OCTET_STREAM);
        context.header("Content-Disposition", "attachment; filename=\"" + pathInfo.fileName() + "\"");
        context.status(200).result(inputStream);
      } catch (IOException ioe) {
        context.status(500).json(new FormattedError(ioe, "Could not load file " + pathInfo.fileName()));
      } catch (SQLException se) {
        context.status(500).json(new FormattedError(se, "Could not load file " + pathInfo.fileName()));
      }
    }
  }

  private void put(Context context) throws NoSuchWorkspaceException, IOException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.write_file_directory)) {
      return;
    }

    final String type;
    final Optional<Boolean> overwrite;

    // Validate the permitted query parameters on Put requests
    try {
      type = context.queryParamAsClass("type", String.class)
                    .allowNullable()
                    .check(Objects::nonNull, "'type' must be provided.")
                    .check(ts -> "file".equalsIgnoreCase(ts) || "directory".equalsIgnoreCase(ts),
                           "'type' must be one of 'file' or 'directory'")
                    .get();
      final var overwriteValidator =  context.queryParamAsClass("overwrite", Boolean.class);
      overwrite = overwriteValidator.hasValue() ? Optional.of(overwriteValidator.get()) : Optional.empty();
    } catch (ValidationException ve) {
      context.status(400).json(new FormattedError(ve));
      return;
    }

    if ("file".equalsIgnoreCase(type)) {
      // Report a "Conflict" status if the file already exists and "overwrite" is false
      // "overwrite" defaults to "false" if unspecified
      if(workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)
         && !overwrite.orElse(false)) {
        context.status(409).json(new FormattedError(pathInfo.fileName() + " already exists."));
        return;
      }

      // Reject the request if the file isn't provided.
      final var file = context.uploadedFile("file");
      if (file == null || !pathInfo.fileName().equals(file.filename())) {
        context.status(400).json(new FormattedError("No file provided with the name " + pathInfo.fileName()));
        return;
      }

      if (workspaceService.saveFile(pathInfo.workspaceId, pathInfo.filePath, file)) {
        context.status(200).result("File " + pathInfo.fileName() + " uploaded to " + pathInfo.filePath);
      } else {
        context.status(500).json(new FormattedError("Could not save file."));
      }
    } else if ("directory".equalsIgnoreCase(type)) {
      // Reject the request if the "overwrite" flag is supplied
      if(overwrite.isPresent()) {
        context.status(400).json(new FormattedError("Query parameter 'overwrite' is not permitted when creating a directory."));
        return;
      }

      if (workspaceService.createDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("Directory created.");
      } else {
        context.status(500).json(new FormattedError("Could not create directory."));
      }
    } else {
      context.status(400).json(new FormattedError("Query param 'type' has invalid value "+type));
    }
  }

  private void post(Context context) {
    final String helpText = """
    Expected JSON body with one of the following formats:

    To move a file:
    {
      "moveTo": "<destination-path>",
      "toWorkspace": <new-workspace-id>, (optional)
    }

    To copy a file:
    {
      "copyTo": "<destination-path>",
      "toWorkspace": <new-workspace-id>, (optional)
    }
    """;

    try (JsonReader bodyReader = Json.createReader(new StringReader(context.body()))) {
      JsonObject bodyJson = bodyReader.readObject();
      final boolean success;

      if (bodyJson.containsKey("moveTo")) {
        success = handleMove(context, bodyJson);
      } else if (bodyJson.containsKey("copyTo")) {
        success = handleCopy(context, bodyJson);
      } else {
        context.status(400).json(new FormattedError("Invalid request. Must include either 'moveTo' or 'copyTo' key.\n\n" + helpText));
        return;
      }

      if (success) {
        context.status(200).result("Success");
      }
      // If the copy or move did not return successfully, but did not set a status code, set the status code to 500
      // Works because `context.status` initializes to HttpStatus.OK
      else if (context.status().equals(HttpStatus.OK)) {
        context.status(500).json(new FormattedError("Internal Error"));
      }


    } catch (JsonException je) {
      // Malformed JSON in request body
      context.status(400).json(new FormattedError(je, "Malformed JSON.\n\n" + helpText));
    } catch (IllegalArgumentException iae) {
      // Logical errors or unsupported operations
      context.status(400).json(new FormattedError(iae, "Invalid request.\n\n" + helpText));
    } catch (NoSuchWorkspaceException nsw) {
      // Workspace not found
      context.status(404).json(new FormattedError(nsw));
    } catch (IOException ioe) {
      logger.error("Error processing workspace request", ioe);
      context.status(500).json(new FormattedError(ioe));
    } catch (SQLException se) {
      // Internal server error
      logger.error("Error processing workspace request", se);
      context.status(500).json(new FormattedError(se));
    } catch (Exception e) {
      // Catch-all for unexpected issues
      logger.error("Unexpected error processing workspace request", e);
      final var message = e.getMessage() != null ? e.getMessage() : "Unknown error.\n\n" + helpText;
      context.status(500).json(new FormattedError("UNKNOWN_ERROR", message, e));
    }
  }

  private record CopyMoveValid(int status, String message){}

  private CopyMoveValid isCopyOrMoveValid(int sourceWorkspace, Path sourceFile, int targetWorkspace, Path targetFile) {
    try {
      // Return "Resource Not Found" if sourceFile does not exist
      if (!workspaceService.checkFileExists(sourceWorkspace, sourceFile)) {
        return new CopyMoveValid(404, sourceFile + " does not exist in the source workspace.");
      }
    } catch (NoSuchWorkspaceException se) {
      // Return "Resource Not Found" if source workspace does not exist
      return new CopyMoveValid(404, "Source workspace with ID "+sourceWorkspace+" does not exist.");
    }

    try {
      // Return "Conflicted" if destination exists
      if (workspaceService.checkFileExists(targetWorkspace, targetFile)) {
        return new CopyMoveValid(409, targetFile + " already exists");
      }
    }
    catch (NoSuchWorkspaceException se) {
      // Return "Resource not found" if target workspace does not exist
      return new CopyMoveValid(404, "Target workspace with ID "+targetWorkspace+" does not exist.");
    }

    return new CopyMoveValid(200, "Success");
  }

  private boolean handleMove(Context context, JsonObject bodyJson)
  throws IOException, NoSuchWorkspaceException, SQLException
  {
    final var pathInfo = PathInformation.of(context);

    final var destination = Path.of(bodyJson.getString("moveTo"));
    int sourceWorkspace = pathInfo.workspaceId;
    int targetWorkspace = pathInfo.workspaceId;  // default to same workspace unless toWorkspace is included
    if (bodyJson.containsKey("toWorkspace")) {
      targetWorkspace = bodyJson.getInt("toWorkspace");
    }

    // Permissions Check
    // Moving between workspaces requires "readFile", "deleteFile" on Workspace 1 and "writeFile" on Workspace 2
    // (Permission derived from mv -v, which shows that moving a file is "copy, then delete")
    if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
          && checkPermissions(context, sourceWorkspace, WorkspaceAction.delete_file_directory)
          && checkPermissions(context, targetWorkspace, WorkspaceAction.write_file_directory))) {
      return false;
    }

    CopyMoveValid validMove = isCopyOrMoveValid(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination);
    if (validMove.status != 200) {
      context.status(validMove.status).json(new FormattedError(validMove.message));
      return false;
    }

    final var errorMsg = "Unable to move '%s' in Workspace %d to '%s' in Workspace %d."
        .formatted(pathInfo, sourceWorkspace, destination, targetWorkspace);
    try {
      if (workspaceService.isDirectory(sourceWorkspace, pathInfo.filePath())) {
        if (workspaceService.moveDirectory(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          return true;
        } else {
          context.status(500).json(new FormattedError(errorMsg));
          return false;
        }
      } else {
        if (workspaceService.moveFile(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          return true;
        } else {
          context.status(500).json(new FormattedError(errorMsg));
          return false;
        }
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new FormattedError(ex, errorMsg));
      return false;
    } catch (SQLException se) {
      context.status(500).json(new FormattedError(se, errorMsg));
      return false;
    } catch (WorkspaceFileOpException wfe) {
      context.status(500).json(new FormattedError(wfe, errorMsg));
      return false;
    }
  }

  private boolean handleCopy(Context context, JsonObject bodyJson)
  throws NoSuchWorkspaceException, SQLException
  {
    final var pathInfo = PathInformation.of(context);

    final var destination = Path.of(bodyJson.getString("copyTo"));
    int sourceWorkspace = pathInfo.workspaceId;
    int targetWorkspace = pathInfo.workspaceId; // default to same workspace unless toWorkspace is included
    if (bodyJson.containsKey("toWorkspace")) {
      targetWorkspace = bodyJson.getInt("toWorkspace");
    }

    // Permissions Check
    // Copying between workspaces requires "readFile" on Workspace 1 and "writeFile" on Workspace 2
    if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
          && checkPermissions(context, targetWorkspace, WorkspaceAction.write_file_directory))) {
      return false;
    }

    CopyMoveValid validCopy = isCopyOrMoveValid(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination);
    if (validCopy.status != 200) {
      context.status(validCopy.status).json(new FormattedError(validCopy.message));
      return false;
    }

    // Error message to use if the operation fails
    final var errorMessage = "Unable to copy '%s' in Workspace %d to '%s' in Workspace %d"
        .formatted(pathInfo.filePath, sourceWorkspace, destination, targetWorkspace);
    try {
      if (workspaceService.isDirectory(sourceWorkspace, pathInfo.filePath())) {
        if (workspaceService.copyDirectory(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          return true;
        } else {
          context.status(500).json(new FormattedError(errorMessage));
          return false;
        }
      } else {
        if (workspaceService.copyFile(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          return true;
        } else {
          context.status(500).json(new FormattedError(errorMessage));
          return false;
        }
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new FormattedError(ex));
      return false;
    } catch (SQLException ex) {
      context.status(500).json(new FormattedError(ex, errorMessage));
      return false;
    } catch (WorkspaceFileOpException ex) {
      context.status(500).json(new FormattedError(ex, errorMessage));
      return false;
    }
  }

  private void delete(Context context) throws NoSuchWorkspaceException, IOException {
    final var pathInfo = PathInformation.of(context);
    final var errorMsg = "Could not delete %s.".formatted(pathInfo.filePath);

    // Permissions Check
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.delete_file_directory)) {
      return;
    }

    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).json(new FormattedError(pathInfo.fileName() + " does not exist."));
      return;
    }

    if (workspaceService.isDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
      if (workspaceService.deleteDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("Directory deleted.");
      } else {
        context.status(500).json(new FormattedError(errorMsg));
      }
    } else {
      if (workspaceService.deleteFile(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("File deleted.");
      } else {
        context.status(500).json(new FormattedError(errorMsg));
      }
    }
  }
  // endregion

  // region Bulk Endpoints
  /**
   * Create multiple files and/or directories in a workspace.
   *
   * Input syntax: Multipart form data with two parts:
   * body: JSON Array of JSON Objects:
   *      [ {"path": "path/to/file", "type": file },
   *        {"path": "diff/path/to/file", "type": file, "input_file_name": "dupe_file", "overwrite": false },
   *        { "path": "path/to/folder/", "type": directory }, ... ]
   * files: Attached file contents
   *
   * "input_file_name" is an optional field such that users can upload multiple files
   *    with different contents but the same name to different directories
   * If "input_file_name" for a file is specified, look for an object called that in the body.
   * Else, look for the file's filename.
   * Regardless, name the file as per `path`
   *
   * "overwrite" is permitted on "file"-type objects.
   * If "true", will overwrite the contents of the file should it already exist.
   * If "false" or not specified, will not upload the file should it already exist.
   *
   * If there is an issue with the request or permissions, returns an appropriate 4XX status.
   * Else, returns a 207 Multi-Status with an individual response per-object
   */
  public void bulkPut(Context context) throws NoSuchWorkspaceException {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    final List<BulkPutItem> toUpload;

    final var helpText = """
        For File Upload:
        {
          "path": "path/to/file.txt"          // required. path to where the file should be placed in the workspace, ending with the file name
          "type": "file"                      // required. must be set to "file" for file-type uploads
          "input_file_name": "other_file.txt" // optional. if specified, attach the file contents under this name.
                                              //  defaults to the filename from the "path" field (in this example, file.txt)
          "overwrite": false                  // optional. if provided, determines whether the uploaded file will overwrite an existing file at "path"
                                              //  defaults to "false".
        }

        For Directory Creation:
        {
          "path": "path/to/directory"         // required. path to where in the workspace the directory will be created, ending with the directory name
          "type": "directory"                 // required. must be set to either "folder" or "directory" for directory-type uploads
        }""";

    // Get body
    if(!context.isMultipartFormData()) {
      context.status(400).json(new FormattedError("Invalid body format.",
                                                  """
                                                  Expected body format is a multipart/form with two fields:
                                                    "body", which contains the list of JSON objects describing where to put each file and directory
                                                    "files", which contains all uploaded file contents"""));
    }
    try(final var bodyReader = Json.createReader(new StringReader(context.formParam("body")))){
      toUpload = bodyReader.readArray().getValuesAs(obj -> BulkPutItem.fromJson(obj.asJsonObject()));
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          je,
          "Invalid body format. Expected body format is an array of JSON objects with the form:\n\n"+helpText));
      return;
    } catch (NullPointerException ne) {
      context.status(400).json(new FormattedError(
          ne,
          "Invalid body format. Expected body format is an array of JSON objects with the form:\n\n"+helpText));
      return;
    }

    // Ensure that the user has specified at least one file or directory to upload
    if(toUpload.isEmpty()) {
      context.status(400).json(new FormattedError("Cannot process request: at least one item must be specified."));
      return;
    }

    // Check permissions
    if(!checkPermissions(context, workspaceId, WorkspaceAction.write_file_directory)) {
      return;
    }

    // Get the files
    final var fileList = context.uploadedFiles("files");
    final Map<String, UploadedFile> fileMap = new HashMap<>(fileList.size());
    fileList.forEach(file -> fileMap.put(file.filename(), file));

    // Create all specified object:
    final var responseArray = Json.createArrayBuilder();

    for(final var item : toUpload) {
      final var response = Json.createObjectBuilder()
                               .add("item", item.path().toString());

      if (item.uploadType() == ItemType.file) {
        // Report a "Conflict" status if the file already exists and "overwrite" is false
        if(workspaceService.checkFileExists(workspaceId, item.path()) && !item.overwrite()) {
          response.add("status", 409)
                  .add("response", new FormattedError(item.path() + " already exists.").toJson());
          responseArray.add(response);
          continue;
        }

        // Do not create the file if the file contents are not provided
        final var uploadedFileName = item.inputFileName().orElse(item.path().getFileName().toString());
        final var file = fileMap.getOrDefault(uploadedFileName, null);
        if(file == null) {
          response.add("status", 400)
                  .add("response", new FormattedError("No file provided with the name "+uploadedFileName).toJson());
          responseArray.add(response);
          continue;
        }

        // Create file
        try {
          if (workspaceService.saveFile(workspaceId, item.path(), file)) {
            response.add("status", 200)
                    .add("result", "File " + item.path().getFileName() + " uploaded to " + item.path());
          } else {
            response.add("status", 500)
                    .add("result", new FormattedError("Could not save file.").toJson());
          }
        } catch (IOException ioe) {
          response.add("status", 500)
                  .add("result", new FormattedError(ioe, "Could not save file.").toJson());
        }
      } else if (item.uploadType() == ItemType.directory) {
        // Create directory
        try {
          if (workspaceService.createDirectory(workspaceId, item.path())) {
             response.add("status", 200)
                    .add("result", "Directory created.");
          } else {
            response.add("status", 500)
                    .add("result", new FormattedError("Could not create directory.").toJson());
          }
        } catch (IOException ioe) {
          response.add("status", 500)
                  .add("result", new FormattedError(ioe, "Could not create directory.").toJson());
        }
      } else {
        response.add("status", 501)
                .add("response", new FormattedError("Unsupported item upload type: "+item.uploadType().name()).toJson());
      }
      // Add response to array
      responseArray.add(response);
    }
    context.status(207).json(responseArray.build().toString());
  }

  /**
   * Move or Copy multiple files and/or directories in a workspace.
   *
   * Input Syntax:
   * {
   *   "paths": [ "path/to/file", "path/to/folder", ... ],
   *   "moveTo": "destination/path" OR "copyTo": "destination/path",
   *   "toWorkspace": 2
   * }
   *
   * If toWorkspace is provided, move or copy the files to that workspace.
   * Otherwise, move or copy the files within the current workspace.
   *
   * If there is an issue with the request or permissions, returns an appropriate 4XX status.
   * Else, returns a 207 Multi-Status with an individual response per-object
   */
  public void bulkPost(Context context) throws NoSuchWorkspaceException {
    final var helpText = """
        To Move Items:
        {
          "paths": [ "path/to/file1.txt", "path/to/file2.txt", "path/to/folder", ... ] // required. list of paths of where each item to be moved is within the workspace
          "toWorkspace": 2                                                             // optional. if provided, items will be moved to the specified workspace.
                                                                                       //   defaults to the current workspace.
          "moveTo": "path/to/destination/folder",                                      // required. path to the folder within the destination workspace where the items will be moved to
          "overwrite": false                                                           // optional. if provided, determines whether the moved items will overwrite existing items in the destination folder
                                                                                       //  defaults to "false".
        }

        To Copy Items:
        {
          "paths": [ "path/to/file1.txt", "path/to/file2.txt", "path/to/folder", ... ] // required. list of paths of where each item to be copied is within the workspace
          "toWorkspace": 2                                                             // optional. if provided, items will be copied to the specified workspace.
                                                                                       //   defaults to the current workspace.
          "copyTo": "path/to/destination/folder",                                      // required. path to the folder within the destination workspace where the items will be copied to
          "overwrite": false                                                           // optional. if provided, determines whether the moved items will overwrite existing items in the destination folder
                                                                                       //  defaults to "false".
        }""";

    final var sourceWorkspace = Integer.parseInt(context.pathParam("workspaceId"));
    final List<String> items;
    final int destinationWorkspace;
    final Path destinationPath;
    final PostActions action;
    final boolean overwrite;

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new FormattedError("Body must be type "+ContentType.JSON));
    }

    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      final var body = bodyReader.readObject();
      items = body.getJsonArray("paths").getValuesAs(JsonString::getString);

      if(body.containsKey("moveTo") && body.containsKey("copyTo")) {
        throw new JsonException("Too many actions specified for a single request.");
      } else if(body.containsKey("moveTo")) {
        action = PostActions.MOVE;
        destinationPath = Path.of(body.getString("moveTo"));
      } else if(body.containsKey("copyTo")) {
        action = PostActions.COPY;
        destinationPath = Path.of(body.getString("copyTo"));
      } else {
        throw new JsonException("No action supplied for request.");
      }

      destinationWorkspace = body.getInt("toWorkspace", sourceWorkspace);
      overwrite = body.getBoolean("toWorkspace", false);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          je,
          "Invalid body format. Expected body format is an array of JSON objects with the form:\n\n"+helpText));
      return;
    }

    // Permissions Check and Action Handling
    switch (action) {
      case PostActions.MOVE -> {
        // Moving between workspaces requires "readFile", "deleteFile" on Workspace 1 and "writeFile" on Workspace 2
        // (Permission derived from mv -v, which shows that moving a file is "copy, then delete")
        if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
              && checkPermissions(context, sourceWorkspace, WorkspaceAction.delete_file_directory)
              && checkPermissions(context, destinationWorkspace, WorkspaceAction.write_file_directory))) {
          return;
        }
        final var moveResults = handleBulkMove(items, destinationPath, sourceWorkspace, destinationWorkspace, overwrite);
        context.status(207).json(moveResults.toString());
      }
      case PostActions.COPY -> {
        // Copying between workspaces requires "readFile" on Workspace 1 and "writeFile" on Workspace 2
        if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
              && checkPermissions(context, destinationWorkspace, WorkspaceAction.write_file_directory))) {
          final var copyResults = handleBulkCopy(items, destinationPath, sourceWorkspace, destinationWorkspace, overwrite);
          context.status(207).json(copyResults.toString());
        }
      }
      default -> context.status(501).json(new FormattedError("Unsupported post action: " + action.name()).toJson());
    }
  }

  private JsonArray handleBulkMove(
      List<String> toMove,
      Path destinationFolder,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite
  ) throws NoSuchWorkspaceException {
    final var responseArray = Json.createArrayBuilder();
    for(final var item : toMove){
      final var path = Path.of(item);
      final var destinationPath = Path.of(destinationFolder.toString(), item);
      final var response = Json.createObjectBuilder().add("item", item);

      final var errorMsg = "Unable to move '%s' in Workspace %d to '%s' in Workspace %d."
        .formatted(path, sourceWorkspaceId, destinationPath, destinationWorkspaceId);
      final var successMsg = "'%s' in Workspace %d moved to '%s' in Workspace %d"
          .formatted(item, sourceWorkspaceId, destinationPath, destinationWorkspaceId);

      if (!workspaceService.checkFileExists(sourceWorkspaceId, path)) {
        response.add("status", 404)
                .add("response", new FormattedError(errorMsg, item + " does not exist in the source workspace.").toJson());
        responseArray.add(response);
        continue;
      }

      if(workspaceService.checkFileExists(destinationWorkspaceId, destinationPath) && !overwrite) {
        response.add("status", 409)
                .add("response", new FormattedError(errorMsg, destinationPath + " already exists.").toJson());
        responseArray.add(response);
        continue;
      }

      try {
        if (workspaceService.isDirectory(sourceWorkspaceId, path)) {
          if (workspaceService.moveDirectory(sourceWorkspaceId, path, destinationWorkspaceId, destinationPath)) {
            response.add("status", 200)
                    .add("response", successMsg);
          } else {
            response.add("status", 500)
                    .add("response", new FormattedError(errorMsg).toJson());
          }
        } else {
          if (workspaceService.moveFile(sourceWorkspaceId, path, destinationWorkspaceId, destinationPath)) {
            response.add("status", 200)
                    .add("response", successMsg);
          } else {
            response.add("status", 500)
                    .add("response", new FormattedError(errorMsg).toJson());
          }
        }
      } catch (SQLException se) {
        response.add("status", 500)
                .add("response", new FormattedError(se, errorMsg).toJson());
      } catch (IOException ioe) {
        response.add("status", 500)
                .add("response", new FormattedError(ioe, errorMsg).toJson());
      } catch (WorkspaceFileOpException wfe) {
        response.add("status", 500)
                .add("response", new FormattedError(wfe, errorMsg).toJson());
      }

      responseArray.add(response);
    }
    return responseArray.build();
  }

  private JsonArray handleBulkCopy(
      List<String> toCopy,
      Path destinationFolder,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite
  ) throws NoSuchWorkspaceException {
    final var responseArray = Json.createArrayBuilder();
    for(final var item : toCopy) {
      final var path = Path.of(item);
      final var destinationPath = Path.of(destinationFolder.toString(), item);
      final var response = Json.createObjectBuilder().add("item", item);

      final var errorMsg = "Unable to copy '%s' in Workspace %d to '%s' in Workspace %d."
        .formatted(path, sourceWorkspaceId, destinationPath, destinationWorkspaceId);
      final var successMsg = "'%s' in Workspace %d copied to '%s' in Workspace %d"
          .formatted(item, sourceWorkspaceId, destinationPath, destinationWorkspaceId);

      if (!workspaceService.checkFileExists(sourceWorkspaceId, path)) {
        response.add("status", 404)
                .add("response", new FormattedError(errorMsg, item + " does not exist in the source workspace.").toJson());
        responseArray.add(response);
        continue;
      }

      if(workspaceService.checkFileExists(destinationWorkspaceId, destinationPath) && !overwrite) {
        response.add("status", 409)
                .add("response", new FormattedError(errorMsg, destinationPath + " already exists.").toJson());
        responseArray.add(response);
        continue;
      }

      try {
        if (workspaceService.isDirectory(sourceWorkspaceId, path)) {
          if (workspaceService.copyDirectory(sourceWorkspaceId, path, destinationWorkspaceId, destinationPath)) {
            response.add("status", 200)
                    .add("response", successMsg);
          } else {
            response.add("status", 500)
                    .add("response", new FormattedError(errorMsg).toJson());
          }
        } else {
          if (workspaceService.copyFile(sourceWorkspaceId, path, destinationWorkspaceId, destinationPath)) {
            response.add("status", 200)
                    .add("response", successMsg);
          } else {
            response.add("status", 500)
                    .add("response", new FormattedError(errorMsg).toJson());
          }
        }
      } catch (SQLException se) {
        response.add("status", 500)
                .add("response", new FormattedError(se, errorMsg).toJson());
      } catch (WorkspaceFileOpException wfe) {
        response.add("status", 500)
                .add("response", new FormattedError(wfe, errorMsg).toJson());
      }

      responseArray.add(response);
    }
    return responseArray.build();
  }

  /**
   * Delete multiple files and/or directories in a workspace
   *
   * Input syntax:
   * [ "path/to/file1", "path/to/folder", ... ]
   *
   * If there is an issue with the request or permissions, returns an appropriate 4XX status.
   * Else, returns a 207 Multi-Status with an individual response per-object
   *
   * Response syntax:
   * [ { "item": "path/to/file1", "status": 200, "response": "File deleted." },
   *   { "item": "path/to/folder", "status": 404, "response": "path/to/folder does not exist."}, ... ]
   */
  public void bulkDelete(Context context) throws NoSuchWorkspaceException {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    final List<String> toDelete;

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new FormattedError("Body must be type "+ContentType.JSON));
    }
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      toDelete = bodyReader.readArray().getValuesAs(JsonString::getString);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(je, "Invalid body format. Expected body format is an array of paths."));
      return;
    }
    // Ensure that the user has specified at least one file or directory to get the contents of
    if(toDelete.isEmpty()) {
      context.status(400).json(new FormattedError("Cannot process request: at least one item must be specified."));
      return;
    }

    // Permissions Check
    if(!checkPermissions(context, workspaceId, WorkspaceAction.delete_file_directory)) {
      return;
    }

    // Delete all specified objects
    final var responseArray = Json.createArrayBuilder();

    for(final var item : toDelete) {
      final var response = Json.createObjectBuilder().add("item", item);
      final var itemPath = Path.of(item);
      final var errorMsg = "Could not delete %s.".formatted(itemPath.getFileName());

      if (!workspaceService.checkFileExists(workspaceId, itemPath)) {
        response.add("status", 404)
                .add("response", new FormattedError(item + " does not exist.").toJson());
        responseArray.add(response);
        continue;
      }

      try {
        if (workspaceService.isDirectory(workspaceId, itemPath)) {
          if (workspaceService.deleteDirectory(workspaceId, itemPath)) {
            response.add("status", 200)
                    .add("response", "Directory deleted.");
          } else {
            response.add("status", 500)
                    .add("response", new FormattedError(errorMsg).toJson());
          }
        } else {
          if (workspaceService.deleteFile(workspaceId, itemPath)) {
            response.add("status", 200)
                    .add("response", "File deleted.");
          } else {
            response.add("status", 500)
                    .add("response", new FormattedError(errorMsg).toJson());
          }
        }
      } catch (IOException ioe) {
        response.add("status", 500)
                .add("response", new FormattedError(ioe, errorMsg).toJson());
      }

      // Add response to array
      responseArray.add(response);
    }
    // Return multipart response
    context.status(207).json(responseArray.build().toString());
  }
  //endregion
}
