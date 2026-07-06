package gov.nasa.jpl.aerie.workspace.server;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void migrateEstablishesHistoryButCreatesNoRevisions(@TempDir Path root) throws Exception {
    write(root.resolve("seq1.txt"), "alpha");

    assertTrue(versioning.migrate(root, "alice"));
    assertFalse(versioning.migrate(root, "alice"), "migration is idempotent");

    // Approach 2: migration establishes git history (a baseline commit) but creates NO revision —
    // revisions are user-triggered tags. The first createRevision is "a".
    assertTrue(versioning.listRevisions(root, Path.of("seq1.txt")).isEmpty(), "no revisions until the user creates one");

    final var a = versioning.createRevision(root, Path.of("seq1.txt"), Optional.empty(), Optional.empty(), "alice");
    assertEquals(1, a.number());
    assertEquals("a", a.name());
    assertEquals("seq1.txt", a.path());
    assertEquals(token("alpha"), a.contentHash(), "revision content hash == the edit-protection ETag of the bytes");
    assertEquals(1, versioning.listRevisions(root, Path.of("seq1.txt")).size());
  }

  @Test
  void createListPreviewRestoreLifecycle(@TempDir Path root) throws Exception {
    final var path = Path.of("seq1.txt");
    final var file = root.resolve("seq1.txt");
    write(file, "alpha");
    versioning.migrate(root, "alice");

    // Revision the current bytes as "a" (alpha).
    final var a = versioning.createRevision(root, path, Optional.empty(), Optional.empty(), "alice");
    assertEquals(1, a.number());
    assertEquals("a", a.name());

    // Edit the working copy and snapshot it as a new revision "b".
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

    // Push seq1 up to revision c (a, b, c — three user-created revisions; migration seeds none).
    versioning.createRevision(root, Path.of("seq1.txt"), Optional.empty(), Optional.empty(), "alice");
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
    versioning.createRevision(root, path, Optional.empty(), Optional.empty(), "alice");

    final var latest = versioning.listRevisions(root, path).get(0);
    // Clean: the working-copy token equals the latest revision's stored content hash.
    assertEquals(latest.contentHash(), WorkspaceVersioningService.contentToken(Files.readAllBytes(file)));

    // Dirty: edit the working copy and the tokens diverge.
    Files.writeString(file, "alpha-edited");
    assertNotEquals(latest.contentHash(), WorkspaceVersioningService.contentToken(Files.readAllBytes(file)));
  }

  @Test
  void workingTreeIsCleanAfterCreatingARevision(@TempDir Path root) throws Exception {
    final var path = Path.of("seq1.txt");
    final var file = root.resolve("seq1.txt");
    write(file, "alpha");
    versioning.migrate(root, "alice");
    Files.writeString(file, "beta");
    versioning.createRevision(root, path, Optional.empty(), Optional.empty(), "bob");

    // createRevision commits the current bytes (commit-on-save) then tags them; the index must be refreshed
    // to the new HEAD afterward, or the repo looks perpetually dirty to Git tooling/mirror.
    try (Repository repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build()) {
      final var status = Git.wrap(repo).status().call();
      assertTrue(
          status.isClean(),
          "working tree + index should match HEAD after createRevision; uncommitted: " + status.getUncommittedChanges());
    }
  }

  @Test
  void commitOnSaveAppendsAPerSaveCommitAndSkipsNoOps(@TempDir Path root) throws Exception {
    final var path = Path.of("seq1.txt");
    final var file = root.resolve("seq1.txt");
    write(file, "v1");
    versioning.migrate(root, "alice");         // baseline commit (captures "v1")

    Files.writeString(file, "v2");
    versioning.commitSave(root, path, "bob");  // content changed → +1 commit
    Files.writeString(file, "v3");
    versioning.commitSave(root, path, "bob");  // content changed → +1 commit
    versioning.commitSave(root, path, "bob");  // identical to HEAD → no-op, no commit

    try (Repository repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build()) {
      int commits = 0;
      for (final var ignored : Git.wrap(repo).log().call()) commits++;
      assertEquals(3, commits, "baseline + two content-changing saves; the identical third save is a no-op");

      final var status = Git.wrap(repo).status().call();
      assertTrue(
          status.isClean(),
          "working tree + index should be clean after commit-on-save; uncommitted: " + status.getUncommittedChanges());
    }
  }

  @Test
  void commitOnRenameFollowsHistoryAcrossTheRename(@TempDir Path root) throws Exception {
    final var oldPath = Path.of("old.txt");
    final var newPath = Path.of("renamed.txt");
    final var file = root.resolve("old.txt");
    write(file, "hello");
    versioning.migrate(root, "alice");            // baseline captures old.txt (+ its metadata sidecar)
    Files.writeString(file, "hello world");
    versioning.commitSave(root, oldPath, "bob");  // a pre-rename content edit

    // Simulate the on-disk move (content unchanged) — content file and its metadata sidecar both move.
    Files.move(root.resolve("old.txt"), root.resolve("renamed.txt"));
    final var oldMeta = fs.resolveMetadataPath(root, oldPath);
    final var newMeta = fs.resolveMetadataPath(root, newPath);
    if (Files.exists(oldMeta)) Files.move(oldMeta, newMeta);
    versioning.commitRename(root, oldPath, newPath, "bob");

    try (Repository repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build()) {
      // git log --follow renamed.txt must walk THROUGH the rename into old.txt's history
      // (rename commit + pre-rename save + baseline) — proving the identical blob was seen as a rename.
      try (RevWalk rw = new RevWalk(repo)) {
        rw.setTreeFilter(FollowFilter.create("renamed.txt", repo.getConfig().get(DiffConfig.KEY)));
        rw.markStart(rw.parseCommit(repo.resolve(Constants.HEAD)));
        int followed = 0;
        for (final RevCommit ignored : rw) followed++;
        assertTrue(followed >= 3, "git log --follow should track the file across the rename; commits followed: " + followed);
      }
      assertTrue(Git.wrap(repo).status().call().isClean(), "working tree + index clean after commit-on-rename");
    }
  }

  @Test
  void commitOnDeleteRemovesFromTreeButKeepsHistory(@TempDir Path root) throws Exception {
    final var path = Path.of("seq1.txt");
    final var file = root.resolve("seq1.txt");
    write(file, "keep me");
    versioning.migrate(root, "alice");
    Files.writeString(file, "final content");
    versioning.commitSave(root, path, "bob");     // HEAD now holds "final content"

    // Delete on disk (as the delete path does — content + metadata sidecar), then commit the soft-delete.
    Files.deleteIfExists(fs.resolveMetadataPath(root, path));
    Files.delete(file);
    versioning.commitDelete(root, path, "bob");

    try (Repository repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build();
         RevWalk rw = new RevWalk(repo)) {
      final var head = rw.parseCommit(repo.resolve(Constants.HEAD));
      // Gone from HEAD's tree...
      try (TreeWalk tw = TreeWalk.forPath(repo, "seq1.txt", head.getTree())) {
        assertNull(tw, "file must be absent from HEAD after commit-on-delete");
      }
      // ...but its content is still reachable in the parent commit (restorable from history).
      final var parent = rw.parseCommit(head.getParent(0));
      try (TreeWalk tw = TreeWalk.forPath(repo, "seq1.txt", parent.getTree())) {
        assertTrue(tw != null, "pre-delete content must remain reachable in history");
        final var bytes = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB).getBytes();
        assertEquals("final content", new String(bytes, StandardCharsets.UTF_8));
      }
      assertTrue(Git.wrap(repo).status().call().isClean(), "working tree + index clean after commit-on-delete");
    }
  }

  @Test
  void snapshotWorkspaceCheckpointsDirtyFiles(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a1");
    write(root.resolve("nested/b.txt"), "b1");
    versioning.migrate(root, "alice"); // history only — no revisions yet (Approach 2)

    // Edit both, then snapshot the whole workspace in one shot.
    Files.writeString(root.resolve("a.txt"), "a2");
    Files.writeString(root.resolve("nested/b.txt"), "b2");
    final var snap = versioning.snapshotWorkspace(root, Optional.empty(), Optional.of("nightly"), "bob");
    assertEquals(1, snap.checkpoint().number());
    assertEquals("a", snap.checkpoint().name());
    assertEquals("bob", snap.checkpoint().author());
    assertEquals(2, snap.checkpoint().fileCount());
    assertEquals(2, snap.fileRevisions().size(), "both dirty files got their first per-file revision");

    // Each dirty file got its first revision ("a"), independently, on the one checkpoint commit.
    final var aRevs = versioning.listRevisions(root, Path.of("a.txt"));
    assertEquals(1, aRevs.size());
    assertEquals("a", aRevs.get(0).name());
    assertEquals(token("a2"), aRevs.get(0).contentHash());
    assertEquals(1, versioning.listRevisions(root, Path.of("nested/b.txt")).size());

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
    assertEquals(2, versioning.listRevisions(root, Path.of("a.txt")).size()); // a, b
    assertEquals(1, versioning.listRevisions(root, Path.of("nested/b.txt")).size()); // unchanged: a

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
  void restoreToCheckpointResetsFilesAndRemovesNewOnes(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a1");
    versioning.migrate(root, "alice"); // a.txt rev a (a1)
    Files.writeString(root.resolve("a.txt"), "a2");
    final var snap = versioning.snapshotWorkspace(root, Optional.empty(), Optional.of("cp"), "bob"); // checkpoint a: a.txt=a2
    assertEquals("a", snap.checkpoint().name());

    // After the checkpoint: change a.txt and add a brand-new file b.txt.
    Files.writeString(root.resolve("a.txt"), "a3");
    write(root.resolve("b.txt"), "b1");

    final var result = versioning.restoreToCheckpoint(root, "a", "carol").orElseThrow();

    // a.txt was reset to its checkpoint content; b.txt (created since) is removed so the workspace matches.
    assertEquals("a2", Files.readString(root.resolve("a.txt")));
    assertTrue(result.restoredPaths().contains("a.txt"));
    assertTrue(result.removedPaths().contains("b.txt"));
    assertFalse(Files.exists(root.resolve("b.txt")), "a file created since the checkpoint is removed on restore");

    // Restore is a working-copy operation — it adds no new *revision* tag for a.txt.
    assertEquals(1, versioning.listRevisions(root, Path.of("a.txt")).size()); // just the checkpoint's "a"

    // The restored state can then be snapshotted as its own checkpoint.
    final var snap2 = versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "carol");
    assertEquals(2, snap2.checkpoint().number());

    // An unknown checkpoint resolves to nothing.
    assertTrue(versioning.restoreToCheckpoint(root, "zz", "carol").isEmpty());
  }

  @Test
  void restoreToCheckpointRecreatesContentAndMetadata(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a1");
    // A sidecar exists (normally created via the file API on save); the checkpoint must round-trip it.
    write(root.resolve(".a.txt.meta.seqdev"), "{\"version\":\"1\",\"readOnly\":false}");
    versioning.migrate(root, "alice");
    versioning.snapshotWorkspace(root, Optional.empty(), Optional.empty(), "bob"); // checkpoint a captures a.txt + its metadata in its tree

    // Delete the file entirely — content AND its .meta.seqdev.
    Files.delete(root.resolve("a.txt"));
    Files.deleteIfExists(root.resolve(".a.txt.meta.seqdev"));

    // Restoring the checkpoint brings back BOTH content and metadata (so it's not "no metadata" in the UI).
    versioning.restoreToCheckpoint(root, "a", "carol").orElseThrow();
    assertEquals("a1", Files.readString(root.resolve("a.txt")), "content recreated");
    assertTrue(Files.exists(root.resolve(".a.txt.meta.seqdev")), "metadata file recreated from the checkpoint");
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
    // fileCount = files the checkpoint CONTAINS; after deleting both, checkpoint b holds none.
    assertEquals(0, snapB.checkpoint().fileCount(), "both files were deleted, so the checkpoint contains 0 files");

    // Restore checkpoint a → both files come back (a's tree still has them).
    versioning.restoreToCheckpoint(root, "a", "carol").orElseThrow();
    assertTrue(Files.exists(root.resolve("f1.txt")));
    assertTrue(Files.exists(root.resolve("f2.txt")));

    // Restore checkpoint b → b's tree has NEITHER file, so both (created since b) are removed to match it.
    final var restoreB = versioning.restoreToCheckpoint(root, "b", "carol").orElseThrow();
    assertTrue(restoreB.restoredPaths().isEmpty(), "checkpoint b contains no files to restore");
    assertTrue(restoreB.removedPaths().contains("f1.txt"));
    assertTrue(restoreB.removedPaths().contains("f2.txt"));
    assertFalse(Files.exists(root.resolve("f1.txt")), "restoring the empty checkpoint removes files created since");
    assertFalse(Files.exists(root.resolve("f2.txt")));
  }

  @Test
  void revisionsFollowARenamedFile(@TempDir Path root) throws Exception {
    final var oldPath = Path.of("old.txt");
    final var newPath = Path.of("renamed.txt");
    final var file = root.resolve("old.txt");
    write(file, "v1");
    versioning.migrate(root, "alice");
    final var a = versioning.createRevision(root, oldPath, Optional.empty(), Optional.empty(), "alice"); // "a" at old.txt
    assertEquals("a", a.name());

    // Rename the file (content + metadata sidecar move), then record the pure-rename commit.
    Files.move(root.resolve("old.txt"), root.resolve("renamed.txt"));
    final var oldMeta = fs.resolveMetadataPath(root, oldPath);
    if (Files.exists(oldMeta)) Files.move(oldMeta, fs.resolveMetadataPath(root, newPath));
    versioning.commitRename(root, oldPath, newPath, "bob");

    // The pre-rename revision "a" still lists under the file's new path (recorded path is a historical path).
    final var afterRename = versioning.listRevisions(root, newPath);
    assertEquals(1, afterRename.size(), "the revision created before the rename still lists after it");
    assertEquals("a", afterRename.get(0).name());
    assertEquals("old.txt", afterRename.get(0).path(), "the revision keeps the path it was created at");

    // Preview by name still returns the original bytes, and the next revision numbers as "b".
    assertEquals("v1", preview(root, newPath, "a"));
    Files.writeString(root.resolve("renamed.txt"), "v2");
    final var b = versioning.createRevision(root, newPath, Optional.empty(), Optional.empty(), "bob");
    assertEquals("b", b.name());
    assertEquals(2, versioning.listRevisions(root, newPath).size());
  }

  @Test
  void inboundMergeAppliesNonOverlappingChangesAndAbortsOnConflict(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a-base");
    write(root.resolve("b.txt"), "b-base");
    versioning.migrate(root, "alice"); // baseline B is the common merge base

    // External branch (off the baseline) edits b.txt; PlanDev edits a.txt — non-overlapping.
    versioning.stageIncomingBranch(root, Map.of("b.txt", "b-ext".getBytes(StandardCharsets.UTF_8)), "ext-bot", "bump b");
    Files.writeString(root.resolve("a.txt"), "a-mine");
    versioning.commitSave(root, Path.of("a.txt"), "bob");

    final var clean = versioning.mergeIncoming(root, "bob");
    assertEquals(WorkspaceVersioningService.MergeOutcome.MERGED, clean.outcome());
    assertEquals("a-mine", Files.readString(root.resolve("a.txt")), "PlanDev's change kept");
    assertEquals("b-ext", Files.readString(root.resolve("b.txt")), "external change applied to the working copy");
    assertTrue(clean.changedPaths().contains("b.txt"));

    // Now the external branch edits a.txt — the SAME file PlanDev also changes → conflict.
    versioning.stageIncomingBranch(root, Map.of("a.txt", "a-ext".getBytes(StandardCharsets.UTF_8)), "ext-bot", "clash a");
    Files.writeString(root.resolve("a.txt"), "a-mine-2");
    versioning.commitSave(root, Path.of("a.txt"), "bob");

    final var conflict = versioning.mergeIncoming(root, "bob");
    assertEquals(WorkspaceVersioningService.MergeOutcome.ABORTED_CONFLICTS, conflict.outcome());
    assertTrue(conflict.conflictedPaths().contains("a.txt"), "the doubly-changed file is reported");
    // Working copy is PlanDev's version, untouched — and there are NO conflict markers on disk.
    assertEquals("a-mine-2", Files.readString(root.resolve("a.txt")));
    assertFalse(Files.readString(root.resolve("a.txt")).contains("<<<<<<<"), "merge aborted → no conflict markers");

    // The tree is clean after the abort (hard reset to pre-merge HEAD).
    try (Repository repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build()) {
      assertTrue(Git.wrap(repo).status().call().isClean(), "clean working tree after merge --abort");
    }
  }

  @Test
  void fileHistoriesDerivesCreatedAndLastEditedFromGit(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "a1");
    write(root.resolve("b.txt"), "b1");
    versioning.migrate(root, "alice"); // baseline: both files created by alice

    Files.writeString(root.resolve("a.txt"), "a2");
    versioning.commitSave(root, Path.of("a.txt"), "bob"); // a.txt last edited by bob; b.txt untouched

    final var histories = versioning.fileHistories(root);
    assertEquals(2, histories.size());

    final var a = histories.get("a.txt");
    assertEquals("alice", a.createdBy(), "created = the earliest (baseline) commit's author");
    assertEquals("bob", a.lastEditedBy(), "last-edited = the latest commit touching the file");

    final var b = histories.get("b.txt");
    assertEquals("alice", b.createdBy());
    assertEquals("alice", b.lastEditedBy(), "an untouched file's last-edit is still its baseline commit");
  }

  private String preview(final Path root, final Path path, final String rev) throws Exception {
    return new String(versioning.readRevision(root, path, rev).orElseThrow(), StandardCharsets.UTF_8);
  }
}
