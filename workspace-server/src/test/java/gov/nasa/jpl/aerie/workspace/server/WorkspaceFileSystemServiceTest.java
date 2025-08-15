package gov.nasa.jpl.aerie.workspace.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
                   service.resolveSubPath(root, Path.of("")));
      assertEquals(Path.of("/workspace/123"),
                   service.resolveSubPath(root, Path.of(".")));
      assertEquals(Path.of("/workspace/123/file.txt"),
                   service.resolveSubPath(root, Path.of("file.txt")));
      assertEquals(Path.of("/workspace/123/folder/subfolder/file.txt"),
                   service.resolveSubPath(root, Path.of("folder/subfolder/file.txt")));
      assertEquals(Path.of("/workspace/123/my/dir"),
                   service.resolveSubPath(root, Path.of("my/dir")));
      // ".." in path is technically allowed as long as it resolves inside root
      assertEquals(Path.of("/workspace/123/my/file.txt"),
                   service.resolveSubPath(root, Path.of("my/dir/../file.txt")));
    }

    @Test
    void absolutePathThrowsSecurityException() {
      // disallow resolving absolute subpath
      assertThrows(SecurityException.class, () ->
          service.resolveSubPath(Path.of("/workspace/123"), Path.of("/etc/passwd")));
    }

    @Test
    void pathTraversalThrowsSecurityException() {
      // disallow resolving subpaths outside of root
      assertThrows(SecurityException.class, () ->
          service.resolveSubPath(Path.of("/workspace/123"), Path.of("../../../etc/passwd")));
      assertThrows(SecurityException.class, () ->
          service.resolveSubPath(Path.of("/workspace/123"), Path.of("folder/../..//../etc/passwd")));
      assertThrows(SecurityException.class, () ->
          service.resolveSubPath(Path.of("/workspace/123"), Path.of("folder/../../workspace/123/../456/file.txt")));
    }
  }
}
