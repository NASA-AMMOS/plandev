package gov.nasa.ammos.plandev.workspace.server.utils;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRepository;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;

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

  public Path createWorkspace(final String workspaceLocation) throws IOException {
    ++workspaceIdCounter;
    return Files.createDirectories(basePath.resolve(workspaceLocation));
  }

  @Override
  public int createWorkspace(final String workspaceLocation, final String workspaceName, final String username, final int parcelId)
  {
    return ++workspaceIdCounter;
  }

  @Override
  public boolean deleteWorkspace(final int workspaceId) {
    return false;
  }

  @Override
  public Path workspaceRootPath(final int workspaceId) {
    return basePath.resolve("ws%d".formatted(workspaceId));
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
