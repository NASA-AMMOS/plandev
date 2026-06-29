package gov.nasa.jpl.aerie.workspace.server;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Phase-1 file-versioning prototype end-to-end against a temp workspace directory:
 * migrate → create → list → preview → restore, plus per-file independent numbering, the shared SHA-256
 * "dirty" token, and the base-26 naming convention. Runs entirely on the root-based core (no DB), mirroring
 * how {@link WorkspaceFileSystemServiceTest} drives the service with a plain root path.
 */
class WorkspaceVersioningServiceTest {

  private WorkspaceFileSystemService fs;
  private WorkspaceVersioningService versioning;

  @BeforeEach
  void setUp() {
    fs = new WorkspaceFileSystemService(null);          // root-based paths don't touch Postgres
    versioning = new WorkspaceVersioningService(null, fs);
  }

  private static void write(final Path file, final String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static String token(final String content) {
    return WorkspaceVersioningService.contentToken(content.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  class RevisionNameTests {
    @Test
    void bijectiveBase26() {
      assertEquals("a", RevisionName.forNumber(1));
      assertEquals("b", RevisionName.forNumber(2));
      assertEquals("z", RevisionName.forNumber(26));
      assertEquals("aa", RevisionName.forNumber(27));
      assertEquals("ab", RevisionName.forNumber(28));
      assertEquals("az", RevisionName.forNumber(52));
      assertEquals("ba", RevisionName.forNumber(53));
      assertEquals("zz", RevisionName.forNumber(702));
      assertEquals("aaa", RevisionName.forNumber(703));
      assertThrows(IllegalArgumentException.class, () -> RevisionName.forNumber(0));
    }
  }

  @Test
  void migrateSeedsRevisionAForExistingFiles(@TempDir Path root) throws Exception {
    write(root.resolve("seq1.txt"), "alpha");

    assertTrue(versioning.migrate(root, "alice"));
    assertFalse(versioning.migrate(root, "alice"), "migration is idempotent");

    final var revisions = versioning.listRevisions(root, Path.of("seq1.txt"));
    assertEquals(1, revisions.size());
    final var a = revisions.get(0);
    assertEquals(1, a.number());
    assertEquals("a", a.name());
    assertEquals("seq1.txt", a.path());
    assertEquals(token("alpha"), a.contentHash(), "revision content hash == the edit-protection ETag of the bytes");
  }

  @Test
  void createListPreviewRestoreLifecycle(@TempDir Path root) throws Exception {
    final var path = Path.of("seq1.txt");
    final var file = root.resolve("seq1.txt");
    write(file, "alpha");
    versioning.migrate(root, "alice");

    // Edit the working copy and snapshot it as a new revision.
    Files.writeString(file, "beta");
    final var b = versioning.createRevision(root, path, Optional.empty(), Optional.of("second take"), "bob");
    assertEquals(2, b.number());
    assertEquals("b", b.name());
    assertEquals(token("beta"), b.contentHash());
    assertEquals("bob", b.author());
    assertEquals("second take", b.message());

    // History: a (alpha) then b (beta), oldest first.
    final var revisions = versioning.listRevisions(root, path);
    assertEquals(2, revisions.size());
    assertEquals("a", revisions.get(0).name());
    assertEquals(token("alpha"), revisions.get(0).contentHash());
    assertEquals("b", revisions.get(1).name());
    assertEquals(token("beta"), revisions.get(1).contentHash());

    // Preview any revision by number or by name.
    assertEquals("alpha", preview(root, path, "a"));
    assertEquals("alpha", preview(root, path, "1"));
    assertEquals("beta", preview(root, path, "b"));
    assertEquals("beta", preview(root, path, "2"));
    assertTrue(versioning.readRevision(root, path, "does-not-exist").isEmpty());

    // Restore to "a": working copy becomes alpha again; history is untouched (non-destructive).
    final var restore = versioning.restore(root, path, "a", "carol").orElseThrow();
    assertEquals(1, restore.number());
    assertEquals("a", restore.name());
    assertEquals(token("alpha"), restore.etag());
    assertEquals("alpha", Files.readString(file), "working copy was overwritten with the revision's bytes");
    assertEquals(2, versioning.listRevisions(root, path).size(), "restore did not delete any revisions");

    // The restored state can itself become a later revision (number keeps climbing).
    final var c = versioning.createRevision(root, path, Optional.empty(), Optional.empty(), "carol");
    assertEquals(3, c.number());
    assertEquals("c", c.name());
    assertEquals(token("alpha"), c.contentHash(), "identical content de-dupes to the same hash");
  }

  @Test
  void numberingIsIndependentPerFile(@TempDir Path root) throws Exception {
    write(root.resolve("seq1.txt"), "one");
    versioning.migrate(root, "alice");

    // Push seq1 up to revision c.
    Files.writeString(root.resolve("seq1.txt"), "one-b");
    versioning.createRevision(root, Path.of("seq1.txt"), Optional.empty(), Optional.empty(), "alice");
    Files.writeString(root.resolve("seq1.txt"), "one-c");
    versioning.createRevision(root, Path.of("seq1.txt"), Optional.empty(), Optional.empty(), "alice");

    // A brand-new file added after migration starts its own timeline at "a" (number 1).
    write(root.resolve("nested/seq2.txt"), "two");
    final var firstOfSeq2 = versioning.createRevision(root, Path.of("nested/seq2.txt"), Optional.empty(), Optional.empty(), "alice");
    assertEquals(1, firstOfSeq2.number());
    assertEquals("a", firstOfSeq2.name());

    assertEquals(3, versioning.listRevisions(root, Path.of("seq1.txt")).size());
    assertEquals(1, versioning.listRevisions(root, Path.of("nested/seq2.txt")).size());
  }

  @Test
  void dirtySinceLastRevisionUsesTheSharedToken(@TempDir Path root) throws Exception {
    final var path = Path.of("seq1.txt");
    final var file = root.resolve("seq1.txt");
    write(file, "alpha");
    versioning.migrate(root, "alice");

    final var latest = versioning.listRevisions(root, path).get(0);
    // Clean: the working-copy token equals the latest revision's stored content hash.
    assertEquals(latest.contentHash(), WorkspaceVersioningService.contentToken(Files.readAllBytes(file)));

    // Dirty: edit the working copy and the tokens diverge.
    Files.writeString(file, "alpha-edited");
    assertNotEquals(latest.contentHash(), WorkspaceVersioningService.contentToken(Files.readAllBytes(file)));
  }

  @Test
  void fileIdIsAssignedAndStable(@TempDir Path root) throws Exception {
    write(root.resolve("seq1.txt"), "alpha");
    versioning.migrate(root, "alice");

    final var fileId = versioning.listRevisions(root, Path.of("seq1.txt")).get(0).fileId();
    assertFalse(fileId.isBlank());

    // The same id rides every revision of that file.
    Files.writeString(root.resolve("seq1.txt"), "beta");
    final var b = versioning.createRevision(root, Path.of("seq1.txt"), Optional.empty(), Optional.empty(), "bob");
    assertEquals(fileId, b.fileId());
  }

  @Test
  void workingTreeIsCleanAfterCreatingARevision(@TempDir Path root) throws Exception {
    final var path = Path.of("seq1.txt");
    final var file = root.resolve("seq1.txt");
    write(file, "alpha");
    versioning.migrate(root, "alice");
    Files.writeString(file, "beta");
    versioning.createRevision(root, path, Optional.empty(), Optional.empty(), "bob");

    // Migration stages the baseline via a porcelain `git add`; createRevision commits via plumbing. The index
    // must be refreshed to the new HEAD afterward, or the repo looks perpetually dirty to Git tooling/mirror.
    try (Repository repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build()) {
      final var status = Git.wrap(repo).status().call();
      assertTrue(
          status.isClean(),
          "working tree + index should match HEAD after createRevision; uncommitted: " + status.getUncommittedChanges());
    }
  }

  @Test
  void snapshotWorkspaceCheckpointsDirtyFiles(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a1");
    write(root.resolve("nested/b.txt"), "b1");
    versioning.migrate(root, "alice"); // baseline: a.txt "a" (a1), b.txt "a" (b1)

    // Edit both, then snapshot the whole workspace in one shot.
    Files.writeString(root.resolve("a.txt"), "a2");
    Files.writeString(root.resolve("nested/b.txt"), "b2");
    final var snap = versioning.snapshotWorkspace(root, Optional.empty(), Optional.of("nightly"), "bob");
    assertEquals(1, snap.checkpoint().number());
    assertEquals("a", snap.checkpoint().name());
    assertEquals("bob", snap.checkpoint().author());
    assertEquals(2, snap.checkpoint().fileCount());
    assertEquals(2, snap.fileRevisions().size(), "both dirty files got a new per-file revision");

    // Each file advanced to its own next revision ("b"), independently, on the one checkpoint commit.
    final var aRevs = versioning.listRevisions(root, Path.of("a.txt"));
    assertEquals(2, aRevs.size());
    assertEquals("b", aRevs.get(1).name());
    assertEquals(token("a2"), aRevs.get(1).contentHash());
    assertEquals(2, versioning.listRevisions(root, Path.of("nested/b.txt")).size());

    // The checkpoint is listed.
    final var checkpoints = versioning.listCheckpoints(root);
    assertEquals(1, checkpoints.size());
    assertEquals("a", checkpoints.get(0).name());
    assertEquals("nightly", checkpoints.get(0).message());

    // A second snapshot with only a.txt dirty bumps a.txt alone; b.txt is left untouched.
    Files.writeString(root.resolve("a.txt"), "a3");
    final var snap2 = versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "bob");
    assertEquals(2, snap2.checkpoint().number());
    assertEquals("b", snap2.checkpoint().name());
    assertEquals(1, snap2.fileRevisions().size());
    assertEquals(3, versioning.listRevisions(root, Path.of("a.txt")).size()); // a, b, c
    assertEquals(2, versioning.listRevisions(root, Path.of("nested/b.txt")).size()); // unchanged: a, b

    // A clean snapshot (nothing dirty) still records a checkpoint, with zero file revisions.
    final var snap3 = versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "bob");
    assertEquals(3, snap3.checkpoint().number());
    assertEquals(0, snap3.fileRevisions().size());
    assertEquals(3, versioning.listCheckpoints(root).size());

    // The working tree + index stay clean after a snapshot (index refreshed to the checkpoint commit).
    try (Repository repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build()) {
      assertTrue(Git.wrap(repo).status().call().isClean());
    }
  }

  @Test
  void restoreToCheckpointResetsFilesAndSurfacesNewOnes(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a1");
    versioning.migrate(root, "alice"); // a.txt rev a (a1)
    Files.writeString(root.resolve("a.txt"), "a2");
    final var snap = versioning.snapshotWorkspace(root, Optional.empty(), Optional.of("cp"), "bob"); // checkpoint a: a.txt=a2
    assertEquals("a", snap.checkpoint().name());

    // After the checkpoint: change a.txt and add a brand-new file b.txt.
    Files.writeString(root.resolve("a.txt"), "a3");
    write(root.resolve("b.txt"), "b1");

    final var result = versioning.restoreToCheckpoint(root, "a", "carol").orElseThrow();

    // a.txt was reset to its checkpoint content; b.txt (created since) is kept and surfaced, not deleted.
    assertEquals("a2", Files.readString(root.resolve("a.txt")));
    assertTrue(result.restoredPaths().contains("a.txt"));
    assertTrue(result.filesCreatedSince().contains("b.txt"));
    assertTrue(Files.exists(root.resolve("b.txt")), "non-destructive: a file created since the checkpoint is kept");
    assertEquals("b1", Files.readString(root.resolve("b.txt")));

    // History is untouched — restore creates no new revision.
    assertEquals(2, versioning.listRevisions(root, Path.of("a.txt")).size()); // a, b

    // The restored state can then be snapshotted as its own checkpoint.
    final var snap2 = versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "carol");
    assertEquals(2, snap2.checkpoint().number());

    // An unknown checkpoint resolves to nothing.
    assertTrue(versioning.restoreToCheckpoint(root, "zz", "carol").isEmpty());
  }

