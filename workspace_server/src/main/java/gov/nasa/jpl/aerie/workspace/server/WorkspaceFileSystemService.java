package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.postgres.WorkspacePostgresRepository;
import io.javalin.http.UploadedFile;
import io.javalin.util.FileUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.stream.Stream;

public class WorkspaceFileSystemService implements WorkspaceService {
  final WorkspacePostgresRepository postgresRepository;

  public WorkspaceFileSystemService(final WorkspacePostgresRepository postgresRepository) {
    this.postgresRepository = postgresRepository;
  }

  @Override
  public Optional<Integer> createWorkspace(final String workspaceLocation, final String workspaceName) {
    return Optional.empty();
  }

  @Override
  public boolean deleteWorkspace(final int workspaceId) throws NoSuchWorkspaceException {
    return false;
  }

  @Override
  public boolean checkFileExists(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var path = repoPath.resolve(filePath);

    return path.toFile().exists();
  }

  @Override
  public RenderType getFileType(final Path filePath) throws SQLException {
    final var fileName = filePath.getFileName().toString();
    return RenderType.getRenderType(fileName, postgresRepository.getExtensionMapping());
  }

  @Override
  public InputStream loadFile(final int workspaceId, final Path filePath) throws IOException, NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var file = repoPath.resolve(filePath).toFile();

    return new FileInputStream(file);
  }

  @Override
  public boolean saveFile(final int workspaceId, final Path filePath, final UploadedFile file) throws NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var path = repoPath.resolve(filePath);

    FileUtil.streamToFile(file.content(), path.toString());
    return true;
  }

  @Override
  public boolean deleteFile(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var file = repoPath.resolve(filePath).toFile();
    return file.delete();
  }

  @Override
  public DirectoryTree listFiles(final int workspaceId, final Optional<Path> directoryPath, final int depth)
  throws SQLException, NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var path = repoPath.resolve(directoryPath.orElse(Path.of("")));

    if(!path.toFile().isDirectory()) {
      return null;
    }

    // Converting our API to the Files API
    final var walkDepth = depth == -1 ? Integer.MAX_VALUE : depth + 1;
    try(final Stream<Path> walkOutput = Files.walk(path, walkDepth)) {
      return new DirectoryTree(path, walkOutput.toList(), postgresRepository.getExtensionMapping());
    } catch (IOException io) {
      return null;
    }
  }

  @Override
  public boolean createDirectory(final int workspaceId, final Path directoryPath) throws IOException, NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var path = repoPath.resolve(directoryPath);
    Files.createDirectories(path);
    return true;
  }

  @Override
  public boolean deleteDirectory(final int workspaceId, final Path directoryPath) throws NoSuchWorkspaceException {
    return deleteFile(workspaceId, directoryPath);
  }
}
