package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.postgres.WorkspaceFileOpException;
import gov.nasa.jpl.aerie.workspace.server.postgres.WorkspacePostgresRepository;
import io.javalin.http.UploadedFile;
import io.javalin.util.FileUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
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
  public Optional<Integer> createWorkspace(final Path workspaceLocation, final String workspaceName, String username, int parcelId) {
    final var repoPath = postgresRepository.getBaseRepositoryPath().resolve(workspaceLocation);
    if(repoPath.toFile().mkdirs()){
      try {
        final int workspaceId = postgresRepository.createWorkspace(workspaceLocation.toString(), workspaceName, username, parcelId);
        return Optional.of(workspaceId);
      } catch (SQLException ex) {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }
  }

  /**
   * Helper method that behaves like "rm -r <DIRECTORY>".
   * This means it will:
   *  1) remove symlinks without following them
   *  2) attempt to delete as much of the contents of "directory" as possible, not stopping on failure
   *  3) recursively enter subdirectories
   * @param directory the directory to be removed from the file system.
   * @return whether the directory was successfully deleted.
   */
  private boolean rmDirectory(final File directory) {
    boolean success = true;

    final var contents = directory.listFiles();
    if(contents == null) {
      return rm(directory);
    }

    for(final var f : contents) {
      if(Files.isSymbolicLink(f.toPath()) || !f.isDirectory()) {
        success = success && rm(f);
      } else {
        success = success && rmDirectory(f);
      }
    }

    return success && rm(directory);
  }

  /**
   * Helper method to remove a file or empty directory while swallowing any SecurityManager exception.
   * This method can be removed and replaced with `file.delete()` when the project moves to Java 24+
   *
   * @param file the file to removed from the file system
   * @return whether the file was successfully deleted
   */
  private boolean rm(final File file) {
    try {
      return file.delete();
    } catch (SecurityException se) {
      return false;
    }
  }

  @Override
  public boolean deleteWorkspace(final int workspaceId) throws NoSuchWorkspaceException, SQLException {
    final var repoDir = postgresRepository.workspaceRootPath(workspaceId).toFile();
    // Only remove DB entry if the files were successfully deleted
    // This allows the user to attempt deleting via this endpoint again
    if(rmDirectory(repoDir)) {
      return postgresRepository.deleteWorkspace(workspaceId);
    }
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
  public boolean moveFile(final int oldWorkspaceId, final Path oldFilePath, final int newWorkspaceId, final Path newFilePath)
  throws NoSuchWorkspaceException, SQLException, WorkspaceFileOpException
  {
    final var oldRepoPath = postgresRepository.workspaceRootPath(oldWorkspaceId);
    final var oldPath = oldRepoPath.resolve(oldFilePath);
    final var newRepoPath = postgresRepository.workspaceRootPath(newWorkspaceId);
    final var newPath = newRepoPath.resolve(newFilePath);
    boolean success = true;

    // Do not move the file if the destination already exists
    if(newPath.toFile().exists()) throw new WorkspaceFileOpException("Destination file \"%s\" in workspace %d already exists.".formatted(newFilePath, newWorkspaceId));

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
  public boolean copyFile(final int sourceWorkspaceId, final Path sourceFilePath, final int destWorkspaceId, final Path destFilePath)
  throws NoSuchWorkspaceException, SQLException, WorkspaceFileOpException
  {
    final var sourceRepoPath = postgresRepository.workspaceRootPath(sourceWorkspaceId);
    final var sourcePath = sourceRepoPath.resolve(sourceFilePath);
    final var destRepoPath = postgresRepository.workspaceRootPath(destWorkspaceId);
    final var destPath = destRepoPath.resolve(destFilePath);

    try {
      // Do not copy the file if the source file does not exist
      if(!sourcePath.toFile().exists()) throw new WorkspaceFileOpException("Source file \"%s\" in workspace %d does not exist.".formatted(sourceFilePath, sourceWorkspaceId));

      // Do not copy the file if the destination already exists
      if(destPath.toFile().exists()) throw new WorkspaceFileOpException("Destination file \"%s\" in workspace %d already exists.".formatted(destFilePath, destWorkspaceId));

      // Copy the main file
      Files.copy(sourcePath, destPath);

      // Find and copy hidden metadata files
      final var metadataExtensions = postgresRepository.getMetadataExtensions();
      for (final var extension : metadataExtensions) {
        final var oldMetaPath = Path.of(sourcePath + extension);
        final var newMetaPath = Path.of(destPath + extension);
        if (Files.exists(oldMetaPath)) {
          Files.copy(oldMetaPath, newMetaPath);
        }
      }

      return true;
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
  }


  @Override
  public boolean copyDirectory(final int sourceWorkspaceId, final Path sourceFilePath, final int destWorkspaceId, final Path destFilePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException
  {
    final var sourceRepoPath = postgresRepository.workspaceRootPath(sourceWorkspaceId);
    final var sourcePath = sourceRepoPath.resolve(sourceFilePath);
    final var destRepoPath = postgresRepository.workspaceRootPath(destWorkspaceId);
    final var destPath = destRepoPath.resolve(destFilePath);

    try {
      // Validate source exists and is a directory
      if (!Files.exists(sourcePath)) throw new WorkspaceFileOpException("Source directory \"%s\" in workspace %d does not exist.".formatted(sourceFilePath, sourceWorkspaceId));
      if (!Files.isDirectory(sourcePath)) throw new WorkspaceFileOpException("Source directory \"%s\" in workspace %d is not actually a directory. This is a bug.".formatted(sourceFilePath, sourceWorkspaceId));

      // Do not copy if destination already exists
      if (Files.exists(destPath)) throw new WorkspaceFileOpException("Destination directory \"%s\" in workspace %d already exists.".formatted(destFilePath, destWorkspaceId));


      // Walk source directory and copy files/subdirectories -- note we have to use a try-with-resources thing here
      // to ensure the stream autocloses
      try (var paths = Files.walk(sourcePath)) {
        paths.forEach(source -> {
          final Path relative = sourcePath.relativize(source);
          final Path target = destPath.resolve(relative);
          try {
            if (Files.isDirectory(source)) {
              Files.createDirectories(target);
            } else {
              Files.copy(source, target);
            }
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
      }

      return true;
    } catch (IOException | UncheckedIOException e) {
      e.printStackTrace();
      return false;
    }
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
  public boolean moveDirectory(final int oldWorkspaceId, final Path oldDirectoryPath, final int newWorkspaceId, final Path newDirectoryPath)
  throws NoSuchWorkspaceException, IOException, SQLException, WorkspaceFileOpException
  {
    final var oldRepoPath = postgresRepository.workspaceRootPath(oldWorkspaceId);
    final var oldPath = oldRepoPath.resolve(oldDirectoryPath);
    final var newRepoPath = postgresRepository.workspaceRootPath(newWorkspaceId);
    final var newPath = newRepoPath.resolve(newDirectoryPath);

    // Do not permit the workspace's root directory to be moved
    if(Files.isSameFile(oldPath, oldRepoPath)) throw new WorkspaceFileOpException("Cannot move the workspace root directory.");

    // Do not permit a directory to replace the root directory
    // TODO: maybe we want to allow this? This check fails anyways because newPath is not a real file at this point
    // if(Files.isSameFile(newPath, newRepoPath)) return false;

    return oldPath.toFile().renameTo(newPath.toFile());
  }

  @Override
  public boolean deleteDirectory(final int workspaceId, final Path directoryPath) throws NoSuchWorkspaceException {
    return deleteFile(workspaceId, directoryPath);
  }
}