  @Test
  void restoreToCheckpointRecreatesContentAndMetadata(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a1");
    versioning.migrate(root, "alice");
    versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "bob"); // checkpoint a captures a.txt + its metadata
    final var fileId = versioning.listRevisions(root, Path.of("a.txt")).get(0).fileId();
    assertFalse(fileId.isBlank());

    // Delete the file entirely — content AND its .meta.seqdev.
    Files.delete(root.resolve("a.txt"));
    Files.deleteIfExists(root.resolve(".a.txt.meta.seqdev"));

    // Restoring the checkpoint brings back BOTH content and metadata (so it's not "no metadata" in the UI).
    versioning.restoreToCheckpoint(root, "a", "carol").orElseThrow();
    assertEquals("a1", Files.readString(root.resolve("a.txt")), "content recreated");
    assertTrue(Files.exists(root.resolve(".a.txt.meta.seqdev")), "metadata file recreated from the checkpoint");
    // Identity preserved: the restored metadata carries the original fileId.
    assertEquals(fileId, versioning.listRevisions(root, Path.of("a.txt")).get(0).fileId());
  }

  @Test
  void snapshotCapturesDeletionsAndRestoreSurfacesThem(@TempDir Path root) throws Exception {
    write(root.resolve("f1.txt"), "one");
    write(root.resolve("f2.txt"), "two");
    versioning.migrate(root, "alice");
    final var snapA = versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "bob"); // a: tree has f1, f2
    assertEquals("a", snapA.checkpoint().name());

    // Delete both files (content + metadata), then snapshot again.
    Files.delete(root.resolve("f1.txt"));
    Files.deleteIfExists(root.resolve(".f1.txt.meta.seqdev"));
    Files.delete(root.resolve("f2.txt"));
    Files.deleteIfExists(root.resolve(".f2.txt.meta.seqdev"));
    final var snapB = versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "bob");
    assertEquals("b", snapB.checkpoint().name());
    assertEquals(2, snapB.checkpoint().fileCount(), "the two deletions are captured (not reported as 0 files)");

    // Restore checkpoint a → both files come back (a's tree still has them).
    versioning.restoreToCheckpoint(root, "a", "carol").orElseThrow();
    assertTrue(Files.exists(root.resolve("f1.txt")));
    assertTrue(Files.exists(root.resolve("f2.txt")));

    // Restore checkpoint b → b's tree has NEITHER file, so both are surfaced as created-since.
    // (Non-destructive: they're kept until the user removes them — the bug was they used to be silently re-added.)
    final var restoreB = versioning.restoreToCheckpoint(root, "b", "carol").orElseThrow();
    assertTrue(restoreB.restoredPaths().isEmpty(), "checkpoint b contains no files to restore");
    assertTrue(restoreB.filesCreatedSince().contains("f1.txt"));
    assertTrue(restoreB.filesCreatedSince().contains("f2.txt"));
  }

  private String preview(final Path root, final Path path, final String rev) throws Exception {
    return new String(versioning.readRevision(root, path, rev).orElseThrow(), StandardCharsets.UTF_8);
  }
}
