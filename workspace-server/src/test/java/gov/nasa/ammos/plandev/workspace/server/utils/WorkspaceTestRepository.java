package gov.nasa.ammos.plandev.workspace.server.utils;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRepository;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class WorkspaceTestRepository implements WorkspaceRepository {
  private int workspaceIdCounter = 0;
  private final Path basePath;

  public WorkspaceTestRepository(final Path basePath) {
    this.basePath = basePath;
  }

  @Override
  public Path getBaseRepositoryPath() {
    return basePath;
  }

  public Workspace createWorkspace(final String workspaceLocation) throws IOException {
    return new Workspace(++workspaceIdCounter, Files.createDirectories(basePath.resolve(workspaceLocation)));
  }

  @Override
  public int createWorkspace(final String workspaceLocation, final String workspaceName, final String username, final int parcelId)
  {
    return ++workspaceIdCounter;
  }

  @Override
  public Workspace getWorkspace(final int workspaceId) {
    return new Workspace(workspaceId, basePath.resolve("ws%d".formatted(workspaceId)));
  }

  @Override
  public boolean deleteWorkspace(final Workspace workspace) {
    try{
      Files.delete(workspace.rootPath());
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
}
