package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFileSystemServiceTest {

  private WorkspaceFileSystemService service;

  @BeforeEach
  void setUp() {
    service = new WorkspaceFileSystemService(null);
  }

  @Nested
  class ResolveSubPathTests {

    @Test
    void validPaths() {
      var root = Path.of("/workspace/123");

      assertEquals(Path.of("/workspace/123"),
                   service.resolveReadingPath(root, Path.of("")));
      assertEquals(Path.of("/workspace/123"),
                   service.resolveReadingPath(root, Path.of(".")));
      assertEquals(Path.of("/workspace/123/file.txt"),
                   service.resolveReadingPath(root, Path.of("file.txt")));
      assertEquals(Path.of("/workspace/123/folder/subfolder/file.txt"),
                   service.resolveReadingPath(root, Path.of("folder/subfolder/file.txt")));
      assertEquals(Path.of("/workspace/123/my/dir"),
                   service.resolveReadingPath(root, Path.of("my/dir")));
      // ".." in path is technically allowed as long as it resolves inside root
      assertEquals(Path.of("/workspace/123/my/file.txt"),
                   service.resolveReadingPath(root, Path.of("my/dir/../file.txt")));
    }

    @Test
    void absolutePathThrowsSecurityException() {
      // disallow resolving absolute subpath
      assertThrows(SecurityException.class, () ->
          service.resolveReadingPath(Path.of("/workspace/123"), Path.of("/etc/passwd")));
    }

    @Test
    void pathTraversalThrowsSecurityException() {
      // disallow resolving subpaths outside of root
      assertThrows(SecurityException.class, () ->
          service.resolveReadingPath(Path.of("/workspace/123"), Path.of("../../../etc/passwd")));
      assertThrows(SecurityException.class, () ->
          service.resolveReadingPath(Path.of("/workspace/123"), Path.of("folder/../..//../etc/passwd")));
      assertThrows(SecurityException.class, () ->
          service.resolveReadingPath(Path.of("/workspace/123"), Path.of("folder/../../workspace/123/../456/file.txt")));
    }
  }

  @Nested
  class ValidatePathTests {
    final static String[] reservedFileNames = {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
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
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of(forbidden)));
      // These characters are not allowed as part of a longer file name
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("foobar" + forbidden)));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("file"+forbidden+".txt")));
      // There characters are not allowed as the last part of the path
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("folder", forbidden)));
      // There characters are not allowed as part of a folder name
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of(forbidden, "file")));
    }

    @ParameterizedTest
    @FieldSource("trailingChars")
    void validatePathTrailingChars(String forbidden) {
      // Trailing characters are not permitted at the end of a file
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of(forbidden)));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("foobar" + forbidden)));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("folder", forbidden)));

      // Trailing characters are permitted before an extension (as the extension makes them non-trailing)
      assertDoesNotThrow(() -> service.validatePath(Path.of("file"+forbidden+".txt")));

      // Trailing characters are not permitted in folder names
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of(forbidden, "file")));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("folder"+forbidden, "file")));
    }

    @ParameterizedTest
    @FieldSource("reservedFileNames")
    void validatePathReservedFilenames(String reserved) {
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of(reserved)));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of(reserved+".txt")));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("folder/"+reserved)));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of("folder/"+reserved+".txt")));
      assertThrows(WorkspaceFileOpException.class, () -> service.validatePath(Path.of(reserved, "file.txt")));

      // They are allowed to be PART of the file name
      assertDoesNotThrow(() -> service.validatePath(Path.of("myFile_"+reserved)));
      assertDoesNotThrow(() -> service.validatePath(Path.of("myFile_"+reserved+".txt")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("myFolder_"+reserved, "myFile.txt")));
    }

    /**
     * Forward slashes are considered a path delineator and will not throw
     */
    @Test
    void forwardSlash() {
      assertDoesNotThrow(() -> service.validatePath(Path.of("/")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("foobar/")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("file/.txt")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("folder", "/")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("folder/")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("/", "file.txt")));
    }

    /**
     * Spaces and periods are allowed in the path so long as they are not trailing
     */
    @Test
    void validatePathPermitsSpacePeriod() {
      assertDoesNotThrow(() -> service.validatePath(Path.of("my file")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("my folder","my file")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("my folder", " my file")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("my.file")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("my.folder","my.file")));
      assertDoesNotThrow(() -> service.validatePath(Path.of("my.folder",".my.file")));
    }
  }

  @Nested
  class ETagTests {
    private static byte[] bytes(String s) {
      return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sameContentProducesSameToken() {
      assertEquals(
          WorkspaceService.computeETag(bytes("command ABC;")),
          WorkspaceService.computeETag(bytes("command ABC;")));
    }

    @Test
    void differentContentProducesDifferentToken() {
      assertNotEquals(
          WorkspaceService.computeETag(bytes("command ABC;")),
          WorkspaceService.computeETag(bytes("command XYZ;")));
    }

    @Test
    void tokenIsQuotedLowercaseSha256Hex() {
      final var token = WorkspaceService.computeETag(new byte[0]);
      // Strong ETag: a quoted, 64-char lowercase-hex SHA-256 digest.
      assertTrue(token.matches("\"[0-9a-f]{64}\""), "unexpected token format: " + token);
      // SHA-256 of empty input is a well-known constant; pins the algorithm and encoding.
      assertEquals("\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"", token);
    }
  }
}
