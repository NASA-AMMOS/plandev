package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.resolveReadingPath;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.resolveWritingPath;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.validatePath;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspacePathsTest {
  private final Path emptyPath = Path.of("/");
  final Workspace root = new Workspace(0, Path.of("/workspace/123"));

  @Test
  void createsNormalizedGitPathToWorkspaceRelativeGitPath() {
    final var file = root.rootPath()
                         .resolve("one")
                         .resolve("discarded")
                         .resolve("..")
                         .resolve("two.txt");
    assertEquals("one/two.txt", WorkspacePaths.toWorkspaceRelativeGitPath(root, file));
  }

  @Test
  void createsEmptyWorkspaceRelativeForToWorkspaceRootGitPath() {
    assertEquals("", WorkspacePaths.toWorkspaceRelativeGitPath(root, root.rootPath()));
  }

  @Nested
  class ResolveSubPathTests {
    @Test
    void validPaths() {
      assertEquals(Path.of("/workspace/123"),
                   resolveReadingPath(root, Path.of("")));
      assertEquals(Path.of("/workspace/123"),
                   resolveReadingPath(root, Path.of(".")));
      assertEquals(Path.of("/workspace/123/file.txt"),
                   resolveReadingPath(root, Path.of("file.txt")));
      assertEquals(Path.of("/workspace/123/folder/subfolder/file.txt"),
                   resolveReadingPath(root, Path.of("folder/subfolder/file.txt")));
      assertEquals(Path.of("/workspace/123/my/dir"),
                   resolveReadingPath(root, Path.of("my/dir")));
      // ".." in path is technically allowed as long as it resolves inside root
      assertEquals(Path.of("/workspace/123/my/file.txt"),
                   resolveReadingPath(root, Path.of("my/dir/../file.txt")));
    }

    @Test
    void absolutePathThrowsSecurityException() {
      // disallow resolving absolute subpath
      assertThrows(SecurityException.class, () ->
          resolveReadingPath(root, Path.of("/etc/passwd")));
    }

    @Test
    void pathTraversalThrowsSecurityException() {
      // disallow resolving subpaths outside of root
      assertThrows(SecurityException.class, () ->
          resolveReadingPath(root, Path.of("../../../etc/passwd")));
      assertThrows(SecurityException.class, () ->
          resolveReadingPath(root, Path.of("folder/../..//../etc/passwd")));
      assertThrows(SecurityException.class, () ->
          resolveReadingPath(root, Path.of("folder/../../workspace/123/../456/file.txt")));
    }
  }

  @Nested
  class ReservedPathTests {
    @ParameterizedTest
    @ValueSource(strings = {
        ".git", ".git/HEAD", ".git/config", ".git/refs/heads/main", "foo/.git", "foo/.git/config", ".GIT/HEAD",
        "a/../.git/HEAD", ".gitignore", "x/.gitattributes", ".gitmodules", ".seqdev", ".seqdev/state.json"})
    void reservedPathsAreRejected(String path) {
      assertThrows(SecurityException.class, () -> resolveReadingPath(root, Path.of(path)));
      assertThrows(SecurityException.class, () -> resolveWritingPath(root, Path.of(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".github/workflow.yml", "my.git", "git", "a/.gitkeep", ".seqdev2"})
    void similarNamesAreAllowed(String path) {
      assertDoesNotThrow(() -> resolveWritingPath(root, Path.of(path)));
    }
  }

  @Nested
  class ValidatePathTests {
    final static String[] reservedFileNames = {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        ".git", ".gitignore", ".gitattributes", ".gitmodules", ".seqdev"
    };
    final static String[] forbiddenChars = {
        // Normal forbidden characters
        "<", ">", ":", "\"", "\\", "|", "?", "*", "%", "#",
        // Unicode/ASCII 0-31 control characters
        // "\u0000" is not tested as it isn't permitted in a Path, meaning it cannot be passed to validatePath
        "\u0001", "\u0002", "\u0003", "\u0004", "\u0005", "\u0006", "\u0007", "\u0008", "\t",
        "\n", "\u000b", "\u000c", "\r", "\u000e", "\u000f",
        "\u0010", "\u0011", "\u0012", "\u0013", "\u0014", "\u0015", "\u0016", "\u0017", "\u0018", "\u0019",
        "\u001A", "\u001b", "\u001c", "\u001d", "\u001e", "\u001f",
        // Unicode 127-159 control characters
        "\u007F", "\u0080", "\u0081", "\u0082", "\u0083", "\u0084", "\u0085", "\u0086", "\u0087", "\u0088", "\u0089",
        "\u008A", "\u008B", "\u008C", "\u008D", "\u008E", "\u008F",
        "\u0090", "\u0091", "\u0092", "\u0093", "\u0094", "\u0095", "\u0096", "\u0097", "\u0098", "\u0099",
        "\u009A", "\u009B", "\u009C", "\u009D", "\u009E", "\u009F",
        };
    final static String[] trailingChars = {"foo ", "foo.", " ", "."};

    @ParameterizedTest
    @FieldSource("forbiddenChars")
    void validatePathForbiddenChars(String forbidden) {
      // These characters are not allowed on their own
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of(forbidden)));
      // These characters are not allowed as part of a longer file name
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("foobar" + forbidden)));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("file"+forbidden+".txt")));
      // There characters are not allowed as the last part of the path
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("folder", forbidden)));
      // There characters are not allowed as part of a folder name
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of(forbidden, "file")));
    }

    @ParameterizedTest
    @FieldSource("trailingChars")
    void validatePathTrailingChars(String forbidden) {
      // Trailing characters are not permitted at the end of a file
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of(forbidden)));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("foobar" + forbidden)));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("folder", forbidden)));

      // Trailing characters are permitted before an extension (as the extension makes them non-trailing)
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("file"+forbidden+".txt")));

      // Trailing characters are not permitted in folder names
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of(forbidden, "file")));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("folder"+forbidden, "file")));
    }

    @ParameterizedTest
    @FieldSource("reservedFileNames")
    void validatePathReservedFilenames(String reserved) {
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of(reserved)));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of(reserved+".txt")));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("folder/"+reserved)));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of("folder/"+reserved+".txt")));
      assertThrows(SecurityException.class, () -> validatePath(emptyPath, Path.of(reserved, "file.txt")));

      // They are allowed to be PART of the file name
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("myFile_"+reserved)));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("myFile_"+reserved+".txt")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("myFolder_"+reserved, "myFile.txt")));
    }

    /**
     * Forward slashes are considered a path delineator and will not throw
     */
    @Test
    void forwardSlash() {
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("/")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("foobar/")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("file/.txt")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("folder", "/")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("folder/")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("/", "file.txt")));
    }

    /**
     * Spaces and periods are allowed in the path so long as they are not trailing
     */
    @Test
    void validatePathPermitsSpacePeriod() {
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("my file")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("my folder","my file")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("my folder", " my file")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("my.file")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("my.folder","my.file")));
      assertDoesNotThrow(() -> validatePath(emptyPath, Path.of("my.folder",".my.file")));
    }
  }

}
