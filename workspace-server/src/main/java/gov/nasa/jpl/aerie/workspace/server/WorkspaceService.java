package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import io.javalin.http.UploadedFile;

import javax.json.JsonException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

/**
 * An interface that defines how the Aerie system can interact with the Workspaces backend.
 */
public interface WorkspaceService {
  record FileStream(InputStream readingStream, String fileName, long fileSize){}

  Optional<Integer> createWorkspace(Path workspaceLocation, String workspaceName, String username, int parcelId);
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

  RenderType getFileType(final Path filePath) throws SQLException;

  FileStream loadFile(final int workspaceId, final Path filePath) throws IOException, NoSuchWorkspaceException;

  /**
   * Save an uploaded file to a workspace
   *
   * @param workspaceId the id of the workspace
   * @param filePath the path, relative to the workspace's root, to save the file at
   * @param file the contents of the file to be saved
   * @param userId the userId of the user saving the file
   * @return true if the file was saved, false otherwise
   */
  boolean saveFile(final int workspaceId, final Path filePath, final UploadedFile file, final String userId)
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

  DirectoryTree listFiles(final int workspaceId, final Path directoryPath, final int depth, final boolean withMetadata)
  throws SQLException, NoSuchWorkspaceException, IOException;

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


  boolean deleteDirectory(final int workspaceId, final Path directoryPath) throws NoSuchWorkspaceException;

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
   * be deep-merged with the current contents of the `user` key.
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file whose metadata will be updated
   * @param updates the set of updates to be applied
   * @return true if the update was applied, false otherwise
   * @throws NoSuchWorkspaceException If the specified workspace does not exist
   * @throws WorkspaceFileOpException If "filePath" refers to a metadata file or directory, or if the metadata file for "filePath" is a directory
   * @throws IOException If the metadata file cannot be opened for reasons other than nonexistence
   * @throws JsonException If the file's metadata is currently malformed
   */
  boolean updateMetadataKeys(final int workspaceId, final Path filePath, MetadataUpdates updates)
  throws NoSuchWorkspaceException, WorkspaceFileOpException, IOException, JsonException;

  /**
   * Unset the specified set of keys in a file's metadata.
   * Subobjects within the "user" object can be specified by following using a "dot-path" syntax, i.e. "user.status"
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
