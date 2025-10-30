package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.exceptions.JWTVerificationException;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.WorkspaceAction;
import gov.nasa.jpl.aerie.permissions.exceptions.Forbidden;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;
import gov.nasa.jpl.aerie.permissions.gql.WorkspaceId;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import io.javalin.Javalin;
import io.javalin.apibuilder.ApiBuilder;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.plugin.Plugin;
import io.javalin.validation.ValidationException;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
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
}
