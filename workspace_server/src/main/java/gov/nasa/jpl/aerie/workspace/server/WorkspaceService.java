package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import io.javalin.http.UploadedFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

/**
 * An interface that defines how the Aerie system can interact with the Workspaces backend.
 */
public interface WorkspaceService {
  record FileStream(InputStream readingStream, String fileName, long fileSize){}

  Optional<Integer> createWorkspace(String workspaceLocation, String workspaceName);
  boolean deleteWorkspace(int workspaceId) throws NoSuchWorkspaceException;


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
   * @param workspaceId the id of the workspace
   * @param filePath the path, relative to the workspace's root, to save the file at
   * @param file the contents of the file to be saved
   * @return true if the file was saved, false otherwise
   */
  boolean saveFile(final int workspaceId, final Path filePath, final UploadedFile file)
  throws IOException, NoSuchWorkspaceException;
  /**
   * Move a file in a workspace to a new location in the workspace.
   * @param workspaceId the id of the workspace
   * @param oldFilePath the path, relative to the workspace's root, that the file is currently at
   * @param newFilePath the new path of the file
   * @return true if the file was moved, false otherwise
   */
  boolean moveFile(final int workspaceId, final Path oldFilePath, final Path newFilePath)
  throws NoSuchWorkspaceException, SQLException;

  /**
   * Delete a file from a workspace
   * @param workspaceId the id of the workspace
   * @param filePath the path, relative to the workspace's root, to the file to be deleted
   * @return true if the file was deleted, false otherwise
   */
  boolean deleteFile(final int workspaceId, final Path filePath) throws IOException, NoSuchWorkspaceException;


  DirectoryTree listFiles(final int workspaceId, final Optional<Path> directoryPath, final int depth) throws SQLException,
                                                                                                             NoSuchWorkspaceException;

  boolean createDirectory(final int workspaceId, final Path directoryPath) throws IOException, NoSuchWorkspaceException;
  boolean moveDirectory(final int workspaceId, final Path oldDirectoryPath, final Path newDirectoryPath)
  throws NoSuchWorkspaceException, IOException;
  boolean deleteDirectory(final int workspaceId, final Path directoryPath) throws IOException, NoSuchWorkspaceException;
}
