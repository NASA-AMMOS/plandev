package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.exceptions.JWTVerificationException;
import gov.nasa.jpl.aerie.json.FormattedError;
import gov.nasa.jpl.aerie.json.FormattedError.AerieService;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.WorkspaceAction;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsException;
import gov.nasa.jpl.aerie.permissions.gql.WorkspaceId;
import gov.nasa.jpl.aerie.workspace.server.exceptions.FileLockedException;
import gov.nasa.jpl.aerie.workspace.server.exceptions.MalformedRequest;
import gov.nasa.jpl.aerie.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.jpl.aerie.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.types.BulkPutItem;
import gov.nasa.jpl.aerie.workspace.server.types.HandlerResult;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataKeys;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataMergeBehavior;
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
    // Since none of these endpoints are Hasura Actions, ensure that Formatted Errors are not using the Hasura style
    FormattedError.FormattedErrorSerializer.USE_HASURA_FORMATTING = false;

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
                      (ex, ctx) -> ctx.status(404).json(new WorkspaceFormattedError(ex)));
    javalin.exception(NoSuchFileException.class, (ex, ctx) -> ctx.status(404).json(new WorkspaceFormattedError(ex)));
    javalin.exception(MalformedRequest.class, (ex, ctx) -> ctx.status(400).json(new WorkspaceFormattedError(ex)));
    javalin.exception(FileLockedException.class, (ex, ctx) -> ctx.status(423).json(new WorkspaceFormattedError(ex)));
    javalin.exception(IOException.class, (ex, ctx) -> {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ex);
      logger.warn("IO Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(SQLException.class, (ex, ctx) -> {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ex);
      logger.warn("SQL Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(UnauthorizedResponse.class, (ex, ctx) -> {
      final var message = ex.getMessage() != null ? ex.getMessage() : "Unauthorized";
      logger.warn("401 Unauthorized: {}", message);
      ctx.status(401).json(new FormattedError(AerieService.WORKSPACE_SERVER, ex));
    });
    javalin.exception(NumberFormatException.class, (ex, ctx) ->
        ctx.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, ex)));
    javalin.exception(SecurityException.class, (ex, ctx) -> {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ex);
      logger.warn("Security Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(HttpResponseException.class, (ex, ctx) ->
        ctx.status(ex.getStatus()).json(new FormattedError(AerieService.WORKSPACE_SERVER, "HTTP_RESPONSE_EXCEPTION", ex)));
    javalin.exception(Exception.class, (ex, ctx) -> {
      // Catch-all for unexpected issues
      final var message = ex.getMessage() != null ? ex.getMessage() : "Unknown error.";
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, "UNKNOWN_ERROR", message, ex);
      logger.error("Unexpected error processing request: {}", fe);
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
    } catch (PermissionsException pe) {
      if (pe.httpStatusCode() == 500) {
        logger.warn("PERMISSIONS SERVICE: Permissions Service Exception: {}", pe.formattedError());
      }
      context.status(pe.httpStatusCode()).json(pe.formattedError());
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
    } catch (PermissionsException pe) {
      if (pe.httpStatusCode() == 500) {
        logger.warn("CREATE WORKSPACE: Permissions Service Exception: {}", pe.formattedError());
      }
      context.status(pe.httpStatusCode()).json(pe.formattedError());
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
        context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg.formatted("parcelId")));
        return;
      }
      parcelId = bodyJson.getInt("parcelId");

      // Workspace Location
      if (!bodyJson.containsKey("workspaceLocation") || bodyJson.isNull("workspaceLocation")) {
        context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg.formatted("workspaceLocation")));
        return;
      }
      final var workspaceString = bodyJson.getString("workspaceLocation");
      if(workspaceString.contains("/") || workspaceString.contains(".") || workspaceString.contains("~")){
        context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Workspace location may not contain '/' or '.' or '~'"));
        return;
      }
      if(workspaceString.isBlank()) {
        context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Workspace location may not be blank."));
        return;
      }
      workspaceLocation = Path.of(workspaceString);

      // Workspace Name
      if(bodyJson.containsKey("workspaceName")) {
        if(bodyJson.isNull("workspaceName")) {
          context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Workspace name may not be null."));
        }
        workspaceName = bodyJson.getString("workspaceName");
        if(workspaceName.isBlank()) {
          context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Workspace name may not be blank"));
        }
      } else {
        workspaceName = workspaceString;
      }
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, je, "Request body is malformed. Request body format is:\n" + helpText));
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
      context.status(500).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Unable to create workspace."));
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
        context.status(500).json(new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new WorkspaceFormattedError(ex, errorMsg));
    } catch (SQLException e) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, e, errorMsg);
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
        context.status(404).json(new FormattedError(AerieService.WORKSPACE_SERVER, "No such directory."));
        return;
      }
      context.status(200).json(fileTree.toJson().toString());
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe);
      logger.warn("LIST CONTENTS: IO Exception: {}", fe);
      context.status(500).json(fe);
    } catch (SQLException se) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, se);
      logger.warn("LIST CONTENTS: SQL Exception: {}", fe);
      context.status(500).json(fe);
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).json(new WorkspaceFormattedError(ex));
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
        context.status(404).json(new WorkspaceFormattedError(new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
        return;
      }

      try {
        final var fileStream = workspaceService.loadFile(pathInfo.workspaceId, pathInfo.filePath());
        context.header("x-render-type", workspaceService.getFileType(pathInfo.filePath).name());
        context.contentType(ContentType.OCTET_STREAM);
        context.header("Content-Disposition", "attachment; filename=\"" + fileStream.fileName() + "\"");
        // The client sends this ETag back as If-Match when it saves.
        context.header("ETag", fileStream.etag());
        context.status(200).result(fileStream.readingStream()); // Javalin auto-closes InputStreams once it has sent the contents
      } catch (IOException ioe) {
        final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe, "Could not load file " + pathInfo.fileName());
        logger.warn("GET FILE: IO Exception: {}", fe);
        context.status(500).json(fe);
      } catch (SQLException se) {
        final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, se, "Could not load file " + pathInfo.fileName());
        logger.warn("GET FILE: SQL Exception: {}", fe);
        context.status(500).json(fe);
      } catch (NoSuchFileException nfe) {
        context.status(404).json(new WorkspaceFormattedError(nfe));
      } catch (WorkspaceFileOpException wfe) {
        context.status(415).json(new WorkspaceFormattedError(wfe));
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
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, ve));
      return;
    } catch (IllegalArgumentException iae) {
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, iae));
      return;
    }

    final HandlerResult uploadResults;
    if (type == ItemType.file) {
      final var file = context.uploadedFile("file");
      // Reject the request if the file isn't provided.
      if (file == null || !pathInfo.fileName().equals(file.filename())) {
        context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "No file provided with the name " + pathInfo.fileName()));
        return;
      }

      final var ifMatch = context.header("If-Match");
      uploadResults = handleFileUpload(
          pathInfo.workspaceId,
          pathInfo.filePath,
          file,
          overwrite.orElse(false),
          ifMatch,
          authorize(context).userId());

    } else if (type == ItemType.directory) {
      // Reject the request if the "overwrite" flag is supplied
      if(overwrite.isPresent()) {
        context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Query parameter 'overwrite' is not permitted when creating a directory."));
        return;
      }
      uploadResults = handleCreateDirectory(pathInfo.workspaceId(), pathInfo.filePath());
    } else {
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Query param 'type' has invalid value "+type));
      return;
    }

    switch (uploadResults){
      case HandlerResult.Success success -> {
        // Return the saved file's new ETag so the client can keep saving without re-fetching.
        success.etag().ifPresent(et -> context.header("ETag", et));
        context.status(success.status()).result(success.response());
      }
      case HandlerResult.Failure failure -> context.status(failure.status()).json(failure.error());
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
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Body must be type "+ContentType.JSON));
    }
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      body = PostBody.fromJson(bodyReader.readObject(), sourceWorkspace);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER,
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
        switch (moveResults){
          case HandlerResult.Success success -> context.status(success.status()).result(success.response());
          case HandlerResult.Failure failure -> context.status(failure.status()).json(failure.error());
        }
      }
      case PostActions.COPY -> {
        // Copying between workspaces requires "readFile" on Workspace 1 and "writeFile" on Workspace 2
        if (!(checkPermissions(context, sourceWorkspace, WorkspaceAction.read_file_directory)
              && checkPermissions(context, body.destinationWorkspaceId(), WorkspaceAction.write_file_directory))) {
          return;
        }
        final var copyResults = handleCopy(
            pathInfo.filePath,
            body.destinationPath(),
            sourceWorkspace,
            body.destinationWorkspaceId(),
            body.overwrite(),
            authorize(context).userId()
        );
        switch (copyResults) {
          case HandlerResult.Success success -> context.status(success.status()).result(success.response());
          case HandlerResult.Failure failure -> context.status(failure.status()).json(failure.error());
        }
      }
      default -> context.status(501).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Unsupported post action: " + body.action().name()).toJson());
    }
  }

  private void deleteFileDirectory(Context context) {
    final var pathInfo = PathInformation.of(context);
    // Permissions Check
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.delete_file_directory)) {
      return;
    }

    final var deleteResults = handleDelete(pathInfo.workspaceId, pathInfo.filePath);

    switch (deleteResults){
      case HandlerResult.Success success -> context.status(success.status()).result(success.response());
      case HandlerResult.Failure failure -> context.status(failure.status()).json(failure.error());
    }
  }
  // endregion

  // region Single Item Action Handlers
  private HandlerResult handleFileUpload(
      int workspaceId,
      Path uploadPath,
      UploadedFile file,
      boolean overwrite,
      String ifMatch,
      final String userId) {
    try {
      // Verify the user isn't attempting to save a metadata file using the main file api
      if(RenderType.isAerieMetadataFile(uploadPath.getFileName().toString())) {
        return new HandlerResult.Failure(
            405,
            new WorkspaceFormattedError(
                new MalformedRequest("Could not save file.",
                    "Metadata files may not be uploaded via the file API."
                    + " Use the metadata API (located at /metadata/{workspaceId}/<basefilepath>) instead.")));
      }

      // Conflict if the file already exists and "overwrite" is false (defaults to false).
      // An If-Match means the client is editing a known file, so let the version check below handle it.
      if (ifMatch == null && workspaceService.checkFileExists(workspaceId, uploadPath) && !overwrite) {
        return new HandlerResult.Failure(409, new FormattedError(AerieService.WORKSPACE_SERVER, uploadPath + " already exists."));
      }

      // Report a "Locked" status if the file is currently marked as "readOnly"
      if(workspaceService.isReadOnly(workspaceId, uploadPath)) {
        return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(uploadPath), "Cannot update file at " + uploadPath));
      }

      // Reject the save if the file changed since the client loaded it. "*" or no If-Match means force-overwrite.
      if (ifMatch != null && !ifMatch.equals("*")) {
        try {
          final var currentETag = workspaceService.getETag(workspaceId, uploadPath);
          if (!currentETag.equals(ifMatch)) {
            // Who/when is best-effort detail for the modal; don't let a metadata read failure make this a 500.
            String lastEditedBy = null;
            String lastEditedAt = null;
            try {
              final var editInfo = workspaceService.getLastEditInfo(workspaceId, uploadPath);
              lastEditedBy = editInfo.lastEditedBy();
              lastEditedAt = editInfo.lastEditedAt();
            } catch (IOException | NoSuchWorkspaceException | WorkspaceFileOpException | JsonException e) {
              logger.warn("UPLOAD FILE: could not read last-edit info for conflict on {}: {}", uploadPath, e.getMessage());
            }
            return new HandlerResult.Failure(
                412,
                WorkspaceFormattedError.saveConflict("conflict", currentETag, lastEditedBy, lastEditedAt));
          }
        } catch (NoSuchFileException nfe) {
          // File is gone — deleted or moved out from under the editor.
          return new HandlerResult.Failure(412, WorkspaceFormattedError.saveConflict("deleted", null, null, null));
        }
      }

      // saveFile hashes as it writes and returns the new ETag, so we don't re-read the file.
      final var newETag = workspaceService.saveFile(workspaceId, uploadPath, file, userId);
      if (newETag.isPresent()) {
        return new HandlerResult.Success(
            200,
            "File " + uploadPath.getFileName() + " uploaded to " + uploadPath,
            newETag);
      } else {
        logger.warn("UPLOAD FILE: Save File failed for path {}", uploadPath);
        return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, "Could not save file."));
      }
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe, "Could not save file.");
      logger.warn("UPLOAD FILE: IOException: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe, "Could not save file.");
      logger.warn("UPLOAD FILE: WorkspaceFileOpException: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw, "Could not create directory."));
    }
  }

  private HandlerResult handleCreateDirectory(int workspaceId, Path destinationPath) {
    try {
      if (workspaceService.createDirectory(workspaceId, destinationPath)) {
        return new HandlerResult.Success(200, "Directory created.");
      } else {
        logger.warn("CREATE DIRECTORY: Create Directory failed for path {}", destinationPath);
        return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, "Could not create directory."));
      }
    } catch (IOException ioe) {
      logger.warn("CREATE DIRECTORY: IOException: {}", destinationPath);
      return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, ioe, "Could not create directory."));
    } catch (WorkspaceFileOpException wfe) {
      logger.warn("CREATE DIRECTORY: WorkspaceFileOpException: {}", destinationPath);
      return new HandlerResult.Failure(500, new WorkspaceFormattedError(wfe, "Could not create directory."));
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw, "Could not create directory."));
    }
  }

  /**
   * Helper method to determine if a given "moveTo" command corresponds to a proper move or just a rename
   */
  private boolean isMove(Path sourcePath, Path destinationPath, int sourceWorkspaceId, int destinationWorkspaceId) {
    // If the workspace differs, it must be a move
    if(sourceWorkspaceId != destinationWorkspaceId) return true;
    // Normalize the paths
    final var normalizedSourcePath = sourcePath.normalize();
    final var normalizedDestPath = destinationPath.normalize();
    // If the paths have different lengths, it must be a move
    if(normalizedSourcePath.getNameCount() != normalizedDestPath.getNameCount()) return true;
    // If both paths are just a file name, it must be a rename
    if(normalizedSourcePath.getNameCount() == 1) return false;
    // Else, it depends on if the paths match
    // If they do, it's a rename
    // If they don't, it's a move
    return !normalizedSourcePath.getParent().equals(normalizedDestPath.getParent());
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
    final var successMsg = "'%s' in Workspace %d moved to '%s' in Workspace %d"
            .formatted(toMove, sourceWorkspaceId, destinationPath, destinationWorkspaceId);

    // Verify the user isn't attempting to move a metadata file using the main file api
    if(RenderType.isAerieMetadataFile(toMove.getFileName().toString())) {
      return new HandlerResult.Failure(
          405,
          new WorkspaceFormattedError(
              new MalformedRequest(
                  errorMsg,
                  "Metadata files may not be directly moved via the file API. Move the main file instead.")));
    }

    // Verify the user isn't attempting to rename a non-metadata file to a metadata file
    if(RenderType.isAerieMetadataFile(destinationPath.getFileName().toString())) {
      return new HandlerResult.Failure(
          405,
          new WorkspaceFormattedError(
              new MalformedRequest(errorMsg, "Normal files may not be renamed to metadata files.")));
    }

    if (!workspaceService.checkFileExists(sourceWorkspaceId, toMove)) {
      return new HandlerResult.Failure(
          404,
          new WorkspaceFormattedError(
              new NoSuchFileException(sourceWorkspaceId, toMove),
              errorMsg));
    }

    final var destinationFileExists = workspaceService.checkFileExists(destinationWorkspaceId, destinationPath);
    if (destinationFileExists && !overwrite) {
      return new HandlerResult.Failure(409, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg, destinationPath + " already exists."));
    }

    try {
      if (workspaceService.isDirectory(sourceWorkspaceId, toMove)) {
        // Check whether it's a move or rename
        if(isMove(toMove, destinationPath, sourceWorkspaceId, destinationWorkspaceId)) {
          // Report a "Locked" status if there is a locked file within the source directory
          // The destination does not need to be checked, because even if there is already a folder with the same name
          // at the destination, the source will only overwrite that folder if it is empty (meaning it cannot contain a locked file)
          final var readOnlyFiles = workspaceService.getReadOnlyFiles(sourceWorkspaceId, toMove);
          if(!readOnlyFiles.isEmpty()){
            return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(toMove, readOnlyFiles), errorMsg));
          }
        }

        if (workspaceService.moveDirectory(sourceWorkspaceId, toMove, destinationWorkspaceId, destinationPath)) {
          return new HandlerResult.Success(200, successMsg);
        } else {
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      } else {
        // Report a "Locked" status if either file is currently marked as "readOnly"
        if(workspaceService.isReadOnly(sourceWorkspaceId, toMove)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(toMove), errorMsg));
        }
        if (destinationFileExists && workspaceService.isReadOnly(destinationWorkspaceId, destinationPath)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(destinationPath), errorMsg));
        }

        if (workspaceService.moveFile(sourceWorkspaceId, toMove, destinationWorkspaceId, destinationPath, userId)) {
          return new HandlerResult.Success(200, successMsg);
        } else {
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      }
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe, errorMsg);
      logger.warn("MOVE: IO EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe, errorMsg);
      logger.warn("MOVE: WORKSPACE FILE OP EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (SQLException se) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, se, errorMsg);
      logger.warn("MOVE: SQL EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
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
    final var successMsg = "'%s' in Workspace %d copied to '%s' in Workspace %d"
            .formatted(toCopy, sourceWorkspaceId, destinationPath, destinationWorkspaceId);

    // Verify the user isn't attempting to copy a metadata file using the main file api
    if(RenderType.isAerieMetadataFile(toCopy.getFileName().toString())) {
      return new HandlerResult.Failure(
          405,
          new WorkspaceFormattedError(
              new MalformedRequest(
                  errorMsg,
                  "Metadata files may not be directly copied via the file API. Copy the main file instead.")));
    }

    // Verify the user isn't attempting to rename a non-metadata file to a metadata file
    if(RenderType.isAerieMetadataFile(destinationPath.getFileName().toString())) {
      return new HandlerResult.Failure(
          405,
          new WorkspaceFormattedError(
              new MalformedRequest(errorMsg, "Normal files may not be renamed to metadata files.")));
    }

    if (!workspaceService.checkFileExists(sourceWorkspaceId, toCopy)) {
      return new HandlerResult.Failure(
          404,
          new WorkspaceFormattedError(new NoSuchFileException(sourceWorkspaceId, toCopy), errorMsg));
    }

    final var destinationFileExists = workspaceService.checkFileExists(destinationWorkspaceId, destinationPath);
    if (destinationFileExists && !overwrite) {
      return new HandlerResult.Failure(409, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg, destinationPath + " already exists."));
    }

    try {
      if (workspaceService.isDirectory(sourceWorkspaceId, toCopy)) {
        if (workspaceService.copyDirectory(sourceWorkspaceId, toCopy, destinationWorkspaceId, destinationPath)) {
          return new HandlerResult.Success(200, successMsg);
        } else {
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      } else {
        // Report a "Locked" status if the destination file is currently marked as "readOnly"
        if(destinationFileExists && workspaceService.isReadOnly(destinationWorkspaceId, destinationPath)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(destinationPath), errorMsg));
        }

        if (workspaceService.copyFile(sourceWorkspaceId, toCopy, destinationWorkspaceId, destinationPath, userId)) {
          return new HandlerResult.Success(200, successMsg);
        } else {
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      }
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe, errorMsg);
      logger.warn("COPY: WORKSPACE FILE OP EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe, errorMsg);
      logger.warn("COPY: IO EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    }
  }

  private HandlerResult handleDelete(int workspaceId, Path filePath) {
    try {
      final var errorMsg = "Could not delete %s.".formatted(filePath);

      // Verify the user isn't attempting to delete a metadata file using the main file api
      if(RenderType.isAerieMetadataFile(filePath.getFileName().toString())) {
        return new HandlerResult.Failure(
            405,
            new WorkspaceFormattedError(
                new MalformedRequest(
                    errorMsg,
                    "Metadata files may not be directly deleted via the file API. "
                    + "Use the metadata API (located at /metadata/{workspaceId}/<basefilepath>) instead.")));
      }

      if (!workspaceService.checkFileExists(workspaceId, filePath)) {
        return new HandlerResult.Failure(404, new WorkspaceFormattedError(new NoSuchFileException(workspaceId, filePath)));
      }

      if (workspaceService.isDirectory(workspaceId, filePath)) {
        // Report a "Locked" status if there is a locked file within the directory
        final var readOnlyFiles = workspaceService.getReadOnlyFiles(workspaceId, filePath);
        if(!readOnlyFiles.isEmpty()){
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(filePath, readOnlyFiles), errorMsg));
        }

        if (workspaceService.deleteDirectory(workspaceId, filePath)) {
          return new HandlerResult.Success(200, "Directory deleted.");
        } else {
          logger.warn("DELETE: Delete Directory failed for path {}", filePath);
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      } else {
        // Report a "Locked" status if the file is currently marked as "readOnly"
        if(workspaceService.isReadOnly(workspaceId, filePath)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(filePath), errorMsg));
        }

        if (workspaceService.deleteFile(workspaceId, filePath)) {
          return new HandlerResult.Success(200, "File deleted.");
        } else {
          logger.warn("DELETE: Delete File failed for path {}", filePath);
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      }
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw));
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe);
      logger.warn("DELETE: WORKSPACE FILE OP EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe);
      logger.warn("DELETE: IO EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (SQLException se) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, se);
      logger.warn("DELETE: SQL EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
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
      context.status(400).json(new WorkspaceFormattedError(
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
          AerieService.WORKSPACE_SERVER,
          je,
          "Invalid body format. Expected body format is an array of JSON objects with the form:\n\n"+helpText));
      return;
    }

    // Ensure that the user has specified at least one file or directory to upload
    if(toUpload.isEmpty()) {
      context.status(400).json(
          new WorkspaceFormattedError(new MalformedRequest("Cannot process request: at least one item must be specified.")));
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
      context.status(400).json(new WorkspaceFormattedError(
          new MalformedRequest(
              "Cannot process request: multiple files are attached under the same name.",
              "Attach file contents under unique names.\n\n" + helpText)));
      return;
    }

    // Check that no two items are trying to be uploaded to the same location
    final var destinationSet = toUpload.stream().map(BulkPutItem::path).collect(Collectors.toSet());
    if(destinationSet.size() != toUpload.size()) {
      context.status(409).json(
          new WorkspaceFormattedError(
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
                       new WorkspaceFormattedError(
                          new MalformedRequest(
                              "No file provided with the name " + uploadedFileName,
                              "Attach file contents under the 'files' part of the request."))
                      .toJson());
          responseArray.add(response);
          continue;
        }

        // Bulk uploads have no per-item If-Match; pass null to skip the version check (unchanged behavior).
        uploadResults = handleFileUpload(
            workspaceId,
            item.path(),
            file,
            item.overwrite(),
            null,
            userId
        );
        response.add("status", uploadResults.status())
                .add("response", uploadResults.jsonResponse());
      }
      else if (item.uploadType() == ItemType.directory) {
        uploadResults = handleCreateDirectory(workspaceId, item.path());
        response.add("status", uploadResults.status())
                .add("response", uploadResults.jsonResponse());
      } else {
        logger.debug("BULK UPLOAD: Unsupported item upload type: {}", item.uploadType());
        response.add("status", 501)
                .add("response", new FormattedError(
                    AerieService.WORKSPACE_SERVER,
                    "Unsupported item upload type: "+item.uploadType().name()
                ).toJson());
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
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest("Body must be type "+ContentType.JSON)));
      return;
    }

    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      final var jsonBody = bodyReader.readObject();
      body = PostBody.fromJson(jsonBody, sourceWorkspace);
      items = jsonBody.getJsonArray("items")
                      .getValuesAs(o -> BulkPostItem.fromJson(o.asJsonObject(), body.destinationPath()));
    } catch (JsonException je) {
      context.status(400).json(
          new FormattedError(
          AerieService.WORKSPACE_SERVER,
          je,
          "Invalid body format. Expected body format is a JSON object with the form:\n\n"+helpText));
      return;
    }

    // Ensure that the user has specified at least one item to alter
    if(items.isEmpty()) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest(
          "Cannot process request: at least one item must be specified.")));
      return;
    }

    // Ensure that no two inputs will try to write to the same location
    final var destinationSet = items.stream().map(BulkPostItem::newPath).collect(Collectors.toSet());
    if(destinationSet.size() != items.size()) {
      context.status(409).json(new WorkspaceFormattedError(new MalformedRequest(
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
      default -> context.status(501).json(
          new FormattedError(AerieService.WORKSPACE_SERVER,
                             "Unsupported post action: " + body.action().name()).toJson());
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
                               .add("status", results.status())
                               .add("response", results.jsonResponse());
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
                               .add("status", results.status())
                               .add("response", results.jsonResponse());
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
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest("Body must be type "+ContentType.JSON)));
      return;
    }
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      toDelete = bodyReader.readArray().getValuesAs(JsonString::getString);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          AerieService.WORKSPACE_SERVER,
          je,
          "Invalid body format. Expected body format is an array of paths."));
      return;
    }
    // Ensure that the user has specified at least one file or directory to get the contents of
    if(toDelete.isEmpty()) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest(
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
                               .add("status", results.status())
                               .add("response", results.jsonResponse());
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
      context.status(404).json(new WorkspaceFormattedError(new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
      return;
    }

    try {
      final var fileStream = workspaceService.loadMetadataFile(pathInfo.workspaceId, pathInfo.filePath());

      // Set up headers for file response
      context.header("x-render-type", RenderType.METADATA.name());
      context.contentType(ContentType.OCTET_STREAM);
      // The client sends this ETag back as If-Match when it saves.
      context.header("ETag", fileStream.etag());
      context.header("Content-Disposition", "attachment; filename=\"" + fileStream.fileName() + "\"");
      context.status(200).result(fileStream.readingStream()); // Javalin auto-closes InputStreams once it has sent the contents
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe, "Could not retrieve metadata file for file "+pathInfo.fileName());
      context.status(400).json(fe);
    }
    catch (IOException ioe) {
      final var fe = new FormattedError(
          AerieService.WORKSPACE_SERVER,
          ioe,
          "Could not retrieve metadata file for file " + pathInfo.fileName());
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
   *
   * Also takes in a query param "mergeBehavior", which can be one of "deep", "shallow", and "overwrite". Behavior is as follows:
   *  - "deep": deep merge the existing "user" field with the new value, if provided (combine nested properties)
   *  - "shallow": shallow merge the existing "user" field with the new value, if provided (combine only top-level properties)
   *  - "overwrite": replace the "user" field with the new value, if provided
   * Defaults to "shallow" if "mergeBehavior" is not specified
   */
  public void setMetadataKeys(final Context context) throws NoSuchWorkspaceException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.write_file_directory)) {
      return;
    }

    final MetadataMergeBehavior mergeBehavior;

    // Validate the query param
    try {
      final var mergeBehaviorParam = context.queryParamAsClass("mergeBehavior", String.class).getOrDefault("shallow");
      mergeBehavior = MetadataMergeBehavior.of(mergeBehaviorParam);
    } catch (ValidationException ve) {
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, ve));
      return;
    } catch (IllegalArgumentException iae) {
      context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, iae));
      return;
    }

    // Get body
    if(!ContentType.JSON.equals(context.contentType())) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest("Body must be type "+ContentType.JSON)));
      return;
    }

    final MetadataUpdates updates;
    try(final var bodyReader = Json.createReader(new StringReader(context.body()))){
      final var jsonBody = bodyReader.readObject();
      updates = MetadataUpdates.fromEndpointBodyJson(authorize(context).userId(), jsonBody);
    } catch (JsonException je) {
      context.status(400).json(new FormattedError(
          AerieService.WORKSPACE_SERVER,
          je,
          "Invalid body format. Expected body format is a JSON object with the set of keys to be updated."));
      return;
    } catch (MalformedRequest mr) {
      context.status(400).json(new WorkspaceFormattedError(mr));
      return;
    }

    // Ensure that the user has specified at least one key to alter
    if(updates.noUserUpdates()) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest("Cannot process request: at least one key must be specified.")));
      return;
    }

    // Check that the underlying file exists
    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).json(new WorkspaceFormattedError(new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
      return;
    }

    // Update the metadata
    try {
      if(workspaceService.updateMetadataKeys(pathInfo.workspaceId, pathInfo.filePath, updates, mergeBehavior)) {
        context.status(200).result("Metadata for file %s updated successfully.".formatted(pathInfo.filePath));
      } else {
        context.status(500).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Unable to update metadata for file %s".formatted(pathInfo.filePath)));
      }
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new WorkspaceFormattedError(nsw));
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe);
      logger.warn("SET METADATA: IO Exception: {}", fe);
      context.status(500).json(fe);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe, "Could not update metadata.");
      logger.warn("SET METADATA: WorkspaceFileOpException: {}", fe);
      context.status(500).json(fe);
    } catch (JsonException je) {
      final var fe = new FormattedError(
          AerieService.WORKSPACE_SERVER,
          je,
          "Metadata for file %s is malformed.".formatted(pathInfo.filePath));
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
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest("Body must be type "+ContentType.JSON)));
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
                new WorkspaceFormattedError(
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
          AerieService.WORKSPACE_SERVER,
          je,
          "Invalid body format. Expected body format is a JSON array with the set of keys to be removed."));
      return;
    }

    // Ensure that the user has specified at least one key to alter
    if(toUnset.isEmpty()) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest("Cannot process request: at least one key must be specified.")));
      return;
    }

    // Check that the underlying file exists
    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).json(new WorkspaceFormattedError(new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
      return;
    }

    // Unset Metadata Keys
    try {
      if(workspaceService.unsetMetadataKeys(pathInfo.workspaceId, pathInfo.filePath, toUnset, authorize(context).userId())) {
        context.status(200).result("Metadata for file %s updated successfully.".formatted(pathInfo.filePath));
      } else {
        context.status(500).json(new FormattedError(
            AerieService.WORKSPACE_SERVER,
            "Unable to update metadata for file %s".formatted(pathInfo.filePath)));
      }
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new WorkspaceFormattedError(nsw));
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe);
      logger.warn("UNSET METADATA: IO Exception: {}", fe);
      context.status(500).json(fe);
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe, "Could not update metadata.");
      logger.warn("UNSET METADATA: WorkspaceFileOpException: {}", fe);
      context.status(500).json(fe);
    } catch (JsonException je) {
      final var fe = new FormattedError(
          AerieService.WORKSPACE_SERVER,
          je,
          "Metadata for file %s is malformed.".formatted(pathInfo.filePath));
      logger.warn("UNSET METADATA: JsonException: {}", fe);
      context.status(500).json(fe);
    }
  }

  /**
   * Deletes all metadata files associated with a file.
   * It does not check if the specified file exists, to permit cleaning up "orphaned" metadata files
   */
  public void deleteMetadata(final Context context) {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.delete_file_directory)) {
      return;
    }

    // Delete Metadata File
    try {
      if(workspaceService.deleteMetadataFile(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("Metadata for file %s deleted.".formatted(pathInfo.filePath));
      } else {
        context.status(500).json(new FormattedError(
            AerieService.WORKSPACE_SERVER,
            "Unable to delete metadata for file %s".formatted(pathInfo.filePath)));
      }
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new WorkspaceFormattedError(nsw));
    } catch (WorkspaceFileOpException wfe) {
      final var fe = new WorkspaceFormattedError(wfe, "Could not delete metadata.");
      logger.warn("DELETE METADATA: WorkspaceFileOpException: {}", fe);
      context.status(500).json(fe);
    }
  }
  //endregion
}
