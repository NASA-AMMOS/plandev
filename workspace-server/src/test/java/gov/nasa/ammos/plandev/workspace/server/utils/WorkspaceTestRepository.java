package gov.nasa.ammos.plandev.workspace.server.utils;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRepository;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkspaceTestRepository implements WorkspaceRepository {
  private int workspaceIdCounter = 0;
  private final Path basePath;

  private final Map<Integer, Workspace> workspaces = new ConcurrentHashMap<>();

  public WorkspaceTestRepository(final Path basePath) {
    this.basePath = basePath;
  }

  @Override
  public Path getBaseRepositoryPath() {
    return basePath;
  }

  public Workspace createWorkspace(final String workspaceLocation) throws IOException {
    final var id = ++workspaceIdCounter;
    final var ws = new Workspace(id, Files.createDirectories(basePath.resolve(workspaceLocation+id)));
    workspaces.put(ws.id(), ws);
    return ws;
  }

  @Override
  public int createWorkspace(final String workspaceLocation, final String workspaceName, final String username, final int parcelId)
  {
    return ++workspaceIdCounter;
  }

  @Override
  public Workspace getWorkspace(final int workspaceId) {
    return workspaces.get(workspaceId);
  }

  @Override
  public boolean deleteWorkspace(final Workspace workspace) {
    try{

      Files.walkFileTree(workspace.rootPath(), new SimpleFileVisitor<>() {
        @Override
        public @NotNull FileVisitResult visitFile(final @NotNull Path file, final @NotNull BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public @NotNull FileVisitResult postVisitDirectory(final @NotNull Path directory, final IOException error) throws IOException {
          if (error != null) throw error;
          Files.delete(directory);
          return FileVisitResult.CONTINUE;
        }
      });

      workspaces.remove(workspace.id());
    } catch (IOException e) {
      return false;
    }
    return true;
  }

  @Override
  public Map<String, RenderType> getExtensionMapping() {
    return Map.of(
        ".txt", RenderType.TEXT,
        ".bin", RenderType.BINARY,
        ".json", RenderType.JSON,
        ".seq.json", RenderType.SEQUENCE,
        ".seq", RenderType.SEQUENCE,
        ".seqN.txt", RenderType.SEQUENCE,
        RenderType.aerieMetadataExtension, RenderType.METADATA);
  }

  @Override
  public List<String> getMetadataExtensions() {
    return List.of(RenderType.aerieMetadataExtension);
  }

  public void close() {
    for(final var workspace : workspaces.values()) {
      deleteWorkspace(workspace);
    }
  }
}
