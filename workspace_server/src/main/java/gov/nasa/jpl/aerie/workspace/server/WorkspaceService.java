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

  Optional<Integer> createWorkspace(Path workspaceLocation, String workspaceName, String username, int parcelId);
  boolean deleteWorkspace(int workspaceId) throws NoSuchWorkspaceException, SQLException;


  /**
   * Check if the specified file exists
   * @param workspaceId the id of the workspace the file lives in
   * @param filePath the path to the file, relative to the workspace's root
   */
  boolean checkFileExists(final int workspaceId, final Path filePath) throws NoSuchWorkspaceException;

  RenderType getFileType(final Path filePath) throws SQLException;

  FileStream loadFile(final int workspaceId, final Path filePath) throws IOException, NoSuchWorkspaceException;

  /**
   * Save an uploaded file to a workspace
   * @param workspaceId the id of the workspace
   * @param filePath the path, relative to the workspace's root, to save the file at
   * @param file the contents of the file to be saved
   * @return true if the file was saved, false otherwise
   */
  boolean saveFile(final int workspaceId, final Path filePath, final UploadedFile file) throws IOException,
                                                                                               NoSuchWorkspaceException;

  /**
   * Delete a file from a workspace
   * @param workspaceId the id of the workspace
   * @param filePath the path, relative to the workspace's root, to the file to be deleted
   * @return true if the file was deleted, false otherwise
   */
  boolean deleteFile(final int workspaceId, final Path filePath) throws IOException, NoSuchWorkspaceException;


  DirectoryTree listFiles(final int workspaceId, final Optional<Path> directoryPath, final int depth) throws SQLException,
                                                                                                             NoSuchWorkspaceException;

  boolean createDirectory(int workspaceId, Path directoryPath) throws IOException, NoSuchWorkspaceException;
  boolean deleteDirectory(int workspaceId, Path directoryPath) throws IOException, NoSuchWorkspaceException;
}
