package gov.nasa.ammos.plandev.workspace.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspacePathsTest {
  @Test
  void createsNormalizedGitPathKey() {
    final var root = Path.of("workspace").toAbsolutePath().normalize();
    final var file = root.resolve("one").resolve("discarded").resolve("..").resolve("two.txt");

    assertEquals("one/two.txt", WorkspacePaths.key(root, file));
  }

  @Test
  void createsEmptyKeyForWorkspaceRoot() {
    final var root = Path.of("workspace").toAbsolutePath().normalize();

    assertEquals("", WorkspacePaths.key(root.resolve("."), root));
  }
}
