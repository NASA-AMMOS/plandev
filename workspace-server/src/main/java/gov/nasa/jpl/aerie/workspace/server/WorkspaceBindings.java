package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.exceptions.JWTVerificationException;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.WorkspaceAction;
import gov.nasa.jpl.aerie.permissions.exceptions.Forbidden;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;
import gov.nasa.jpl.aerie.permissions.gql.WorkspaceId;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.types.BulkPutItem;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataKeys;
import gov.nasa.jpl.aerie.workspace.server.types.PostActions;
import gov.nasa.jpl.aerie.workspace.server.types.ItemType;
import gov.nasa.jpl.aerie.workspace.server.types.PostBody;
import gov.nasa.jpl.aerie.workspace.server.types.BulkPostItem;
import io.javalin.Javalin;
import io.javalin.apibuilder.ApiBuilder;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.UploadedFile;
import io.javalin.plugin.Plugin;
import io.javalin.validation.ValidationException;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
      final var filePath = Path.of(context.pathParam("path"));

      return new PathInformation(workspaceId, filePath);
    }

    String fileName() {
      return filePath.getFileName().toString();
    }

    String metadataFileName() {
      return RenderType.toMetadataFileName(fileName());
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
      before("/metadata/*", ctx -> {
        if(ctx.method() != HandlerType.OPTIONS) {
          authorize(ctx);
        }
      });
      // Health check
      path("/health", () -> ApiBuilder.get(ctx -> ctx.status(200)));

      // Bulk CRUD operations for Files and Directories:
      // Placed 'bulk' before 'workspaceId' to avoid accidentally matching on the individual File/Directory pattern
      path("/ws/bulk/{workspaceId}", () -> {
        ApiBuilder.put(this::bulkUpload);
        ApiBuilder.post(this::bulkPost);
        ApiBuilder.delete(this::bulkDelete);
      });

      // CRUD operations for Files and Directories:
      path("/ws/{workspaceId}/<path>",
           () -> {
             ApiBuilder.get(this::getFileDirectory);
             ApiBuilder.put(this::createFileDirectory);
             ApiBuilder.delete(this::deleteFileDirectory);
             ApiBuilder.post(this::post);
           });

      // CRD operations for Workspaces
      path("/ws/{workspaceId}", () -> {
        ApiBuilder.get(this::listWorkspaceContents);
        ApiBuilder.delete(this::deleteWorkspace);
      });
      path("/ws/create", () -> ApiBuilder.post(this::createWorkspace));

      // Unset Metadata key
      // Placed before CRUD operations to avoid accidentally matching on the general POST pattern
      path("/metadata/unset/{workspaceId}/<path>", () -> ApiBuilder.post(this::unsetMetadataKeys));
      // CRUD Operations for File Metadata
      path("/metadata/{workspaceId}/<path>", () -> {
        ApiBuilder.get(this::getMetadataFile);
        ApiBuilder.post(this::setMetadataKeys);
        ApiBuilder.delete(this::deleteMetadata);
      });
    });

    // Default exception handlers for common endpoint exceptions
    javalin.exception(NoSuchWorkspaceException.class,
                      (ex, ctx) -> ctx.status(404).json(new FormattedError(ex)));
    javalin.exception(IOException.class, (ex, ctx) -> {
      final var fe = new FormattedError(ex);
      logger.warn("IO Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(SQLException.class, (ex, ctx) -> {
      final var fe = new FormattedError(ex);
      logger.warn("SQL Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(UnauthorizedResponse.class, (ex, ctx) -> {
      final var message = ex.getMessage() != null ? ex.getMessage() : "Unauthorized";
      logger.warn("401 Unauthorized: {}", message);
      ctx.status(401).json(new FormattedError(ex));
    });
    javalin.exception(NumberFormatException.class,
                      (ex, ctx) -> ctx.status(400).json(new FormattedError(ex)));
    javalin.exception(SecurityException.class, (ex, ctx) -> {
      final var fe = new FormattedError(ex);
      logger.warn("Security Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(HttpResponseException.class, (ex, ctx) -> ctx.status(ex.getStatus()).json(new FormattedError("HTTP_RESPONSE_EXCEPTION", ex)));
    javalin.exception(Exception.class, (ex, ctx) -> {
      // Catch-all for unexpected issues
      final var message = ex.getMessage() != null ? ex.getMessage() : "Unknown error.";
      final var fe = new FormattedError("UNKNOWN_ERROR", message, ex);
      logger.error("Unexpected error processing workspace request {}", fe);
      ctx.status(500).json(fe);
    });
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
      final var fe = new FormattedError(ioe, "Could not check permissions.");
      logger.warn("PERMISSIONS SERVICE: IO Exception: {}", fe);
      context.status(500).json(fe);
      return false;
    } catch (PermissionsServiceException pse) {
      final var fe = new FormattedError(pse, "Could not check permissions.");
      logger.warn("PERMISSIONS SERVICE: Permissions Service Exception: {}", fe);
      context.status(500).json(new FormattedError(pse, "Could not check permissions."));
      return false;
    } catch (gov.nasa.jpl.aerie.permissions.exceptions.NoSuchWorkspaceException nsw) {
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
      final var fe = new FormattedError(ioe, "Could not create workspace.");
      logger.warn("CREATE WORKSPACE: IO Exception: {}", fe);
      context.status(500).json(fe);
      return;
    } catch (PermissionsServiceException pse) {
      final var fe = new FormattedError(pse, "Could not create workspace.");
      logger.warn("Permissions Service Exception: {}", fe);
      context.status(500).json(fe);
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
      logger.warn(
          """
          Create Workspace failed for inputs:
          \tLocation: {},
          \tName: {},
          \tParcel ID: {},
          \tUser: {} (active role: {})
          """, workspaceLocation, workspaceName, parcelId, user.userId(), user.activeRole());
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
        logger.warn(errorMsg);
        context.status(500).json(new FormattedError(errorMsg));
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new FormattedError(ex, errorMsg));
    } catch (SQLException e) {
      final var fe = new FormattedError(e, errorMsg);
      logger.warn("DELETE WORKSPACE: SQL Exception: {}", fe);
      context.status(500).json(fe);
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

    final var directoryPath = context.pathParamMap().containsKey("path")
        ? Path.of(context.pathParam("path"))
        : Path.of("");

    // Query params
    final var depthString = context.queryParam("depth");
    final int depth = depthString != null ? Integer.parseInt(depthString) : -1;
    final boolean withMetadata = Boolean.parseBoolean(context.queryParam("withMetadata"));

    try {
      final var fileTree = workspaceService.listFiles(workspaceId, directoryPath, depth, withMetadata);
      if (fileTree == null) {
        context.status(404).json(new FormattedError("No such directory."));
        return;
      }
      context.status(200).json(fileTree.toJson().toString());
    } catch (IOException ioe) {
      final var fe = new FormattedError(ioe);
      logger.warn("LIST CONTENTS: IO Exception: {}", fe);
      context.status(500).json(fe);
    } catch (SQLException se) {
      final var fe = new FormattedError(se);
      logger.warn("LIST CONTENTS: SQL Exception: {}", fe);
      context.status(500).json(fe);
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new FormattedError(ex));
    }
  }

  private void getFileDirectory(Context context) throws NoSuchWorkspaceException {
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
        final var fe = new FormattedError(ioe, "Could not load file " + pathInfo.fileName());
        logger.warn("GET FILE: IO Exception: {}", fe);
        context.status(500).json(fe);
      } catch (SQLException se) {
        final var fe = new FormattedError(se, "Could not load file " + pathInfo.fileName());
        logger.warn("GET FILE: SQL Exception: {}", fe);
        context.status(500).json(fe);
      }
    }
  }

  private void createFileDirectory(Context context) {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.write_file_directory)) {
      return;
    }

    final ItemType type;
    final Optional<Boolean> overwrite;

    // Validate the permitted query parameters on Put requests
    try {
      final var typeParam = context.queryParamAsClass("type", String.class)
                                   .allowNullable()
                                   .check(Objects::nonNull, "'type' must be provided.")
                                   .get();
      type = ItemType.of(typeParam);
      final var overwriteValidator =  context.queryParamAsClass("overwrite", Boolean.class);
      overwrite = overwriteValidator.hasValue() ? Optional.of(overwriteValidator.get()) : Optional.empty();
    } catch (ValidationException ve) {
      context.status(400).json(new FormattedError(ve));
      return;
    } catch (IllegalArgumentException iae) {
      context.status(400).json(new FormattedError(iae));
      return;
    }

    final HandlerResult uploadResults;
    if (type == ItemType.file) {
      final var file = context.uploadedFile("file");
      // Reject the request if the file isn't provided.
      if (file == null || !pathInfo.fileName().equals(file.filename())) {
        context.status(400).json(new FormattedError("No file provided with the name " + pathInfo.fileName()));
        return;
      }

      uploadResults = handleFileUpload(
          pathInfo.workspaceId,
          pathInfo.filePath,
          file,
          overwrite.orElse(false),
          authorize(context).userId());

    } else if (type == ItemType.directory) {
      // Reject the request if the "overwrite" flag is supplied
      if(overwrite.isPresent()) {
        context.status(400).json(new FormattedError("Query parameter 'overwrite' is not permitted when creating a directory."));
        return;
      }
      uploadResults = handleCreateDirectory(pathInfo.workspaceId(), pathInfo.filePath());
    } else {
      context.status(400).json(new FormattedError("Query param 'type' has invalid value "+type));
      return;
    }

    if(uploadResults.response.getValueType() == JsonValue.ValueType.STRING) {
      context.status(uploadResults.status).result(((JsonString) uploadResults.response()).getString());
    } else {
      context.status(uploadResults.status).json(uploadResults.response);
    }
  }

  private void post(Context context) throws NoSuchWorkspaceException {
    final String helpText = """
    Expected JSON body with one of the following formats:

    To move an item:
    {
        "toWorkspace": 2,                             // optional. if provided, the item will be moved to the specified workspace.
                                                      //   defaults to the current workspace.
        "moveTo": "path/to/destination",              // required. path within the destination workspace to move the item to, ending with the item.
                                                      //  to rename an item, end the 'moveTo' path with a name that differs from the item's current name.
        "overwrite": false                            // optional. only permitted when moving a file.
                                                      //  if provided, determines whether the moved file will overwrite an existing file at "moveTo".
                                                      //  defaults to "false".
    }

    To copy an item:
    {
        "toWorkspace": 2,                             // optional. if provided, the item will be copied to the specified workspace.
                                                      //   defaults to the current workspace.
        "copyTo": "path/to/destination/folder",       // required. path within the destination workspace to copy the item to, ending with the item.
        "overwrite": false                            // optional. only permitted when moving a file.
                                                      //  if provided, determines whether the moved file will overwrite an existing file at "moveTo".
                                                      //  defaults to "false".
    }""";

    final var pathInfo = PathInformation.of(context);
    final var sourceWorkspace = pathInfo.workspaceId;
    final PostBody body;

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new FormattedError("Body must be type "+ContentType.JSON));
    }
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      body = PostBody.fromJson(bodyReader.readObject(), sourceWorkspace);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          je,
          "Invalid body format. Expected body format is an array of JSON objects with the form:\n\n"+helpText));
      return;
    }

    // Permissions Check and Action Handling
    switch (body.action()) {
      case PostActions.MOVE -> {
        // Moving between workspaces requires "readFile", "deleteFile" on Workspace 1 and "writeFile" on Workspace 2
        // (Permission derived from mv -v, which shows that moving a file is "copy, then delete")
        if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
              && checkPermissions(context, sourceWorkspace, WorkspaceAction.delete_file_directory)
              && checkPermissions(context, body.destinationWorkspaceId(), WorkspaceAction.write_file_directory))) {
          return;
        }
        final var moveResults = handleMove(
            pathInfo.filePath(),
            body.destinationPath(),
            sourceWorkspace,
            body.destinationWorkspaceId(),
            body.overwrite(),
            authorize(context).userId()
        );
        if(moveResults.response.getValueType() == JsonValue.ValueType.STRING) {
          context.status(moveResults.status).result(((JsonString) moveResults.response()).getString());
        } else {
          context.status(moveResults.status).json(moveResults.response);
        }
      }
      case PostActions.COPY -> {
        // Copying between workspaces requires "readFile" on Workspace 1 and "writeFile" on Workspace 2
        if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
              && checkPermissions(context, body.destinationWorkspaceId(), WorkspaceAction.write_file_directory))) {
          final var copyResults = handleCopy(
              pathInfo.filePath,
              body.destinationPath(),
              sourceWorkspace,
              body.destinationWorkspaceId(),
              body.overwrite(),
              authorize(context).userId()
          );
          if (copyResults.response.getValueType() == JsonValue.ValueType.STRING) {
            context.status(copyResults.status).result(((JsonString) copyResults.response()).getString());
          } else {
            context.status(copyResults.status).json(copyResults.response);
          }
        }
      }
      default -> context.status(501).json(new FormattedError("Unsupported post action: " + body.action().name()).toJson());
    }
  }

  private void deleteFileDirectory(Context context) {
    final var pathInfo = PathInformation.of(context);
    // Permissions Check
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.delete_file_directory)) {
      return;
    }

    final var deleteResults = handleDelete(pathInfo.workspaceId, pathInfo.filePath);

    if(deleteResults.response.getValueType() == JsonValue.ValueType.STRING) {
      context.status(deleteResults.status).result(((JsonString) deleteResults.response()).getString());
    } else {
      context.status(deleteResults.status).json(deleteResults.response);
    }
  }
  // endregion

  // region Single Item Action Handlers
  private record HandlerResult(int status, JsonValue response){
    HandlerResult(int status, FormattedError fe) {
      this(status, fe.toJson());
    }
  }

  private HandlerResult handleFileUpload(
      int workspaceId,
      Path uploadPath,
      UploadedFile file,
      boolean overwrite,
      final String userId) {
    try {
      // Verify the user isn't attempting to save a metadata file using the main file api
      if(RenderType.isAerieMetadataFile(uploadPath.getFileName().toString())) {
        return new HandlerResult(
            405,
            new FormattedError(
                new MalformedRequest("Could not save file.",
                    "Metadata files may not be uploaded via the file API."
                    + " Use the metadata API (located at /metadata/{workspaceId}/<basefilepath>) instead.")));
      }

      // Report a "Conflict" status if the file already exists and "overwrite" is false
      // "overwrite" defaults to "false" if unspecified
      if (workspaceService.checkFileExists(workspaceId, uploadPath) && !overwrite) {
        return new HandlerResult(409, new FormattedError(uploadPath + " already exists."));
      }

      // Report a "Locked" status if the file is currently marked as "readOnly"
      if(workspaceService.isReadOnly(workspaceId, uploadPath)) {
        return new HandlerResult(423, new FormattedError("Cannot update file at " + uploadPath + ". It is currently marked as read only."));
      }

      if (workspaceService.saveFile(workspaceId, uploadPath, file, userId)) {
        return new HandlerResult(
            200,
            Json.createValue("File " + uploadPath.getFileName() + " uploaded to " + uploadPath));
      } else {
        logger.warn("UPLOAD FILE: Save File failed for path {}", uploadPath);
        return new HandlerResult(500, new FormattedError("Could not save file."));
      }
    } catch (IOException ioe) {
      final var fe = new FormattedError(ioe, "Could not save file.");
      logger.warn("UPLOAD FILE: IOException: {}", fe);
      return new HandlerResult(500, fe);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new FormattedError(wfe, "Could not save file.");
      logger.warn("UPLOAD FILE: WorkspaceFileOpException: {}", fe);
      return new HandlerResult(500, fe);
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult(404, new FormattedError(nsw, "Could not create directory."));
    }
  }

  private HandlerResult handleCreateDirectory(int workspaceId, Path destinationPath) {
    try {
      if (workspaceService.createDirectory(workspaceId, destinationPath)) {
        return new HandlerResult(200, Json.createValue("Directory created."));
      } else {
        logger.warn("CREATE DIRECTORY: Create Directory failed for path {}", destinationPath);
        return new HandlerResult(500, new FormattedError("Could not create directory."));
      }
    } catch (IOException ioe) {
      logger.warn("CREATE DIRECTORY: IOException: {}", destinationPath);
      return new HandlerResult(500, new FormattedError(ioe, "Could not create directory."));
    } catch (WorkspaceFileOpException wfe) {
      logger.warn("CREATE DIRECTORY: WorkspaceFileOpException: {}", destinationPath);
      return new HandlerResult(500, new FormattedError(wfe, "Could not create directory."));
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult(404, new FormattedError(nsw, "Could not create directory."));
    }
  }

  private HandlerResult handleMove(
      Path toMove,
      Path destinationPath,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite,
      String userId
  ) throws NoSuchWorkspaceException
  {
    final var errorMsg = "Unable to move '%s' in Workspace %d to '%s' in Workspace %d."
            .formatted(toMove, sourceWorkspaceId, destinationPath, destinationWorkspaceId);
    final var successMsg = Json.createValue(
        "'%s' in Workspace %d moved to '%s' in Workspace %d"
            .formatted(toMove, sourceWorkspaceId, destinationPath, destinationWorkspaceId));

    // Verify the user isn't attempting to move a metadata file using the main file api
    if(RenderType.isAerieMetadataFile(toMove.getFileName().toString())) {
      return new HandlerResult(
          405,
          new FormattedError(
              new MalformedRequest(
                  errorMsg,
                  "Metadata files may not be directly moved via the file API. Move the main file instead.")));
    }

    // Verify the user isn't attempting to rename a non-metadata file to a metadata file
    if(RenderType.isAerieMetadataFile(destinationPath.getFileName().toString())) {
      return new HandlerResult(
          405,
          new FormattedError(
              new MalformedRequest(errorMsg, "Normal files may not be renamed to metadata files.")));
    }

    if (!workspaceService.checkFileExists(sourceWorkspaceId, toMove)) {
      return new HandlerResult(
          404,
          new FormattedError(errorMsg, toMove + " does not exist in the source workspace.").toJson());
    }

    final var destinationFileExists = workspaceService.checkFileExists(destinationWorkspaceId, destinationPath);
    if (destinationFileExists && !overwrite) {
      return new HandlerResult(409, new FormattedError(errorMsg, destinationPath + " already exists.").toJson());
    }

    try {
      if (workspaceService.isDirectory(sourceWorkspaceId, toMove)) {
        if (workspaceService.moveDirectory(sourceWorkspaceId, toMove, destinationWorkspaceId, destinationPath)) {
          return new HandlerResult(200, successMsg);
        } else {
          return new HandlerResult(500, new FormattedError(errorMsg).toJson());
        }
      } else {
        // Report a "Locked" status if either file is currently marked as "readOnly"
        if(workspaceService.isReadOnly(sourceWorkspaceId, toMove) ||
           (destinationFileExists && workspaceService.isReadOnly(destinationWorkspaceId, destinationPath))) {
          return new HandlerResult(423, new FormattedError(errorMsg,  "File is currently marked as read only."));
        }
        if (workspaceService.moveFile(sourceWorkspaceId, toMove, destinationWorkspaceId, destinationPath, userId)) {
          return new HandlerResult(200, successMsg);
        } else {
          return new HandlerResult(500, new FormattedError(errorMsg).toJson());
        }
      }
    } catch (IOException ioe) {
      return new HandlerResult(500, new FormattedError(ioe, errorMsg).toJson());
    } catch (WorkspaceFileOpException wfe) {
      return new HandlerResult(500, new FormattedError(wfe, errorMsg).toJson());
    }
  }

  private HandlerResult handleCopy(
      Path toCopy,
      Path destinationPath,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite,
      String userId
  ) throws NoSuchWorkspaceException
  {
    final var errorMsg = "Unable to copy '%s' in Workspace %d to '%s' in Workspace %d."
        .formatted(toCopy, sourceWorkspaceId, destinationPath, destinationWorkspaceId);
    final var successMsg = Json.createValue(
        "'%s' in Workspace %d copied to '%s' in Workspace %d"
            .formatted(toCopy, sourceWorkspaceId, destinationPath, destinationWorkspaceId));

    // Verify the user isn't attempting to copy a metadata file using the main file api
    if(RenderType.isAerieMetadataFile(toCopy.getFileName().toString())) {
      return new HandlerResult(
          405,
          new FormattedError(
              new MalformedRequest(
                  errorMsg,
                  "Metadata files may not be directly copied via the file API. Copy the main file instead.")));
    }

    // Verify the user isn't attempting to rename a non-metadata file to a metadata file
    if(RenderType.isAerieMetadataFile(destinationPath.getFileName().toString())) {
      return new HandlerResult(
          405,
          new FormattedError(
              new MalformedRequest(errorMsg, "Normal files may not be renamed to metadata files.")));
    }

    if (!workspaceService.checkFileExists(sourceWorkspaceId, toCopy)) {
      return new HandlerResult(
          404,
          new FormattedError(errorMsg, toCopy + " does not exist in the source workspace.").toJson());
    }

    final var destinationFileExists = workspaceService.checkFileExists(destinationWorkspaceId, destinationPath);
    if (destinationFileExists && !overwrite) {
      return new HandlerResult(409, new FormattedError(errorMsg, destinationPath + " already exists.").toJson());
    }

    try {
      if (workspaceService.isDirectory(sourceWorkspaceId, toCopy)) {
        if (workspaceService.copyDirectory(sourceWorkspaceId, toCopy, destinationWorkspaceId, destinationPath)) {
          return new HandlerResult(200, successMsg);
        } else {
          return new HandlerResult(500, new FormattedError(errorMsg).toJson());
        }
      } else {
        // Report a "Locked" status if either file is currently marked as "readOnly"
        if(workspaceService.isReadOnly(sourceWorkspaceId, toCopy) ||
           (destinationFileExists && workspaceService.isReadOnly(destinationWorkspaceId, destinationPath))) {
          return new HandlerResult(423, new FormattedError(errorMsg,  "File is currently marked as read only."));
        }

        if (workspaceService.copyFile(sourceWorkspaceId, toCopy, destinationWorkspaceId, destinationPath, userId)) {
          return new HandlerResult(200, successMsg);
        } else {
          return new HandlerResult(500, new FormattedError(errorMsg).toJson());
        }
      }
    } catch (WorkspaceFileOpException wfe) {
      return new HandlerResult(500, new FormattedError(wfe, errorMsg).toJson());
    } catch (IOException ioe) {
      return new HandlerResult(500, new FormattedError(ioe, errorMsg).toJson());
    }
  }

  private HandlerResult handleDelete(int workspaceId, Path filePath) {
    try {
      final var errorMsg = "Could not delete %s.".formatted(filePath);

      // Verify the user isn't attempting to delete a metadata file using the main file api
      if(RenderType.isAerieMetadataFile(filePath.getFileName().toString())) {
        return new HandlerResult(
            405,
            new FormattedError(
                new MalformedRequest(
                    errorMsg,
                    "Metadata files may not be directly deleted via the file API. "
                    + "Use the metadata API (located at /metadata/{workspaceId}/<basefilepath>) instead.")));
      }

      if (!workspaceService.checkFileExists(workspaceId, filePath)) {
        return new HandlerResult(404, new FormattedError(filePath.getFileName() + " does not exist."));
      }

      if (workspaceService.isDirectory(workspaceId, filePath)) {
        if (workspaceService.deleteDirectory(workspaceId, filePath)) {
          return new HandlerResult(200, Json.createValue("Directory deleted."));
        } else {
          logger.warn("DELETE: Delete Directory failed for path {}", filePath);
          return new HandlerResult(500, new FormattedError(errorMsg));
        }
      } else {
        // Report a "Locked" status if the file is currently marked as "readOnly"
        if(workspaceService.isReadOnly(workspaceId, filePath)) {
          return new HandlerResult(423, new FormattedError(errorMsg,  "File is currently marked as read only."));
        }

        if (workspaceService.deleteFile(workspaceId, filePath)) {
          return new HandlerResult(200, Json.createValue("File deleted."));
        } else {
          logger.warn("DELETE: Delete File failed for path {}", filePath);
          return new HandlerResult(500, new FormattedError(errorMsg));
        }
      }
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult(404, new FormattedError(nsw));
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new FormattedError(wfe);
      logger.warn("DELETE: WORKSPACE FILE OP EXCEPTION: {}", fe);
      return new HandlerResult(500, fe);
    } catch (IOException ioe) {
      final var fe = new FormattedError(ioe);
      logger.warn("DELETE: IO EXCEPTION: {}", fe);
      return new HandlerResult(500, fe);
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
  public void bulkUpload(Context context) {
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
    if(!context.isMultipartFormData() || context.formParam("body") == null) {
      context.status(400).json(new FormattedError(
          new MalformedRequest(
              "Invalid body format.",
              """
                  Expected body format is a multipart/form with two fields:
                  "body", which contains the list of JSON objects describing where to put each file and directory
                  "files", which contains all uploaded file contents""")));
      return;
    }
    try(final var bodyReader = Json.createReader(new StringReader(context.formParam("body")))){
      toUpload = bodyReader.readArray().getValuesAs(obj -> BulkPutItem.fromJson(obj.asJsonObject()));
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          je,
          "Invalid body format. Expected body format is an array of JSON objects with the form:\n\n"+helpText));
      return;
    }

    // Ensure that the user has specified at least one file or directory to upload
    if(toUpload.isEmpty()) {
      context.status(400).json(
          new FormattedError(new MalformedRequest("Cannot process request: at least one item must be specified.")));
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

    // Check that files all had unique upload names:
    if(fileList.size() != fileMap.size()) {
      context.status(400).json(new FormattedError(
          new MalformedRequest(
              "Cannot process request: multiple files are attached under the same name.",
              "Attach file contents under unique names.\n\n" + helpText)));
      return;
    }

    // Check that no two items are trying to be uploaded to the same location
    final var destinationSet = toUpload.stream().map(BulkPutItem::path).collect(Collectors.toSet());
    if(destinationSet.size() != toUpload.size()) {
      context.status(409).json(
          new FormattedError(
              new MalformedRequest(
                  "Multiple items are attempting to be uploaded to the same location. Please give all items unique names.")));
      return;
    }

    // Create all specified objects
    context.status(207).json(handleBulkUpload(toUpload, fileMap, workspaceId, authorize(context).userId()).toString());
  }

  private JsonArray handleBulkUpload(
      List<BulkPutItem> toUpload,
      Map<String, UploadedFile> fileMap,
      int workspaceId,
      String userId
  ) {
    final var responseArray = Json.createArrayBuilder();

    for(final var item : toUpload){
      final HandlerResult uploadResults;
      final var response = Json.createObjectBuilder()
                               .add("item", item.path().toString());

      if(item.uploadType() == ItemType.file) {
        // Do not create the file if the file contents are not provided
        final var uploadedFileName = item.inputFileName().orElse(item.path().getFileName().toString());
        final var file = fileMap.getOrDefault(uploadedFileName, null);
        if(file == null) {
          response.add("status", 400)
                  .add("response",
                       new FormattedError(
                          new MalformedRequest(
                              "No file provided with the name " + uploadedFileName,
                              "Attach file contents under the 'files' part of the request."))
                      .toJson());
          responseArray.add(response);
          continue;
        }

        uploadResults = handleFileUpload(
            workspaceId,
            item.path(),
            file,
            item.overwrite(),
            userId
        );
        response.add("status", uploadResults.status)
                .add("response", uploadResults.response);
      }
      else if (item.uploadType() == ItemType.directory) {
        uploadResults = handleCreateDirectory(workspaceId, item.path());
        response.add("status", uploadResults.status)
                .add("response", uploadResults.response);
      } else {
        logger.debug("BULK UPLOAD: Unsupported item upload type: {}", item.uploadType());
        response.add("status", 501)
                .add("response", new FormattedError("Unsupported item upload type: "+item.uploadType().name()).toJson());
      }
      // Add response to array
      responseArray.add(response);
    }

    return responseArray.build();
  }

  /**
   * Move or Copy multiple files and/or directories in a workspace.
   *
   * See help text for Input Syntax
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
          "items": [                                    // required. list of items to be moved
              {
                "path": "path/to/file1.txt",            // required. path to the item within the workspace
                "renameTo": "newFileName.txt"           // optional. if provided, the new name of the item at the destination
                                                        //   defaults to the item's current name (in this example "file1.txt")
              },
              { "path": "path/to/file2.txt" },
              {
                "path": "path/to/folder",
                "renameTo": "newFolderName"
              }, ...
          ],
          "toWorkspace": 2,                             // optional. if provided, items will be moved to the specified workspace.
                                                        //   defaults to the current workspace.
          "moveTo": "path/to/destination/folder",       // required. path to the folder within the destination workspace where the items will be moved to
          "overwrite": false                            // optional. if provided, determines whether the moved items will overwrite existing items in the destination folder
                                                        //   defaults to "false".
        }

        To Copy Items:
        {
          "items": [                                    // required. list of items to be copied
              {
                "path": "path/to/file1.txt",            // required. path to the item within the workspace
                "renameTo": "newFileName.txt"           // optional. if provided, the new name of the item at the destination
                                                        //   defaults to the item's current name (in this example "file1.txt")
              },
              { "path": "path/to/file2.txt" },
              {
                "path": "path/to/folder",
                "renameTo": "newFolderName"
              }, ...
          ],
          "toWorkspace": 2,                             // optional. if provided, items will be copied to the specified workspace.
                                                        //   defaults to the current workspace.
          "copyTo": "path/to/destination/folder",       // required. path to the folder within the destination workspace where the items will be copied to
          "overwrite": false                            // optional. if provided, determines whether the moved items will overwrite existing items in the destination folder
                                                        //  defaults to "false".
        }""";

    final var sourceWorkspace = Integer.parseInt(context.pathParam("workspaceId"));
    final List<BulkPostItem> items;
    final PostBody body;

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new FormattedError(new MalformedRequest("Body must be type "+ContentType.JSON)));
      return;
    }

    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      final var jsonBody = bodyReader.readObject();
      body = PostBody.fromJson(jsonBody, sourceWorkspace);
      items = jsonBody.getJsonArray("items")
                      .getValuesAs(o -> BulkPostItem.fromJson(o.asJsonObject(), body.destinationPath()));
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          je,
          "Invalid body format. Expected body format is a JSON object with the form:\n\n"+helpText));
      return;
    }

    // Ensure that the user has specified at least one item to alter
    if(items.isEmpty()) {
      context.status(400).json(new FormattedError(new MalformedRequest(
          "Cannot process request: at least one item must be specified.")));
      return;
    }

    // Ensure that no two inputs will try to write to the same location
    final var destinationSet = items.stream().map(BulkPostItem::newPath).collect(Collectors.toSet());
    if(destinationSet.size() != items.size()) {
      context.status(409).json(new FormattedError(new MalformedRequest(
          "Multiple entries in 'item' have the same destination location. Use \"renameTo\" to resolve conflicts.")));
      return;
    }

    // Permissions Check and Action Handling
    switch (body.action()) {
      case PostActions.MOVE -> {
        // Moving between workspaces requires "readFile", "deleteFile" on Workspace 1 and "writeFile" on Workspace 2
        // (Permission derived from mv -v, which shows that moving a file is "copy, then delete")
        if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
              && checkPermissions(context, sourceWorkspace, WorkspaceAction.delete_file_directory)
              && checkPermissions(context, body.destinationWorkspaceId(), WorkspaceAction.write_file_directory))) {
          return;
        }
        final var moveResults = handleBulkMove(
            items,
            sourceWorkspace,
            body.destinationWorkspaceId(),
            body.overwrite(),
            authorize(context).userId()
        );
        context.status(207).json(moveResults.toString());
      }
      case PostActions.COPY -> {
        // Copying between workspaces requires "readFile" on Workspace 1 and "writeFile" on Workspace 2
        if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
              && checkPermissions(context, body.destinationWorkspaceId(), WorkspaceAction.write_file_directory))) {
          return;
        }
        final var copyResults = handleBulkCopy(
            items,
            sourceWorkspace,
            body.destinationWorkspaceId(),
            body.overwrite(),
            authorize(context).userId()
        );
        context.status(207).json(copyResults.toString());
      }
      default -> context.status(501).json(new FormattedError("Unsupported post action: " + body.action().name()).toJson());
    }
  }

  private JsonArray handleBulkMove(
      List<BulkPostItem> toMove,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite,
      String userId
  ) throws NoSuchWorkspaceException {
    final var responseArray = Json.createArrayBuilder();
    for(final var item : toMove){
      final var results = handleMove(
          item.currentLocation(),
          item.newPath(),
          sourceWorkspaceId,
          destinationWorkspaceId,
          overwrite,
          userId
      );
      final var response = Json.createObjectBuilder()
                               .add("item", item.currentLocation().toString())
                               .add("status", results.status)
                               .add("response", results.response);
      responseArray.add(response);
    }
    return responseArray.build();
  }

  private JsonArray handleBulkCopy(
      List<BulkPostItem> toCopy,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite,
      String userId
  ) throws NoSuchWorkspaceException {
    final var responseArray = Json.createArrayBuilder();
    for(final var item : toCopy) {
      final var results = handleCopy(
          item.currentLocation(),
          item.newPath(),
          sourceWorkspaceId,
          destinationWorkspaceId,
          overwrite,
          userId
      );
      final var response = Json.createObjectBuilder()
                               .add("item", item.currentLocation().toString())
                               .add("status", results.status)
                               .add("response", results.response);
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
  public void bulkDelete(Context context) {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    final List<String> toDelete;

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new FormattedError(new MalformedRequest("Body must be type "+ContentType.JSON)));
      return;
    }
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      toDelete = bodyReader.readArray().getValuesAs(JsonString::getString);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(je, "Invalid body format. Expected body format is an array of paths."));
      return;
    }
    // Ensure that the user has specified at least one file or directory to get the contents of
    if(toDelete.isEmpty()) {
      context.status(400).json(new FormattedError(new MalformedRequest(
          "Cannot process request: at least one item must be specified.")));
      return;
    }

    // Permissions Check
    if(!checkPermissions(context, workspaceId, WorkspaceAction.delete_file_directory)) {
      return;
    }

    // Return multipart response
    context.status(207).json(handleBulkDelete(workspaceId, toDelete).toString());
  }

  private JsonArray handleBulkDelete(int workspaceId, List<String> toDelete) {
    final var responseArray = Json.createArrayBuilder();

    for(final var item : toDelete) {
      final var results = handleDelete(workspaceId, Path.of(item));
      final var response = Json.createObjectBuilder()
                               .add("item", item)
                               .add("status", results.status)
                               .add("response", results.response);
      responseArray.add(response);
    }

    return responseArray.build();
  }
  //endregion

  //region Metadata
  /**
   * Get the metadata file for the file located at filepath.
   *
   * Returns {version: '1'} if no metadata file exists.
   * Returns 404 if the underlying file doesn't exist.
   * Returns 400 if the requested file is a directory or a metadata file itself
   */
  public void getMetadataFile(final Context context) throws NoSuchWorkspaceException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.read_file_directory)) {
      return;
    }

    // Check that the underlying file exists
    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).json(new FormattedError("No such file exists in the workspace: " + pathInfo.filePath));
      return;
    }

    try {
      final var fileStream = workspaceService.loadMetadataFile(pathInfo.workspaceId, pathInfo.filePath());
      final var inputStream = fileStream.readingStream();

      // Set up headers for file response
      context.header("x-render-type", RenderType.METADATA.name());
      context.contentType(ContentType.OCTET_STREAM);
      context.header("Content-Disposition", "attachment; filename=\"" + pathInfo.metadataFileName() + "\"");
      context.status(200).result(inputStream);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new FormattedError(wfe, "Could not retrieve metadata file for file "+pathInfo.fileName());
      context.status(400).json(fe);
    }
    catch (IOException ioe) {
      final var fe = new FormattedError(ioe, "Could not retrieve metadata file for file " + pathInfo.fileName());
      logger.warn("GET METADATA: IO Exception: {}", fe);
      context.status(500).json(fe);
    }
  }

  /**
   * Set multiple keys in a file's metadata file using a deep-merge. Creates metadata file if it is not present.
   *
   * Example Input Syntax:
   * {
   *   "readOnly": false
   *   "user": {
   *     "status": "draft"
   *   }
   * }
   *
   * If the metadata files contents are malformed, returns a 500 error response
   * If the user passes a malformed set of keys (including non-existent top-level keys or a non-json object "user" field), returns a 400 error.
   */
  public void setMetadataKeys(final Context context) throws NoSuchWorkspaceException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.write_file_directory)) {
      return;
    }

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new FormattedError(
          "MALFORMED_REQUEST",
          "Body must be type "+ContentType.JSON,
          Optional.empty()));
      return;
    }

    final MetadataUpdates updates;
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      final var jsonBody = bodyReader.readObject();
      updates = MetadataUpdates.fromEndpointBodyJson(authorize(context).userId(), jsonBody);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          je,
          "Invalid body format. Expected body format is a JSON object with the set of keys to be updated."));
      return;
    } catch (MalformedRequest mr) {
      context.status(400).json(new FormattedError(mr));
      return;
    }

    // Ensure that the user has specified at least one key to alter
    if(updates.noUserUpdates()) {
      context.status(400).json(new FormattedError(new MalformedRequest("Cannot process request: at least one key must be specified.")));
      return;
    }

    // Check that the underlying file exists
    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).json(new FormattedError("No such file exists in the workspace: " + pathInfo.filePath));
      return;
    }

    // Update the metadata
    try {
      if(workspaceService.updateMetadataKeys(pathInfo.workspaceId, pathInfo.filePath, updates)) {
        context.status(200).result("Metadata for file %s updated successfully.".formatted(pathInfo.filePath));
      } else {
        context.status(500).json(new FormattedError("Unable to update metadata for file %s".formatted(pathInfo.filePath)));
      }
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new FormattedError(nsw));
    } catch (IOException ioe) {
      final var fe = new FormattedError(ioe);
      logger.warn("SET METADATA: IO Exception: {}", fe);
      context.status(500).json(fe);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new FormattedError(wfe, "Could not update metadata.");
      logger.warn("SET METADATA: WorkspaceFileOpException: {}", fe);
      context.status(500).json(fe);
    } catch (JsonException je) {
      final var fe = new FormattedError(je, "Metadata for file %s is malformed.".formatted(pathInfo.filePath));
      logger.warn("SET METADATA: JsonException: {}", fe);
      context.status(500).json(fe);
    }
  }

  /**
   * Unset multiple keys in file's metadata file. Creates metadata file if it is not present.
   * Subobjects within the "user" object can be specified by following using a "dot-path" syntax, i.e. "user.status"
   *
   * Example Input Syntax:
   * [ "readOnly", "user.status", "user.info.name" ]
   */
  public void unsetMetadataKeys(final Context context) throws NoSuchWorkspaceException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.write_file_directory)) {
      return;
    }

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new FormattedError(new MalformedRequest("Body must be type "+ContentType.JSON)));
      return;
    }

    final Set<String> toUnset;
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      final var unsetList = bodyReader.readArray().getValuesAs(JsonString::getString);
      for(final var key : unsetList) {
        // Check that top-level keys, if provided are on the whitelist
        if(!key.startsWith("user.")) {
          if(!MetadataKeys.whitelist.contains(key)) {
            context.status(400).json(
                new FormattedError(
                    new MalformedRequest("Request body contains unpermitted keys. "
                                         + "Only the following keys may be updated: "
                                         + String.join(", ", MetadataKeys.whitelist))));
            return;
          }
        }
      }

      toUnset = new HashSet<>(unsetList);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          je,
          "Invalid body format. Expected body format is a JSON array with the set of keys to be removed."));
      return;
    }

    // Ensure that the user has specified at least one key to alter
    if(toUnset.isEmpty()) {
      context.status(400).json(new FormattedError(new MalformedRequest("Cannot process request: at least one key must be specified.")));
      return;
    }

    // Check that the underlying file exists
    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).json(new FormattedError("No such file exists in the workspace: " + pathInfo.filePath));
      return;
    }

    // Unset Metadata Keys
    try {
      if(workspaceService.unsetMetadataKeys(pathInfo.workspaceId, pathInfo.filePath, toUnset, authorize(context).userId())) {
        context.status(200).result("Metadata for file %s updated successfully.".formatted(pathInfo.filePath));
      } else {
        context.status(500).json(new FormattedError("Unable to update metadata for file %s".formatted(pathInfo.filePath)));
      }
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new FormattedError(nsw));
    } catch (IOException ioe) {
      final var fe = new FormattedError(ioe);
      logger.warn("UNSET METADATA: IO Exception: {}", fe);
      context.status(500).json(fe);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new FormattedError(wfe, "Could not update metadata.");
      logger.warn("UNSET METADATA: WorkspaceFileOpException: {}", fe);
      context.status(500).json(fe);
    } catch (JsonException je) {
      final var fe = new FormattedError(je, "Metadata for file %s is malformed.".formatted(pathInfo.filePath));
      logger.warn("UNSET METADATA: JsonException: {}", fe);
      context.status(500).json(fe);
    }
  }

  /**
   * Deletes all metadata files associated with a file.
   */
  public void deleteMetadata(final Context context) throws NoSuchWorkspaceException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.delete_file_directory)) {
      return;
    }

    // Check that the underlying file exists
    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).json(new FormattedError("No such file exists in the workspace: " + pathInfo.filePath));
      return;
    }

    // Delete Metadata File
    try {
      if(workspaceService.deleteMetadataFile(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("Metadata for file %s deleted.".formatted(pathInfo.filePath));
      } else {
        context.status(500).json(new FormattedError("Unable to delete metadata for file %s".formatted(pathInfo.filePath)));
      }
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new FormattedError(nsw));
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new FormattedError(wfe, "Could not delete metadata.");
      logger.warn("DELETE METADATA: WorkspaceFileOpException: {}", fe);
      context.status(500).json(fe);
    }
  }
  //endregion
}
