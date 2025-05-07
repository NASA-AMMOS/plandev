package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.exceptions.JWTVerificationException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.plugin.Plugin;

import javax.json.Json;
import javax.json.stream.JsonParsingException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

public class WorkspaceBindings implements Plugin {
  private final JWTService jwtService;
  private final WorkspaceService workspaceService;

  public WorkspaceBindings(final JWTService jwtService, final WorkspaceService workspaceService) {
    this.jwtService = jwtService;
    this.workspaceService = workspaceService;
  }

  private record PathInformation(int workspaceId, Path filePath) {
    static PathInformation ofFile(Context context) {
      final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
      final var filePath = Path.of(context.pathParam("filePath"));

      return new PathInformation(workspaceId, filePath);
    }

    static PathInformation ofDirectory(Context context) {
      final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
      final var directoryPath = Path.of(context.pathParam("directoryPath"));

      return new PathInformation(workspaceId, directoryPath);
    }

    String fileName() {
      return filePath.getFileName().toString();
    }
  }

  @Override
  public void apply(final Javalin javalin) {
    javalin.routes(() -> {
      //before("/ws/*", authorize); <- dont want to force auth on the health check.
      // Health check
      path("/health", () -> get(ctx -> ctx.status(200)));

      // CRUD operations for Files:
      path("/ws/{workspaceId}/file/<filePath>",
           () -> {
             get(this::loadFile);
             put(this::saveFile);
             delete(this::deleteFile);
             // post(this::handlePostFile); <- work out, move and rename are the same op
           });
      // CRUD operations for Directories <- confirm that file endpoint is not being hit instead
      path("/ws/{workspaceId}/dir/<directoryPath>",
           () -> {
             get(this::listFiles);
             put(this::createDirectory);
             delete(this::deleteDirectory);
             // post(this::handlePostDirectory); <- work out, move and rename are the same op
           });


      // CRD operations for Workspaces
      path("/ws/{workspaceId}", () -> {
        get(this::listFiles);
        delete(this::deleteWorkspace);
      });
      path("/ws/create", () -> post(this::createWorkspace));
    });

    // This exception is expected when the request body entity is not a legal JsonValue.
    javalin.exception(JsonParsingException.class, (ex, ctx) -> ctx.status(400).result("Invalid json body"));
  }

  private JWTService.UserSession authorize(Context context) {
    final var authHeader = context.header("Authorization");
    final var activeRole = context.header("x-hasura-role");
    try{
      return jwtService.validateAuthorization(authHeader, activeRole);
    } catch (JWTVerificationException jve) {
      context.status(401);
      throw new UnauthorizedResponse();
    }
  }

  private void createWorkspace(Context context) {
    final Path workspaceLocation;
    final String workspaceName;
    final int parcelId;
    final var user = authorize(context);

    try(final var reader = Json.createReader(new StringReader(context.body()))) {
      final var bodyJson = reader.readObject();

      parcelId = bodyJson.getInt("parcelId");
      final var workspaceString = bodyJson.getString("workspaceLocation");
      if(workspaceString.contains("/")){
        context.status(400).result("Workspace location may not contain '/'");
      }

      workspaceLocation = Path.of(bodyJson.getString("workspaceLocation"));
      workspaceName = bodyJson.containsKey("workspaceName") ? bodyJson.getString("workspaceName") : workspaceLocation.toString();
    } catch (NullPointerException npe) {
      context.status(400).result(
          "Mandatory body parameter is null. Request body format is the following: \n" +
          """
          {
            "workspaceLocation": text     // Name of the folder the workspace will live in
            "parcelId": number            // Id of the workspace's parcel
            "workspaceName": text?        // Optional. If provided, the workspace will be called the specified value (defaults to the value of "workspaceLocation")
          }""");
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
      context.status(500).result("Unable to create workspace.");
    }
  }

