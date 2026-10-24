package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataKeys;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataMergeBehavior;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.versioning.LastEdit;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import io.javalin.http.UploadedFile;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.stream.JsonGenerator;

import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.resolveWritingPath;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.resolveReadingPath;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.resolveMetadataPath;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.tempFile;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.walk;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.writeAtomically;

public class WorkspaceFileSystemService implements WorkspaceService {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceFileSystemService.class);

  // Configure how the Metadata JSONs are written
  private static final Map<String,String> config = Map.of(JsonGenerator.PRETTY_PRINTING, "");

  final WorkspaceRepository workspaceRepository;
  final WorkspaceGitService gitService;
  private final WorkspaceRevisions revisions = new WorkspaceRevisions();

  public WorkspaceFileSystemService(final WorkspaceRepository workspaceRepository, final WorkspaceGitService gitService) {
    this.workspaceRepository = workspaceRepository;
    this.gitService = gitService;
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
  public boolean checkFileExists(final Workspace workspace, final Path filePath) {
    return resolveReadingPath(workspace, filePath).toFile().exists();
  }

  @Override
  public boolean isDirectory(final Workspace workspace, final Path filePath) {
    return Files.isDirectory(resolveReadingPath(workspace, filePath));
  }

  @Override
  public RenderType getFileType(final Path filePath) throws SQLException {
    final var fileName = filePath.getFileName().toString();
    return RenderType.getRenderType(fileName, workspaceRepository.getExtensionMapping());
  }

  //region Workspace Operations
  @Override
  public Optional<Integer> createWorkspace(final Path workspaceLocation, final String workspaceName, String username, int parcelId) {
    final var wsRootPath = workspaceRepository.getBaseRepositoryPath().resolve(workspaceLocation).normalize();
    final Workspace workspace;

    if (!wsRootPath.toFile().mkdirs()) return Optional.empty();

    try {
      final int workspaceId = workspaceRepository.createWorkspace(
          workspaceLocation.toString(), workspaceName, username, parcelId);
      workspace = new Workspace(workspaceId, wsRootPath);
    } catch (SQLException ex) {
      logger.error("CREATE WORKSPACE: Unable to create database entry for {}", wsRootPath, ex);
      return Optional.empty();
    }

    try {
      gitService.initializeWorkspace(workspace);
    } catch (IOException | GitAPIException ex) {
      logger.error("CREATE WORKSPACE: Unable to initialize workspace history at {}", wsRootPath, ex);
      return Optional.empty();
    }
    return Optional.of(workspace.id());
  }

  @Override
  public Workspace getWorkspace(final int workspaceId) throws SQLException, NoSuchWorkspaceException {
    return workspaceRepository.getWorkspace(workspaceId);
  }

  @Override
  public boolean deleteWorkspace(final Workspace workspace) throws SQLException {
    // Only remove DB entry if the files were successfully deleted
    // This allows the user to attempt deleting via this endpoint again
    if(rmDirectory(workspace.rootPath().toFile())) {
      return workspaceRepository.deleteWorkspace(workspace);
    }
    return false;
  }
  //endregion

  //region File Operations
  @Override
  public FileStream loadFile(final Workspace workspace, final Path filePath)
  throws IOException, NoSuchFileException, WorkspaceFileOpException
  {
    final var path = resolveReadingPath(workspace, filePath);
    final var file = path.toFile();

    if(Files.isDirectory(path)) {
      throw new WorkspaceFileOpException("Cannot get the file contents of a directory.");
    }
    if(RenderType.isAerieMetadataFile(file.getName())) {
      throw new WorkspaceFileOpException("Cannot load a metadata file directly.");
    }
    if(!Files.exists(path)) {
      throw new NoSuchFileException(workspace, filePath);
    }

    return new FileStream(new FileInputStream(file), file.getName(), Files.size(file.toPath()), getETag(path));
  }

  @Override
  public String getETag(final Workspace workspace, final Path filePath)
  throws IOException, NoSuchFileException, WorkspaceFileOpException {
    final var path = resolveReadingPath(workspace, filePath);
    if(Files.isDirectory(path)) {
      throw new WorkspaceFileOpException("Cannot compute the Entity Tag for a directory.");
    }
    if(!Files.exists(path)) {
      throw new NoSuchFileException(workspace, filePath);
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
  public LastEditInfo getLastEditInfo(final Workspace workspace, final Path filePath)
  throws IOException, WorkspaceFileOpException
  {
    final var metadata = effectiveMetadata(workspace, filePath);
    return new LastEditInfo(
        metadata.getString(MetadataKeys.lastEditedBy.name(), null),
        metadata.getString(MetadataKeys.lastEditedAt.name(), null));
  }

  @Override
  public Optional<String> saveFile(final Workspace workspace, final Path filePath, final UploadedFile file, final String userId)
  throws WorkspaceFileOpException, IOException
  {
    gitService.requireLocked(workspace);
    final var path = resolveWritingPath(workspace, filePath);
    resolveMetadataPath(workspace, filePath); // validates the path can carry metadata
    // lastEdited* is derived from history. The builder's edit attribution seeds createdBy/createdAt for a new file.
    final var metadataUpdates = new MetadataUpdates.Builder(userId).build();

    if(Files.isDirectory(path)) return Optional.empty();

    final var etag = writeContent(workspace, path, file.content());
    updateMetadata(workspace, filePath, metadataUpdates, MetadataMergeBehavior.deepMerge);
    return Optional.of(etag);
  }

  /**
   * Stream to a temp file, hashing as we go so the returned ETag matches what we wrote, then rename it into place
   * so lock-free readers see the old or the new bytes, never a partial file. Overwriting keeps the file's
   * permissions (notably the executable bit, which Git versions), so a write changes content only.
   */
  private static String writeContent(final Workspace workspace, final Path path, final InputStream content) throws IOException {
    final var md = WorkspaceService.newSHA256Digest();
    final var tmp = tempFile(workspace);
    try {
      Files.createDirectories(path.getParent());
      try (final var contentStream = new DigestInputStream(content, md)) {
        Files.copy(contentStream, tmp);
      }
      if (Files.exists(path) && path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(path));
      }
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
    return WorkspaceService.eTagFromDigest(md.digest());
  }

  @Override
  public boolean moveFile(
      final Workspace sourceWorkspace,
      final Path sourceFilePath,
      final Workspace destWorkspace,
      final Path destFilePath,
      final String userId)
  throws WorkspaceFileOpException, IOException
  {
    gitService.requireLocked(sourceWorkspace, destWorkspace);
    final var oldPath = resolveReadingPath(sourceWorkspace, sourceFilePath);
    final var oldMetadataPath = resolveMetadataPath(sourceWorkspace, sourceFilePath);

    final var newPath = resolveWritingPath(destWorkspace, destFilePath);
    final var newMetadataPath = resolveMetadataPath(destWorkspace, destFilePath);

    // The moved file keeps its own metadata, never that of a file it overwrites
    if(Files.exists(oldMetadataPath)) {
      Files.move(oldMetadataPath, newMetadataPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      markWritten(newMetadataPath); // updateMetadata below skips the write when nothing changes
    } else {
      Files.deleteIfExists(newMetadataPath);
    }
    // Ensure the moved file has a sidecar; lastEdited* itself comes from the move's commit.
    final var metadataUpdates = new MetadataUpdates.Builder(userId).build();
    updateMetadata(destWorkspace, destFilePath, metadataUpdates, MetadataMergeBehavior.deepMerge);

    Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    markWritten(newPath);
    return true;
  }

  @Override
  public boolean copyFile(
      final Workspace sourceWorkspace,
      final Path sourceFilePath,
      final Workspace destWorkspace,
      final Path destFilePath,
      final String userId)
  throws WorkspaceFileOpException, IOException
  {
    // Source workspace lock is required to prevent the item being copied from changing mid-transaction
    gitService.requireLocked(sourceWorkspace, destWorkspace);
    final var sourcePath = resolveReadingPath(sourceWorkspace, sourceFilePath);
    final var sourceMetadataPath = resolveMetadataPath(sourceWorkspace, sourceFilePath);

    final var destPath = resolveWritingPath(destWorkspace, destFilePath);
    final var destMetadataPath = resolveMetadataPath(destWorkspace, destFilePath);

    // Do not copy the file if the source file does not exist
    if (!Files.exists(sourcePath)) {
      throw new WorkspaceFileOpException("Source file \"%s\" in workspace %d does not exist.".formatted(
          sourceFilePath,
          sourceWorkspace.id()));
    }
    // Create any parent directories that don't already exist
    Files.createDirectories(destPath.getParent());
    copyAsNewFile(sourcePath, sourceMetadataPath, destWorkspace, destPath, destMetadataPath, userId, Instant.now());
    return true;
  }

  /**
   * Copy one content file as a new file in the destination, whether it is copied on its own or inside a directory:
   * its sidecar's semantic metadata (version and user) comes along, createdBy and createdAt are the copier and the
   * copy time, readOnly does not carry over, and lastEdited* is derived from
   * the destination's commit. Nothing is inherited from a file the copy overwrites. The content keeps the source's
   * permissions (a copy of an executable is executable).
   */
  private void copyAsNewFile(
      final Path sourcePath,
      final Path sourceMetadataPath,
      final Workspace destWorkspace,
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
    final var metadataUpdates = new MetadataUpdates.Builder(userId)
        .createdAt(now)
        .createdBy(userId)
        .readOnly(false)
        .lastEditedAt(now)
        .lastEditedBy(userId)
        .build();
    updateMetadata(destWorkspace, destWorkspace.rootPath().normalize().relativize(destPath), metadataUpdates, MetadataMergeBehavior.deepMerge);
    Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    markWritten(destPath);
  }

  /**
   * Give a file moved or copied into place the current time. Both keep the source's time, so a file overwriting
   * another of the same size could match the index's record of the old bytes, and staging, which trusts size and time,
   * would not commit it.
   */
  private static void markWritten(final Path path) throws IOException {
    Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
  }

  @Override
  public boolean deleteFile(final Workspace workspace, final Path filePath)
  throws WorkspaceFileOpException
  {
    gitService.requireLocked(workspace);
    final var path = resolveReadingPath(workspace, filePath);
    final var file = path.toFile();
    final var metadataFile = resolveMetadataPath(workspace, filePath).toFile();

    // Delete the metadata file if it exists
    if(metadataFile.exists()) {
      if(!rm(metadataFile)){
        logger.warn("DELETE FILE: Unable to delete metadata file {}", metadataFile);
        return false;
      }
    }

    return rm(file);
  }
  //endregion

  //region Directory Operations
  @Override
  public DirectoryTree listFiles(final Workspace workspace, final Path directoryPath, final int depth, final boolean withMetadata)
  throws SQLException, IOException, WorkspaceFileOpException
  {
    final var path = resolveReadingPath(workspace, directoryPath);
    if(!Files.isDirectory(path)) {
      return null;
    }
    // Convert to our API from the Files API
    final var walkDepth = depth == -1 ? Integer.MAX_VALUE : depth + 1;
    // Walk the tree
    final var walkList = walk(path, walkDepth); // never includes .git or .seqdev
    final var map = withMetadata
        ? metadataMap(workspace, walkList)
        : new HashMap<Path, Pair<Path, LastEdit>>();
    return new DirectoryTree(path, walkList, workspaceRepository.getExtensionMapping(), map);
  }

  @Override
  public boolean createDirectory(final Workspace workspace, final Path directoryPath)
  throws IOException {
    gitService.requireLocked(workspace);
    Files.createDirectories(resolveWritingPath(workspace, directoryPath));
    return true;
  }

  @Override
  public boolean moveDirectory(
      final Workspace sourceWorkspace,
      final Path sourceDirectoryPath,
      final Workspace destWorkspace,
      final Path destDirectoryPath
  ) throws IOException, WorkspaceFileOpException {
    gitService.requireLocked(sourceWorkspace, destWorkspace);
    final var sourcePath = resolveReadingPath(sourceWorkspace, sourceDirectoryPath);
    final var destPath = resolveWritingPath(destWorkspace, destDirectoryPath);

    // Do not permit the source workspace's root directory to be moved
    if(Files.isSameFile(sourcePath, sourceWorkspace.rootPath())) {
      throw new WorkspaceFileOpException("Cannot move the workspace root directory.");
    }

    // Do not permit a moved directory to replace the target workspace's root directory
    if (Files.exists(destPath) && Files.isSameFile(destPath, destWorkspace.rootPath())) {
      throw new WorkspaceFileOpException("Cannot replace the workspace root directory.");
    }

    // Do not try to move a directory into itself
    if(sourceWorkspace == destWorkspace && destPath.startsWith(sourcePath)){
      throw new WorkspaceFileOpException("Cannot move a directory into itself.");
    }

    return sourcePath.toFile().renameTo(destPath.toFile());
  }

  @Override
  public boolean copyDirectory(
      final Workspace sourceWorkspace,
      final Path sourceFilePath,
      final Workspace destWorkspace,
      final Path destFilePath,
      final String userId)
  throws WorkspaceFileOpException
  {
    // Source workspace lock is required to prevent the item being copied from changing mid-transaction
    gitService.requireLocked(sourceWorkspace, destWorkspace);
    final var sourcePath = resolveReadingPath(sourceWorkspace, sourceFilePath);
    final var destPath = resolveWritingPath(destWorkspace, destFilePath);

    // The root owns the workspace's history and internal files; copying "the whole workspace" is not a file operation
    if (sourcePath.equals(sourceWorkspace.rootPath())) {
      throw new WorkspaceFileOpException("Cannot copy the workspace root directory.");
    }

    try {
      // Validate source exists and is a directory
      if (!Files.exists(sourcePath)) {
        throw new WorkspaceFileOpException("Source directory \"%s\" in workspace %d does not exist."
                                               .formatted(sourceFilePath, sourceWorkspace.id()));
      }
      if (!Files.isDirectory(sourcePath)) {
        throw new WorkspaceFileOpException("Source directory \"%s\" in workspace %d is not a directory."
                                               .formatted(sourceFilePath, sourceWorkspace.id()));
      }

      // Do not permit a copied directory to replace the target workspace's root directory
      if (Files.exists(destPath) && Files.isSameFile(destPath, destWorkspace.rootPath())) {
        throw new WorkspaceFileOpException("Cannot replace the workspace root directory.");
      }

      // Do not try to copy a directory into itself
      if(sourceWorkspace == destWorkspace && destPath.startsWith(sourcePath)){
        throw new WorkspaceFileOpException("Cannot copy a directory into itself.");
      }

      // Every content file is copied exactly as copyFile copies one; sidecars travel with their content file, and a
      // sidecar whose content file is gone is metadata of nothing and is not copied.
      final var now = Instant.now();
      Files.createDirectories(destPath);
      for (final var source : walk(sourcePath, Integer.MAX_VALUE)) {
        final Path target = destPath.resolve(sourcePath.relativize(source));
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else if (!RenderType.isAerieMetadataFile(source.getFileName().toString())) {
          final var sidecarName = RenderType.toMetadataFileName(source.getFileName().toString());
          copyAsNewFile(source, source.resolveSibling(sidecarName), destWorkspace, target, target.resolveSibling(sidecarName), userId, now);
        }
      }
      return true;
    } catch (IOException e) {
      logger.error("Error copying directory", e);
      return false;
    }
  }

  @Override
  public boolean deleteDirectory(final Workspace workspace, final Path directoryPath)
  throws WorkspaceFileOpException
  {
    gitService.requireLocked(workspace);
    final var path = resolveReadingPath(workspace, directoryPath);
    // Deleting the root would delete the workspace's history; that is deleteWorkspace's job
    if (path.equals(workspace.rootPath())) {
      throw new WorkspaceFileOpException("Cannot delete the workspace root directory.");
    }
    return rmDirectory(path.toFile());
  }

  /** Whether a file's metadata marks it read-only. */
  @Override
  public boolean isReadOnly(final Workspace workspace, final Path filePath)
  throws WorkspaceFileOpException, IOException, JsonException
  {
    final var metadataPath = resolveMetadataPath(workspace, filePath); // rejects directories and metadata files
    return readMetadataFile(metadataPath.toFile()).getBoolean(MetadataKeys.readOnly.name(), false);
  }

  @Override
  public List<Path> getReadOnlyFiles(final Workspace workspace, final Path dirPath)
  throws IOException, WorkspaceFileOpException, JsonException, SQLException
  {
    final var directoryPath = resolveReadingPath(workspace, dirPath);

    if(!Files.isDirectory(directoryPath)) {
      if(isReadOnly(workspace, dirPath)) {
        return List.of(directoryPath);
      }
      return List.of();
    }

    return listFiles(workspace, dirPath, -1, true).readOnlyNodes();
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
   * Map each Path in resolvedPaths to a pair containing the Path to its metadata file and the last git edit
   */
  private Map<Path, Pair<Path, LastEdit>> metadataMap(final Workspace workspace, final List<Path> resolvedPaths)
  throws IOException, WorkspaceHistoryException, WorkspaceFileOpException
  {
    final var map = new HashMap<Path, Pair<Path, LastEdit>>();

    // Filter out folders and metadata files from the input set, as neither of them have metadata
    final var filteredInputs = resolvedPaths
        .stream()
        .filter(p -> !Files.isDirectory(p) && !RenderType.isAerieMetadataFile(p.getFileName().toString()))
        .collect(Collectors.toSet());
    final var lastEditsMap = gitService.lastEdits(workspace, filteredInputs);

    for (final var path : resolvedPaths) {
      if(Files.isDirectory(path) || RenderType.isAerieMetadataFile(path.getFileName().toString())) {
        continue;
      }
      final var metadataPath = resolveMetadataPath(workspace, workspace.rootPath().relativize(path));
      final var edit = lastEditsMap.get(path);
      map.put(path, Pair.of(metadataPath, edit));
    }

    return map;
  }

  private JsonObject effectiveMetadata(final Workspace workspace, final Path filePath)
  throws IOException, WorkspaceFileOpException
  {
    final var path = resolveReadingPath(workspace, filePath);
    final var metadataPath = resolveMetadataPath(workspace, filePath);

    final var metadataFile = readMetadataFile(metadataPath.toFile());

    final var lastEditInfo = gitService.lastEdit(workspace, path);
    return WorkspaceGitService.applyLastEdit(metadataFile, lastEditInfo);
  }

  @Override
  public FileStream loadMetadataFile(final Workspace workspace, final Path filePath)
  throws IOException, WorkspaceFileOpException
  {
    final var metadataFile = resolveMetadataPath(workspace, filePath).toFile();

    // If the file's metadata file doesn't exist, return a file containing just the current metadata file version
    if(!metadataFile.exists()) {
      return generateFallbackMetadataResponse(metadataFile.getName());
    }

    try {
      final var out = new StringWriter();
      try (final var writer = Json.createWriterFactory(config).createWriter(out)) {
        writer.writeObject(effectiveMetadata(workspace, filePath));
      }
      final var bytes = out.toString().getBytes(StandardCharsets.UTF_8);
      return new FileStream(
          new ByteArrayInputStream(bytes),
          metadataFile.getName(),
          bytes.length,
          getETag(workspace, filePath));
    } catch (NoSuchFileException nfe) {
      logger.error("Metadata file deleted mid-read.");
      return generateFallbackMetadataResponse(metadataFile.getName());
    }
  }

  @Override
  public boolean updateMetadataKeys(final Workspace workspace, final Path filePath, MetadataUpdates updates, MetadataMergeBehavior mergeBehavior)
  throws WorkspaceFileOpException, IOException, JsonException
  {
    gitService.requireLocked(workspace);
    updateMetadata(workspace, filePath, updates, mergeBehavior);
    return true;
  }

  /**
   * Apply metadata updates to the sidecar. lastEdited* remains stored as a baseline fallback but is derived from
   * history after the first content mutation. Caller holds the workspace lock.
   */
  private void updateMetadata(
      final Workspace workspace,
      final Path filePath,
      final MetadataUpdates updates,
      final MetadataMergeBehavior mergeBehavior)
  throws IOException, JsonException, WorkspaceFileOpException
  {
    final var metadataFile = resolveMetadataPath(workspace, filePath).toFile();
    final var newFileContents = generateUpdatedMetadataFile(readMetadataFile(metadataFile), updates, mergeBehavior).build();
    writeMetadataFile(workspace, metadataFile, newFileContents);
  }

  /**
   * Write a sidecar's stored fields. Skips the write when the bytes would not change.
   *
   * @param workspace The workspace root (for its temp directory; the write is an atomic replace).
   * @param metadataFile The File to be written to.
   * @param contents The contents of the metadata file to be written.
   * @throws IOException If the File cannot be written to for any reason
   */
  private void writeMetadataFile(final Workspace workspace, final File metadataFile, final MetadataUpdates contents) throws IOException {
    // Fill in "created" information, using the "metadataLastEdited" information as a fallback
    final var serialized = serializeSidecar(
        contents.version().orElse("1"),
        contents.createdBy().orElse(contents.metadataLastEditedBy()),
        contents.createdAt().orElse(contents.metadataLastEditedAt()).toString(),
        contents.lastEditedBy().orElse(null),
        contents.lastEditedAt().map(Instant::toString).orElse(null),
        contents.readOnly().orElse(null),
        contents.user().orElse(null));
    final var path = metadataFile.toPath();
    if (Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).equals(serialized)) return;
    writeAtomically(workspace, path, serialized.getBytes(StandardCharsets.UTF_8));
  }

  /** The on-disk (and committed) form of a sidecar. Null fields are omitted; a missing version defaults to "1". */
  static String serializeSidecar(
      final String version,
      final String createdBy,
      final String createdAt,
      final String lastEditedBy,
      final String lastEditedAt,
      final Boolean readOnly,
      final JsonObject user)
  {
    final var out = new StringWriter();
    try (final var generator = Json.createGeneratorFactory(config).createGenerator(out)) {
      generator.writeStartObject();
      generator.write("version", version == null ? "1" : version);
      if (createdBy != null) generator.write("createdBy", createdBy);
      if (createdAt != null) generator.write("createdAt", createdAt);
      if (lastEditedBy != null) generator.write("lastEditedBy", lastEditedBy);
      if (lastEditedAt != null) generator.write("lastEditedAt", lastEditedAt);
      if (readOnly != null) generator.write("readOnly", readOnly);
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
      final Workspace workspace,
      final Path filePath,
      final Set<String> keysToUnset,
      final String userId)
  throws WorkspaceFileOpException, IOException, JsonException
  {
    gitService.requireLocked(workspace);
    final var metadataFile = resolveMetadataPath(workspace, filePath).toFile();

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

    // Write out the updated sidecar. lastEdited* may remain as a baseline fallback but are ignored after a content edit.
    writeMetadataFile(workspace, metadataFile, fileContentsBuilder.build());
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
  public boolean deleteMetadataFile(final Workspace workspace, final Path filePath)
  throws WorkspaceFileOpException
  {
    gitService.requireLocked(workspace);
    final var metadataFilePath = resolveMetadataPath(workspace, filePath);
    // If the file already doesn't exist, silently succeed
    if(!metadataFilePath.toFile().exists()) {
      return true;
    }
    return rm(metadataFilePath.toFile());
  }
  //endregion

  //region Revisions
  @Override
  public WorkspaceRevisions.Revision createRevision(
      final Workspace workspace,
      final Path filePath,
      final String message,
      final String userId)
  throws NoSuchFileException, WorkspaceFileOpException, IOException, GitAPIException
  {
    gitService.requireLocked(workspace);
    final var root = workspace.rootPath();
    final var key = revisionKey(workspace, filePath);
    return revisions.create(root, key, message, userId, gitService.lastEdit(workspace, root.resolve(key)));
  }

  @Override
  public List<WorkspaceRevisions.Revision> listRevisions(final Workspace workspace, final Path filePath)
  throws NoSuchFileException, WorkspaceFileOpException, IOException
  {
    final var root = workspace.rootPath();
    return revisions.list(root, revisionKey(workspace, filePath));
  }

  @Override
  public Optional<WorkspaceRevisions.Revision> matchingRevision(
      final Workspace workspace, final Path filePath, final List<WorkspaceRevisions.Revision> revisionList)
  throws NoSuchFileException, WorkspaceFileOpException, IOException
  {
    return revisions.matching(workspace.rootPath(), revisionKey(workspace, filePath), revisionList);
  }

  @Override
  public Optional<WorkspaceRevisions.HistoricalFile> readRevision(final Workspace workspace, final Path filePath, final String name)
  throws NoSuchFileException, WorkspaceFileOpException, IOException
  {
    final var root = workspace.rootPath();
    final var revision = revisions.find(root, revisionKey(workspace, filePath), name);
    return revision.isEmpty() ? Optional.empty() : Optional.of(WorkspaceRevisions.read(root, revision.get()));
  }

  @Override
  public Optional<String> restoreRevision(final Workspace workspace, final Path filePath, final String name, final String userId)
  throws NoSuchFileException, WorkspaceFileOpException, IOException, JsonException
  {
    gitService.requireLocked(workspace);
    final var historical = readRevision(workspace, filePath, name);
    if (historical.isEmpty()) return Optional.empty();
    final var root = workspace.rootPath();
    final var etag = writeContent(workspace, resolveWritingPath(workspace, filePath), new ByteArrayInputStream(historical.get().content()));

    // Only the user-managed "user" field comes back; readOnly and the system fields stay as they are now.
    final var metadataFile = WorkspacePaths.resolveMetadataPath(workspace, filePath).toFile();
    final var restored = generateUpdatedMetadataFile(
        readMetadataFile(metadataFile),
        new MetadataUpdates.Builder(userId).build(),
        MetadataMergeBehavior.overwrite);
    restored.user(historical.get().metadata().get(MetadataKeys.user.name()) instanceof JsonObject user ? user : null);
    writeMetadataFile(workspace, metadataFile, restored.build());
    return Optional.of(etag);
  }

  /** The workspace key of an existing content file; directories and sidecars have no revisions. */
  private String revisionKey(final Workspace workspace, final Path filePath)
  throws NoSuchFileException, WorkspaceFileOpException
  {
    final var root = workspace.rootPath();
    final var path = WorkspacePaths.resolveReadingPath(workspace, filePath);
    if (RenderType.isAerieMetadataFile(path.getFileName().toString())) {
      throw new WorkspaceFileOpException("Metadata files do not have revisions.");
    }
    if (Files.isDirectory(path)) throw new WorkspaceFileOpException("Directories do not have revisions.");
    if (!Files.isRegularFile(path)) throw new NoSuchFileException(workspace, filePath);
    return WorkspacePaths.key(root, path);
  }
  //endregion
}
