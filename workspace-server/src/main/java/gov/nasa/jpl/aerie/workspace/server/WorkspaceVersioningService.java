package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.postgres.WorkspacePostgresRepository;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataMergeBehavior;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Git-native, per-file workspace file versioning (Phase-1 prototype).
 *
 * <p>Each workspace directory <em>is</em> a Git repository; a "revision" is a commit. A file's content is
 * stored as a Git blob and its metadata (carrying the stable {@code fileId}) as a committed
 * {@code .meta.seqdev} blob alongside it. Revisions are indexed by a per-{@code fileId} ref
 * ({@code refs/seqdev/rev/<fileId>/<number>}) so listing a file's history is a cheap ref scan rather than a
 * full-history walk, and reads are lock-free (Git objects are immutable). Every <em>mutation</em>
 * (migrate / create / restore) serializes on a per-workspace mutex; this correctness argument assumes a
 * single workspace-server instance (the current deployment), which must stay enforced.
 *
 * <p>Faithful to the design's hard constraint: nothing here ever performs a Git merge. Restore is a plain
 * overwrite of the working copy; history is never rewritten.
 *
 * <p><b>Prototype scope.</b> Deliberately omitted (documented as later phases): the Git-host mirror and
 * inbound pull-back, LFS, "snapshot all dirty"/workspace checkpoints, rename-robust reads (revisions are
 * read at the file's <em>current</em> path), and {@code .git}/{@code .meta.seqdev} path-traversal
 * hardening. The token, ref index, commit-via-plumbing, migration, and lock are real.
 */
public class WorkspaceVersioningService {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceVersioningService.class);

  /** Revisions are indexed under this ref namespace: {@code refs/seqdev/rev/<fileId>/<number>}. */
  static final String REV_REF_PREFIX = "refs/seqdev/rev/";

  // Commit-message trailers that record the per-file revision facts (the commit itself carries author/time).
  private static final String TRAILER_FILE_ID = "Seqdev-File-Id";
  private static final String TRAILER_NUMBER = "Seqdev-Number";
  private static final String TRAILER_NAME = "Seqdev-Name";
  private static final String TRAILER_CONTENT_HASH = "Seqdev-Content-Hash";
  private static final String TRAILER_ADOPTED_FROM = "Seqdev-Adopted-From";

  // Mirror pull-back refs (Phase-2 spike). "incoming" stands in for a fetched external branch; "last-synced"
  // is the incoming commit we last reconciled (the 3-way base); "stash" holds the pre-pull working copy.
  static final String REF_INCOMING = "refs/seqdev/incoming";
  static final String REF_LAST_SYNCED = "refs/seqdev/last-synced";
  static final String REF_STASH = "refs/seqdev/stash";

  // Workspace-level checkpoint refs (snapshot-all-dirty): refs/seqdev/ws/<n> → a snapshot commit.
  static final String REF_WS_PREFIX = "refs/seqdev/ws/";
  private static final String TRAILER_WS_NUMBER = "Seqdev-Ws-Number";
  private static final String TRAILER_WS_NAME = "Seqdev-Ws-Name";
  private static final String TRAILER_WS_FILES = "Seqdev-Ws-Files";

  private final WorkspacePostgresRepository postgresRepository;
  private final WorkspaceFileSystemService fs;

  /** Per-workspace mutex (keyed by absolute root path) guarding mutations. Reads do not take it. */
  private final Map<String, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();

  public WorkspaceVersioningService(
      final WorkspacePostgresRepository postgresRepository,
      final WorkspaceFileSystemService fs) {
    this.postgresRepository = postgresRepository;
    this.fs = fs;
  }

  /** The SHA-256 content token, computed identically to {@code WorkspaceFileSystemService#getETag}. */
  static String contentToken(final byte[] content) {
    return WorkspaceService.computeETag(WorkspaceService.newSHA256Digest().digest(content));
  }

  // region Public (workspaceId-based) API — resolves the root, then delegates to the root-based core.
  public boolean migrate(final int workspaceId, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return migrate(rootOf(workspaceId), userId);
  }

  public WorkspaceFileRevision createRevision(
      final int workspaceId, final Path filePath, final Optional<String> name,
      final Optional<String> message, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return createRevision(rootOf(workspaceId), filePath, name, message, userId);
  }

  public List<WorkspaceFileRevision> listRevisions(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return listRevisions(rootOf(workspaceId), filePath);
  }

  public Optional<byte[]> readRevision(final int workspaceId, final Path filePath, final String rev)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return readRevision(rootOf(workspaceId), filePath, rev);
  }

  public Optional<RestoreResult> restore(
      final int workspaceId, final Path filePath, final String rev, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return restore(rootOf(workspaceId), filePath, rev, userId);
  }

  private Path rootOf(final int workspaceId) throws NoSuchWorkspaceException {
    return postgresRepository.workspaceRootPath(workspaceId);
  }
  // endregion

  // region Root-based core (no DB dependency — directly unit-testable against a temp directory)

  /** Result of a restore: the working copy's new ETag plus which revision it was restored to. */
  public record RestoreResult(String etag, int number, String name) {}

  /**
   * Initialize the workspace as a Git repo (if it isn't one), assign a {@code fileId} to every existing
   * file, and make a baseline commit seeded as revision {@code a} for each file. Idempotent: a no-op once a
   * {@code .git} exists. Takes the per-workspace lock.
   */
  boolean migrate(final Path root, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      return migrateLocked(root, userId);
    } finally {
      lock.unlock();
    }
  }

  WorkspaceFileRevision createRevision(
      final Path root, final Path filePath, final Optional<String> nameOverride,
      final Optional<String> message, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      migrateLocked(root, userId); // lazy migration on first revision-related mutation

      final var contentPath = fs.resolveReadingPath(root, filePath);
      if (!Files.isRegularFile(contentPath)) {
        throw new WorkspaceFileOpException("Cannot create a revision of a missing or non-regular file: " + filePath);
      }
      if (RenderType.isAerieMetadataFile(contentPath.getFileName().toString())) {
        throw new WorkspaceFileOpException("Cannot create a revision of a metadata file directly: " + filePath);
      }

      final var metaPath = fs.resolveMetadataPath(root, filePath);
      final var fileId = ensureFileId(root, filePath, userId);

      final byte[] content = Files.readAllBytes(contentPath);
      final byte[] metaBytes = Files.readAllBytes(metaPath); // ensureFileId guarantees this exists

      try (Repository repo = openRepo(root)) {
        final var ident = personIdent(userId);
        return commitFileRevision(
            repo, root, filePath, fileId, content, metaBytes, ident, ident, nameOverride, message.orElse(null), null);
      }
    } finally {
      lock.unlock();
    }
  }

  /** List a file's revisions, oldest first. Lock-free. Empty if the workspace isn't a repo or the file has no id. */
  List<WorkspaceFileRevision> listRevisions(final Path root, final Path filePath)
  throws IOException, WorkspaceFileOpException {
    if (!isRepo(root)) return List.of();
    final var fileId = fileIdFor(root, filePath);
    if (fileId == null) return List.of();

    final var pathStr = relativize(root, fs.resolveReadingPath(root, filePath));
    try (Repository repo = openRepo(root)) {
      final var prefix = REV_REF_PREFIX + fileId + "/";
      final var refs = repo.getRefDatabase().getRefsByPrefix(prefix);
      final var out = new ArrayList<WorkspaceFileRevision>(refs.size());
      try (RevWalk rw = new RevWalk(repo)) {
        for (final var ref : refs) {
          final int number = parseTrailingInt(ref.getName(), prefix);
          if (number < 0) continue;
          final RevCommit commit = rw.parseCommit(ref.getObjectId());
          out.add(toRevision(repo, rw, commit, fileId, number, pathStr));
        }
      }
      out.sort(Comparator.comparingInt(WorkspaceFileRevision::number));
      return out;
    }
  }

  /** Read a revision's bytes (preview). {@code rev} may be a number ("3") or a name ("c"). Lock-free. */
  Optional<byte[]> readRevision(final Path root, final Path filePath, final String rev)
  throws IOException, WorkspaceFileOpException {
    if (!isRepo(root)) return Optional.empty();
    final var fileId = fileIdFor(root, filePath);
    if (fileId == null) return Optional.empty();

    final var pathStr = relativize(root, fs.resolveReadingPath(root, filePath));
    try (Repository repo = openRepo(root)) {
      final ObjectId commitId = resolveRevisionCommit(repo, fileId, rev);
      if (commitId == null) return Optional.empty();
      try (RevWalk rw = new RevWalk(repo)) {
        final RevCommit commit = rw.parseCommit(commitId);
        return Optional.ofNullable(readBlobAtPath(repo, commit, pathStr));
      }
    }
  }

  /**
   * Restore the working copy to a revision's content. Non-destructive: revisions/history are untouched, so
   * the restored state can itself become a later revision. Takes the per-workspace lock. (Prototype restores
   * content only; restoring the committed metadata blob alongside it is the full-fidelity behavior.)
   */
  Optional<RestoreResult> restore(final Path root, final Path filePath, final String rev, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      if (!isRepo(root)) return Optional.empty();
      final var fileId = fileIdFor(root, filePath);
      if (fileId == null) return Optional.empty();

      final var contentPath = fs.resolveReadingPath(root, filePath);
      final var pathStr = relativize(root, contentPath);

      try (Repository repo = openRepo(root)) {
        final ObjectId commitId = resolveRevisionCommit(repo, fileId, rev);
        if (commitId == null) return Optional.empty();
        final int number = numberForRev(repo, fileId, rev);
        try (RevWalk rw = new RevWalk(repo)) {
          final RevCommit commit = rw.parseCommit(commitId);
          final byte[] bytes = readBlobAtPath(repo, commit, pathStr);
          if (bytes == null) return Optional.empty();

          Files.write(contentPath, bytes); // overwrite working copy — a plain file write, never a merge
          // Reflect the edit in the working-copy metadata (fileId is preserved by the writer).
          fs.updateMetadataKeys(
              fs.resolveMetadataPath(root, filePath),
              new MetadataUpdates.Builder(userId).lastEditedAt(Instant.now()).lastEditedBy(userId).build(),
              MetadataMergeBehavior.deepMerge);

          final String name = number > 0 ? RevisionName.forNumber(number) : rev;
          return Optional.of(new RestoreResult(contentToken(bytes), number, name));
        }
      }
    } finally {
      lock.unlock();
    }
  }
  // endregion

  // region Migration helpers

  /** Assumes the per-workspace lock is held. Returns true if a migration was performed. */
  private boolean migrateLocked(final Path root, final String userId)
  throws IOException, WorkspaceFileOpException {
    if (isRepo(root)) return false;
    try (Git git = Git.init().setDirectory(root.toFile()).call()) {
      final Repository repo = git.getRepository();
      configureRepo(repo);

      // Assign a stable fileId to every existing content file before the baseline snapshot.
      forEachContentFile(root, p -> ensureFileId(root, root.relativize(p), userId));

      git.add().addFilepattern(".").call();
      final RevCommit baseline = git.commit()
          .setMessage("Revision a (baseline)\n\nSeqdev baseline snapshot for workspace versioning.")
          .setAuthor(personIdent(userId))
          .setCommitter(personIdent(userId))
          .setAllowEmpty(true)
          .call();

      // Seed revision "a" (number 1) for every file present at baseline.
      forEachContentFile(root, p -> {
        final var fileId = fileIdFor(root, root.relativize(p));
        if (fileId != null) writeRef(repo, revRef(fileId, 1), baseline.getId());
      });
      logger.info("Migrated workspace at {} into Git (baseline {})", root, baseline.getId().abbreviate(8).name());
      return true;
    } catch (GitAPIException e) {
      throw new IOException("Failed to initialize Git repository for workspace at " + root, e);
    }
  }

  private void configureRepo(final Repository repo) throws IOException {
    // Disable content filters so a restore returns the exact original bytes (content fidelity).
    final var cfg = repo.getConfig();
    cfg.setBoolean("core", null, "autocrlf", false);
    cfg.setString("core", null, "eol", "lf");
    cfg.save();
  }

  /**
   * Ensure the file has a stable fileId in its committed metadata, generating and persisting one if absent.
   * @return the file's fileId
   */
  private String ensureFileId(final Path root, final Path filePath, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var metaPath = fs.resolveMetadataPath(root, filePath);
    final var existing = fs.readMetadataFile(metaPath.toFile());
    final var current = existing.getString("fileId", null);
    if (current != null && !current.isBlank()) return current;

    final var id = UUID.randomUUID().toString();
    fs.updateMetadataKeys(
        metaPath,
        new MetadataUpdates.Builder(userId).fileId(id).build(),
        MetadataMergeBehavior.deepMerge);
    return id;
  }
  // endregion

  // region Git plumbing helpers

  /**
   * Build a new tree equal to {@code base}'s tree with the given path→blob entries inserted/replaced.
   * Pure plumbing: no working index or checkout is touched.
   */
  private ObjectId buildTreeWith(
      final Repository repo, final ObjectInserter inserter, final ObjectId base,
      final Map<String, ObjectId> blobsByPath) throws IOException {
    return buildTreeApplying(repo, inserter, base, blobsByPath, Set.of());
  }

  /**
   * Build a new tree equal to {@code baseCommit}'s tree with {@code upserts} (path→blob) inserted/replaced
   * and {@code deletes} removed. Pure plumbing: no working index or checkout is touched.
   */
  private ObjectId buildTreeApplying(
      final Repository repo, final ObjectInserter inserter, final ObjectId baseCommit,
      final Map<String, ObjectId> upserts, final Set<String> deletes) throws IOException {
    final DirCache dc = DirCache.newInCore();
    if (baseCommit != null) {
      try (RevWalk rw = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
        final RevTree baseTree = rw.parseCommit(baseCommit).getTree();
        final DirCacheBuilder dcBuilder = dc.builder();
        dcBuilder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, baseTree);
        dcBuilder.finish();
      }
    }
    // Apply each edit in its own pass so we never depend on add-order/sort assumptions.
    for (final var entry : upserts.entrySet()) {
      final var blob = entry.getValue();
      final DirCacheEditor editor = dc.editor();
      editor.add(new DirCacheEditor.PathEdit(entry.getKey()) {
        @Override public void apply(final DirCacheEntry ent) {
          ent.setFileMode(FileMode.REGULAR_FILE);
          ent.setObjectId(blob);
        }
      });
      editor.finish();
    }
    for (final var path : deletes) {
      final DirCacheEditor editor = dc.editor();
      editor.add(new DirCacheEditor.DeletePath(path));
      editor.finish();
    }
    return dc.writeTree(inserter);
  }

  /** Advance the workspace's append-only branch (the ref HEAD points to) from {@code expectedOld} to {@code next}. */
  private void advanceBranch(final Repository repo, final ObjectId expectedOld, final ObjectId next)
  throws IOException {
    final String branch = repo.getFullBranch(); // e.g. refs/heads/master (may be unborn after init)
    final RefUpdate update = repo.updateRef(branch);
    if (expectedOld != null) update.setExpectedOldObjectId(expectedOld);
    update.setNewObjectId(next);
    final RefUpdate.Result result = update.update();
    switch (result) {
      case NEW, FAST_FORWARD, FORCED, NO_CHANGE -> { }
      default -> throw new IOException("Failed to advance branch " + branch + ": " + result);
    }
  }

  private void writeRef(final Repository repo, final String refName, final ObjectId target) throws IOException {
    final RefUpdate update = repo.updateRef(refName);
    update.setNewObjectId(target);
    update.setForceUpdate(true);
    final RefUpdate.Result result = update.update();
    switch (result) {
      case NEW, FAST_FORWARD, FORCED, NO_CHANGE -> { }
      default -> throw new IOException("Failed to write ref " + refName + ": " + result);
    }
  }

  /**
   * Sync the Git index to the current HEAD after a plumbing commit. Migration stages the baseline via a
   * porcelain {@code git add} (which writes {@code .git/index}); {@link #commitFileRevision} commits via pure
   * plumbing and never touches the index, so without this the index is left at the baseline and the repo
   * looks perpetually dirty to Git tooling (and the future mirror/transport). A MIXED reset updates HEAD's
   * index only — it never touches the working tree, so genuine uncommitted edits still show correctly.
   */
  private void refreshIndex(final Repository repo) throws IOException {
    try {
      Git.wrap(repo).reset().setMode(ResetCommand.ResetType.MIXED).call();
    } catch (GitAPIException e) {
      throw new IOException("Failed to refresh the Git index after a revision commit", e);
    }
  }

  private int nextNumber(final Repository repo, final String fileId) throws IOException {
    final var prefix = REV_REF_PREFIX + fileId + "/";
    int max = 0;
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
      max = Math.max(max, parseTrailingInt(ref.getName(), prefix));
    }
    return max + 1;
  }

  /** Resolve a revision selector (number string or name) to its commit, or null if not found. */
  private ObjectId resolveRevisionCommit(final Repository repo, final String fileId, final String rev)
  throws IOException {
    final Integer number = tryParseInt(rev);
    if (number != null) {
      final var ref = repo.exactRef(revRef(fileId, number));
      return ref == null ? null : ref.getObjectId();
    }
    // Treat as a name: find the ref whose number maps to that base-26 name.
    final var prefix = REV_REF_PREFIX + fileId + "/";
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
      final int n = parseTrailingInt(ref.getName(), prefix);
      if (n >= 1 && RevisionName.forNumber(n).equals(rev)) return ref.getObjectId();
    }
    return null;
  }

  private int numberForRev(final Repository repo, final String fileId, final String rev) throws IOException {
    final Integer number = tryParseInt(rev);
    if (number != null) return number;
    final var prefix = REV_REF_PREFIX + fileId + "/";
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
      final int n = parseTrailingInt(ref.getName(), prefix);
      if (n >= 1 && RevisionName.forNumber(n).equals(rev)) return n;
    }
    return -1;
  }

  private byte[] readBlobAtPath(final Repository repo, final RevCommit commit, final String pathStr)
  throws IOException {
    try (TreeWalk tw = TreeWalk.forPath(repo, pathStr, commit.getTree())) {
      if (tw == null) return null;
      final ObjectId blobId = tw.getObjectId(0);
      return repo.open(blobId, Constants.OBJ_BLOB).getBytes();
    }
  }

  /**
   * Commit one file's content+metadata as its next revision: insert blobs, build a tree on the current
   * branch tip with those two paths replaced, commit, advance the branch, and write the per-{@code fileId}
   * ref. Shared by in-app revision creation and mirror pull-back adoption.
   *
   * @param author      revision author (a PlanDev userId for in-app revisions; the original external
   *                    committer for an adopted revision — preserving provenance)
   * @param committer   who recorded it (PlanDev's service/user identity)
   * @param userMessage optional human message ("" / null for none)
   * @param adoptedFrom nullable incoming commit sha, recorded as a {@code Seqdev-Adopted-From} back-reference
   */
  private WorkspaceFileRevision commitFileRevision(
      final Repository repo, final Path root, final Path filePath, final String fileId,
      final byte[] content, final byte[] metaBytes,
      final PersonIdent author, final PersonIdent committer,
      final Optional<String> nameOverride, final String userMessage, final String adoptedFrom)
  throws IOException, WorkspaceFileOpException {
    final var contentHash = contentToken(content);
    final int number = nextNumber(repo, fileId);
    final String name = nameOverride.filter(s -> !s.isBlank()).orElse(RevisionName.forNumber(number));
    final String relContent = relativize(root, fs.resolveReadingPath(root, filePath));
    final String relMeta = relativize(root, fs.resolveMetadataPath(root, filePath));

    final ObjectId commitId;
    try (ObjectInserter inserter = repo.newObjectInserter()) {
      final ObjectId contentBlob = inserter.insert(Constants.OBJ_BLOB, content);
      final ObjectId metaBlob = inserter.insert(Constants.OBJ_BLOB, metaBytes);
      final ObjectId head = repo.resolve(Constants.HEAD);

      final ObjectId treeId = buildTreeWith(repo, inserter, head, Map.of(relContent, contentBlob, relMeta, metaBlob));

      final var commit = new CommitBuilder();
      commit.setTreeId(treeId);
      if (head != null) commit.setParentId(head);
      commit.setAuthor(author);
      commit.setCommitter(committer);
      commit.setMessage(buildMessage(
          name, filePath, Optional.ofNullable(userMessage).filter(s -> !s.isBlank()),
          fileId, number, contentHash, adoptedFrom));
      commitId = inserter.insert(commit);
      inserter.flush();

      // Advance the workspace's append-only branch, then index this file's new revision.
      advanceBranch(repo, head, commitId);
      writeRef(repo, revRef(fileId, number), commitId);
    }

    // Keep the Git index in sync with the new HEAD (commits above bypass the index via plumbing).
    refreshIndex(repo);

    return new WorkspaceFileRevision(
        fileId, number, name, relContent, contentHash,
        author.getName(), Instant.now().toString(), userMessage == null ? "" : userMessage.strip(), commitId.getName());
  }

  /** Compose the revision commit message: a human subject, the optional user message, then machine trailers. */
  private String buildMessage(
      final String name, final Path filePath, final Optional<String> message,
      final String fileId, final int number, final String contentHash, final String adoptedFrom) {
    final var sb = new StringBuilder();
    sb.append("Revision ").append(name).append(" of ").append(filePath.toString().replace('\\', '/')).append("\n");
    message.filter(m -> !m.isBlank()).ifPresent(m -> sb.append("\n").append(m.strip()).append("\n"));
    sb.append("\n");
    sb.append(TRAILER_FILE_ID).append(": ").append(fileId).append("\n");
    sb.append(TRAILER_NUMBER).append(": ").append(number).append("\n");
    sb.append(TRAILER_NAME).append(": ").append(name).append("\n");
    sb.append(TRAILER_CONTENT_HASH).append(": ").append(contentHash).append("\n");
    if (adoptedFrom != null && !adoptedFrom.isBlank()) {
      sb.append(TRAILER_ADOPTED_FROM).append(": ").append(adoptedFrom).append("\n");
    }
    return sb.toString();
  }

  private WorkspaceFileRevision toRevision(
      final Repository repo, final RevWalk rw, final RevCommit commit,
      final String fileId, final int number, final String pathStr) throws IOException {
    final var trailers = parseTrailers(commit.getFullMessage());
    final var name = trailers.getOrDefault(TRAILER_NAME, RevisionName.forNumber(number));
    String contentHash = trailers.get(TRAILER_CONTENT_HASH);
    if (contentHash == null) {
      // Baseline ("a") commits carry no per-file trailer; recompute from the blob (identical bytes → identical token).
      final byte[] bytes = readBlobAtPath(repo, commit, pathStr);
      contentHash = bytes == null ? "" : contentToken(bytes);
    }
    final var author = commit.getAuthorIdent().getName();
    final var createdAt = Instant.ofEpochSecond(commit.getCommitTime()).toString();
    return new WorkspaceFileRevision(
        fileId, number, name, pathStr, contentHash, author, createdAt, userMessage(commit), commit.getName());
  }
  // endregion

  // region Mirror pull-back (Phase-2 spike) — inbound changes resolved file-by-file, never a Git merge.
  //
  // Model: an external writer commits to an "incoming" branch (here, the refs/seqdev/incoming ref, populated
  // by the test helpers below that stand in for the network fetch). We classify each file 3-way against a
  // recorded last-synced base — NOT raw HEAD-vs-HEAD — so we know what changed *and by whom since we last
  // agreed*. Clean changes auto-adopt as new revisions (preserving the external author + a back-reference);
  // genuine conflicts and upstream deletions are surfaced as a per-file take-mine/take-theirs choice. After
  // applying, the base advances to the incoming tip, so a resolved decision (e.g. a kept deletion) is never
  // re-surfaced unless the upstream genuinely changes it again.

  /** What pulling the incoming changes would do to a file. */
  public enum PullDisposition {
    /** Nothing to do (upstream unchanged since the base, or both sides already converged). */
    NOOP,
    /** Upstream changed, PlanDev did not — adopt the incoming bytes silently. Also covers upstream adds. */
    CLEAN_ADOPT,
    /** Both sides edited the file — needs a take-mine/take-theirs decision. */
    CONFLICT,
    /** Upstream deleted a file PlanDev left untouched — reviewable (accept the delete, or keep). */
    DELETE,
    /** Upstream deleted a file PlanDev also edited — needs a decision. */
    CONFLICT_DELETE
  }

  /** Per-file decision for a reviewable item. For deletes, {@code TAKE_THEIRS} means "accept the deletion." */
  public enum PullChoice { TAKE_MINE, TAKE_THEIRS }

  /** One file's classification in a pull plan. */
  public record PullItem(String path, PullDisposition disposition) {}

  /** What actually happened to one file when a pull was applied. {@code outcome} ∈ ADOPTED|DELETED|KEPT_MINE. */
  public record PullOutcome(String path, String outcome) {}

  /** Summary of an applied pull. */
  public record PullResult(List<PullOutcome> outcomes, String stashCommit, String syncedTo) {}

  // Public (workspaceId-based) wrappers — HTTP wiring is a later step, once the fetch transport lands.
  public List<PullItem> computePullPlan(final int workspaceId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return computePullPlan(rootOf(workspaceId));
  }

  public PullResult applyPull(
      final int workspaceId, final Map<String, PullChoice> decisions,
      final boolean autoAcceptDeletions, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return applyPull(rootOf(workspaceId), decisions, autoAcceptDeletions, userId);
  }

  /** Classify what a pull would do to each changed file. Lock-free read; empty if nothing has been fetched. */
  List<PullItem> computePullPlan(final Path root) throws IOException, WorkspaceFileOpException {
    if (!isRepo(root)) return List.of();
    try (Repository repo = openRepo(root)) {
      final RevCommit incoming = incomingCommit(repo);
      if (incoming == null) return List.of();
      final RevCommit base = lastSyncedCommit(repo);

      final var paths = unionOfPaths(repo, root, base, incoming);
      final var out = new ArrayList<PullItem>();
      for (final var path : paths) {
        final var disp = classify(
            tokenInTree(repo, base, path), tokenInTree(repo, incoming, path), tokenWorkingCopy(root, path));
        if (disp != PullDisposition.NOOP) out.add(new PullItem(path, disp));
      }
      return out;
    }
  }

  /**
   * Apply a pull: auto-adopt clean changes, apply the caller's decisions to conflicts/deletions, and advance
   * the synced base. Takes the per-workspace lock and recomputes the plan under it (never trusts a stale plan).
   */
  PullResult applyPull(
      final Path root, final Map<String, PullChoice> decisions,
      final boolean autoAcceptDeletions, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      if (!isRepo(root)) return new PullResult(List.of(), null, null);
      try (Repository repo = openRepo(root)) {
        final RevCommit incoming = incomingCommit(repo);
        if (incoming == null) return new PullResult(List.of(), null, null);
        final RevCommit base = lastSyncedCommit(repo);

        final var paths = unionOfPaths(repo, root, base, incoming);
        final var toAdopt = new ArrayList<String>();
        final var toDelete = new ArrayList<String>();
        final var outcomes = new ArrayList<PullOutcome>();

        for (final var path : paths) {
          final var b = tokenInTree(repo, base, path);
          final var t = tokenInTree(repo, incoming, path);
          final var m = tokenWorkingCopy(root, path);
          switch (classify(b, t, m)) {
            case NOOP -> { }
            case CLEAN_ADOPT -> toAdopt.add(path);
            case CONFLICT -> {
              if (decisions.get(path) == PullChoice.TAKE_THEIRS) toAdopt.add(path);
              else outcomes.add(new PullOutcome(path, "KEPT_MINE"));
            }
            case DELETE -> {
              if (autoAcceptDeletions || decisions.get(path) == PullChoice.TAKE_THEIRS) toDelete.add(path);
              else outcomes.add(new PullOutcome(path, "KEPT_MINE"));
            }
            case CONFLICT_DELETE -> {
              if (decisions.get(path) == PullChoice.TAKE_THEIRS) toDelete.add(path);
              else outcomes.add(new PullOutcome(path, "KEPT_MINE"));
            }
          }
        }

        // Pre-pull stash of exactly the files about to change (recoverable safety net for take-theirs).
        final var affected = new TreeSet<String>();
        affected.addAll(toAdopt);
        affected.addAll(toDelete);
        final var stash = stashAffected(repo, root, affected);

        for (final var path : toAdopt) {
          adoptFile(repo, root, Path.of(path), readBlobAtPath(repo, incoming, path), incoming, userId);
          outcomes.add(new PullOutcome(path, "ADOPTED"));
        }
        for (final var path : toDelete) {
          softDelete(root, Path.of(path));
          outcomes.add(new PullOutcome(path, "DELETED"));
        }

        // Advance the synced base so resolved decisions are not re-surfaced on the next pull.
        writeRef(repo, REF_LAST_SYNCED, incoming.getId());
        return new PullResult(outcomes, stash, incoming.getName());
      }
    } finally {
      lock.unlock();
    }
  }

  /** The 3-way classification table (base / theirs=incoming / mine=working copy), all as SHA-256 content tokens. */
  private PullDisposition classify(final String base, final String theirs, final String mine) {
    if (Objects.equals(theirs, base)) return PullDisposition.NOOP;       // upstream unchanged since base
    if (Objects.equals(mine, base)) {                                    // local untouched since base
      return (theirs == null) ? PullDisposition.DELETE : PullDisposition.CLEAN_ADOPT;
    }
    if (Objects.equals(theirs, mine)) return PullDisposition.NOOP;       // both converged to the same content
    return (theirs == null) ? PullDisposition.CONFLICT_DELETE : PullDisposition.CONFLICT;
  }

  /** Adopt incoming bytes for one file: converge the working copy, then record an adopted revision. */
  private WorkspaceFileRevision adoptFile(
      final Repository repo, final Path root, final Path filePath,
      final byte[] theirs, final RevCommit incoming, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var contentPath = fs.resolveReadingPath(root, filePath);
    if (contentPath.getParent() != null) Files.createDirectories(contentPath.getParent());
    Files.write(contentPath, theirs);
    final var fileId = ensureFileId(root, filePath, userId);
    final byte[] metaBytes = Files.readAllBytes(fs.resolveMetadataPath(root, filePath));
    // Preserve provenance: author = the original external committer, committer = PlanDev's identity.
    return commitFileRevision(
        repo, root, filePath, fileId, theirs, metaBytes,
        incoming.getAuthorIdent(), personIdent(userId), Optional.empty(), incoming.getShortMessage(), incoming.getName());
  }

  /** Soft-delete: remove the file (and its metadata) from the working copy; revision history is retained. */
  private void softDelete(final Path root, final Path filePath) throws IOException, WorkspaceFileOpException {
    Files.deleteIfExists(fs.resolveReadingPath(root, filePath));
    Files.deleteIfExists(fs.resolveMetadataPath(root, filePath));
  }

  /** Snapshot the current working bytes of the affected files into a throwaway commit; returns its sha (or null). */
  private String stashAffected(final Repository repo, final Path root, final Set<String> affectedPaths)
  throws IOException {
    try (ObjectInserter inserter = repo.newObjectInserter()) {
      final var upserts = new LinkedHashMap<String, ObjectId>();
      for (final var path : affectedPaths) {
        final var p = root.resolve(path);
        if (Files.isRegularFile(p)) upserts.put(path, inserter.insert(Constants.OBJ_BLOB, Files.readAllBytes(p)));
      }
      if (upserts.isEmpty()) return null;
      final var treeId = buildTreeApplying(repo, inserter, null, upserts, Set.of());
      final var ident = personIdent("seqdev");
      final var commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(ident);
      commit.setCommitter(ident);
      commit.setMessage("Pre-pull stash (recoverable working copy of files about to change)\n");
      final var commitId = inserter.insert(commit);
      inserter.flush();
      writeRef(repo, REF_STASH, commitId);
      return commitId.getName();
    }
  }

  private Set<String> unionOfPaths(final Repository repo, final Path root, final RevCommit base, final RevCommit incoming)
  throws IOException {
    final var paths = new TreeSet<String>();
    paths.addAll(contentPathsInTree(repo, base));
    paths.addAll(contentPathsInTree(repo, incoming));
    paths.addAll(workingCopyContentPaths(root));
    return paths;
  }

  private RevCommit incomingCommit(final Repository repo) throws IOException { return resolveCommit(repo, REF_INCOMING); }
  private RevCommit lastSyncedCommit(final Repository repo) throws IOException { return resolveCommit(repo, REF_LAST_SYNCED); }

  private RevCommit resolveCommit(final Repository repo, final String ref) throws IOException {
    final var r = repo.exactRef(ref);
    if (r == null || r.getObjectId() == null) return null;
    try (RevWalk rw = new RevWalk(repo)) {
      return rw.parseCommit(r.getObjectId());
    }
  }

  private ObjectId resolveObj(final Repository repo, final String ref) throws IOException {
    final var r = repo.exactRef(ref);
    return r == null ? null : r.getObjectId();
  }

  private Set<String> contentPathsInTree(final Repository repo, final RevCommit commit) throws IOException {
    final var out = new TreeSet<String>();
    if (commit == null) return out;
    try (TreeWalk tw = new TreeWalk(repo)) {
      tw.addTree(commit.getTree());
      tw.setRecursive(true);
      while (tw.next()) {
        final var path = tw.getPathString();
        final var name = path.substring(path.lastIndexOf('/') + 1);
        if (!RenderType.isAerieMetadataFile(name)) out.add(path);
      }
    }
    return out;
  }

  private String tokenInTree(final Repository repo, final RevCommit commit, final String path) throws IOException {
    if (commit == null) return null;
    final byte[] bytes = readBlobAtPath(repo, commit, path);
    return bytes == null ? null : contentToken(bytes);
  }

  private String tokenWorkingCopy(final Path root, final String path) throws IOException {
    final var p = root.resolve(path);
    if (!Files.isRegularFile(p)) return null;
    return contentToken(Files.readAllBytes(p));
  }

  private Set<String> workingCopyContentPaths(final Path root) throws IOException {
    final var out = new TreeSet<String>();
    final var gitDir = root.resolve(Constants.DOT_GIT).toAbsolutePath().normalize();
    try (var stream = Files.walk(root)) {
      stream.filter(Files::isRegularFile)
            .filter(p -> !p.toAbsolutePath().normalize().startsWith(gitDir))
            .filter(p -> !RenderType.isAerieMetadataFile(p.getFileName().toString()))
            .forEach(p -> out.add(relativize(root, p)));
    }
    return out;
  }

  // --- Test/spike-only transport simulation (stands in for the network push/fetch, which is out of spike scope) ---

  /**
   * TEST/SPIKE ONLY — models the initial mirror link (PlanDev pushes, then records the synced base): point
   * both the incoming and last-synced refs at the current PlanDev HEAD, so base == incoming == working copy.
   */
  void establishMirrorBaseline(final Path root) throws IOException {
    try (Repository repo = openRepo(root)) {
      final ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) throw new IOException("No baseline commit to mirror; migrate first.");
      writeRef(repo, REF_INCOMING, head);
      writeRef(repo, REF_LAST_SYNCED, head);
    }
  }

  /**
   * TEST/SPIKE ONLY — models an external writer committing to the incoming branch and a fetch landing it:
   * apply {@code changes} (a null value deletes that path) on top of the current incoming tip.
   * @return the new incoming commit sha
   */
  String stageIncoming(final Path root, final Map<String, byte[]> changes, final String authorName, final String message)
  throws IOException {
    try (Repository repo = openRepo(root)) {
      final ObjectId incomingTip = resolveObj(repo, REF_INCOMING);
      final ObjectId baseForTree = (incomingTip != null) ? incomingTip : repo.resolve(Constants.HEAD);
      try (ObjectInserter inserter = repo.newObjectInserter()) {
        final var upserts = new LinkedHashMap<String, ObjectId>();
        final var deletes = new TreeSet<String>();
        for (final var e : changes.entrySet()) {
          if (e.getValue() == null) deletes.add(e.getKey());
          else upserts.put(e.getKey(), inserter.insert(Constants.OBJ_BLOB, e.getValue()));
        }
        final ObjectId treeId = buildTreeApplying(repo, inserter, baseForTree, upserts, deletes);
        final var ident = new PersonIdent(authorName, authorName + "@external.example");
        final var commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (incomingTip != null) commit.setParentId(incomingTip);
        commit.setAuthor(ident);
        commit.setCommitter(ident);
        commit.setMessage(message);
        final ObjectId commitId = inserter.insert(commit);
        inserter.flush();
        writeRef(repo, REF_INCOMING, commitId);
        return commitId.getName();
      }
    }
  }
  // endregion

  // region Workspace checkpoints (snapshot-all-dirty)
  //
  // A checkpoint is ONE commit snapshotting the whole workspace, tagged by a workspace-level ref
  // refs/seqdev/ws/<n> (named ws "a", "b", …). Every *dirty* file in it also gets its own next per-file
  // revision (sharing the checkpoint commit as their target — listing derives each file's number/name from
  // its ref, so a multi-file commit lists correctly per file). "Dirty" = the working copy differs from HEAD,
  // whose tree always holds each file's latest committed content. Restore-to-checkpoint is a later step.

  /** A workspace-level checkpoint: one commit snapshotting the workspace, named ws "a", "b", … */
  public record WorkspaceCheckpoint(
      int number, String name, String commitSha, String author, String createdAt, String message, int fileCount) {}

  /** Result of taking a checkpoint: the checkpoint plus the per-file revisions it created (the dirty files). */
  public record WorkspaceSnapshot(WorkspaceCheckpoint checkpoint, List<WorkspaceFileRevision> fileRevisions) {}

  /**
   * Result of restoring to a checkpoint. {@code restoredPaths} were reset to the checkpoint's content;
   * {@code filesCreatedSince} exist now but weren't in the checkpoint — left in place (never silently deleted),
   * surfaced so the caller can offer a keep/remove choice.
   */
  public record RestoreCheckpointResult(List<String> restoredPaths, List<String> filesCreatedSince) {}

  private record PendingFile(String path, String fileId, String contentHash) {}

  // Public (workspaceId-based) wrappers.
  public WorkspaceSnapshot snapshotWorkspace(
      final int workspaceId, final Optional<String> name, final Optional<String> message, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return snapshotWorkspace(rootOf(workspaceId), name, message, userId);
  }

  public List<WorkspaceCheckpoint> listCheckpoints(final int workspaceId)
  throws NoSuchWorkspaceException, IOException {
    return listCheckpoints(rootOf(workspaceId));
  }

  public Optional<RestoreCheckpointResult> restoreToCheckpoint(
      final int workspaceId, final String checkpoint, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return restoreToCheckpoint(rootOf(workspaceId), checkpoint, userId);
  }

  /**
   * Snapshot every dirty file into a single checkpoint commit (advancing each dirty file's per-file revision)
   * and record a workspace-level checkpoint ref. A true point-in-time snapshot: runs under the per-workspace
   * lock so no save lands mid-snapshot. A clean workspace still gets a checkpoint (an empty marker commit), so
   * a checkpoint always maps to a commit carrying the workspace trailers.
   */
  WorkspaceSnapshot snapshotWorkspace(
      final Path root, final Optional<String> nameOverride, final Optional<String> message, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      migrateLocked(root, userId);
      try (Repository repo = openRepo(root)) {
        final ObjectId head = repo.resolve(Constants.HEAD);

        final var workingPaths = workingCopyContentPaths(root);

        // Dirty = working copy differs from HEAD's tree; deleted = in HEAD's tree but gone from the working copy.
        // A snapshot must capture BOTH, or a checkpoint taken after a delete would still contain the file.
        final var dirty = new ArrayList<Path>();
        final var deletions = new TreeSet<String>();
        try (RevWalk rw = new RevWalk(repo)) {
          final RevCommit headCommit = head == null ? null : rw.parseCommit(head);
          for (final var rel : workingPaths) {
            if (!Objects.equals(tokenWorkingCopy(root, rel), tokenInTree(repo, headCommit, rel))) {
              dirty.add(Path.of(rel));
            }
          }
          for (final var rel : contentPathsInTree(repo, headCommit)) {
            if (!workingPaths.contains(rel)) {
              deletions.add(rel);
            }
          }
        }

        // Remove each deleted file and its metadata file from the checkpoint tree.
        final var treeDeletes = new TreeSet<String>();
        for (final var del : deletions) {
          treeDeletes.add(del);
          treeDeletes.add(relativize(root, fs.resolveMetadataPath(root, Path.of(del))));
        }
        final int totalChanged = dirty.size() + deletions.size();

        final int wsNumber = nextWsNumber(repo);
        final String wsName = nameOverride.filter(s -> !s.isBlank()).orElse(RevisionName.forNumber(wsNumber));
        final var ident = personIdent(userId);
        final var fileRevisions = new ArrayList<WorkspaceFileRevision>();

        final ObjectId checkpointCommit;
        try (ObjectInserter inserter = repo.newObjectInserter()) {
          final var upserts = new LinkedHashMap<String, ObjectId>();
          final var pending = new ArrayList<PendingFile>();
          for (final var filePath : dirty) {
            final var contentPath = fs.resolveReadingPath(root, filePath);
            final var metaPath = fs.resolveMetadataPath(root, filePath);
            final var fileId = ensureFileId(root, filePath, userId);
            final byte[] content = Files.readAllBytes(contentPath);
            final byte[] meta = Files.readAllBytes(metaPath);
            upserts.put(relativize(root, contentPath), inserter.insert(Constants.OBJ_BLOB, content));
            upserts.put(relativize(root, metaPath), inserter.insert(Constants.OBJ_BLOB, meta));
            pending.add(new PendingFile(relativize(root, contentPath), fileId, contentToken(content)));
          }

          // Tree = HEAD's tree with the dirty files' new blobs and the deleted files removed
          // (no changes at all → an empty marker commit).
          final ObjectId treeId = buildTreeApplying(repo, inserter, head, upserts, treeDeletes);
          final var commit = new CommitBuilder();
          commit.setTreeId(treeId);
          if (head != null) commit.setParentId(head);
          commit.setAuthor(ident);
          commit.setCommitter(ident);
          commit.setMessage(buildWsMessage(wsName, wsNumber, totalChanged, message));
          checkpointCommit = inserter.insert(commit);
          inserter.flush();

          advanceBranch(repo, head, checkpointCommit);

          for (final var p : pending) {
            final int number = nextNumber(repo, p.fileId()); // distinct fileIds → independent numbering
            writeRef(repo, revRef(p.fileId(), number), checkpointCommit);
            fileRevisions.add(new WorkspaceFileRevision(
                p.fileId(), number, RevisionName.forNumber(number), p.path(), p.contentHash(),
                ident.getName(), Instant.now().toString(), message.orElse(""), checkpointCommit.getName()));
          }
        }

        writeRef(repo, wsRef(wsNumber), checkpointCommit);
        refreshIndex(repo);

        final var checkpoint = new WorkspaceCheckpoint(
            wsNumber, wsName, checkpointCommit.getName(), ident.getName(),
            Instant.now().toString(), message.orElse(""), totalChanged);
        return new WorkspaceSnapshot(checkpoint, fileRevisions);
      }
    } finally {
      lock.unlock();
    }
  }

  /** List workspace checkpoints, oldest first. Lock-free. */
  List<WorkspaceCheckpoint> listCheckpoints(final Path root) throws IOException {
    if (!isRepo(root)) return List.of();
    try (Repository repo = openRepo(root)) {
      final var refs = repo.getRefDatabase().getRefsByPrefix(REF_WS_PREFIX);
      final var out = new ArrayList<WorkspaceCheckpoint>(refs.size());
      try (RevWalk rw = new RevWalk(repo)) {
        for (final var ref : refs) {
          final int number = parseTrailingInt(ref.getName(), REF_WS_PREFIX);
          if (number < 0) continue;
          final RevCommit commit = rw.parseCommit(ref.getObjectId());
          final var trailers = parseTrailers(commit.getFullMessage());
          final var name = trailers.getOrDefault(TRAILER_WS_NAME, RevisionName.forNumber(number));
          int fileCount = -1;
          try {
            fileCount = Integer.parseInt(trailers.getOrDefault(TRAILER_WS_FILES, "-1"));
          } catch (NumberFormatException ignore) {
            // leave as -1 (unknown)
          }
          out.add(new WorkspaceCheckpoint(
              number, name, commit.getName(), commit.getAuthorIdent().getName(),
              Instant.ofEpochSecond(commit.getCommitTime()).toString(), userMessage(commit), fileCount));
        }
      }
      out.sort(Comparator.comparingInt(WorkspaceCheckpoint::number));
      return out;
    }
  }

  /**
   * Restore the workspace to a checkpoint, non-destructively. Every file in the checkpoint is reset to its
   * checkpoint content (overwriting/recreating the working copy — a plain file write, no commit, history
   * untouched). Files that exist now but weren't in the checkpoint (created since) are <em>left in place</em>
   * and surfaced in {@link RestoreCheckpointResult#filesCreatedSince()} for the caller to offer keep/remove.
   * Takes the per-workspace lock. (Slice: resets content by path; metadata is left as-is.)
   */
  Optional<RestoreCheckpointResult> restoreToCheckpoint(final Path root, final String rev, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      if (!isRepo(root)) return Optional.empty();
      try (Repository repo = openRepo(root)) {
        final ObjectId checkpointCommit = resolveCheckpointCommit(repo, rev);
        if (checkpointCommit == null) return Optional.empty();
        try (RevWalk rw = new RevWalk(repo)) {
          final RevCommit commit = rw.parseCommit(checkpointCommit);
          final var checkpointPaths = contentPathsInTree(repo, commit);
          final var currentPaths = workingCopyContentPaths(root);

          // Reset every checkpoint file to its checkpoint content AND metadata (recreating any deleted since),
          // so a restored/recreated file comes back with its `.meta.seqdev` (fileId, readOnly, user) intact.
          final var restored = new ArrayList<String>();
          for (final var path : checkpointPaths) {
            final byte[] content = readBlobAtPath(repo, commit, path);
            if (content == null) continue;
            final var contentPath = fs.resolveReadingPath(root, Path.of(path));
            if (contentPath.getParent() != null) Files.createDirectories(contentPath.getParent());
            Files.write(contentPath, content);

            // Restore the file's committed metadata blob too (the snapshot captured it).
            final var metaPath = fs.resolveMetadataPath(root, Path.of(path));
            final byte[] metaBytes = readBlobAtPath(repo, commit, relativize(root, metaPath));
            if (metaBytes != null) {
              Files.write(metaPath, metaBytes);
            }
            restored.add(path);
          }

          // Files present now but absent from the checkpoint = created since → kept, surfaced for review.
          final var createdSince = new ArrayList<String>();
          for (final var path : currentPaths) {
            if (!checkpointPaths.contains(path)) createdSince.add(path);
          }

          return Optional.of(new RestoreCheckpointResult(restored, createdSince));
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /** Resolve a checkpoint selector (workspace number or name) to its commit, or null if not found. */
  private ObjectId resolveCheckpointCommit(final Repository repo, final String rev) throws IOException {
    final Integer number = tryParseInt(rev);
    if (number != null) {
      final var ref = repo.exactRef(wsRef(number));
      return ref == null ? null : ref.getObjectId();
    }
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(REF_WS_PREFIX)) {
      final int n = parseTrailingInt(ref.getName(), REF_WS_PREFIX);
      if (n >= 1 && RevisionName.forNumber(n).equals(rev)) return ref.getObjectId();
    }
    return null;
  }

  private int nextWsNumber(final Repository repo) throws IOException {
    int max = 0;
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(REF_WS_PREFIX)) {
      max = Math.max(max, parseTrailingInt(ref.getName(), REF_WS_PREFIX));
    }
    return max + 1;
  }

  private String wsRef(final int number) {
    return REF_WS_PREFIX + number;
  }

  private String buildWsMessage(final String name, final int number, final int fileCount, final Optional<String> message) {
    final var sb = new StringBuilder();
    sb.append("Workspace checkpoint ").append(name)
        .append(" (").append(fileCount).append(fileCount == 1 ? " file)" : " files)").append("\n");
    message.filter(m -> !m.isBlank()).ifPresent(m -> sb.append("\n").append(m.strip()).append("\n"));
    sb.append("\n");
    sb.append(TRAILER_WS_NUMBER).append(": ").append(number).append("\n");
    sb.append(TRAILER_WS_NAME).append(": ").append(name).append("\n");
    sb.append(TRAILER_WS_FILES).append(": ").append(fileCount).append("\n");
    return sb.toString();
  }
  // endregion

  // region Small utilities
  private boolean isRepo(final Path root) {
    return root.resolve(Constants.DOT_GIT).toFile().isDirectory();
  }

  private Repository openRepo(final Path root) throws IOException {
    return new FileRepositoryBuilder().setGitDir(root.resolve(Constants.DOT_GIT).toFile()).build();
  }

  private ReentrantLock lockFor(final Path root) {
    return workspaceLocks.computeIfAbsent(root.toAbsolutePath().normalize().toString(), k -> new ReentrantLock());
  }

  private String fileIdFor(final Path root, final Path filePath) throws IOException, WorkspaceFileOpException {
    final var meta = fs.readMetadataFile(fs.resolveMetadataPath(root, filePath).toFile());
    return meta.getString("fileId", null);
  }

  private PersonIdent personIdent(final String userId) {
    final var who = (userId == null || userId.isBlank()) ? "seqdev" : userId;
    return new PersonIdent(who, who + "@seqdev.local");
  }

  private String revRef(final String fileId, final int number) {
    return REV_REF_PREFIX + fileId + "/" + number;
  }

  private String relativize(final Path root, final Path absolute) {
    return root.toAbsolutePath().normalize().relativize(absolute.toAbsolutePath().normalize())
        .toString().replace('\\', '/');
  }

  private int parseTrailingInt(final String refName, final String prefix) {
    if (!refName.startsWith(prefix)) return -1;
    final var n = tryParseInt(refName.substring(prefix.length()));
    return n == null ? -1 : n;
  }

  private Integer tryParseInt(final String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Map<String, String> parseTrailers(final String message) {
    final var out = new HashMap<String, String>();
    for (final var line : message.split("\n")) {
      if (!line.startsWith("Seqdev-")) continue;
      final int idx = line.indexOf(": ");
      if (idx > 0) out.put(line.substring(0, idx), line.substring(idx + 2).trim());
    }
    return out;
  }

  private String userMessage(final RevCommit commit) {
    final var lines = commit.getFullMessage().split("\n");
    final var sb = new StringBuilder();
    for (int i = 1; i < lines.length; i++) { // skip the subject line
      if (lines[i].startsWith("Seqdev-")) continue;
      sb.append(lines[i]).append("\n");
    }
    return sb.toString().trim();
  }

  /** Run an action for every non-metadata regular file under {@code root}, excluding the {@code .git} dir. */
  private void forEachContentFile(final Path root, final ContentFileAction action)
  throws IOException, WorkspaceFileOpException {
    final var gitDir = root.resolve(Constants.DOT_GIT).toAbsolutePath().normalize();
    final List<Path> files;
    try (var stream = Files.walk(root)) {
      files = stream
          .filter(Files::isRegularFile)
          .filter(p -> !p.toAbsolutePath().normalize().startsWith(gitDir))
          .filter(p -> !RenderType.isAerieMetadataFile(p.getFileName().toString()))
          .toList();
    }
    for (final var p : files) {
      action.accept(p);
    }
  }

  @FunctionalInterface
  private interface ContentFileAction {
    void accept(Path file) throws IOException, WorkspaceFileOpException;
  }
  // endregion
}
