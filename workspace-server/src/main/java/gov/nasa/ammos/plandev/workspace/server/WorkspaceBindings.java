package gov.nasa.ammos.plandev.workspace.server;

import com.auth0.jwt.exceptions.JWTVerificationException;
import gov.nasa.ammos.plandev.json.FormattedError;
import gov.nasa.ammos.plandev.json.FormattedError.AerieService;
import gov.nasa.ammos.plandev.permissions.PermissionsService;
import gov.nasa.ammos.plandev.permissions.WorkspaceAction;
import gov.nasa.ammos.plandev.permissions.exceptions.PermissionsException;
import gov.nasa.ammos.plandev.permissions.gql.WorkspaceId;
import gov.nasa.ammos.plandev.workspace.server.exceptions.FileLockedException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.MalformedRequest;
import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.PartialMutationException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.BulkPutItem;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataKeys;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataMergeBehavior;
import gov.nasa.ammos.plandev.workspace.server.types.PostActions;
import gov.nasa.ammos.plandev.workspace.server.types.ItemType;
import gov.nasa.ammos.plandev.workspace.server.types.PostBody;
import gov.nasa.ammos.plandev.workspace.server.types.BulkPostItem;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceVersioningService;
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
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
  private final WorkspaceVersioningService versioningService;
  private final PermissionsService permissionsService;
  private final String hasuraAdminSecret;

  public WorkspaceBindings(
      final JWTService jwtService,
      final WorkspaceService workspaceService,
      final WorkspaceVersioningService versioningService,
      final PermissionsService permissionsService,
      final String hasuraAdminSecret) {
    this.jwtService = jwtService;
    this.workspaceService = workspaceService;
    this.versioningService = versioningService;
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
      before("/versioning/*", ctx -> {
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

      // Versioning Paths
      // Register the fixed segments first: "/versioning/{workspaceId}" would otherwise match "restore" or "metadata".
      path("/versioning/metadata/{workspaceId}/<path>", () -> ApiBuilder.get(this::getRevisionMetadata));
      path("/versioning/restore/{workspaceId}/<path>", () -> ApiBuilder.post(this::restoreRevision));
      path("/versioning/{workspaceId}/<path>", () -> {
        ApiBuilder.get(this::getFileRevision);
        ApiBuilder.post(this::createRevision);
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
    javalin.exception(
        WorkspaceHistoryException.class, (ex, ctx) -> {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ex.kind.errorType, ex);
      logger.error("Workspace history error: {}", fe);
      ctx.status(500).json(fe);
    });
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
      final var workspace = workspaceService.getWorkspace(workspaceId);
      if (versioningService.deleteWorkspace(workspace, workspaceService)) {
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
    } catch (Exception e) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, "INTERNAL_ERROR", errorMsg, e);
      logger.warn("DELETE WORKSPACE: Exception: {}", fe);
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
      final var workspace = workspaceService.getWorkspace(workspaceId);
      final var fileTree = workspaceService.listFiles(workspace, directoryPath, depth, withMetadata);
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
    } catch (WorkspaceFileOpException wfe) {
      // TODO: Cleanup when I'm done messing w listFiles
      final var fe = new WorkspaceFormattedError(wfe, "Could not save file.");
      logger.warn("LIST FILES: WorkspaceFileOpException: {}", fe);
      context.status(500).json(fe);
    }
  }

  private void getFileDirectory(Context context) throws NoSuchWorkspaceException {
    // Permissions Check
    final var pathInfo = PathInformation.of(context);
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.read_file_directory)) {
      return;
    }

    try {
      final var workspace = workspaceService.getWorkspace(pathInfo.workspaceId);

      if (workspaceService.isDirectory(workspace, pathInfo.filePath)) {
        listContents(context);
      } else {
        if (!workspaceService.checkFileExists(workspace, pathInfo.filePath)) {
          context.status(404).json(
              new WorkspaceFormattedError(
                  new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
          return;
        }

        final var fileStream = workspaceService.loadFile(workspace, pathInfo.filePath());
        context.header("x-render-type", workspaceService.getFileType(pathInfo.filePath).name());
        context.contentType(ContentType.OCTET_STREAM);
        context.header("Content-Disposition", "attachment; filename=\"" + fileStream.fileName() + "\"");
        // The client sends this ETag back as If-Match when it saves.
        context.header("ETag", fileStream.etag());
        context.status(200).result(fileStream.readingStream()); // Javalin auto-closes InputStreams once it has sent the contents
      }
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

      logger.info("Attempting to create/update file: {} in workspace {} (overwrite: {})", pathInfo.filePath(), pathInfo.workspaceId(), overwrite.orElse(false));
      final var ifMatch = context.header("If-Match");
      uploadResults = handleFileUpload(
          pathInfo.workspaceId(),
          pathInfo.filePath(),
          file,
          overwrite.orElse(false),
          ifMatch,
          authorize(context).userId());

    } else if (type == ItemType.directory) {
      logger.info("Attempting to create directory: {} in workspace {} (overwrite: {})", pathInfo.filePath(), pathInfo.workspaceId(), overwrite.orElse(false));
      // Reject the request if the "overwrite" flag is supplied
      if(overwrite.isPresent()) {
        context.status(400).json(new FormattedError(AerieService.WORKSPACE_SERVER, "Query parameter 'overwrite' is not permitted when creating a directory."));
        return;
      }
      uploadResults = handleCreateDirectory(pathInfo.workspaceId(), pathInfo.filePath(), authorize(context).userId());
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

  private void post(Context context) {
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
    var userId = authorize(context).userId();
    logger.info(
      "Attempting to {}: {} to {} (from WS: {}, to WS: {}, overwrite: {}, user: {})",
      body.action(),
      pathInfo.filePath(),
      body.destinationPath(),
      sourceWorkspace,
      body.destinationWorkspaceId(),
      body.overwrite(),
      userId
    );
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
            userId
        );
        switch (moveResults){
          case HandlerResult.Success success -> {
        success.etag().ifPresent(et -> context.header("ETag", et));
        context.status(success.status()).result(success.response());
      }
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
            userId
        );
        switch (copyResults) {
          case HandlerResult.Success success -> {
        success.etag().ifPresent(et -> context.header("ETag", et));
        context.status(success.status()).result(success.response());
      }
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

    final var deleteResults = handleDelete(pathInfo.workspaceId, pathInfo.filePath, authorize(context).userId());

    switch (deleteResults){
      case HandlerResult.Success success -> {
        success.etag().ifPresent(et -> context.header("ETag", et));
        context.status(success.status()).result(success.response());
      }
      case HandlerResult.Failure failure -> context.status(failure.status()).json(failure.error());
    }
  }
  // endregion

  // region Single Item Action Handlers
  /**
   * Run a handler as one workspace mutation: its validation (existence, readOnly, ETag) and its filesystem change run
   * under the workspace lock, and the change is committed to the workspace's history before success is reported.
   * A Failure result, an exception, or a history failure leaves the workspace as it was.
   */
  private HandlerResult mutate(
      final Workspace workspace,
      final String userId,
      final String commitMessage,
      final WorkspaceGitService.Mutation<HandlerResult> handler)
  {
    final var commits = new LinkedHashMap<Workspace, String>();
    commits.put(workspace, commitMessage);
    return mutate(commits, userId, null, handler);
  }

  /**
   * As {@link #mutate(Workspace, String, String, WorkspaceGitService.Mutation)}, across workspaces committed in the given
   * order. partialMessage describes the outcome if a later workspace's commit fails after an earlier one's
   * succeeded (see {@link PartialMutationException}).
   */
  private HandlerResult mutate(
      final LinkedHashMap<Workspace, String> commits,
      final String userId,
      final String partialMessage,
      final WorkspaceGitService.Mutation<HandlerResult> handler)
  {
    try {
      return versioningService.mutate(commits, userId, handler, result -> result instanceof HandlerResult.Success);
    } catch (WorkspaceHistoryException whe) {
      final var fe = whe instanceof PartialMutationException pme ?
          new WorkspaceFormattedError(pme, partialMessage)
          : new WorkspaceFormattedError(whe);
      logger.error("WORKSPACE MUTATION: Workspace History Exception {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (SecurityException se) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, se);
      logger.warn("WORKSPACE MUTATION: Security Exception: {}", fe);
      // Reported per item so one bad path in a bulk request does not hide the other items' results
      return new HandlerResult.Failure(500, fe);
    } catch (Exception e) {
      final var message = e.getMessage() != null ? e.getMessage() : "Internal error.";
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, "INTERNAL_ERROR", message, e);
      logger.error("WORKSPACE MUTATION: Internal exception: {}", fe);
      return new HandlerResult.Failure(500, fe);
    }
  }

  private static void respond(final Context context, final HandlerResult result) {
    switch (result) {
      case HandlerResult.Success success -> {
        success.etag().ifPresent(et -> context.header("ETag", et));
        context.status(success.status()).result(success.response());
      }
      case HandlerResult.Failure failure -> context.status(failure.status()).json(failure.error());
    }
  }

  /** The 412 for a stale If-Match, with who/when last edited the file for the conflict modal. */
  private HandlerResult.Failure conflict(final Workspace workspace, final Path path, final String currentETag) {
    // Who/when is best-effort detail for the modal; don't let a metadata read failure make this a 500.
    String lastEditedBy = null;
    String lastEditedAt = null;
    try {
      final var editInfo = workspaceService.getLastEditInfo(workspace, path);
      lastEditedBy = editInfo.lastEditedBy();
      lastEditedAt = editInfo.lastEditedAt();
    } catch (IOException | WorkspaceFileOpException | JsonException e) {
      logger.warn("Could not read last-edit info for conflict on {}: {}", path, e.getMessage());
    }
    return new HandlerResult.Failure(412, WorkspaceFormattedError.saveConflict("conflict", currentETag, lastEditedBy, lastEditedAt));
  }

  public HandlerResult handleFileUpload(
      int workspaceId,
      Path uploadPath,
      UploadedFile file,
      boolean overwrite,
      String ifMatch,
      final String userId
  ) {
    final Workspace workspace;
    try{
      workspace = workspaceService.getWorkspace(workspaceId);
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("UPLOAD FILE: SQL EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw));
    }
    return mutate(workspace, userId, "Update " + uploadPath,
                  () -> handleFileUploadLocked(workspace, uploadPath, file, overwrite, ifMatch, userId));
  }

  private HandlerResult handleFileUploadLocked(
      Workspace workspace,
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
      if (ifMatch == null && workspaceService.checkFileExists(workspace, uploadPath) && !overwrite) {
        return new HandlerResult.Failure(409, new FormattedError(AerieService.WORKSPACE_SERVER, uploadPath + " already exists."));
      }

      // Report a "Locked" status if the file is currently marked as "readOnly"
      if(workspaceService.isReadOnly(workspace, uploadPath)) {
        return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(uploadPath), "Cannot update file at " + uploadPath));
      }

      // Reject the save if the file changed since the client loaded it. "*" or no If-Match means force-overwrite.
      if (ifMatch != null && !ifMatch.equals("*")) {
        try {
          final var currentETag = workspaceService.getETag(workspace, uploadPath);
          if (!currentETag.equals(ifMatch)) return conflict(workspace, uploadPath, currentETag);
        } catch (NoSuchFileException nfe) {
          // File is gone — deleted or moved out from under the editor.
          return new HandlerResult.Failure(412, WorkspaceFormattedError.saveConflict("deleted", null, null, null));
        }
      }

      // saveFile hashes as it writes and returns the new ETag, so we don't re-read the file.
      final var newETag = workspaceService.saveFile(workspace, uploadPath, file, userId);
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
    }
  }

  public HandlerResult handleCreateDirectory(int workspaceId, Path destinationPath, String userId) {
    final Workspace workspace;
    try{
      workspace = workspaceService.getWorkspace(workspaceId);
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("CREATE DIRECTORY: SQL EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw));
    }

    // Empty directories are not representable in Git, so this usually records nothing; it still runs under the lock.
    return mutate(workspace, userId, "Create directory " + destinationPath,
                  () -> handleCreateDirectoryLocked(workspace, destinationPath));
  }

  private HandlerResult handleCreateDirectoryLocked(Workspace workspace, Path destinationPath) {
    try {
      if (workspaceService.createDirectory(workspace, destinationPath)) {
        return new HandlerResult.Success(200, "Directory created.");
      } else {
        logger.warn("CREATE DIRECTORY: Create Directory failed for path {}", destinationPath);
        return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, "Could not create directory."));
      }
    } catch (IOException ioe) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe, "Could not create directory: "+destinationPath);
      logger.warn("CREATE DIRECTORY: IO Exception: {}", fe);
      return new HandlerResult.Failure(500, fe);
    }
  }

  /**
   * Helper method to determine if a given "moveTo" command corresponds to a proper move or just a rename
   */
  private boolean isMove(Path sourcePath, Path destinationPath, Workspace sourceWorkspace, Workspace destinationWorkspace) {
    // If the workspace differs, it must be a move
    if(sourceWorkspace != destinationWorkspace) return true;
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

  public HandlerResult handleMove(
      Path toMove,
      Path destinationPath,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite,
      String userId
  ) {
    final Workspace sourceWorkspace;
    final Workspace destWorkspace;
    try{
      sourceWorkspace = workspaceService.getWorkspace(sourceWorkspaceId);
      destWorkspace = sourceWorkspaceId == destinationWorkspaceId ? sourceWorkspace : workspaceService.getWorkspace(destinationWorkspaceId);
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("MOVE FILE: SQL EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw));
    }

    // Destination first: if the source's commit then fails, the move degrades to a copy instead of losing data.
    final var commits = new LinkedHashMap<Workspace, String>();
    if (sourceWorkspaceId == destinationWorkspaceId) {
      commits.put(sourceWorkspace, "Move %s -> %s".formatted(toMove, destinationPath));
    } else {
      commits.put(destWorkspace, "Move workspace %d:%s -> %s".formatted(sourceWorkspaceId, toMove, destinationPath));
      commits.put(sourceWorkspace, "Move %s -> workspace %d:%s".formatted(toMove, destinationWorkspaceId, destinationPath));
    }
    final var partialMessage = ("Move degraded to a copy: '%s' in Workspace %d was copied, with its metadata, to '%s' in "
                                + "Workspace %d, but could not be removed from Workspace %d and was restored there.")
        .formatted(toMove, sourceWorkspaceId, destinationPath, destinationWorkspaceId, sourceWorkspaceId);
    return mutate(commits, userId, partialMessage, () -> handleMoveLocked(
        toMove, destinationPath, sourceWorkspace, destWorkspace, overwrite, userId));
  }

  private HandlerResult handleMoveLocked(
      Path toMove,
      Path destinationPath,
      Workspace sourceWorkspace,
      Workspace destinationWorkspace,
      boolean overwrite,
      String userId
  ) {
    final var errorMsg = "Unable to move '%s' in Workspace %d to '%s' in Workspace %d."
            .formatted(toMove, sourceWorkspace.id(), destinationPath, destinationWorkspace.id());
    final var successMsg = "'%s' in Workspace %d moved to '%s' in Workspace %d"
            .formatted(toMove, sourceWorkspace.id(), destinationPath, destinationWorkspace.id());

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

    if (!workspaceService.checkFileExists(sourceWorkspace, toMove)) {
      return new HandlerResult.Failure(
          404,
          new WorkspaceFormattedError(
              new NoSuchFileException(sourceWorkspace.id(), toMove),
              errorMsg));
    }

    final var destinationFileExists = workspaceService.checkFileExists(destinationWorkspace, destinationPath);
    if (destinationFileExists && !overwrite) {
      return new HandlerResult.Failure(409, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg, destinationPath + " already exists."));
    }

    try {
      if (workspaceService.isDirectory(sourceWorkspace, toMove)) {
        // Check whether it's a move or rename
        if(isMove(toMove, destinationPath, sourceWorkspace, destinationWorkspace)) {
          // Report a "Locked" status if there is a locked file within the source directory
          // The destination does not need to be checked, because even if there is already a folder with the same name
          // at the destination, the source will only overwrite that folder if it is empty (meaning it cannot contain a locked file)
          final var readOnlyFiles = workspaceService.getReadOnlyFiles(sourceWorkspace, toMove);
          if(!readOnlyFiles.isEmpty()){
            return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(toMove, readOnlyFiles), errorMsg));
          }
        }

        if (workspaceService.moveDirectory(sourceWorkspace, toMove, destinationWorkspace, destinationPath)) {
          return new HandlerResult.Success(200, successMsg);
        } else {
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      } else {
        // Report a "Locked" status if either file is currently marked as "readOnly"
        if(workspaceService.isReadOnly(sourceWorkspace, toMove)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(toMove), errorMsg));
        }
        if (destinationFileExists && workspaceService.isReadOnly(destinationWorkspace, destinationPath)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(destinationPath), errorMsg));
        }

        if (workspaceService.moveFile(sourceWorkspace, toMove, destinationWorkspace, destinationPath, userId)) {
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

  public HandlerResult handleCopy(
      Path toCopy,
      Path destinationPath,
      int sourceWorkspaceId,
      int destinationWorkspaceId,
      boolean overwrite,
      String userId
  ) {
    final Workspace sourceWorkspace;
    final Workspace destWorkspace;
    try{
      sourceWorkspace = workspaceService.getWorkspace(sourceWorkspaceId);
      destWorkspace = sourceWorkspaceId == destinationWorkspaceId ? sourceWorkspace : workspaceService.getWorkspace(destinationWorkspaceId);
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("COPY FILE: SQL EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw));
    }

    // The source is locked too, so it cannot change while being read; it has nothing to commit.
    final var commits = new LinkedHashMap<Workspace, String>();
    commits.put(destWorkspace, sourceWorkspaceId == destinationWorkspaceId
        ? "Copy %s -> %s".formatted(toCopy, destinationPath)
        : "Copy workspace %d:%s -> %s".formatted(sourceWorkspaceId, toCopy, destinationPath));
    commits.putIfAbsent(sourceWorkspace, "Copy %s -> workspace %d:%s".formatted(toCopy, destinationWorkspaceId, destinationPath));
    return mutate(commits, userId, null, () -> handleCopyLocked(
        toCopy, destinationPath, sourceWorkspace, destWorkspace, overwrite, userId));
  }

  private HandlerResult handleCopyLocked(
      Path toCopy,
      Path destinationPath,
      Workspace sourceWorkspace,
      Workspace destinationWorkspace,
      boolean overwrite,
      String userId
  ) {
    final var errorMsg = "Unable to copy '%s' in Workspace %d to '%s' in Workspace %d."
        .formatted(toCopy, sourceWorkspace.id(), destinationPath, destinationWorkspace.id());
    final var successMsg = "'%s' in Workspace %d copied to '%s' in Workspace %d"
            .formatted(toCopy, sourceWorkspace.id(), destinationPath, destinationWorkspace.id());

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

    if (!workspaceService.checkFileExists(sourceWorkspace, toCopy)) {
      return new HandlerResult.Failure(
          404,
          new WorkspaceFormattedError(new NoSuchFileException(sourceWorkspace, toCopy), errorMsg));
    }

    final var destinationFileExists = workspaceService.checkFileExists(destinationWorkspace, destinationPath);
    if (destinationFileExists && !overwrite) {
      return new HandlerResult.Failure(409, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg, destinationPath + " already exists."));
    }

    try {
      if (workspaceService.isDirectory(sourceWorkspace, toCopy)) {
        if (workspaceService.copyDirectory(sourceWorkspace, toCopy, destinationWorkspace, destinationPath, userId)) {
          return new HandlerResult.Success(200, successMsg);
        } else {
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      } else {
        // Report a "Locked" status if the destination file is currently marked as "readOnly"
        if(destinationFileExists && workspaceService.isReadOnly(destinationWorkspace, destinationPath)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(destinationPath), errorMsg));
        }

        if (workspaceService.copyFile(sourceWorkspace, toCopy, destinationWorkspace, destinationPath, userId)) {
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

  public HandlerResult handleDelete(int workspaceId, Path filePath, String userId) {
    final Workspace workspace;
    try{
      workspace = workspaceService.getWorkspace(workspaceId);
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("DELETE FILE: SQL EXCEPTION: {}", fe);
      return new HandlerResult.Failure(500, fe);
    } catch (NoSuchWorkspaceException nsw) {
      return new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw));
    }

    return mutate(workspace, userId, "Delete " + filePath, () -> handleDeleteLocked(workspace, filePath));
  }

  private HandlerResult handleDeleteLocked(Workspace workspace, Path filePath) {
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

      if (!workspaceService.checkFileExists(workspace, filePath)) {
        return new HandlerResult.Failure(404, new WorkspaceFormattedError(new NoSuchFileException(workspace, filePath)));
      }

      if (workspaceService.isDirectory(workspace, filePath)) {
        // Report a "Locked" status if there is a locked file within the directory
        final var readOnlyFiles = workspaceService.getReadOnlyFiles(workspace, filePath);
        if(!readOnlyFiles.isEmpty()){
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(filePath, readOnlyFiles), errorMsg));
        }

        if (workspaceService.deleteDirectory(workspace, filePath)) {
          return new HandlerResult.Success(200, "Directory deleted.");
        } else {
          logger.warn("DELETE: Delete Directory failed for path {}", filePath);
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      } else {
        // Report a "Locked" status if the file is currently marked as "readOnly"
        if(workspaceService.isReadOnly(workspace, filePath)) {
          return new HandlerResult.Failure(423, new WorkspaceFormattedError(new FileLockedException(filePath), errorMsg));
        }

        if (workspaceService.deleteFile(workspace, filePath)) {
          return new HandlerResult.Success(200, "File deleted.");
        } else {
          logger.warn("DELETE: Delete File failed for path {}", filePath);
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, errorMsg));
        }
      }
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
        uploadResults = handleCreateDirectory(workspaceId, item.path(), userId);
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
  public void bulkPost(Context context) {
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
  ) {
    final var responseArray = Json.createArrayBuilder();
    for(final var item : toMove){
      logger.info(
        "Attempting to move: {} to {} (from WS: {}, to WS: {}, overwrite: {}, user: {})",
        item.currentLocation(),
        item.newPath(),
        sourceWorkspaceId,
        destinationWorkspaceId,
        overwrite,
        userId
      );
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
  ) {
    final var responseArray = Json.createArrayBuilder();
    for(final var item : toCopy) {
      logger.info(
        "Attempting to copy: {} to {} (from WS: {}, to WS: {}, overwrite: {}, user: {})",
        item.currentLocation(),
        item.newPath(),
        sourceWorkspaceId,
        destinationWorkspaceId,
        overwrite,
        userId
      );
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
    context.status(207).json(handleBulkDelete(workspaceId, toDelete, authorize(context).userId()).toString());
  }

  private JsonArray handleBulkDelete(int workspaceId, List<String> toDelete, String userId) {
    final var responseArray = Json.createArrayBuilder();

    for(final var item : toDelete) {
      final var results = handleDelete(workspaceId, Path.of(item), userId);
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

    try {
      final var workspace = workspaceService.getWorkspace(pathInfo.workspaceId);

      // Check that the underlying file exists
      if (!workspaceService.checkFileExists(workspace, pathInfo.filePath)) {
        context.status(404).json(new WorkspaceFormattedError(new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
        return;
      }

      final var fileStream = workspaceService.loadMetadataFile(workspace, pathInfo.filePath());

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
    } catch (SQLException sqle) {
      final var fe = new FormattedError(
          AerieService.WORKSPACE_SERVER,
          sqle,
          "Could not retrieve metadata file for file " + pathInfo.fileName());
      logger.warn("GET METADATA: SQL Exception: {}", fe);
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
  public void setMetadataKeys(final Context context) {
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

    final Workspace workspace;
    try{
      workspace = workspaceService.getWorkspace(pathInfo.workspaceId());
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("SET METADATA: SQL EXCEPTION: {}", fe);
      context.status(500).json(fe);
      return;
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new WorkspaceFormattedError(nsw));
      return;
    }

    respond(context, mutate(workspace, authorize(context).userId(), "Update metadata for " + pathInfo.filePath, () -> {
      // Update the metadata
      try {
        // Check that the underlying file exists
        if (!workspaceService.checkFileExists(workspace, pathInfo.filePath)) {
          return new HandlerResult.Failure(404, new WorkspaceFormattedError(new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
        }

        if(workspaceService.updateMetadataKeys(workspace, pathInfo.filePath, updates, mergeBehavior)) {
          return new HandlerResult.Success(200, "Metadata for file %s updated successfully.".formatted(pathInfo.filePath));
        } else {
          return new HandlerResult.Failure(500, new FormattedError(AerieService.WORKSPACE_SERVER, "Unable to update metadata for file %s".formatted(pathInfo.filePath)));
        }
      } catch (IOException ioe) {
        final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe);
        logger.warn("SET METADATA: IO Exception: {}", fe);
        return new HandlerResult.Failure(500, fe);
      } catch (WorkspaceFileOpException wfe) {
        final var fe = new WorkspaceFormattedError(wfe, "Could not update metadata.");
        logger.warn("SET METADATA: WorkspaceFileOpException: {}", fe);
        return new HandlerResult.Failure(500, fe);
      } catch (JsonException je) {
        final var fe = new FormattedError(
            AerieService.WORKSPACE_SERVER,
            je,
            "Metadata for file %s is malformed.".formatted(pathInfo.filePath));
        logger.warn("SET METADATA: JsonException: {}", fe);
        return new HandlerResult.Failure(500, fe);
      }
    }));
  }

  /**
   * Unset multiple keys in file's metadata file. Creates metadata file if it is not present.
   * Subobjects within the "user" object can be specified by following using a "dot-path" syntax, i.e. "user.status"
   *
   * Example Input Syntax:
   * [ "readOnly", "user.status", "user.info.name" ]
   */
  public void unsetMetadataKeys(final Context context) {
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

    final Workspace workspace;
    try{
      workspace = workspaceService.getWorkspace(pathInfo.workspaceId());
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("UNSET METADATA: SQL EXCEPTION: {}", fe);
      context.status(500).json(fe);
      return;
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new WorkspaceFormattedError(nsw));
      return;
    }

    final var userId = authorize(context).userId();
    respond(context, mutate(workspace, userId, "Update metadata for " + pathInfo.filePath, () -> {
      // Unset Metadata Keys
      try {
        // Check that the underlying file exists
        if (!workspaceService.checkFileExists(workspace, pathInfo.filePath)) {
          return new HandlerResult.Failure(404, new WorkspaceFormattedError(new NoSuchFileException(pathInfo.workspaceId, pathInfo.filePath)));
        }

        if(workspaceService.unsetMetadataKeys(workspace, pathInfo.filePath, toUnset, userId)) {
          return new HandlerResult.Success(200, "Metadata for file %s updated successfully.".formatted(pathInfo.filePath));
        } else {
          return new HandlerResult.Failure(500, new FormattedError(
              AerieService.WORKSPACE_SERVER,
              "Unable to update metadata for file %s".formatted(pathInfo.filePath)));
        }
      } catch (IOException ioe) {
        final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe);
        logger.warn("UNSET METADATA: IO Exception: {}", fe);
        return new HandlerResult.Failure(500, fe);
      } catch (WorkspaceFileOpException wfe) {
        final var fe = new WorkspaceFormattedError(wfe, "Could not update metadata.");
        logger.warn("UNSET METADATA: WorkspaceFileOpException: {}", fe);
        return new HandlerResult.Failure(500, fe);
      } catch (JsonException je) {
        final var fe = new FormattedError(
            AerieService.WORKSPACE_SERVER,
            je,
            "Metadata for file %s is malformed.".formatted(pathInfo.filePath));
        logger.warn("UNSET METADATA: JsonException: {}", fe);
        return new HandlerResult.Failure(500, fe);
      }
    }));
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

    final Workspace workspace;
    try{
      workspace = workspaceService.getWorkspace(pathInfo.workspaceId());
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("DELETE METADATA: SQL EXCEPTION: {}", fe);
      context.status(500).json(fe);
      return;
    } catch (NoSuchWorkspaceException nsw) {
      context.status(404).json(new WorkspaceFormattedError(nsw));
      return;
    }

    // Delete Metadata File
    respond(context, mutate(workspace, authorize(context).userId(), "Delete metadata for " + pathInfo.filePath, () -> {
      try {
        if(workspaceService.deleteMetadataFile(workspace, pathInfo.filePath)) {
          return new HandlerResult.Success(200, "Metadata for file %s deleted.".formatted(pathInfo.filePath));
        } else {
          return new HandlerResult.Failure(500, new FormattedError(
              AerieService.WORKSPACE_SERVER,
              "Unable to delete metadata for file %s".formatted(pathInfo.filePath)));
        }
      } catch (WorkspaceFileOpException wfe) {
        final var fe = new WorkspaceFormattedError(wfe, "Could not delete metadata.");
        logger.warn("DELETE METADATA: WorkspaceFileOpException: {}", fe);
        return new HandlerResult.Failure(500, fe);
      } catch (IOException ioe) {
        final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, ioe);
        logger.warn("DELETE METADATA: IO Exception: {}", fe);
        return new HandlerResult.Failure(500, fe);
      }
    }));
  }
  //endregion

  //region Versioning
  /**
   * Create a new revision of the file's current state. Requires If-Match: the file's current ETag, or "*".
   * An optional JSON body {"message": "...", "startAtRevision": "..."} describes the revision. "startAtRevision" names the file's first
   * revision (e.g. "c" to start at c instead of a) and is rejected once the file has revisions.
   *
   * Returns the new revision's name.
   */
  private void createRevision(Context context) throws NoSuchWorkspaceException {
    final var pathInfo = PathInformation.of(context);
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.create_file_revision)){
      return;
    }

    final var ifMatch = context.header("If-Match");
    if (ifMatch == null) {
      context
          .status(400)
          .json(new WorkspaceFormattedError(new MalformedRequest("Required header is missing: `If-Match`")));
      return;
    }

    final JsonObject body;
    try {
      body = context.body().isBlank() ? JsonValue.EMPTY_JSON_OBJECT : Json.createReader(new StringReader(context.body())).readObject();
    } catch (JsonException | ClassCastException e) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest(
          "Invalid body format. Expected an optional JSON object of the form {\"message\": \"...\", \"startAtRevision\": \"...\"}.")));
      return;
    }

    final var startAtRevision = body.getString("startAtRevision", null);
    respond(context, handleCreateRevision(pathInfo.workspaceId, pathInfo.filePath, ifMatch,
        body.getString("message", ""), startAtRevision == null ? null : startAtRevision.toLowerCase(Locale.ROOT), authorize(context).userId()));
  }

  public HandlerResult handleCreateRevision(int workspaceId, Path filePath, String ifMatch, String message, String startAtRevision, String userId) {
    // Runs as a mutation for its lock and trust checks; a revision changes no file, so no commit is made.
    final var workspace = workspaceOrFailure(workspaceId);
    if (workspace.failure() != null) return workspace.failure();
    return mutate(workspace.workspace(), userId, "Create revision of " + filePath, () -> {
      try {
        final var failure = checkRevisionTarget(workspace.workspace(), filePath, ifMatch, false);
        if (failure != null) return failure;
        return new HandlerResult.Success(200, workspaceService.createRevision(workspace.workspace(), filePath, message, userId, startAtRevision).name());
      } catch (WorkspaceFileOpException wfe) {
        return new HandlerResult.Failure(400, new WorkspaceFormattedError(wfe, "Could not create a revision of " + filePath));
      }
    });
  }

  /**
   * Get a specific revision for the file, or a list of all revisions on the file if no revision is specified.
   *
   * Returns:
   *  - If the query param "revision" is present: returns the contents of the file at that revision,
   *  - If the query param "revision" is absent: returns {"revisions": [all file revisions, newest to oldest], "matchingRevision": name of the newest revision whose
   *    content and metadata equal the file's saved state, or null}.
   */
  private void getFileRevision(Context context) throws NoSuchWorkspaceException, NoSuchFileException, IOException, SQLException {
    final var pathInfo = PathInformation.of(context);

    // Permissions Check
    if(!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.read_file_directory)) {
      return;
    }

    final var revision = context.queryParam("revision");
    try {
      if (revision == null) {
        final var workspace = workspaceService.getWorkspace(pathInfo.workspaceId);
        final var revisions = workspaceService.listRevisions(workspace, pathInfo.filePath);
        final var list = Json.createArrayBuilder();
        revisions.forEach(r -> list.add(r.toJson()));
        final var matching = workspaceService.matchingRevision(workspace, pathInfo.filePath, revisions);
        final var body = Json.createObjectBuilder().add("revisions", list);
        matching.ifPresentOrElse(r -> body.add("matchingRevision", r.name()), () -> body.addNull("matchingRevision"));
        context.status(200).json(body.build().toString()); // javax JSON must be serialized as text, not via Jackson
        return;
      }

      // Find and return the latest revision if requested
      String effectiveRevision = revision;
      if ("latest".equals(revision)) {
        final var mostRecentRevision = workspaceService.getMostRecentRevision(workspaceService.getWorkspace(pathInfo.workspaceId), pathInfo.filePath);
        if (mostRecentRevision.isPresent()) {
          effectiveRevision = mostRecentRevision.get().name();
        } else {
          context.status(404).json(noSuchRevision(pathInfo.filePath, revision));
          return;
        }
      }

      // Otherwise, we read the historical revision.
      final var historical = workspaceService.readRevision(workspaceService.getWorkspace(pathInfo.workspaceId), pathInfo.filePath, effectiveRevision);
      if (historical.isEmpty()) {
        context.status(404).json(noSuchRevision(pathInfo.filePath, effectiveRevision));
        return;
      }
      // No ETag: this is not the file's current content, so it must never be sent back as If-Match.
      context.header("x-render-type", workspaceService.getFileType(pathInfo.filePath).name());
      context.contentType(ContentType.OCTET_STREAM);
      context.header("Content-Disposition", "attachment; filename=\"" + pathInfo.fileName() + "\"");
      context.status(200).result(historical.get().content());
    } catch (WorkspaceFileOpException wfe) {
      context.status(400).json(new WorkspaceFormattedError(wfe, "Could not get revisions of " + pathInfo.filePath));
    }
  }

  /**
   * Get the metadata for a file at a revision, along with the revision's own metadata.
   *
   * Returns {"versionMetadata": {name, message, createdAt, createdBy}, "fileMetadata": {...}}.
   */
  private void getRevisionMetadata(Context context) throws NoSuchWorkspaceException, NoSuchFileException, IOException, SQLException {
    final var pathInfo = PathInformation.of(context);

    // Permissions Check
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.read_file_directory)) {
      return;
    }

    final var revision = context.queryParam("revision");
    if (revision == null) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest(
          "Unable to get revision metadata",
          "Required query parameter is missing: `revision`")));
      return;
    }

    try {
      final var historical = workspaceService.readRevision(workspaceService.getWorkspace(pathInfo.workspaceId), pathInfo.filePath, revision);
      if (historical.isEmpty()) {
        context.status(404).json(noSuchRevision(pathInfo.filePath, revision));
        return;
      }
      context.status(200).json(Json.createObjectBuilder()
                                   .add("versionMetadata", historical.get().revision().toJson())
                                   .add("fileMetadata", historical.get().metadata())
                                   .build()
                                   .toString());
    } catch (WorkspaceFileOpException wfe) {
      context.status(400).json(new WorkspaceFormattedError(wfe, "Could not get revision metadata of " + pathInfo.filePath));
    }
  }

  /**
   * Restore a file's contents to a specific revision, as a normal commit. Only restores the "user" field of the file's
   * metadata. Requires If-Match: the file's current ETag, or "*". Returns the file's new ETag.
   */
  private void restoreRevision(Context context) throws NoSuchWorkspaceException {
    final var pathInfo = PathInformation.of(context);

    // Permissions Check
    if (!checkPermissions(context, pathInfo.workspaceId, WorkspaceAction.write_file_directory)) {
      return;
    }

    // Verify that a revision was provided
    final var revision = context.queryParam("revision");
    if (revision == null) {
      context.status(400).json(new WorkspaceFormattedError(new MalformedRequest(
          "Required query parameter is missing: `revision`")));
      return;
    }

    final var ifMatch = context.header("If-Match");
    if (ifMatch == null) {
      context
          .status(400)
          .json(new WorkspaceFormattedError(new MalformedRequest("Required header is missing: `If-Match`")));
      return;
    }

    respond(context, handleRestoreRevision(pathInfo.workspaceId, pathInfo.filePath, revision, ifMatch, authorize(context).userId()));
  }

  public HandlerResult handleRestoreRevision(int workspaceId, Path filePath, String revision, String ifMatch, String userId) {
    final var workspace = workspaceOrFailure(workspaceId);
    if (workspace.failure() != null) return workspace.failure();
    return mutate(workspace.workspace(), userId, "Restore %s to revision %s".formatted(filePath, revision), () -> {
      try {
        final var failure = checkRevisionTarget(workspace.workspace(), filePath, ifMatch, true);
        if (failure != null) return failure;
        final var etag = workspaceService.restoreRevision(workspace.workspace(), filePath, revision, userId);
        if (etag.isEmpty()) {
          return new HandlerResult.Failure(404, noSuchRevision(filePath, revision));
        }
        return new HandlerResult.Success(200, "%s restored to revision %s".formatted(filePath, revision), etag);
      } catch (WorkspaceFileOpException wfe) {
        return new HandlerResult.Failure(400, new WorkspaceFormattedError(wfe, "Could not restore revision %s of %s".formatted(revision, filePath)));
      }
    });
  }

  /**
   * Shared checks for creating or restoring a revision, run under the workspace lock: the target still exists, is not
   * readOnly (restore only), and matches If-Match. Returns the failure, or null to proceed. A metadata file or directory
   * is rejected afterwards by the service.
   */
  private HandlerResult.Failure checkRevisionTarget(final Workspace workspace, final Path filePath, final String ifMatch, final boolean writes)
  throws NoSuchFileException, WorkspaceFileOpException, IOException
  {
    if (!workspaceService.checkFileExists(workspace, filePath)) {
      return ifMatch.equals("*")
          ? new HandlerResult.Failure(404, new WorkspaceFormattedError(new NoSuchFileException(workspace, filePath)))
          : new HandlerResult.Failure(412, WorkspaceFormattedError.saveConflict("deleted", null, null, null));
    }
    if (writes && workspaceService.isReadOnly(workspace, filePath)) {
      return new HandlerResult.Failure(423, new WorkspaceFormattedError(
          new FileLockedException(filePath),
          "Cannot update file at " + filePath));
    }
    if (!ifMatch.equals("*")) {
      final var currentETag = workspaceService.getETag(workspace, filePath);
      if (!currentETag.equals(ifMatch)) return conflict(workspace, filePath, currentETag);
    }
    return null;
  }

  private record WorkspaceOrFailure(Workspace workspace, HandlerResult.Failure failure) {}

  private WorkspaceOrFailure workspaceOrFailure(final int workspaceId) {
    try {
      return new WorkspaceOrFailure(workspaceService.getWorkspace(workspaceId), null);
    } catch (SQLException sqle) {
      final var fe = new FormattedError(AerieService.WORKSPACE_SERVER, sqle);
      logger.warn("REVISION: SQL Exception: {}", fe);
      return new WorkspaceOrFailure(null, new HandlerResult.Failure(500, fe));
    } catch (NoSuchWorkspaceException nsw) {
      return new WorkspaceOrFailure(null, new HandlerResult.Failure(404, new WorkspaceFormattedError(nsw)));
    }
  }

  private static FormattedError noSuchRevision(final Path filePath, final String revision) {
    return new FormattedError(
        AerieService.WORKSPACE_SERVER,
        "NO_SUCH_REVISION",
        "%s has no revision %s".formatted(filePath, revision),
        Optional.empty());
  }
  //endregion
}
