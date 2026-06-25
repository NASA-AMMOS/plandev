package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataMergeBehavior;
import io.javalin.http.UploadedFile;

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
 */
public interface WorkspaceService {
  /**
   * Compute a byte array into a strong Entity Tag (lowercase hex) using the SHA-256 algorithm.
   */
  static String computeETag(final byte[] content) {
    return eTagFromDigest(newSHA256Digest().digest(content));
  }

  /** Quote a digest as a strong ETag (lowercase hex). */
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
   */
  record FileStream(InputStream readingStream, String fileName, long fileSize){}

  /**
   * A file's bytes plus its ETag (a tag identifying this exact content).
   * @param content the file's bytes
   * @param etag the file's version tag
   */
  record FileContent(byte[] content, String etag){}

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
   * @return an Optional containing the id of the new workspace if it was created, otherwise an empty Optional
   */
  Optional<Integer> createWorkspace(Path workspaceLocation, String workspaceName, String username, int parcelId);

  /**
   * Delete an existing workspace
   * @param workspaceId the id of the workspace to be deleted
   * @return true if the workspace was deleted, otherwise false
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   * @throws SQLException if the database entry for the workspace is unable to be deleted
   */
  boolean deleteWorkspace(int workspaceId) throws NoSuchWorkspaceException, SQLException;

