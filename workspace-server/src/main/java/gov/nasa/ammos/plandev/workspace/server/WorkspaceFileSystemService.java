package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.ReservedPathException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.postgres.WorkspacePostgresRepository;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataKeys;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataMergeBehavior;
import io.javalin.http.UploadedFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.stream.JsonGenerator;

public class WorkspaceFileSystemService implements WorkspaceService {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceFileSystemService.class);

  // Configure how the Metadata JSONs are written
  private static final Map<String,String> config = Map.of(JsonGenerator.PRETTY_PRINTING, "");

  final WorkspaceRoots roots;
  final WorkspacePostgresRepository postgresRepository;
  final WorkspaceHistory history;

  public WorkspaceFileSystemService(final WorkspacePostgresRepository postgresRepository, final WorkspaceHistory history) {
    this(postgresRepository, postgresRepository, history);
  }

  /**
   * @param roots resolves workspace ids to directories
   * @param postgresRepository used for workspace creation/deletion and extension mappings
   * @param history the workspace mutation boundary; every mutating method here requires its lock to be held
   */
  WorkspaceFileSystemService(
      final WorkspaceRoots roots,
      final WorkspacePostgresRepository postgresRepository,
      final WorkspaceHistory history)
  {
    this.roots = roots;
    this.postgresRepository = postgresRepository;
    this.history = history;
  }

  //region Path Resolution
  /**
   * Resolve and validate a relative path against a workspace root for the purpose of writing while ensuring the
   * result stays within the root directory.
   * Prevents path traversal attacks by rejecting absolute paths and any resolved path that escape the specified root.
   * @param rootPath the workspace root path
   * @param filePath the untrusted path to resolve against the root
   * @return the resolved and normalized path, guaranteed to be within the root
   * @throws SecurityException if the resolved path escapes the root or if the input is absolute
   * @throws WorkspaceFileOpException if the resolved path is invalid (ie, contains illegal characters)
   */
  Path resolveWritingPath(final Path rootPath, final Path filePath) throws WorkspaceFileOpException {
    final var resolvedPath = resolveReadingPath(rootPath, filePath);
    validatePath(resolvedPath);
    return resolvedPath;
  }

  /**
   * Resolves a relative path against a workspace root for reading or otherwise fetching a File while ensuring the
   *   result stays within the root directory.
   * Prevents path traversal attacks by rejecting absolute paths and any resolved path that escape the specified root.
   * Also rejects any path naming a reserved internal name ({@link WorkspacePaths}, e.g. {@code .git}) at any
   * segment, and any path through an existing symbolic link (unsupported workspace content: a link could reach
   * {@code .git} or leave the root while the lexical path looks fine), so every file API (read, write, move, copy,
   * delete, metadata) shares one rule.
   * @param rootPath the workspace root path
   * @param filePath the untrusted path to resolve against the root
   * @return the resolved and normalized path, guaranteed to be within the root
   * @throws SecurityException if the resolved path escapes the root or if the input is absolute
   * @throws ReservedPathException if the path names a reserved internal name or goes through a symbolic link
   */
  Path resolveReadingPath(final Path rootPath, final Path filePath) {
    // disallow absolute file paths, since Path.of("/foo").resolve(Path.of("/etc/passwd")) -> "/etc/passwd"
    if (filePath.isAbsolute()) {
      throw new SecurityException("Absolute file paths not allowed");
    }
    final var normalizedRootPath = rootPath.normalize();
    final var resolvedPath = normalizedRootPath.resolve(filePath).normalize();
    if (!resolvedPath.startsWith(normalizedRootPath)) {
      throw new SecurityException("Path traversal attempt detected");
    }
    final var reserved = WorkspacePaths.firstReservedSegment(normalizedRootPath.relativize(resolvedPath));
    if (reserved != null) {
      throw new ReservedPathException(filePath, reserved);
    }
    for (var p = resolvedPath; !p.equals(normalizedRootPath); p = p.getParent()) {
      if (Files.isSymbolicLink(p)) throw ReservedPathException.symbolicLink(filePath, p.getFileName().toString());
    }
    return resolvedPath;
  }

  /**
   * Fetches and then resolves a relative path against a workspace root for reading or otherwise fetching a File while
   *    ensuring the result stays within the root directory.
   * Prevents path traversal attacks by rejecting absolute paths and any resolved path that escape the specified root.
   * @param workspaceId the workspace the path lives in
   * @param filePath the untrusted path to resolve against the workspace root
   * @return the resolved and normalized path, guaranteed to be within the root
   * @throws NoSuchWorkspaceException if the workspace to resolve within does not exist
   * @throws SecurityException if the resolved path escapes the root or if the input is absolute
   */
  private Path resolveReadingPath(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException {
    return resolveReadingPath(roots.workspaceRootPath(workspaceId), filePath);
  }

  /**
   * Fetches, resolves, and validates a relative path against a workspace root, then resolves and validates the path to
   *  that file's metadata file.
   * @param rootPath the workspace's root path
   * @param filePath the untrusted path to the file
   * @return the path to the metadata file for the specified file
   */
  private Path resolveMetadataPath(final Path rootPath, final Path filePath) throws WorkspaceFileOpException {
    final var baseFilePath = resolveWritingPath(rootPath, filePath);

    // Check that the given filepath is not a directory or a metadata file, both of which are not allowed to have associated metadata files
    if(Files.isDirectory(baseFilePath)) {
      throw new WorkspaceFileOpException("Cannot resolve metadata file path: %s is a directory.".formatted(baseFilePath.getFileName()));
    }

    if (RenderType.isAerieMetadataFile(baseFilePath.getFileName().toString())) {
      throw new WorkspaceFileOpException("Cannot resolve metadata file path: %s is already a metadata file.".formatted(
          baseFilePath.getFileName()));
    }

    // Convert base file path to metadata file path
    final var metadataFileName = RenderType.toMetadataFileName(baseFilePath.getFileName().toString());
    final var metadataFilePath = baseFilePath.resolveSibling(metadataFileName); // Metadata files are hidden sibling files

    if(Files.isDirectory(metadataFilePath)) {
      throw new WorkspaceFileOpException("Cannot retrieve metadata file: %s is a directory".formatted(metadataFilePath.getFileName()));
    }

    return metadataFilePath;
  }

  /**
   * Fetches, resolves, and validates a relative path against a workspace root, then resolves and validates the path to
   *  that file's metadata file.
   * @param workspaceId the workspace the file lives in
   * @param filePath the untrusted path to the file
   * @return the path to the metadata file for the specified file
   */
  private Path resolveMetadataPath(final int workspaceId, final Path filePath)
  throws WorkspaceFileOpException, NoSuchWorkspaceException
  {
    return resolveMetadataPath(roots.workspaceRootPath(workspaceId), filePath);
  }

  /**
   * Validates that the path does not contain any invalid characters.
   *
   * Forbidden Characters for File and Folder names:
   *   < (less than), > (greater than), : (colon), " (double quote), / (forward slash), \ (backslash),
   *   | (vertical bar or pipe), ? (question mark), * (asterisk),
   *   % (percent sign - causes issues with URL path resolution as it is not automatically encoded),
   *   # (pound sign - causes issues with URL path resolution as it is not automatically encoded),
   *   Unicode Control Characters (0-31, 127-159),
   *   trailing .
   *   trailing space
   *
   * While / (forward slash) is a forbidden characters in filenames, it's interpreted by Java's Path class as a
   *  folder delineator, meaning that it will not appear as a path segment.
   *  The character is still checked for just in case.
   *
   * Reserved Filenames (these are not permitted on Windows even if they have an extension):
   *   CON, PRN, AUX, NUL, COM1, COM2, COM3, COM4, COM5, COM6, COM7, COM8, COM9,
   *   LPT1, LPT2, LPT3, LPT4, LPT5, LPT6, LPT7, LPT8, LPT9
   *
   * @param path the Path to validate
   */
  void validatePath(final Path path) throws WorkspaceFileOpException {
    final String[] reservedFilenames = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
                                         "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};
    final var controlCharacters = Pattern.compile("([\u0000-\u001F]|[\u007F-\u009F])+", Pattern.UNICODE_CHARACTER_CLASS);
    final var forbiddenCharacters = Pattern.compile("([|<>:/\"?*%#\\\\])+", Pattern.UNICODE_CHARACTER_CLASS);


    for(final var pathSegment : path) {
      final var segment = pathSegment.toString();
      // Check for trailing period or space
      if(segment.endsWith(" ")) {
        throw new WorkspaceFileOpException("Path segment '"+ segment+ "' cannot end in a space.");
      }
      if(segment.endsWith(".")) {
        throw new WorkspaceFileOpException("Path segment '"+ segment+ "' cannot end in a period.");
      }

      // Check for control characters
      final var controlMatcher = controlCharacters.matcher(segment);
      if(controlMatcher.find()){
        throw new WorkspaceFileOpException("Path segment '"+ segment+ "' has illegal characters: "+controlMatcher.group());
      }

      // Check for forbidden characters
      final var forbiddenMatcher = forbiddenCharacters.matcher(segment);
      if(forbiddenMatcher.find()){
        throw new WorkspaceFileOpException("Path segment '"+ segment+ "' has illegal characters: "+forbiddenMatcher.group());
      }

      // Check that the segment is not a reserved filenames:
      final var name = segment.split("\\.")[0];
      if(Arrays.asList(reservedFilenames).contains(name)){
        throw new WorkspaceFileOpException("Path segment '"+ segment+ "' contains reserved name: "+name);
      }
    }
  }
  //endregion

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
      if(Files.isSymbolicLink(f.toPath()) || !Files.isDirectory(f.toPath())) {
        success = rm(f) && success;
      } else {
        success = rmDirectory(f) && success;
      }
    }

    return rm(directory) && success;
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
  public boolean checkFileExists(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException {
    final var path = resolveReadingPath(workspaceId, filePath);
    return path.toFile().exists();
  }

  @Override
  public boolean isDirectory(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException {
    final var path = resolveReadingPath(workspaceId, filePath);
    return Files.isDirectory(path);
  }

  @Override
  public RenderType getFileType(final Path filePath) throws SQLException {
    final var fileName = filePath.getFileName().toString();
    return RenderType.getRenderType(fileName, postgresRepository.getExtensionMapping());
  }

  //region Workspace Operations
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

  @Override
  public boolean deleteWorkspace(final int workspaceId) throws NoSuchWorkspaceException, SQLException {
    final var repoDir = roots.workspaceRootPath(workspaceId).toFile();
    // Only remove DB entry if the files were successfully deleted
    // This allows the user to attempt deleting via this endpoint again
    if(rmDirectory(repoDir)) {
      return postgresRepository.deleteWorkspace(workspaceId);
    }
    return false;
  }
  //endregion

  //region File Operations
  @Override
  public FileStream loadFile(final int workspaceId, final Path filePath)
  throws IOException, NoSuchWorkspaceException, NoSuchFileException, WorkspaceFileOpException
  {
    final var path = resolveReadingPath(workspaceId, filePath);
    final var file = path.toFile();

    if(Files.isDirectory(path)) {
      throw new WorkspaceFileOpException("Cannot get the file contents of a directory.");
    }
    if(RenderType.isAerieMetadataFile(file.getName())) {
      throw new WorkspaceFileOpException("Cannot load a metadata file directly.");
    }
    if(!Files.exists(path)) {
      throw new NoSuchFileException(workspaceId, filePath);
    }

    return new FileStream(new FileInputStream(file), file.getName(), Files.size(file.toPath()), getETag(path));
  }

  @Override
  public String getETag(final int workspaceId, final Path filePath)
  throws IOException, NoSuchWorkspaceException, NoSuchFileException, WorkspaceFileOpException {
    final var path = resolveReadingPath(workspaceId, filePath);
    if(Files.isDirectory(path)) {
      throw new WorkspaceFileOpException("Cannot compute the Entity Tag for a directory.");
    }
    if(!Files.exists(path)) {
      throw new NoSuchFileException(workspaceId, filePath);
    }
    return getETag(path);
  }

  /**
   * Override of getETag that takes in a resolved, tested file path
   * @param filePath the resolved path to a file in the workspace
   * @return the file's current Entity Tag
   * @throws IOException If there is an I/O Error while reading the file's contents
   */
  private String getETag(final Path filePath) throws IOException{
    // Read the file in 1 MB chunks to avoid loading it all into memory at once
    final var md = WorkspaceService.newSHA256Digest();
    try(final var inputStream = new DigestInputStream(new FileInputStream(filePath.toFile()), md)) {
      final var buffer = new byte[1048576]; // 1 MB
      while(inputStream.read(buffer) > 0) {
        // This while body is left intentionally empty, since the DigestInputStream automatically
        // processes the chunk as part of read().
      }
      return WorkspaceService.eTagFromDigest(md.digest());
    }
  }

  @Override
  public LastEditInfo getLastEditInfo(final int workspaceId, final Path filePath)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException {
    final var metadata = effectiveMetadata(roots.workspaceRootPath(workspaceId), filePath);
    return new LastEditInfo(
        metadata.getString(MetadataKeys.lastEditedBy.name(), null),
        metadata.getString(MetadataKeys.lastEditedAt.name(), null));
  }

  @Override
  public Optional<String> saveFile(final int workspaceId, final Path filePath, final UploadedFile file, final String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException
  {
    history.requireLocked(workspaceId);
    final var repoPath = roots.workspaceRootPath(workspaceId);
    final var path = resolveWritingPath(repoPath, filePath);
    resolveMetadataPath(repoPath, filePath); // validates the path can carry metadata
    // lastEdited* is derived from history; here it only seeds createdBy/createdAt for a new file.
    final var metadataUpdates = new MetadataUpdates.Builder(userId)
        .lastEditedAt(Instant.now())
        .lastEditedBy(userId)
        .build();

    if(Files.isDirectory(path)) return Optional.empty();

    // Stream to a temp file, hashing as we go so the returned ETag matches what we wrote, then rename it into place
    // so lock-free readers see the old or the new bytes, never a partial file. Overwriting keeps the file's
    // permissions (notably the executable bit, which Git versions), so a save changes content only.
    final var md = WorkspaceService.newSHA256Digest();
    final var tmp = WorkspacePaths.tempFile(repoPath);
    try {
      Files.createDirectories(path.getParent());
      try (final var contentStream = new DigestInputStream(file.content(), md)) {
        Files.copy(contentStream, tmp);
      }
      if (Files.exists(path) && path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(path));
      }
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
    updateMetadata(repoPath, filePath, metadataUpdates, MetadataMergeBehavior.deepMerge);
    return Optional.of(WorkspaceService.eTagFromDigest(md.digest()));
  }

  @Override
  public boolean moveFile(
      final int oldWorkspaceId,
      final Path oldFilePath,
      final int newWorkspaceId,
      final Path newFilePath,
      final String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException
  {
    history.requireLocked(oldWorkspaceId, newWorkspaceId);
    final var oldRepoPath = roots.workspaceRootPath(oldWorkspaceId);
    final var oldPath = resolveReadingPath(oldRepoPath, oldFilePath);
    final var oldMetadataPath = resolveMetadataPath(oldRepoPath, oldFilePath);

    final var newRepoPath = (oldWorkspaceId == newWorkspaceId) ? oldRepoPath : roots.workspaceRootPath(newWorkspaceId);
    final var newPath = resolveWritingPath(newRepoPath, newFilePath);
    final var newMetadataPath = resolveMetadataPath(newRepoPath, newFilePath);

    // The moved file keeps its own metadata, never that of a file it overwrites
    if(Files.exists(oldMetadataPath)) {
      Files.move(oldMetadataPath, newMetadataPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.deleteIfExists(newMetadataPath);
    }
    // Ensure the moved file has a sidecar (lastEdited* itself comes from the move's commit)
    final var metadataUpdates = new MetadataUpdates.Builder(userId)
        .lastEditedAt(Instant.now())
        .lastEditedBy(userId)
        .build();
    updateMetadata(newRepoPath, newFilePath, metadataUpdates, MetadataMergeBehavior.deepMerge);

    Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    moveState(oldRepoPath, oldPath, newRepoPath, newPath);
    return true;
  }

  @Override
  public boolean copyFile(
      final int sourceWorkspaceId,
      final Path sourceFilePath,
      final int destWorkspaceId,
      final Path destFilePath,
      final String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException
  {
    history.requireLocked(sourceWorkspaceId, destWorkspaceId);
    final var sourceRepoPath = roots.workspaceRootPath(sourceWorkspaceId);
    final var sourcePath = resolveReadingPath(sourceRepoPath, sourceFilePath);
    final var sourceMetadataPath = resolveMetadataPath(sourceWorkspaceId, sourceFilePath);

    final var destRepoPath = (sourceWorkspaceId == destWorkspaceId)
        ? sourceRepoPath
        : roots.workspaceRootPath(destWorkspaceId);
    final var destPath = resolveWritingPath(destRepoPath, destFilePath);
    final var destMetadataPath = resolveMetadataPath(destWorkspaceId, destFilePath);

    // Do not copy the file if the source file does not exist
    if (!Files.exists(sourcePath)) {
      throw new WorkspaceFileOpException("Source file \"%s\" in workspace %d does not exist.".formatted(
          sourceFilePath,
          sourceWorkspaceId));
    }
    // Create any parent directories that don't already exist
    Files.createDirectories(destPath.getParent());
    copyAsNewFile(sourcePath, sourceMetadataPath, destRepoPath, destPath, destMetadataPath, userId, Instant.now());
    return true;
  }

  /**
   * Copy one content file as a new file in the destination, whether it is copied on its own or inside a directory:
   * its sidecar's semantic metadata ({@code version}, {@code user}) comes along, {@code createdBy}/{@code createdAt}
   * are the copier and the copy time, {@code readOnly} does not carry over, and {@code lastEdited*} is derived from
   * the destination's commit. Nothing is inherited from a file the copy overwrites. The content keeps the source's
   * permissions (a copy of an executable is executable).
   */
  private void copyAsNewFile(
      final Path sourcePath,
      final Path sourceMetadataPath,
      final Path destRoot,
      final Path destPath,
      final Path destMetadataPath,
      final String userId,
      final Instant now)
  throws IOException, WorkspaceFileOpException
  {
    if (Files.exists(sourceMetadataPath)) {
      Files.copy(sourceMetadataPath, destMetadataPath, StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.deleteIfExists(destMetadataPath);
    }
    final var key = WorkspacePaths.key(destRoot, destPath);
    updateState(destRoot, s -> s.remove(key));
    final var metadataUpdates = new MetadataUpdates.Builder(userId)
        .createdAt(now)
        .createdBy(userId)
        .lastEditedAt(now)
        .lastEditedBy(userId)
        .readOnly(false)
        .build();
    updateMetadata(destRoot, destRoot.normalize().relativize(destPath), metadataUpdates, MetadataMergeBehavior.deepMerge);
    Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }

  @Override
  public boolean deleteFile(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException
  {
    history.requireLocked(workspaceId);
    final var repoPath = roots.workspaceRootPath(workspaceId);
    final var path = resolveReadingPath(repoPath, filePath);
    final var file = path.toFile();
    final var metadataFile = resolveMetadataPath(repoPath, filePath).toFile();

    // Delete the metadata file if it exists
    if(metadataFile.exists()) {
      if(!rm(metadataFile)){
        logger.warn("DELETE FILE: Unable to delete metadata file {}", metadataFile);
        return false;
      }
    }

    if (!rm(file)) return false;
    final var key = WorkspacePaths.key(repoPath, path);
    updateState(repoPath, s -> s.remove(key));
    return true;
  }
  //endregion

  //region Directory Operations
  @Override
  public DirectoryTree listFiles(final int workspaceId, final Path directoryPath, final int depth, final boolean withMetadata)
  throws SQLException, NoSuchWorkspaceException, IOException {
    final var root = roots.workspaceRootPath(workspaceId);
    final var path = resolveReadingPath(root, directoryPath);
    if(!Files.isDirectory(path)) {
      return null;
    }
    // Convert to our API from the Files API
    final var walkDepth = depth == -1 ? Integer.MAX_VALUE : depth + 1;
    final var walkList = WorkspacePaths.walk(path, walkDepth); // never includes .git or .seqdev
    final var view = withMetadata ? metadataView(root) : (BiFunction<Path, JsonObject, JsonObject>) (p, m) -> m;
    return new DirectoryTree(path, walkList, postgresRepository.getExtensionMapping(), withMetadata, view);
  }

  @Override
  public boolean createDirectory(final int workspaceId, final Path directoryPath)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException {
    history.requireLocked(workspaceId);
    final var repoPath = roots.workspaceRootPath(workspaceId);
    final var path = resolveWritingPath(repoPath, directoryPath);
    Files.createDirectories(path);
    return true;
  }

  @Override
  public boolean moveDirectory(final int oldWorkspaceId, final Path oldDirectoryPath, final int newWorkspaceId, final Path newDirectoryPath)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException
  {
    history.requireLocked(oldWorkspaceId, newWorkspaceId);
    final var oldRepoPath = roots.workspaceRootPath(oldWorkspaceId).normalize();
    final var oldPath = resolveReadingPath(oldRepoPath, oldDirectoryPath);
    final var newRepoPath = (oldWorkspaceId == newWorkspaceId) ? oldRepoPath : roots.workspaceRootPath(newWorkspaceId).normalize();
    final var newPath = resolveWritingPath(newRepoPath, newDirectoryPath);

    // Do not permit the source workspace's root directory to be moved
    if(Files.isSameFile(oldPath, oldRepoPath)) throw new WorkspaceFileOpException("Cannot move the workspace root directory.");

    // Do not permit a moved directory to replace the target workspace's root directory
    if (Files.exists(newPath) && Files.isSameFile(newPath, newRepoPath)) {
      throw new WorkspaceFileOpException("Cannot replace the workspace root directory.");
    }

    // Do not try to move a directory into itself
    if(oldWorkspaceId == newWorkspaceId && newPath.startsWith(oldPath)){
      throw new WorkspaceFileOpException("Cannot move a directory into itself.");
    }

    if (!oldPath.toFile().renameTo(newPath.toFile())) return false;
    moveState(oldRepoPath, oldPath, newRepoPath, newPath);
    return true;
  }

  @Override
  public boolean copyDirectory(
      final int sourceWorkspaceId,
      final Path sourceFilePath,
      final int destWorkspaceId,
      final Path destFilePath,
      final String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException
  {
    history.requireLocked(sourceWorkspaceId, destWorkspaceId);
    final var sourceRepoPath = roots.workspaceRootPath(sourceWorkspaceId);
    final var sourcePath = resolveReadingPath(sourceRepoPath, sourceFilePath);
    final var destRepoPath = (sourceWorkspaceId == destWorkspaceId) ? sourceRepoPath : roots.workspaceRootPath(destWorkspaceId);
    final var destPath = resolveWritingPath(destRepoPath, destFilePath);

    // The root owns the workspace's history and runtime state; copying "the whole workspace" is not a file operation
    if (sourcePath.equals(sourceRepoPath.normalize())) {
      throw new WorkspaceFileOpException("Cannot copy the workspace root directory.");
    }

    try {
      // Validate source exists and is a directory
      if (!Files.exists(sourcePath)) throw new WorkspaceFileOpException("Source directory \"%s\" in workspace %d does not exist.".formatted(sourceFilePath, sourceWorkspaceId));
      if (!Files.isDirectory(sourcePath)) throw new WorkspaceFileOpException("Source directory \"%s\" in workspace %d is not a directory.".formatted(sourceFilePath, sourceWorkspaceId));

      // Do not try to copy a directory into itself
      if(sourceWorkspaceId == destWorkspaceId && destPath.startsWith(sourcePath)){
        throw new WorkspaceFileOpException("Cannot copy a directory into itself.");
      }

      // Every content file is copied exactly as copyFile copies one; sidecars travel with their content file, and a
      // sidecar whose content file is gone is metadata of nothing and is not copied.
      final var now = Instant.now();
      Files.createDirectories(destPath);
      for (final var source : WorkspacePaths.walk(sourcePath, Integer.MAX_VALUE)) {
        final Path target = destPath.resolve(sourcePath.relativize(source));
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else if (!RenderType.isAerieMetadataFile(source.getFileName().toString())) {
          final var sidecarName = RenderType.toMetadataFileName(source.getFileName().toString());
          copyAsNewFile(source, source.resolveSibling(sidecarName), destRepoPath, target, target.resolveSibling(sidecarName), userId, now);
        }
      }
      return true;
    } catch (IOException e) {
      logger.error("Error copying directory", e);
      return false;
    }
  }

  @Override
  public boolean deleteDirectory(final int workspaceId, final Path directoryPath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException
  {
    history.requireLocked(workspaceId);
    final var repoPath = roots.workspaceRootPath(workspaceId);
    final var path = resolveReadingPath(repoPath, directoryPath);
    // Deleting the root would delete the workspace's history; that is deleteWorkspace's job
    if (path.equals(repoPath.normalize())) {
      throw new WorkspaceFileOpException("Cannot delete the workspace root directory.");
    }
    if (!rmDirectory(path.toFile())) return false;
    final var key = WorkspacePaths.key(repoPath, path);
    updateState(repoPath, s -> s.remove(key));
    return true;
  }

  /**
   * Whether a file is read-only. Read-only is runtime state ({@link WorkspaceState}), so this must be called within a
   * workspace mutation, where the workspace is guaranteed to have been migrated.
   */
  @Override
  public boolean isReadOnly(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException, JsonException
  {
    return isReadOnly(roots.workspaceRootPath(workspaceId), filePath);
  }

  private boolean isReadOnly(final Path repoPath, final Path filePath)
  throws WorkspaceFileOpException, IOException, JsonException
  {
    resolveMetadataPath(repoPath, filePath); // rejects directories and metadata files
    if (!history.isManaged(repoPath)) {
      throw new IllegalStateException("readOnly was checked outside a workspace mutation for " + repoPath);
    }
    final var key = WorkspacePaths.key(repoPath, resolveReadingPath(repoPath, filePath));
    return WorkspaceState.load(repoPath).readOnly(key).orElse(false);
  }

  @Override
  public List<Path> getReadOnlyFiles(final int workspaceId, final Path dirPath)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException, JsonException, SQLException
  {
    final var repoPath = roots.workspaceRootPath(workspaceId);
    final var directoryPath = resolveReadingPath(repoPath, dirPath);

    if(!Files.isDirectory(directoryPath)) {
      if(isReadOnly(repoPath, dirPath)) {
        return List.of(directoryPath);
      }
      return List.of();
    }

    return WorkspaceState.load(repoPath)
                         .readOnlyAtOrUnder(WorkspacePaths.key(repoPath, directoryPath))
                         .stream()
                         .map(repoPath::resolve)
                         .filter(Files::exists)
                         .toList();
  }

  /** Runtime state follows a moved file or directory; pre-history lastEdited values do not (the move is a commit). */
  private static void moveState(final Path fromRoot, final Path from, final Path toRoot, final Path to) throws IOException {
    final var fromKey = WorkspacePaths.key(fromRoot, from);
    final var toKey = WorkspacePaths.key(toRoot, to);
    final var flags = WorkspaceState.load(fromRoot).readOnlyFlagsAtOrUnder(fromKey);
    updateState(fromRoot, s -> s.remove(fromKey));
    updateState(toRoot, s -> {
      s.remove(toKey);
      flags.forEach((k, v) -> s.setReadOnly(toKey + k.substring(fromKey.length()), v));
    });
  }

  private static void updateState(final Path root, final Consumer<WorkspaceState> change) throws IOException {
    final var state = WorkspaceState.load(root);
    change.accept(state);
    state.save();
  }
  //endregion

  //region Metadata Operations

  /**
   * Read and return the contents of a metadata file
   * @param metadataFile the metadata file to be read
   * @return the contents of the metadata file, if it exists, or else an empty json object
   * @throws JsonException if the metadata file is malformed
   * @throws IOException if the metadata file cannot be opened for reading for reasons other than nonexistence
   */
  private JsonObject readMetadataFile(final File metadataFile) throws IOException, JsonException {
    try(final var reader = Json.createReader(new FileReader(metadataFile))){
      return reader.readObject();
    } catch (FileNotFoundException fnf) {
      // If we got this exception, the file probably does not exist, but we need to confirm since new FileReader()
      //    can throw this exception if the file "cannot be opened for other reasons"
      if(!metadataFile.exists()) {
        return JsonValue.EMPTY_JSON_OBJECT;
      } else {
        throw new IOException("Unable to update metadata file at "+metadataFile.getPath(), fnf);
      }
    }
  }

  /**
   * Generates a generic FileStream response to use as a fallback in the event
   * that a user attempts to load a non-existent metadata file.
   * @param metadataFileName the name of the metadata file
   */
  private FileStream generateFallbackMetadataResponse(final String metadataFileName) {
    final byte[] fallbackResponse = Json.createObjectBuilder()
                                        .add("version", "1")
                                        .build()
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
    final var inputStream = new ByteArrayInputStream(fallbackResponse);
    final var eTag = WorkspaceService.computeETag(fallbackResponse);
    return new FileStream(inputStream, metadataFileName, fallbackResponse.length, eTag);
  }

  /**
   * How file metadata is presented by the API: the versioned sidecar fields (version, createdBy, createdAt, user),
   * plus lastEditedBy/lastEditedAt derived from history and readOnly from runtime state. Workspaces that have not
   * been put under history yet still carry every field in their sidecars and are presented as-is.
   * The returned function maps (absolute content path, raw sidecar) to the presented metadata.
   */
  private BiFunction<Path, JsonObject, JsonObject> metadataView(final Path root) throws IOException {
    if (!history.isManaged(root)) return (path, sidecar) -> sidecar;
    final var state = WorkspaceState.load(root);
    final var edits = history.lastEdits(root);
    return (path, sidecar) -> effectiveMetadata(sidecar, WorkspacePaths.key(root, path), state, edits);
  }

  private JsonObject effectiveMetadata(final Path root, final Path filePath) throws IOException, WorkspaceFileOpException {
    final var sidecar = readMetadataFile(resolveMetadataPath(root, filePath).toFile());
    return metadataView(root).apply(resolveReadingPath(root, filePath), sidecar);
  }

  static JsonObject effectiveMetadata(
      final JsonObject sidecar,
      final String key,
      final WorkspaceState state,
      final Map<String, WorkspaceHistory.LastEdit> edits)
  {
    final var builder = Json.createObjectBuilder(sidecar)
                            .remove(MetadataKeys.readOnly.name())
                            .remove(MetadataKeys.lastEditedBy.name())
                            .remove(MetadataKeys.lastEditedAt.name());

    // Prefer the file's latest commit, unless that is the history baseline and the pre-history value is known.
    final var edit = edits.get(key);
    final var legacy = state.entry(key).filter(e -> e.legacyLastEditedBy() != null && e.legacyLastEditedAt() != null);
    if (edit != null && !(edit.baseline() && legacy.isPresent())) {
      builder.add(MetadataKeys.lastEditedBy.name(), edit.by()).add(MetadataKeys.lastEditedAt.name(), edit.at().toString());
    } else if (legacy.isPresent()) {
      builder.add(MetadataKeys.lastEditedBy.name(), legacy.get().legacyLastEditedBy())
             .add(MetadataKeys.lastEditedAt.name(), legacy.get().legacyLastEditedAt());
    } else if (sidecar.get("createdBy") instanceof JsonString by && sidecar.get("createdAt") instanceof JsonString at) {
      // Not committed yet (only observable by a lock-free reader mid-mutation)
      builder.add(MetadataKeys.lastEditedBy.name(), by).add(MetadataKeys.lastEditedAt.name(), at);
    }

    state.readOnly(key).ifPresent(readOnly -> builder.add(MetadataKeys.readOnly.name(), readOnly));
    return builder.build();
  }

  @Override
  public FileStream loadMetadataFile(final int workspaceId, final Path filePath)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException
  {
    final var root = roots.workspaceRootPath(workspaceId);
    final var metadataFile = resolveMetadataPath(root, filePath).toFile();

    // If the file doesn't exist, return a file containing just the current metadata file version
    if(!metadataFile.exists()) {
      return generateFallbackMetadataResponse(metadataFile.getName());
    }

    try {
      final var out = new StringWriter();
      try (final var writer = Json.createWriterFactory(config).createWriter(out)) {
        writer.writeObject(effectiveMetadata(root, filePath));
      }
      final var bytes = out.toString().getBytes(StandardCharsets.UTF_8);
      return new FileStream(
          new ByteArrayInputStream(bytes),
          metadataFile.getName(),
          bytes.length,
          getETag(workspaceId, filePath));
    } catch (NoSuchFileException nfe) {
      logger.error("Metadata file deleted mid-read.");
      return generateFallbackMetadataResponse(metadataFile.getName());
    }
  }

  @Override
  public boolean updateMetadataKeys(final int workspaceId, final Path filePath, MetadataUpdates updates, MetadataMergeBehavior mergeBehavior)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException, JsonException
  {
    history.requireLocked(workspaceId);
    updateMetadata(roots.workspaceRootPath(workspaceId), filePath, updates, mergeBehavior);
    return true;
  }

  /**
   * Apply metadata updates: versioned fields go to the sidecar, readOnly goes to runtime state, and lastEdited* is
   * ignored (it is derived from history). Caller holds the workspace lock.
   */
  private void updateMetadata(
      final Path root,
      final Path filePath,
      final MetadataUpdates updates,
      final MetadataMergeBehavior mergeBehavior)
  throws IOException, JsonException, WorkspaceFileOpException
  {
    final var metadataFile = resolveMetadataPath(root, filePath).toFile();
    final var newFileContents = generateUpdatedMetadataFile(readMetadataFile(metadataFile), updates, mergeBehavior).build();
    writeMetadataFile(root, newFileContents, metadataFile);
    if (updates.readOnly().isPresent()) {
      final var key = WorkspacePaths.key(root, resolveReadingPath(root, filePath));
      updateState(root, s -> s.setReadOnly(key, updates.readOnly().get()));
    }
  }

  /**
   * Write a sidecar's versioned fields (version, createdBy, createdAt, user). Skips the write when the bytes would
   * not change, so an edit that only touches derived or runtime fields leaves the sidecar, and history, untouched.
   * @param root The workspace root (for its temp directory; the write is an atomic replace).
   * @param contents The contents of the metadata file to be written.
   * @param metadataFile The File to be written to.
   * @throws IOException If the File cannot be written to for any reason
   */
  private void writeMetadataFile(final Path root, final MetadataUpdates contents, final File metadataFile) throws IOException {
    // Fill in "created" information, using the "metadataLastEdited" information as a fallback
    final var serialized = serializeSidecar(
        contents.version().orElse("1"),
        contents.createdBy().orElse(contents.metadataLastEditedBy()),
        contents.createdAt().orElse(contents.metadataLastEditedAt()).toString(),
        contents.user().orElse(null));
    final var path = metadataFile.toPath();
    if (Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).equals(serialized)) return;
    WorkspacePaths.writeAtomically(root, path, serialized.getBytes(StandardCharsets.UTF_8));
  }

  /** The on-disk (and committed) form of a sidecar. Null fields are omitted; a missing version defaults to "1". */
  static String serializeSidecar(final String version, final String createdBy, final String createdAt, final JsonObject user) {
    final var out = new StringWriter();
    try (final var generator = Json.createGeneratorFactory(config).createGenerator(out)) {
      generator.writeStartObject();
      generator.write("version", version == null ? "1" : version);
      if (createdBy != null) generator.write("createdBy", createdBy);
      if (createdAt != null) generator.write("createdAt", createdAt);
      if (user != null) generator.write("user", user);
      generator.writeEnd();
    }
    return out.toString();
  }

  /**
   * Merge the requested MetadataUpdates with the current contents of the metadata file. Returns the builder for the merged object.
   * @param currentContents The current contents of the metadata file, represented as a JsonObject.
   * @param updates The requested updates to be applied.
   * @param mergeBehavior How to merge the `user` object
   * @return The new contents of the metadata file, expressed as a MetadataUpdates object
   * @throws JsonException If the current contents are malformed.
   */
  private MetadataUpdates.Builder generateUpdatedMetadataFile(
      final JsonObject currentContents,
      MetadataUpdates updates,
      MetadataMergeBehavior mergeBehavior
  ) throws JsonException {
    // Create the combined builder, initializing "metadataLastEditedAt" and "metadataLastEditedBy" to the value in the `updates` parameter
    final var mergedBuilder = new MetadataUpdates.Builder(updates.metadataLastEditedBy(), updates.metadataLastEditedAt());

    // Upsert the rest of the fields
    updates.version().ifPresentOrElse(
        mergedBuilder::version,
        () -> {
          if(currentContents.containsKey("version")) {
            mergedBuilder.version(currentContents.getString("version"));
          } else {
            mergedBuilder.version("1"); // Fallback
          }
        }
    );
    updates.createdBy().ifPresentOrElse(
        mergedBuilder::createdBy,
        () -> {
          if(currentContents.containsKey("createdBy")) {
            mergedBuilder.createdBy(currentContents.getString("createdBy"));
          } else {
            // Fallback, trying to use last file edit before the current metadata edits
            updates.lastEditedBy().ifPresentOrElse(
                mergedBuilder::createdBy,
                () -> mergedBuilder.createdBy(updates.metadataLastEditedBy())
            );
          }
        }
    );
    updates.createdAt().ifPresentOrElse(
        mergedBuilder::createdAt,
        () -> {
          if(currentContents.containsKey("createdAt")) {
            mergedBuilder.createdAt(Instant.parse(currentContents.getString("createdAt")));
          } else {
            // Fallback, trying to use last file edit before the current metadata edits
            updates.lastEditedAt().ifPresentOrElse(
                mergedBuilder::createdAt,
                () -> mergedBuilder.createdAt(updates.metadataLastEditedAt())
            );
          }
        }
    );
    updates.lastEditedBy().ifPresentOrElse(
        mergedBuilder::lastEditedBy,
        () -> {
          if(currentContents.containsKey("lastEditedBy")) {
            mergedBuilder.lastEditedBy(currentContents.getString("lastEditedBy"));
          } else {
            mergedBuilder.lastEditedBy(updates.metadataLastEditedBy()); // Fallback
          }
        }
    );
    updates.lastEditedAt().ifPresentOrElse(
        mergedBuilder::lastEditedAt,
        () -> {
          if(currentContents.containsKey("lastEditedAt")) {
            mergedBuilder.lastEditedAt(Instant.parse(currentContents.getString("lastEditedAt")));
          } else {
            mergedBuilder.lastEditedAt(updates.metadataLastEditedAt()); // Fallback
          }
        }
    );

    updates.readOnly().ifPresentOrElse(
        mergedBuilder::readOnly,
        () -> {
          if(currentContents.containsKey("readOnly")) {
            mergedBuilder.readOnly(currentContents.getBoolean("readOnly")); // No fallback, as this key is optional
          }
        }
    );
    updates.user().ifPresentOrElse(
        newUser -> {
          // Merge "user" object, if needed
          if(currentContents.containsKey("user")) {
            switch (mergeBehavior) {
              case overwrite -> mergedBuilder.user(newUser);
              case deepMerge -> mergedBuilder.user(deepMergeJsonObjects(currentContents.getJsonObject("user"), newUser));
              case shallowMerge -> mergedBuilder.user(shallowMergeJsonObjects(currentContents.getJsonObject("user"), newUser));
            }
          } else {
            mergedBuilder.user(newUser);
          }
        },
        () -> {
          if(currentContents.containsKey("user")) {
            mergedBuilder.user(currentContents.getJsonObject("user")); // No fallback, as this key is optional
          }
        }
    );

    return mergedBuilder;
  }

  /**
   * Performs a deep merge of two JsonObjects, giving priority to keys in the "newObject" in the event the same key
   *    appears in both objects.
   * @param oldObject the initial object
   * @param newObject the new object. Keys in this object have priority.
   * @return the merged JsonObject
   */
  private JsonObject deepMergeJsonObjects(final JsonObject oldObject, final JsonObject newObject) {
    final var mergedObjectBuilder = Json.createObjectBuilder(oldObject);

    for (final var entry : newObject.entrySet()) {
      final var key = entry.getKey();
      final var val = entry.getValue();

      // Merge nested objects if needed
      if (val instanceof JsonObject newNestedObj) {
        if(oldObject.containsKey(key) && oldObject.get(key) instanceof JsonObject oldNestedObj) {
          mergedObjectBuilder.add(key, deepMergeJsonObjects(oldNestedObj, newNestedObj));
        } else {
          mergedObjectBuilder.add(key, newNestedObj);
        }
      } else {
        // Overwrite the old values
        if(val == null) {
          mergedObjectBuilder.addNull(key);
        } else {
          mergedObjectBuilder.add(key, val);
        }
      }
    }
    return mergedObjectBuilder.build();
  }

  /**
   * Performs a shallow merge of two JsonObjects.
   * @param oldObject the initial object
   * @param newObject the new object. Keys in this object have priority.
   * @return the merged JsonObject
   */
  private JsonObject shallowMergeJsonObjects(final JsonObject oldObject, final JsonObject newObject) {
    final var mergedObjectBuilder = Json.createObjectBuilder(oldObject);

    // Add the values of the newObject, overwriting keys if necessary
    for (final var entry : newObject.entrySet()) {
      final var key = entry.getKey();
      final var val = entry.getValue();
      if(val == null) {
        mergedObjectBuilder.addNull(key);
      } else {
        mergedObjectBuilder.add(key, val);
      }
    }
    return mergedObjectBuilder.build();
  }

  @Override
  public boolean unsetMetadataKeys(
      final int workspaceId,
      final Path filePath,
      final Set<String> keysToUnset,
      final String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException, JsonException
  {
    history.requireLocked(workspaceId);
    final var root = roots.workspaceRootPath(workspaceId);
    final var metadataFile = resolveMetadataPath(root, filePath).toFile();

    // Get the contents of the current metadata file, or the default template if it doesn't exist
    final var fileContentsBuilder = generateUpdatedMetadataFile(
        readMetadataFile(metadataFile),
        new MetadataUpdates.Builder(userId).build(),
        MetadataMergeBehavior.deepMerge);

    // Unset the specified keys
    for (final var key : keysToUnset) {
      if (key.startsWith("user.")) {
        final var userObject = fileContentsBuilder.getUser();
        if (userObject == null) continue;

        // Get the entire path to the nested key
        final var path = new ArrayList<>(Arrays.asList(key.split("\\.")));
        // Remove index 0 ("user")
        path.removeFirst();
        // Remove the specified key
        fileContentsBuilder.user(removeKey(userObject, path));
      } else {
        final var mKey = MetadataKeys.valueOf(key);
        switch (mKey) {
          case user -> fileContentsBuilder.user(null);
          case readOnly -> fileContentsBuilder.readOnly(null);
          case createdBy -> fileContentsBuilder.createdBy(null);
          case createdAt -> fileContentsBuilder.createdAt(null);
          case lastEditedAt -> fileContentsBuilder.lastEditedAt(null);
          case lastEditedBy -> fileContentsBuilder.lastEditedBy(null);
          case version -> fileContentsBuilder.version(null);
        }
      }
    }

    // Write out the updated file; readOnly lives in runtime state, lastEdited* is derived and has nothing to unset
    writeMetadataFile(root, fileContentsBuilder.build(), metadataFile);
    if (keysToUnset.contains(MetadataKeys.readOnly.name())) {
      final var key = WorkspacePaths.key(root, resolveReadingPath(root, filePath));
      updateState(root, s -> s.setReadOnly(key, null));
    }
    return true;
  }

  /**
   * Helper method to remove a key n-levels deep within a JSON Object
   * @param object the original JSON Object
   * @param path the path to the key
   * @return A new JSON Object with the key removed
   */
  private JsonObject removeKey(JsonObject object, List<String> path) {
    if(object == null) return null;

    if(object.containsKey(path.getFirst())) {
      final var key = path.removeFirst();

      // Base case: `key` is the last element
      if(path.isEmpty()) {
        return Json.createObjectBuilder(object).remove(key).build();
      }

      // Base case: `key` isn't a JsonObject
      if(!(object.get(key) instanceof JsonObject)) return object;

      // Recurse case: Look for the key in the nested object
      final var newSubObject = removeKey(object.getJsonObject(key), path);
      // Construct with top level object
      return Json.createObjectBuilder(object)
                 .add(key, newSubObject)
                 .build();
    }
    // If the key isn't present, stop
    return object;
  }


  @Override
  public boolean deleteMetadataFile(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException
  {
    history.requireLocked(workspaceId);
    final var root = roots.workspaceRootPath(workspaceId);
    final var metadataFilePath = resolveMetadataPath(root, filePath);
    // Deleting a file's metadata also clears its runtime state (readOnly), as it did when readOnly lived in the sidecar
    final var key = WorkspacePaths.key(root, resolveReadingPath(root, filePath));
    updateState(root, s -> s.remove(key));
    // If the file already doesn't exist, silently succeed
    if(!metadataFilePath.toFile().exists()) {
      return true;
    }
    return rm(metadataFilePath.toFile());
  }
  //endregion
}
