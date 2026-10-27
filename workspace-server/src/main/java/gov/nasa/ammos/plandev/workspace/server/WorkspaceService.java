package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataMergeBehavior;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import io.javalin.http.UploadedFile;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.json.JsonException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An interface that defines how the Aerie system can interact with the Workspaces backend.
 *
 * Every method that changes workspace content must be called inside {@link WorkspaceGitService#mutate}, which holds the
 * workspace lock and records the change in the workspace's Git history.
 */
public interface WorkspaceService {
  /**
   * Compute a byte array into a strong Entity Tag (lowercase hex) using the SHA-256 algorithm.
   * @param content a byte array containing the original (non-digested) content
   */
  static String computeETag(final byte[] content) {
    return eTagFromDigest(newSHA256Digest().digest(content));
  }

  /**
   * Quote a digest as a strong ETag (lowercase hex).
   * @param digestBytes the message digest to be quoted
   */
  static String eTagFromDigest(final byte[] digestBytes) {
    return "\"" + HexFormat.of().formatHex(digestBytes) + "\"";
  }

  /** A fresh SHA-256 digest (always available on the JVM). */
  static MessageDigest newSHA256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * A record containing a File ready to be streamed over the network
   * @param readingStream a stream of the file's contents
   * @param fileName the file's name, for the purpose of the "filename" header
   * @param fileSize the file's length, for the purpose of the "content-length" header
   * @param etag the file's entity version tag, generated as its SHA-256 checksum, for the purpose of the "ETag" header
   */
  record FileStream(InputStream readingStream, String fileName, long fileSize, String etag){}

  /**
   * Lightweight last-edit info read from a file's metadata, used to describe a save conflict.
   * Either field may be null if the metadata does not record it.
   * @param lastEditedBy the user who last edited the file
   * @param lastEditedAt the ISO-8601 timestamp of the last edit
   */
  record LastEditInfo(String lastEditedBy, String lastEditedAt){}

  /**
   * Create a new workspace
   * @param workspaceLocation the name of the root folder for the workspace
   * @param workspaceName the name of the workspace in the database
   * @param username the user who is creating the workspace
   * @param parcelId the parcel the workspace should load when reading sequencing files
   * @return an Optional containing the id of the new, history-managed workspace if it was created, otherwise empty
   */
  Optional<Integer> createWorkspace(Path workspaceLocation, String workspaceName, String username, int parcelId);

  /**
   * Get a Workspace object from its database id.
   * @param workspaceId the database id of the workspace
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   * @throws SQLException if the database entry for the workspace is unable to be fetched
   */
  Workspace getWorkspace(final int workspaceId) throws SQLException, NoSuchWorkspaceException;

  /**
   * Delete an existing workspace
   * @param workspace the workspace to be deleted
   * @return true if the workspace was deleted, otherwise false
   * @throws SQLException if the database entry for the workspace is unable to be deleted
   */
  boolean deleteWorkspace(final Workspace workspace) throws SQLException;

  /**
   * Check if the specified file exists
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   */
  boolean checkFileExists(final Workspace workspace, final Path filePath);

  /**
   * Check if the specified file is a directory
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   */
  boolean isDirectory(final Workspace workspace, final Path filePath);

  /**
   * Determine a given file's RenderType based on its file name
   * @param filePath the file to get the render type of
   * @return the file's RenderType
   * @throws SQLException If there is a database communication failure while getting the list of extension mappings
   */
  RenderType getFileType(final Path filePath) throws SQLException;

  /**
   * Load a file to send over as a multipart HTTP response
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   * @return a FileStream with a handler to the file's contents,
   *         as well as the values to fill in for the "filename", "content-length", and "ETag" headers
   * @throws IOException if an I/O error occurs while attempting to open the file
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   * @throws NoSuchFileException if the specified file does not exist
   * @throws WorkspaceFileOpException if a 'filePath' refers to a directory or metadata file
   */
  FileStream loadFile(final Workspace workspace, final Path filePath)
  throws IOException, NoSuchWorkspaceException, NoSuchFileException, WorkspaceFileOpException;

  /**
   * Get the current concurrency token (ETag) for a file.
   * Used to validate an If-Match precondition on save.
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   * @throws IOException if an I/O error occurs while reading the file
   * @throws NoSuchFileException if the specified file does not exist
   * @throws WorkspaceFileOpException if "filePath" refers to a directory
   */
  String getETag(final Workspace workspace, final Path filePath)
  throws IOException, NoSuchFileException, WorkspaceFileOpException;

  /**
   * Read the last-edit info (editor and timestamp) from a file's metadata, used to describe a save conflict.
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   * @throws IOException if the metadata file cannot be opened for reasons other than nonexistence
   * @throws WorkspaceFileOpException if "filePath" refers to a metadata file or directory
   */
  LastEditInfo getLastEditInfo(final Workspace workspace, final Path filePath)
  throws IOException, WorkspaceFileOpException;

  /**
   * Save an uploaded file to a workspace.
   *
   * @param workspace the workspace the file will be saved in
   * @param filePath the path, relative to the workspace's root, to save the file at
   * @param file the contents of the file to be saved
   * @param userId the userId of the user saving the file
   * @return the saved file's new concurrency token (ETag), or an empty Optional if it was not saved
   */
  Optional<String> saveFile(final Workspace workspace, final Path filePath, final UploadedFile file, final String userId)
  throws IOException, WorkspaceFileOpException;

  /**
   * Copy a file within a workspace or between workspaces.
   * @param sourceWorkspace the source workspace
   * @param sourceFilePath the path, relative to the workspace root, that the file is currently at
   * @param destWorkspace the destination workspace. this can be the same as sourceWorkspace
   * @param destFilePath the path of the copied file, relative to the new workspace root
   * @param userId the userId of the user making the change
   * @return true if the file was copied, false otherwise
   */
  boolean copyFile(
      final Workspace sourceWorkspace,
      final Path sourceFilePath,
      final Workspace destWorkspace,
      final Path destFilePath,
      final String userId)
  throws WorkspaceFileOpException, IOException;

  /**
   * Move a file within a workspace or between workspaces.
   *
   * @param sourceWorkspace the source workspace
   * @param sourceFilePath the path, relative to the source workspace root, that the file is currently at
   * @param destWorkspace the target workspace. this can be the same as sourceWorkspace
   * @param destFilePath the new path of the file, relative to the new workspace root
   * @param userId the userId of the user making the change
   * @return true if the file was moved, false otherwise
   */
  boolean moveFile(
      final Workspace sourceWorkspace,
      final Path sourceFilePath,
      final Workspace destWorkspace,
      final Path destFilePath,
      final String userId)
  throws WorkspaceFileOpException, IOException;

  /**
   * Delete a file from a workspace
   * @param workspace the workspace the file lives in
   * @param filePath the path, relative to the workspace's root, to the file to be deleted
   * @return true if the file was deleted, false otherwise
   */
  boolean deleteFile(final Workspace workspace, final Path filePath)
  throws WorkspaceFileOpException, IOException;

  /**
   * Get a DirectoryTree representing the contents of the directory down to the specified depth
   * @param workspace the workspace the directory lives in
   * @param directoryPath the path to the directory, relative to the workspace root
   * @param depth how many levels deep into the directory's subfolders to traverse.
   *              use -1 to traverse the whole tree.
   *              use 0 to just list the contents at the root of `directoryPath`
   * @param withMetadata whether to get the metadata of files within the workspace
   * @throws SQLException if there is a database communication failure while getting the list of extension mappings
   * @throws IOException if an I/O error occurs while opening 'directoryPath'
   */
  DirectoryTree listFiles(final Workspace workspace, final Path directoryPath, final int depth, final boolean withMetadata)
  throws SQLException, IOException, WorkspaceFileOpException;

  /**
   * Create a new directory within a workspace
   * @param workspace the workspace to create the directory in
   * @param directoryPath the path to the new directory, relative to the workspace root
   * @return true if the directory was created, false otherwise
   * @throws IOException if an I/O error occurs while creating the directory
   * @throws SecurityException if `directoryPath` is invalid (ie contains illegal characters)
   */
  boolean createDirectory(final Workspace workspace, final Path directoryPath)
  throws IOException, SecurityException;

  /**
   * Move a directory within a workspace or between workspaces.
   *
   * @param sourceWorkspace the source workspace
   * @param sourceDirectoryPath the path, relative to the source workspace root, of the directory
   * @param destWorkspace the target workspace. this can be the same as sourceWorkspace
   * @param destDirectoryPath the new path of the directory, relative to the new workspace root
   * @return true if the directory was moved, false otherwise
   */
  boolean moveDirectory(
      final Workspace sourceWorkspace,
      final Path sourceDirectoryPath,
      final Workspace destWorkspace,
      final Path destDirectoryPath)
  throws IOException, WorkspaceFileOpException;

  /**
   * Copy a directory within a workspace or between workspaces.
   *
   * @param sourceWorkspace the source workspace
   * @param sourceFilePath the path, relative to the workspace root, of the directory
   * @param destWorkspace the destination workspace. this can be the same as sourceWorkspaceId
   * @param destFilePath the path of the copied directory, relative to the new workspace root
   * @param userId the user copying the directory, recorded as the creator of every copied file
   * @return true if the directory was copied, false otherwise
   */
  boolean copyDirectory(
      final Workspace sourceWorkspace,
      final Path sourceFilePath,
      final Workspace destWorkspace,
      final Path destFilePath,
      final String userId)
  throws WorkspaceFileOpException;

  /**
   * Delete a directory within a workspace.
   * @param workspace the workspace the directory lives in
   * @param directoryPath the path to the directory
   * @return true if the directory was deleted, false otherwise
   * @throws WorkspaceFileOpException if `directoryPath` is the workspace root
   * @throws IOException if the directory cannot be deleted
   */
  boolean deleteDirectory(final Workspace workspace, final Path directoryPath)
  throws WorkspaceFileOpException, IOException;

  /**
   * Returns whether the file located at 'filePath' is marked as "readOnly" in its metadata.
   * @param workspace the id of the workspace the file lives in
   * @param filePath the path to the file whose metadata will be updated
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   * @throws IOException If the metadata file cannot be opened for reasons other than nonexistence
   * @throws JsonException If the file's metadata is malformed
   */
  boolean isReadOnly(final Workspace workspace, final Path filePath)
  throws WorkspaceFileOpException, IOException, JsonException;

  /**
   * Get the complete list of readOnly files contained at any level in the folder.
   * @param workspace the workspace the file lives in
   * @param directoryPath the path to the directory to check the files
   * @throws IOException If a metadata file within the directory cannot be opened for reasons other than nonexistence
   * @throws WorkspaceFileOpException If a metadata file is passed to this method
   * @throws JsonException If a metadata file within the directory is malformed
   * @throws SQLException If there is a database communication failure while getting the list of extension mappings
   */
  List<Path> getReadOnlyFiles(final Workspace workspace, Path directoryPath)
  throws IOException, WorkspaceFileOpException, JsonException, SQLException;

  /**
   * Retrieve the associated metadata file for the given file.
   * Returns an JSON file with only "version" specified in the event said file does not exist.
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file to get the metadata of
   */
  FileStream loadMetadataFile(final Workspace workspace, final Path filePath)
  throws IOException, WorkspaceFileOpException;

  /**
   * Update the specified metadata keys on a file's metadata. If a `user` update is specified, its contents will
   * be merged with the current contents of the `user` key according to the behavior specified by "mergeBehavior"
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file whose metadata will be updated
   * @param updates the set of updates to be applied
   * @param mergeBehavior how to merge the `user` object, if provided
   * @return true if the update was applied, false otherwise
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   * @throws IOException If the metadata file cannot be opened for reasons other than nonexistence
   * @throws JsonException If the file's metadata is currently malformed
   */
  boolean updateMetadataKeys(
      final Workspace workspace,
      final Path filePath,
      MetadataUpdates updates,
      MetadataMergeBehavior mergeBehavior)
  throws WorkspaceFileOpException, IOException, JsonException;

  /**
   * Unset the specified set of keys in a file's metadata.
   * Subobjects within the "user" object can be specified by following using a "dot-path" syntax, i.e. "user.status" or "user.info.name"
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file whose metadata will be updated
   * @param keysToUnset the set of keys to unset
   * @param userId the userId of the user making the change
   * @return true if the update was applied, false otherwise
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   * @throws IOException If the metadata file cannot be opened for reasons other than nonexistence
   * @throws JsonException If the file's metadata is currently malformed
   */
  boolean unsetMetadataKeys(final Workspace workspace, final Path filePath, Set<String> keysToUnset, String userId)
  throws WorkspaceFileOpException, IOException, JsonException;

  /**
   * Delete the PlanDev metadata file for the specified file
   * @param workspace the workspace the file lives in
   * @param filePath the path to the file whose metadata will be deleted
   * @return true if the metadata file was deleted or does not exist, false otherwise
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   */
  boolean deleteMetadataFile(final Workspace workspace, final Path filePath)
  throws WorkspaceFileOpException, IOException;

  /**
   * Record the file's committed state as its next revision. Makes no commit. Caller holds the workspace lock.
   * @param startAtRevision the name for the file's first revision instead of "a", or null
   * @return the new revision
   * @throws NoSuchFileException If the file does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory
   */
  WorkspaceRevisions.Revision createRevision(final Workspace workspace, final Path filePath, final String message, final String userId, final String startAtRevision)
  throws NoSuchFileException, WorkspaceFileOpException, IOException, GitAPIException;

  /**
   * The file's revisions, newest first, following same-workspace renames (see {@link WorkspaceRevisions}).
   * @throws NoSuchFileException If the file does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory
   */
  List<WorkspaceRevisions.Revision> listRevisions(final Workspace workspace, final Path filePath)
  throws NoSuchFileException, WorkspaceFileOpException, IOException;

  /**
   * The newest of the file's revisions whose content and metadata equal the file's saved state, or empty if none does.
   *
   * @param revisions the file's revisions, as returned by {@link #listRevisions}
   */
  Optional<WorkspaceRevisions.Revision> matchingRevision(
      final Workspace workspace, final Path filePath, final List<WorkspaceRevisions.Revision> revisions)
  throws NoSuchFileException, WorkspaceFileOpException, IOException;

  /**
   * The content and committed metadata of one of the file's revisions, or empty if it has no revision by that name.
   * @throws NoSuchFileException If the file does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory
   */
  Optional<WorkspaceRevisions.HistoricalFile> readRevision(final Workspace workspace, final Path filePath, final String name)
  throws NoSuchFileException, WorkspaceFileOpException, IOException;

  /**
   * Replace the file's content and the "user" field of its metadata with one of its revisions'. Caller holds the
   * workspace lock and has checked readOnly and If-Match.
   * @return the file's new ETag, or empty if it has no revision by that name
   * @throws NoSuchFileException If the file does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory
   */
  Optional<String> restoreRevision(final Workspace workspace, final Path filePath, final String name, final String userId)
  throws NoSuchFileException, WorkspaceFileOpException, IOException, JsonException;
}
