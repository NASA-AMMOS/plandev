package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.plugin.Plugin;

import javax.json.stream.JsonParsingException;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

public class WorkspaceBindings implements Plugin {
  private final WorkspaceService workspaceService;

  public WorkspaceBindings(final WorkspaceService workspaceService) {
    this.workspaceService = workspaceService;
  }

  private record PathInformation(int workspaceId, String fileName, Path filePath) {
    static PathInformation of(Context context) {
      final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
      final var fileName = context.pathParam("fileName");
      final var filePath = Path.of(context.pathParam("filePath"), fileName);

      return new PathInformation(workspaceId, fileName, filePath);
    }
  }

  @Override
  public void apply(final Javalin javalin) {
    javalin.routes(() -> {
      //before("/ws/*", authorize); <- dont want to force auth on the health check.
      // Health check
      path("/health", () -> get(ctx -> ctx.status(200)));

      // CRUD operations for Files:
      path("/ws/{workspaceId}/<filePath>/{fileName}",
           () -> {
             get(this::loadFile);
             put(this::saveFile);
             delete(this::deleteFile);
             // post(this::handlePostFile); <- work out, move and rename are the same op
           });
      // CRUD operations for Directories <- confirm that file endpoint is not being hit instead
      path("/ws/{workspaceId}/<directoryPath>/",
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
      // Permits a query string "name", which will set the name to the given value
      path("/ws/create/{workspaceLocation}", () -> post(this::createWorkspace));
    });

    // This exception is expected when the request body entity is not a legal JsonValue.
    javalin.exception(JsonParsingException.class, (ex, ctx) -> ctx.status(400).result("Invalid json body"));
  }

  private void createWorkspace(Context context) {
    final var workspaceLocation = context.pathParam("workspaceLocation");
    final var workspaceName = context.queryParam("name") == null ? context.queryParam("name") : workspaceLocation;

    final Optional<Integer> workspaceId = workspaceService.createWorkspace(workspaceLocation, workspaceName);

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
    }
  }

  private void loadFile(Context context) {
    final var pathInfo = PathInformation.of(context);

    try {
      if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(404).result("No such file exists in the workspace: " + pathInfo.filePath);
        return;
      }

      try (final var fileReader = workspaceService.loadFile(pathInfo.workspaceId, pathInfo.filePath)) {
        context.contentType(ContentType.MULTIPART_FORM_DATA);
        context.header("x-render-type", workspaceService.getFileType(pathInfo.filePath).name());
        context.status(200).result(fileReader);
      } catch (IOException | SQLException e) {
        context.status(500).result("Could not load file " + pathInfo.fileName);
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  private void saveFile(Context context) {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    final var fileName = context.pathParam("fileName");
    final var filePath = Path.of(context.pathParam("filePath"), fileName);
    final var file = context.uploadedFile("fileName");

    if (file == null) {
      context.status(400).result("No file provided with the name "+fileName);
      return;
    }

    try {
      if (workspaceService.saveFile(workspaceId, filePath, file)) {
        context.status(200).result("File " + fileName + " uploaded to " + filePath);
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
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    final var fileName = context.pathParam("fileName");
    final var filePath = Path.of(context.pathParam("filePath"), fileName);

    try {
      if (!workspaceService.checkFileExists(workspaceId, filePath)) {
        context.status(404).result("No such file exists in the workspace: " + filePath);
        return;
      }

      try {
        if (workspaceService.deleteFile(workspaceId, filePath)) {
          context.status(200).result("File " + fileName + " deleted.");
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

      context.status(200).json(fileTree.toJson());
    } catch (SQLException e) {
      context.status(500).result(e.getMessage());
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  private void handlePostDirectory(Context context) {}

  private void deleteDirectory(Context context) {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
    final var directoryPath = Path.of(context.pathParam("directoryPath"));

    try {
      if (!workspaceService.checkFileExists(workspaceId, directoryPath)) {
        context.status(404).html("No such directory exists in the workspace: " + directoryPath);
        return;
      }

      try {
        if (workspaceService.deleteDirectory(workspaceId, directoryPath)) {
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