  private void deleteWorkspace(Context context) {
    final int workspaceId  = Integer.parseInt(context.pathParam("workspaceId"));

    try {
      if (workspaceService.deleteWorkspace(workspaceId)) {
        context.status(200).result("Workspace deleted.");
      } else {
        context.status(500).result("Unable to delete workspace.");
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    } catch (SQLException e) {
      context.status(500).result("Unable to delete workspace. " +e.getMessage());
    }
  }

  private void loadFile(Context context) {
    final var pathInfo = PathInformation.ofFile(context);

    try {
      if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(404).result("No such file exists in the workspace: " + pathInfo.filePath);
        return;
      }

      try {
        final var fileStream = workspaceService.loadFile(pathInfo.workspaceId, pathInfo.filePath());
        final var fileReader = new BufferedInputStream(fileStream.readingStream());
        context.header("x-render-type", workspaceService.getFileType(pathInfo.filePath).name());
        context.contentType(ContentType.OCTET_STREAM);
        context.header("Content-Disposition", "attachment; filename=\"" + pathInfo.fileName() + "\"");
        context.header("Content-Length", "" + fileStream.fileSize());
        context.status(200).result(fileReader);
      } catch (IOException | SQLException e) {
        context.status(500).result("Could not load file " + pathInfo.fileName());
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  private void saveFile(Context context) {
    final var pathInfo = PathInformation.ofFile(context);
    final var file = context.uploadedFile("file");

    if (file == null || !pathInfo.fileName().equals(file.filename())) {
      context.status(400).result("No file provided with the name "+pathInfo.fileName());
      return;
    }

    try {
      if (workspaceService.saveFile(pathInfo.workspaceId, pathInfo.filePath, file)) {
        context.status(200).result("File " + pathInfo.fileName() + " uploaded to " + pathInfo.filePath);
      } else {
        context.status(500).result("Could not save file.");
      }
    } catch (IOException io) {
      context.status(500).result(io.getMessage());
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  // Move metadata, if it exists
  // <some file>.<some extension>.aerie
  private void handlePostFile(Context context) {
    // parse what the post request is for (move)
    // perform the request
    // { moveTo: destination }
  }

  private void deleteFile(Context context) {
    final var pathInfo = PathInformation.ofFile(context);

    try {
      if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(404).result("No such file exists in the workspace: " + pathInfo.filePath);
        return;
      }

      try {
        if (workspaceService.deleteFile(pathInfo.workspaceId, pathInfo.filePath)) {
          context.status(200).result("File " + pathInfo.fileName() + " deleted.");
        } else {
          context.status(500).result("Could not delete file.");
        }
      } catch (IOException io) {
        context.status(500).result(io.getMessage());
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  private void createDirectory(Context context) {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    final var directoryPath = Path.of(context.pathParam("directoryPath"));

    try {
      if (workspaceService.createDirectory(workspaceId, directoryPath)) {
        context.status(200).result("Directory created.");
      } else {
        context.status(500).result("Could not create directory.");
      }
    } catch (IOException io) {
      context.status(500).result(io.getMessage());
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  private void listFiles(Context context) {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));

    final Optional<Path> directoryPath;
    if(context.pathParamMap().containsKey("directoryPath")) {
      directoryPath = Optional.of(Path.of(context.pathParam("directoryPath")));
    } else {
      directoryPath = Optional.empty();
    }

    // Query params
    final var depthString = context.queryParam("depth");
    final int depth = depthString != null ? Integer.parseInt(depthString) : -1;

    try {
      final var fileTree = workspaceService.listFiles(workspaceId, directoryPath, depth);

      if (fileTree == null) {
        context.status(404).result("No such directory.");
        return;
      }

      context.status(200).json(fileTree.toJson().toString());
    } catch (SQLException e) {
      context.status(500).result(e.getMessage());
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  private void handlePostDirectory(Context context) {}

  private void deleteDirectory(Context context) {
    final var pathInfo = PathInformation.ofDirectory(context);

    try {
      if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(404).html("No such directory exists in the workspace: " + pathInfo.filePath);
        return;
      }

      try {
        if (workspaceService.deleteDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
          context.status(200).result("Directory deleted.");
        } else {
          context.status(500).result("Could not delete directory.");
        }
      } catch (IOException io) {
        context.status(500).result(io.getMessage());
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }
}
