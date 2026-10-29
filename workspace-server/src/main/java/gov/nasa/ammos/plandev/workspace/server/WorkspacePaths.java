package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single source of truth for which names inside a workspace are internal to the workspace server.
 *
 * - .git holds the workspace's history repository (see {@link WorkspaceGitService}).
 * - .gitignore, .gitattributes and .gitmodules would change what Git tracks or how it rewrites file bytes, which would
 *   let a "clean" repository silently omit or alter user files. They are reserved in Phase 1; .gitignore may be relaxed
 *   once Git remote interoperability exists.
 * - .seqdev holds same-filesystem temporary files used for atomic replacement. It is never committed and is not part
 *   of the versioned workspace.
 *
 * Reserved names are rejected at every path segment, case-insensitively, by
 * {@link WorkspacePaths#resolveReadingPath}, so no user-facing file API can read or write them.
 * .meta.seqdev sidecars are SeqDev-managed but are not reserved here: they are addressed through the
 * metadata API, and the file API handlers reject them directly.
 */
public final class WorkspacePaths {
  private WorkspacePaths() {}

  public static final String GIT_DIR = ".git";
  public static final String INTERNAL_DIR = ".seqdev";

  /**
   * Reserved names from Windows (these are not permitted on Windows even if they have an extension):
   *    CON, PRN, AUX, NUL, COM1, COM2, COM3, COM4, COM5, COM6, COM7, COM8, COM9,
   *    LPT1, LPT2, LPT3, LPT4, LPT5, LPT6, LPT7, LPT8, LPT9
   *
   * Reserved names from Git:
   *    .git, .gitignore, .gitattributes, .gitmodules
   *
   * Reserved names from SeqDev:
   *   .seqdev
   */
  private static final Set<String> RESERVED_FILENAMES = Set.of(
      "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
      "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
      GIT_DIR, ".gitignore", ".gitattributes", ".gitmodules", INTERNAL_DIR);

  /**
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
   */
  private static final Pattern controlCharacters = Pattern.compile("([\u0000-\u001F]|[\u007F-\u009F])+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern forbiddenCharacters = Pattern.compile("([|<>:/\"?*%#\\\\])+", Pattern.UNICODE_CHARACTER_CLASS);

  private static final Pattern TEMP_FILE = Pattern.compile("tmp-\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}");

  /**
   * Returns whether the file name at the end of a Path is a reserved file name.
   */
  public static boolean filenameIsReserved(final Path path) {
    final var fileName = path.getFileName().toString();
    return RESERVED_FILENAMES.stream()
                             .anyMatch(rname -> rname.equalsIgnoreCase(fileName));
  }

  //region Path Resolution
  /**
   * Resolve and validate a relative path against a workspace root for the purpose of writing while ensuring the
   * result stays within the root directory.
   * Prevents path traversal attacks by rejecting absolute paths and any resolved path that escape the specified root.
   * @param workspace the workspace's identifier, containing its root path
   * @param filePath the untrusted path to resolve against the root
   * @return the resolved and normalized path, guaranteed to be within the root
   * @throws SecurityException if the input is absolute, if the resolved path escapes the root,
   *    or if the resolved path contains invalid characters or goes through a symbolic link
   */
  public static Path resolveWritingPath(final Workspace workspace, final Path filePath) throws SecurityException {
    return resolveReadingPath(workspace, filePath);
  }

  /**
   * Resolve and validate a relative path against a workspace root for reading or otherwise fetching a File while ensuring the
   *   result stays within the root directory.
   * Prevents path traversal attacks by rejecting absolute paths and any resolved path that escape the specified root.
   * @param workspace the workspace's identifier, containing its root path
   * @param filePath the untrusted path to resolve against the root
   * @return the resolved and normalized path, guaranteed to be within the root
   * @throws SecurityException if the input is absolute, if the resolved path escapes the root,
   *    or if the resolved path contains invalid characters or goes through a symbolic link
   */
  public static Path resolveReadingPath(final Workspace workspace, final Path filePath) throws SecurityException {
    // disallow absolute file paths, since Path.of("/foo").resolve(Path.of("/etc/passwd")) -> "/etc/passwd"
    if (filePath.isAbsolute()) {
      throw new SecurityException("Absolute file paths not allowed");
    }
    final var normalizedRootPath = workspace.rootPath().normalize();
    final var resolvedPath = normalizedRootPath.resolve(filePath).normalize();
    if (!resolvedPath.startsWith(normalizedRootPath)) {
      throw new SecurityException("Path traversal attempt detected");
    }

    validatePath(normalizedRootPath, resolvedPath);
    return resolvedPath;
  }

  /**
   * Fetches, resolves, and validates a relative path against a workspace root, then resolves and validates the path to
   *  that file's metadata file.
   * @param workspace the workspace's identifier, containing its root path
   * @param filePath the untrusted path to the file
   * @return the path to the metadata file for the specified file
   */
  public static Path resolveMetadataPath(final Workspace workspace, final Path filePath) throws WorkspaceFileOpException {
    final var baseFilePath = resolveReadingPath(workspace, filePath);

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
   * Validates that the path does not contain any invalid characters,
   * including forbidden characters and reserved file names.
   * @param path the Path to validate
   * @throws SecurityException if the path is invalid
   */
  public static void validatePath(final Path rootPath, final Path path) throws SecurityException {
    for (var p = path; p != null && !rootPath.equals(p); p = p.getParent()) {
      // Check that this section of the path is not a symlink
      if (Files.isSymbolicLink(p)) {
        throw new SecurityException(
            "Path '%s' is not allowed: '%s' is a symbolic link, which workspaces do not support."
                .formatted(path, p));
      }
      // Validate the file or directory name at the current end of this path
      final var segment = p.getFileName().toString();
      // Check for trailing period or space
      if(segment.endsWith(" ")) {
        throw new SecurityException("Path segment '"+ segment+ "' cannot end in a space.");
      }
      if(segment.endsWith(".")) {
        throw new SecurityException("Path segment '"+ segment+ "' cannot end in a period.");
      }

      // Check for control characters
      final var controlMatcher = controlCharacters.matcher(segment);
      if(controlMatcher.find()){
        throw new SecurityException("Path segment '"+ segment+ "' has illegal characters: "+controlMatcher.group());
      }

      // Check for forbidden characters
      final var forbiddenMatcher = forbiddenCharacters.matcher(segment);
      if(forbiddenMatcher.find()){
        throw new SecurityException("Path segment '"+ segment+ "' has illegal characters: "+forbiddenMatcher.group());
      }

      // Check that the segment is not a reserved filename:
      final var name = segment.startsWith(".") ? segment.split("(?=\\.)")[0] : segment.split("\\.")[0];
      if(RESERVED_FILENAMES.stream().anyMatch(r -> r.equalsIgnoreCase(name))){
        throw new SecurityException("Path segment '"+ segment+ "' contains reserved name: "+name);
      }
    }
  }
  //endregion

  // region Git Helpers
  /** Returns a workspace-relative Git-formatted Path.  */
  public static String toWorkspaceRelativeGitPath(final Workspace workspace, final Path absolutePath) {
    return toGitPath(workspace.rootPath().relativize(absolutePath.normalize()));
  }

  /**
   * Convert a regular Path object to a Git tree path.
   * Git tree paths always use '/', regardless of the host filesystem separator.
   */
  public static String toGitPath(final Path fileSystemPath) {
    final var oldSeparator = fileSystemPath.getFileSystem().getSeparator();
    if(oldSeparator.equals("/")) {
      return fileSystemPath.toString(); // No work needed
    }
    return fileSystemPath.toString().replace(oldSeparator, "/");
  }

  /** Get the path to the workspace's git directory */
  public static Path gitDirectory(final Workspace workspace) {
    return workspace.rootPath().resolve(WorkspacePaths.GIT_DIR);
  }

  /** Get the path to the workspace's internal SeqDev directory */
  public static Path internalDirectory(final Workspace workspace) {
    return workspace.rootPath().resolve(WorkspacePaths.INTERNAL_DIR);
  }
  // endregion

  /**
   * A fresh path for a server-owned temp file. It lives in the internal directory on the workspace's filesystem, so it
   * can be renamed atomically into the tree; ignored by Git, so a crash cannot leave stray content in the tree; and
   * named so {@link #removeStaleTempFiles} can recognize it.
   */
  public static Path tempFile(final Workspace workspace) throws IOException {
    final var dir = internalDirectory(workspace);
    Files.createDirectories(dir);
    return dir.resolve("tmp-" + UUID.randomUUID());
  }

  /** Replace target with bytes so readers, and a crash, see either the old or the new content. */
  public static void writeAtomically(final Workspace workspace, final Path target, final byte[] bytes) throws IOException {
    final var tmp = tempFile(workspace);
    try {
      Files.write(tmp, bytes);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** Delete temp files a crashed process left behind. Only exact {@link #tempFile} names are touched. */
  public static void removeStaleTempFiles(final Workspace workspace) throws IOException {
    final var dir = internalDirectory(workspace);
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
    try (final var entries = Files.list(dir)) {
      for (final var p : entries.toList()) {
        if (TEMP_FILE.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
          Files.delete(p);
        }
      }
    }
  }

  /**
   * Perform a depth-first walk of the file tree, excluding:
   *    - The starting directory
   *    - symbolic links (symlinks are not supported by Workspaces)
   *    - directories and files whose names are reserved (see {@link #RESERVED_FILENAMES} for a complete list of
   *      excluded files)
   * @param startingDirectory the directory to start the walk from
   * @param maxDepth how many levels deep into the directory's subfolders to traverse
   * @return A list containing all valid paths encountered during this walk
   */
  public static List<Path> walk(final Path startingDirectory, final int maxDepth) throws IOException {
    final var out = new ArrayList<Path>();
    Files.walkFileTree(startingDirectory, Set.of(), maxDepth, new SimpleFileVisitor<>() {
      @Override
      public @NotNull FileVisitResult preVisitDirectory(final @NotNull Path directory, final @NotNull BasicFileAttributes attrs) {
        if (directory.equals(startingDirectory)) {
          // Skip the starting path
          return FileVisitResult.CONTINUE;
        }
        if (filenameIsReserved(directory)) {
          // Skip directories with reserved names
          return FileVisitResult.SKIP_SUBTREE;
        }
        out.add(directory);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public @NotNull FileVisitResult visitFile(final @NotNull Path file, final @NotNull BasicFileAttributes attrs) {
        // Directories at maxDepth arrive here instead of preVisitDirectory; unfollowed symlinks arrive here too
        if (!attrs.isSymbolicLink() && !filenameIsReserved(file)) {
          out.add(file);
        }
        return FileVisitResult.CONTINUE;
      }
    });
    return out;
  }
}
