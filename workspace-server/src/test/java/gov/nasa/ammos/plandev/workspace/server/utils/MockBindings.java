package gov.nasa.ammos.plandev.workspace.server.utils;

import gov.nasa.ammos.plandev.workspace.server.MetadataUpdates;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceBindings;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceFileSystemService;
import gov.nasa.ammos.plandev.workspace.server.WorkspacePaths;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataMergeBehavior;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.Part;
import org.opentest4j.AssertionFailedError;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class MockBindings extends WorkspaceBindings {
  private final FlakyHistory history;
  private final WorkspaceFileSystemService fs;

  public static final String DEFAULT_USER = "alice";
  public static final String DEFAULT_USER_IDENT = "alice <alice@SeqDev>";

  public MockBindings(
      final WorkspaceFileSystemService workspaceService,
      final FlakyHistory versioningService
  ) {
    super(null, workspaceService, versioningService, null, "secret");
    this.fs = workspaceService;
    this.history = versioningService;
  }

  public static UploadedFile upload(final String name, final String content) {
    final var bytes = content.getBytes(StandardCharsets.UTF_8);
    final var pa = (Part) Proxy.newProxyInstance(Part.class.getClassLoader(), new Class<?>[]{Part.class}, (p, m, a) ->
        switch (m.getName()) {
          case "getInputStream" -> new ByteArrayInputStream(bytes);
          case "getSubmittedFileName", "getName" -> name;
          case "getSize" -> (long) bytes.length;
          case "getContentType" -> "application/octet-stream";
          //default -> null;
          default -> throw new IllegalStateException("Unexpected value: " + m.getName());
        });
    return new UploadedFile(pa);
  }

  public HandlerResult handleMove(
      final Path toMove,
      final Path destinationPath,
      final Workspace sourceWorkspace,
      final Workspace destinationWorkspace,
      final boolean overwrite
  ) {
    return super.handleMove(toMove, destinationPath, sourceWorkspace.id(), destinationWorkspace.id(), overwrite, DEFAULT_USER);
  }

  public HandlerResult handleCopy(
      final Path toCopy,
      final Path destinationPath,
      final Workspace sourceWorkspace,
      final Workspace destinationWorkspace,
      final boolean overwrite
  ) {
    return super.handleCopy(toCopy, destinationPath, sourceWorkspace.id(), destinationWorkspace.id(), overwrite, DEFAULT_USER);
  }

  public HandlerResult handleDelete(final Workspace workspace, final Path filePath) {
    return super.handleDelete(workspace.id(), filePath, DEFAULT_USER);
  }

  public HandlerResult handleCreateDirectory(final Workspace workspace, final Path destinationPath) {
    return super.handleCreateDirectory(workspace.id(), destinationPath, DEFAULT_USER);
  }

  public HandlerResult saveFile(final Workspace ws, final Path path, final String content) {
    return saveFile(ws, path, content, null);
  }

  public HandlerResult saveFile(final Workspace ws, final Path path, final String content, final String ifMatch) {
    return handleFileUpload(ws.id(), path, upload(path.getFileName().toString(), content), ifMatch == null, ifMatch,
                            DEFAULT_USER);
  }

  public String saveAndGetEtag(final Workspace ws, final String path, final String content) {
    return saveAndGetEtag(ws, Path.of(path), content);
  }

  public String saveAndGetEtag(final Workspace ws, final Path path, final String content) {
    final var result = saveFile(ws, path, content);
    assertInstanceOf(HandlerResult.Success.class, result, () -> result.jsonResponse().toString());
    return ((HandlerResult.Success) result).etag().orElseThrow(() -> new AssertionFailedError("No ETag generated during save."));
  }

  public String readFile(final Workspace ws, final String path) throws IOException {
    return readFile(ws, Path.of(path));
  }

  public String readFile(final Workspace ws, final Path path) throws IOException {
    return Files.readString(WorkspacePaths.resolveReadingPath(ws, path));
  }

  public boolean isReadOnly(final Workspace ws, final String path) throws Exception {
    return fs.isReadOnly(ws, Path.of(path));
  }

  public void setReadOnly(final Workspace ws, final Path path, final boolean readOnly) throws Exception {
    history.mutate(ws, "lock", DEFAULT_USER, () -> fs.updateMetadataKeys(
                       ws, path, new MetadataUpdates.Builder(DEFAULT_USER).readOnly(readOnly).build(), MetadataMergeBehavior.deepMerge),
                   r -> true);
  }

  public void deleteMetadata(final Workspace ws, final String path) throws Exception {
    history.mutate(ws, "drop metadata", DEFAULT_USER, () -> fs.deleteMetadataFile(ws, Path.of(path)), r -> r);
  }

  public void setUserMetadata(final Workspace ws, final String path, final String status) throws Exception {
    history.mutate(ws, "meta", DEFAULT_USER, () -> fs.updateMetadataKeys(
        ws, Path.of(path),
        new MetadataUpdates.Builder(DEFAULT_USER).user(Json.createObjectBuilder().add("status", status).build()).build(),
        MetadataMergeBehavior.deepMerge), r -> true);
  }

  public JsonObject getMetadata(final Workspace ws, final String path) throws Exception {
    try (final var in = fs.loadMetadataFile(ws, Path.of(path)).readingStream();
         final var reader = Json.createReader(in)) {
      return reader.readObject();
    }
  }
}
