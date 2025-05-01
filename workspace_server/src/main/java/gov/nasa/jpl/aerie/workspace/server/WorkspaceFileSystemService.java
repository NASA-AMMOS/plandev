package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.postgres.WorkspacePostgresRepository;
import io.javalin.http.UploadedFile;
import io.javalin.util.FileUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
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
  public boolean isDirectory(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var path = repoPath.resolve(filePath);

    return path.toFile().isDirectory();
  }

  @Override
  public RenderType getFileType(final Path filePath) throws SQLException {
    final var fileName = filePath.getFileName().toString();
    return RenderType.getRenderType(fileName, postgresRepository.getExtensionMapping());
  }

  @Override
  public FileStream loadFile(final int workspaceId, final Path filePath) throws IOException, NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var file = repoPath.resolve(filePath).toFile();

    return new FileStream(new FileInputStream(file), file.getName(), Files.size(file.toPath()));
  }

  @Override
  public boolean saveFile(final int workspaceId, final Path filePath, final UploadedFile file)
  throws NoSuchWorkspaceException {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var path = repoPath.resolve(filePath);

    if(path.toFile().isDirectory()) return false;

    FileUtil.streamToFile(file.content(), path.toString());
    return true;
  }

  @Override
  public boolean moveFile(final int workspaceId, final Path oldFilePath, final Path newFilePath)
  throws NoSuchWorkspaceException, SQLException
  {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var oldPath = repoPath.resolve(oldFilePath);
    final var newPath = repoPath.resolve(newFilePath);
    boolean success = true;

    // Do not move the file if the destination already exists
    if(newPath.toFile().exists()) return false;

    // Find hidden metadata files, if they exist, and move them
    final var metadataExtensions = postgresRepository.getMetadataExtensions();
    for(final var extension : metadataExtensions) {
      final File oldFile = Path.of(oldPath + extension).toFile();
      if(oldFile.exists()) {
        final var newFile = Path.of(newPath + extension).toFile();
        success = success && oldFile.renameTo(newFile); // Do not fast-fail
      }
    }

    return success && oldPath.toFile().renameTo(newPath.toFile());
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
      final var walkList = new ArrayList<>(walkOutput.toList());
      walkList.removeFirst(); // remove the initial path
      return new DirectoryTree(path, walkList, postgresRepository.getExtensionMapping());
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
  public boolean moveDirectory(final int workspaceId, final Path oldDirectoryPath, final Path newDirectoryPath)
  throws NoSuchWorkspaceException, IOException
  {
    final var repoPath = postgresRepository.workspaceRootPath(workspaceId);
    final var oldPath = repoPath.resolve(oldDirectoryPath);
    final var newPath = repoPath.resolve(newDirectoryPath);

    // Do not permit the workspace's root directory to be moved
    // Or to move a directory to replace the root directory
    if(Files.isSameFile(oldPath, repoPath) || Files.isSameFile(newPath, repoPath)) return false;

    return oldPath.toFile().renameTo(newPath.toFile());
  }

  @Override
  public boolean deleteDirectory(final int workspaceId, final Path directoryPath) throws NoSuchWorkspaceException {
    return deleteFile(workspaceId, directoryPath);
  }
}