  /**
   * Check if the specified file exists
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   */
  boolean checkFileExists(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException;

  /**
   * Check if the specified file is a directory
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   */
  boolean isDirectory(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException;

  /**
   * Determine a given file's RenderType based on its file name
   * @param filePath the file to get the render type of
   * @return the file's RenderType
   * @throws SQLException If there is a database communication failure while getting the list of extension mappings
   */
  RenderType getFileType(final Path filePath) throws SQLException;

  /**
   * Load a file's bytes and its ETag (computed from those bytes).
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   * @return the file's bytes and its ETag
   * @throws IOException if an I/O error occurs while reading the file
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   */
  FileContent loadFileWithETag(final int workspaceId, final Path filePath) throws IOException, NoSuchWorkspaceException;

  /**
   * Get the current concurrency token (ETag) for a file.
   * Used to validate an If-Match precondition on save.
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   * @throws IOException if an I/O error occurs while reading the file
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   * @throws NoSuchFileException if the specified file does not exist
   * @throws WorkspaceFileOpException if "filePath" refers to a directory
   */
  String getETag(final int workspaceId, final Path filePath)
  throws IOException, NoSuchWorkspaceException, NoSuchFileException, WorkspaceFileOpException;

  /**
   * Read the last-edit info (editor and timestamp) from a file's metadata, used to describe a save conflict.
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   * @throws IOException if the metadata file cannot be opened for reasons other than nonexistence
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   * @throws WorkspaceFileOpException if "filePath" refers to a metadata file or directory
   */
  LastEditInfo getLastEditInfo(final int workspaceId, final Path filePath)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException;

  /**
   * Save an uploaded file to a workspace.
   *
   * @param workspaceId the id of the workspace
   * @param filePath the path, relative to the workspace's root, to save the file at
   * @param file the contents of the file to be saved
   * @param userId the userId of the user saving the file
   * @return the saved file's new concurrency token (ETag), or an empty Optional if it was not saved
   */
  Optional<String> saveFile(final int workspaceId, final Path filePath, final UploadedFile file, final String userId)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException;

  /**
   * Copy a file within a workspace or between workspaces.
   * @param sourceWorkspaceId the id of the source workspace
   * @param sourceFilePath the path, relative to the workspace root, that the file is currently at
   * @param destWorkspaceId the id of the destination workspace, note that this can be the same as sourceWorkspaceId
   * @param destFilePath the path of the copied file, relative to the new workspace root
   * @param userId the userId of the user making the change
   * @return true if the file was copied, false otherwise
   */
  boolean copyFile(
      final int sourceWorkspaceId,
      final Path sourceFilePath,
      final int destWorkspaceId,
      final Path destFilePath,
      final String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException;

  /**
   * Move a file within a workspace or between workspaces.
   * @param oldWorkspaceId the id of the source workspace
   * @param oldFilePath the path, relative to the source workspace root, that the file is currently at
   * @param newWorkspaceId the id of the target workspace, note that this can be the same as oldWorkspaceId
   * @param newFilePath the new path of the file, relative to the new workspace root
   * @param userId the userId of the user making the change
   * @return true if the file was moved, false otherwise
   */
  boolean moveFile(
      final int oldWorkspaceId,
      final Path oldFilePath,
      final int newWorkspaceId,
      final Path newFilePath,
      final String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException;

  /**
   * Delete a file from a workspace
   * @param workspaceId the id of the workspace
   * @param filePath the path, relative to the workspace's root, to the file to be deleted
   * @return true if the file was deleted, false otherwise
   */
  boolean deleteFile(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException;

  /**
   * Get a DirectoryTree representing the contents of the directory down to the specified depth
   * @param workspaceId the workspace the directory lives in
   * @param directoryPath the path to the directory, relative to the workspace root
   * @param depth how many levels deep into the directory's subfolders to traverse.
   *              use -1 to traverse the whole tree.
   *              use 0 to just list the contents at the root of `directoryPath`
   * @param withMetadata whether to get the metadata of files within the workspace
   * @throws SQLException if there is a database communication failure while getting the list of extension mappings
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   * @throws IOException if an I/O error occurs while opening 'directoryPath'
   */
  DirectoryTree listFiles(final int workspaceId, final Path directoryPath, final int depth, final boolean withMetadata)
  throws SQLException, NoSuchWorkspaceException, IOException;

  /**
   * Create a new directory within a workspace
   * @param workspaceId the workspace to create the directory in
   * @param directoryPath the path to the new directory, relative to the workspace root
   * @return true if the directory was created, false otherwise
   * @throws IOException if an I/O error occurs while creating the directory
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   * @throws WorkspaceFileOpException if `directoryPath` is invalid (ie contains illegal characters)
   */
  boolean createDirectory(final int workspaceId, final Path directoryPath)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException;

  /**
   * Move a directory within a workspace or between workspaces.
   * @param oldWorkspaceId the id of the source workspace
   * @param oldDirectoryPath the path, relative to the source workspace root, of the directory
   * @param newWorkspaceId the id of the target workspace, note that this can be the same as oldWorkspaceId
   * @param newDirectoryPath the new path of the directory, relative to the new workspace root
   * @return true if the directory was moved, false otherwise
   */
  boolean moveDirectory(final int oldWorkspaceId, final Path oldDirectoryPath, final int newWorkspaceId, final Path newDirectoryPath)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException;

  /**
   * Copy a directory within a workspace or between workspaces.
   * @param sourceWorkspaceId the id of the source workspace
   * @param sourceFilePath the path, relative to the workspace root, of the directory
   * @param destWorkspaceId the id of the destination workspace, note that this can be the same as sourceWorkspaceId
   * @param destFilePath the path of the copied directory, relative to the new workspace root
   * @return true if the directory was copied, false otherwise
   */
  boolean copyDirectory(final int sourceWorkspaceId, final Path sourceFilePath, final int destWorkspaceId, final Path destFilePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException;

  /**
   * Delete a directory within a workspace.
   * @param workspaceId the workspace the directory lives in
   * @param directoryPath the path to the directory
   * @return true if the directory was deleted, false otherwise
   * @throws NoSuchWorkspaceException if the specified workspace does not exist
   */
  boolean deleteDirectory(final int workspaceId, final Path directoryPath) throws NoSuchWorkspaceException;

  /**
   * Returns whether the file located at 'filePath' is marked as "readOnly" in its metadata.
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file whose metadata will be updated
   * @throws NoSuchWorkspaceException If the specified workspace does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   * @throws IOException If the metadata file cannot be opened for reasons other than nonexistence
   * @throws JsonException If the file's metadata is malformed
   */
  boolean isReadOnly(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException, JsonException;

  /**
   * Get the complete list of readOnly files contained at any level in the folder.
   * @param workspaceId the id of the workspace the file lives in
   * @param directoryPath the path to the directory to check the files
   * @throws NoSuchWorkspaceException If the specified workspace does not exist
   * @throws IOException If a metadata file within the directory cannot be opened for reasons other than nonexistence
   * @throws WorkspaceFileOpException If a metadata file is passed to this method
   * @throws JsonException If a metadata file within the directory is malformed
   * @throws SQLException If there is a database communication failure while getting the list of extension mappings
   */
  List<Path> getReadOnlyFiles(int workspaceId, Path directoryPath)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException, JsonException, SQLException;

  /**
   * Retrieve the associated metadata file for the given file.
   * Returns an JSON file with only "version" specified in the event said file does not exist.
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file to get the metadata of
   */
  FileStream loadMetadataFile(final int workspaceId, final Path filePath)
  throws IOException, NoSuchWorkspaceException, WorkspaceFileOpException;

  /**
   * Update the specified metadata keys on a file's metadata. If a `user` update is specified, its contents will
   * be merged with the current contents of the `user` key according to the behavior specified by "mergeBehavior"
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file whose metadata will be updated
   * @param updates the set of updates to be applied
   * @param mergeBehavior how to merge the `user` object, if provided
   * @return true if the update was applied, false otherwise
   * @throws NoSuchWorkspaceException If the specified workspace does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   * @throws IOException If the metadata file cannot be opened for reasons other than nonexistence
   * @throws JsonException If the file's metadata is currently malformed
   */
  boolean updateMetadataKeys(final int workspaceId, final Path filePath, MetadataUpdates updates, MetadataMergeBehavior mergeBehavior)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException, JsonException;

  /**
   * Unset the specified set of keys in a file's metadata.
   * Subobjects within the "user" object can be specified by following using a "dot-path" syntax, i.e. "user.status" or "user.info.name"
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file whose metadata will be updated
   * @param keysToUnset the set of keys to unset
   * @param userId the userId of the user making the change
   * @return true if the update was applied, false otherwise
   * @throws NoSuchWorkspaceException If the specified workspace does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   * @throws IOException If the metadata file cannot be opened for reasons other than nonexistence
   * @throws JsonException If the file's metadata is currently malformed
   */
  boolean unsetMetadataKeys(final int workspaceId, final Path filePath, Set<String> keysToUnset, String userId)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException, JsonException;

  /**
   * Delete the PlanDev metadata file for the specified file
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file whose metadata will be deleted
   * @return true if the metadata file was deleted or does not exist, false otherwise
   * @throws NoSuchWorkspaceException If the specified workspace does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   */
  boolean deleteMetadataFile(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, WorkspaceFileOpException;
}
