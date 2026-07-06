package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.RenderType;
import gov.nasa.jpl.aerie.workspace.server.postgres.WorkspacePostgresRepository;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataMergeBehavior;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RenameCallback;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
public class WorkspaceVersioningService implements FileHistoryProvider {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceVersioningService.class);

  // Approach 2: a revision is an annotated tag on a save-commit. The ref name is an opaque UUID; the facts
  // (path, number, name, content hash) live in the tag message. Listing selects tags whose recorded path is
  // one the file has occupied (git log --follow), so renames keep their revisions.
  static final String REV_TAG_SHORT_PREFIX = "seqdev/rev/";               // under refs/tags/
  static final String REV_TAG_PREFIX = Constants.R_TAGS + REV_TAG_SHORT_PREFIX; // refs/tags/seqdev/rev/
  private static final String TRAILER_REV_PATH = "Seqdev-Rev-Path";

  // Revision-fact trailers shared by tag messages (the commit/tag itself carries author/time).
  private static final String TRAILER_NUMBER = "Seqdev-Number";
  private static final String TRAILER_NAME = "Seqdev-Name";
  private static final String TRAILER_CONTENT_HASH = "Seqdev-Content-Hash";

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

  /**
   * Approach-2 commit-on-save: record the file's just-saved state as a commit on the workspace branch.
   * Called by the save path after the working copy is written. Best-effort at the call site (a failure
   * here must not fail the user's save — the bytes are already durably on disk).
   */
  public void commitSave(final int workspaceId, final Path filePath, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    commitSave(rootOf(workspaceId), filePath, userId);
  }

  /**
   * Commit-on-rename (Approach 2): record an in-app rename as a pure-rename commit (identical content blob
   * moved to the new path) so {@code git log --follow} tracks the file across the rename at 100%. Called by
   * the move path after the working copy has been moved. Best-effort at the call site.
   */
  public void commitRename(final int workspaceId, final Path oldFilePath, final Path newFilePath, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    commitRename(rootOf(workspaceId), oldFilePath, newFilePath, userId);
  }

  /**
   * Commit-on-delete (Approach 2): record a soft-delete as a commit that removes the file's content+metadata
   * from the tree. The bytes stay reachable in ancestor commits (restorable from history); only the working
   * copy is gone. Called by the delete path after the working copy is removed. Best-effort at the call site.
   */
  public void commitDelete(final int workspaceId, final Path filePath, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    commitDelete(rootOf(workspaceId), filePath, userId);
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
      final byte[] content = Files.readAllBytes(contentPath);
      final byte[] metaBytes = Files.isRegularFile(metaPath) ? Files.readAllBytes(metaPath) : null;

      try (Repository repo = openRepo(root)) {
        final var ident = personIdent(userId);
        // Approach 2: a revision is a *tag* on a save-commit. Ensure the current bytes are committed
        // (no-op if unchanged), then tag the latest commit that touches this file.
        commitSaveLocked(repo, root, filePath, content, metaBytes, ident);
        return tagRevision(repo, root, filePath, ident, nameOverride, message.orElse(null));
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Create a revision = an annotated tag (opaque UUID ref under {@code refs/tags/seqdev/rev/}, facts in the
   * tag message) on the latest commit that touches this file. Per-file numbering (a, b, c…) is derived from
   * how many revisions the file already has along its {@code git log --follow} history. Caller holds the lock.
   */
  private WorkspaceFileRevision tagRevision(
      final Repository repo, final Path root, final Path filePath,
      final PersonIdent tagger, final Optional<String> nameOverride, final String userMessage)
  throws IOException, WorkspaceFileOpException {
    final String relContent = relativize(root, fs.resolveReadingPath(root, filePath));
    final ObjectId head = repo.resolve(Constants.HEAD);
    final RevCommit target = latestCommitTouching(repo, head, relContent);
    if (target == null) {
      throw new WorkspaceFileOpException("Cannot create a revision of a file with no committed history: " + filePath);
    }
    return tagRevisionOnCommit(repo, relContent, target, head, tagger, nameOverride, userMessage);
  }

  /**
   * Tag {@code target} as the next revision of the file at {@code relContent}. Per-file numbering is derived
   * from the file's existing revisions along the history reachable from {@code headForNumbering}. Shared by
   * in-app revision creation (tags the latest commit touching the file) and workspace checkpoints (tag the
   * one checkpoint commit once per dirty file, each tag recording that file's own path).
   */
  private WorkspaceFileRevision tagRevisionOnCommit(
      final Repository repo, final String relContent, final RevCommit target, final ObjectId headForNumbering,
      final PersonIdent tagger, final Optional<String> nameOverride, final String userMessage)
  throws IOException {
    final byte[] content = readBlobAtPath(repo, target, relContent);
    final String contentHash = content == null ? "" : contentToken(content);

    final int number = revisionsForFile(repo, headForNumbering, relContent).size() + 1;
    final String name = nameOverride.filter(s -> !s.isBlank()).orElse(RevisionName.forNumber(number));
    final String strippedMessage = (userMessage == null || userMessage.isBlank()) ? "" : userMessage.strip();

    final var msg = new StringBuilder();
    msg.append("Revision ").append(name).append(" of ").append(relContent).append("\n");
    if (!strippedMessage.isEmpty()) msg.append("\n").append(strippedMessage).append("\n");
    msg.append("\n");
    msg.append(TRAILER_REV_PATH).append(": ").append(relContent).append("\n");
    msg.append(TRAILER_NUMBER).append(": ").append(number).append("\n");
    msg.append(TRAILER_NAME).append(": ").append(name).append("\n");
    msg.append(TRAILER_CONTENT_HASH).append(": ").append(contentHash).append("\n");

    final String shortTag = REV_TAG_SHORT_PREFIX + UUID.randomUUID();
    final ObjectId tagId;
    try (ObjectInserter inserter = repo.newObjectInserter()) {
      final TagBuilder tag = new TagBuilder();
      tag.setObjectId(target, Constants.OBJ_COMMIT);
      tag.setTag(shortTag);
      tag.setTagger(tagger);
      tag.setMessage(msg.toString());
      tagId = inserter.insert(tag);
      inserter.flush();
    }
    writeRef(repo, Constants.R_TAGS + shortTag, tagId);

    return new WorkspaceFileRevision(
        number, name, relContent, contentHash,
        tagger.getName(), Instant.now().toString(), strippedMessage, target.getName());
  }

  /**
   * Commit-on-save (Approach 2). Append a path-keyed commit capturing the file's current content and its
   * metadata sidecar to the workspace's append-only branch, making git history the fine-grained per-file
   * history. A curated "revision" (later slice) becomes a <em>tag</em> on one of these save-commits. A save
   * whose content and metadata are byte-identical to HEAD is a no-op (no empty commit). Takes the
   * per-workspace lock.
   */
  void commitSave(final Path root, final Path filePath, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      migrateLocked(root, userId); // lazy migration on first mutation

      final var contentPath = fs.resolveReadingPath(root, filePath);
      if (!Files.isRegularFile(contentPath)) return; // directories / missing files are never committed
      if (RenderType.isAerieMetadataFile(contentPath.getFileName().toString())) return; // metadata rides with its content, never committed alone

      final byte[] content = Files.readAllBytes(contentPath);
      final var metaPath = fs.resolveMetadataPath(root, filePath);
      final byte[] metaBytes = Files.isRegularFile(metaPath) ? Files.readAllBytes(metaPath) : null;

      try (Repository repo = openRepo(root)) {
        commitSaveLocked(repo, root, filePath, content, metaBytes, personIdent(userId));
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Insert the content (+ metadata) blobs, build a tree on the current branch tip with those paths replaced,
   * commit, and advance the branch. Pure plumbing — no working index/checkout. Caller holds the lock.
   *
   * @return the new commit id, or {@code null} if the save was a no-op (all paths identical to HEAD)
   */
  private ObjectId commitSaveLocked(
      final Repository repo, final Path root, final Path filePath,
      final byte[] content, final byte[] metaBytes, final PersonIdent ident)
  throws IOException, WorkspaceFileOpException {
    final String relContent = relativize(root, fs.resolveReadingPath(root, filePath));
    final String relMeta = relativize(root, fs.resolveMetadataPath(root, filePath));
    final var contentHash = contentToken(content);

    final ObjectId commitId;
    try (ObjectInserter inserter = repo.newObjectInserter()) {
      final ObjectId head = repo.resolve(Constants.HEAD);
      final var upserts = new HashMap<String, ObjectId>();
      upserts.put(relContent, inserter.insert(Constants.OBJ_BLOB, content));
      if (metaBytes != null) {
        upserts.put(relMeta, inserter.insert(Constants.OBJ_BLOB, metaBytes));
      }

      // Skip no-op saves: if every path already resolves to the same blob at HEAD, there is nothing new to record.
      if (head != null && unchangedAtHead(repo, head, upserts)) return null;

      final ObjectId treeId = buildTreeWith(repo, inserter, head, upserts);
      final var commit = new CommitBuilder();
      commit.setTreeId(treeId);
      if (head != null) commit.setParentId(head);
      commit.setAuthor(ident);
      commit.setCommitter(ident);
      commit.setMessage("Save " + filePath.toString().replace('\\', '/') + "\n\n"
          + TRAILER_CONTENT_HASH + ": " + contentHash + "\n");
      commitId = inserter.insert(commit);
      inserter.flush();
      advanceBranch(repo, head, commitId);
    }
    refreshIndex(repo);
    return commitId;
  }

  /** True iff every {@code path -> blobId} already resolves to that same blob in {@code headCommit}'s tree. */
  private boolean unchangedAtHead(final Repository repo, final ObjectId headCommit, final Map<String, ObjectId> upserts)
  throws IOException {
    try (RevWalk rw = new RevWalk(repo)) {
      final RevCommit head = rw.parseCommit(headCommit);
      for (final var e : upserts.entrySet()) {
        try (TreeWalk tw = TreeWalk.forPath(repo, e.getKey(), head.getTree())) {
          if (tw == null || !e.getValue().equals(tw.getObjectId(0))) return false;
        }
      }
      return true;
    }
  }

  /**
   * Commit-on-rename (Approach 2). Emit a single commit that deletes the old content+metadata paths and adds
   * them at the new paths with the <em>same</em> content blob, so git records an exact rename and
   * {@code git log --follow <new-path>} walks straight through the file's pre-rename history. Content that
   * genuinely changed in the same operation is captured too (the new blob is whatever is on disk now). A no-op
   * if the workspace isn't a repo yet or nothing actually moved. Takes the per-workspace lock.
   */
  void commitRename(final Path root, final Path oldFilePath, final Path newFilePath, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      migrateLocked(root, userId);

      final var newContentPath = fs.resolveReadingPath(root, newFilePath);
      if (!Files.isRegularFile(newContentPath)) return;               // directory move / vanished — nothing to record
      if (RenderType.isAerieMetadataFile(newContentPath.getFileName().toString())) return;

      final String oldContent = relativize(root, fs.resolveReadingPath(root, oldFilePath));
      final String oldMeta = relativize(root, fs.resolveMetadataPath(root, oldFilePath));
      final String newContent = relativize(root, newContentPath);
      final String newMeta = relativize(root, fs.resolveMetadataPath(root, newFilePath));
      if (oldContent.equals(newContent)) return;                       // not actually a rename

      final byte[] content = Files.readAllBytes(newContentPath);
      final var newMetaPath = fs.resolveMetadataPath(root, newFilePath);
      final byte[] metaBytes = Files.isRegularFile(newMetaPath) ? Files.readAllBytes(newMetaPath) : null;

      try (Repository repo = openRepo(root); ObjectInserter inserter = repo.newObjectInserter()) {
        final ObjectId head = repo.resolve(Constants.HEAD);
        final var upserts = new HashMap<String, ObjectId>();
        upserts.put(newContent, inserter.insert(Constants.OBJ_BLOB, content));
        if (metaBytes != null) upserts.put(newMeta, inserter.insert(Constants.OBJ_BLOB, metaBytes));
        final var deletes = new java.util.HashSet<>(Set.of(oldContent, oldMeta));

        final ObjectId treeId = buildTreeApplying(repo, inserter, head, upserts, deletes);
        final var commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (head != null) commit.setParentId(head);
        final var ident = personIdent(userId);
        commit.setAuthor(ident);
        commit.setCommitter(ident);
        commit.setMessage("Rename " + oldContent + " -> " + newContent + "\n\n"
            + TRAILER_CONTENT_HASH + ": " + contentToken(content) + "\n");
        final ObjectId commitId = inserter.insert(commit);
        inserter.flush();
        advanceBranch(repo, head, commitId);
        refreshIndex(repo);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Commit-on-delete (Approach 2). Emit a commit that removes the file's content+metadata paths from the tree
   * (soft-delete: the bytes remain reachable via ancestor commits and are restorable from history). A no-op if
   * the workspace isn't a repo or the path was never committed. Takes the per-workspace lock. The working copy
   * is expected to already be gone from disk (the delete path removed it before calling this).
   */
  void commitDelete(final Path root, final Path filePath, final String userId)
  throws IOException, WorkspaceFileOpException {
    if (RenderType.isAerieMetadataFile(filePath.getFileName().toString())) return;
    final var lock = lockFor(root);
    lock.lock();
    try {
      if (!isRepo(root)) return; // never migrated → nothing tracked yet

      final String relContent = relativize(root, fs.resolveReadingPath(root, filePath));
      final String relMeta = relativize(root, fs.resolveMetadataPath(root, filePath));

      try (Repository repo = openRepo(root);
           ObjectInserter inserter = repo.newObjectInserter();
           RevWalk rw = new RevWalk(repo)) {
        final ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) return;
        // Nothing to remove if the content path isn't in HEAD's tree.
        try (TreeWalk tw = TreeWalk.forPath(repo, relContent, rw.parseCommit(head).getTree())) {
          if (tw == null) return;
        }

        final ObjectId treeId = buildTreeApplying(repo, inserter, head, Map.of(), Set.of(relContent, relMeta));
        final var commit = new CommitBuilder();
        commit.setTreeId(treeId);
        commit.setParentId(head);
        final var ident = personIdent(userId);
        commit.setAuthor(ident);
        commit.setCommitter(ident);
        commit.setMessage("Delete " + relContent + "\n");
        final ObjectId commitId = inserter.insert(commit);
        inserter.flush();
        advanceBranch(repo, head, commitId);
        refreshIndex(repo);
      }
    } finally {
      lock.unlock();
    }
  }

  /** List a file's revisions, oldest first (by frozen number). Lock-free. Empty if the workspace isn't a repo. */
  List<WorkspaceFileRevision> listRevisions(final Path root, final Path filePath)
  throws IOException, WorkspaceFileOpException {
    if (!isRepo(root)) return List.of();
    final var relContent = relativize(root, fs.resolveReadingPath(root, filePath));
    try (Repository repo = openRepo(root)) {
      return revisionsForFile(repo, repo.resolve(Constants.HEAD), relContent);
    }
  }

  /** Read a revision's bytes (preview). {@code rev} may be a number ("3") or a name ("c"). Lock-free. */
  Optional<byte[]> readRevision(final Path root, final Path filePath, final String rev)
  throws IOException, WorkspaceFileOpException {
    if (!isRepo(root)) return Optional.empty();
    final var relContent = relativize(root, fs.resolveReadingPath(root, filePath));
    try (Repository repo = openRepo(root); RevWalk rw = new RevWalk(repo)) {
      final var match = findRevision(repo, repo.resolve(Constants.HEAD), relContent, rev);
      if (match.isEmpty()) return Optional.empty();
      final var r = match.get();
      // The bytes live at the revision's recorded path (which may differ from the current path if renamed).
      return Optional.ofNullable(readBlobAtPath(repo, rw.parseCommit(ObjectId.fromString(r.commitSha())), r.path()));
    }
  }

  /**
   * Restore the working copy to a revision's content. Non-destructive: revisions/history are untouched, so
   * the restored state can itself become a later revision. The restore is recorded as a save-commit so the
   * working tree stays in sync with git (Approach 2). Takes the per-workspace lock.
   */
  Optional<RestoreResult> restore(final Path root, final Path filePath, final String rev, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      if (!isRepo(root)) return Optional.empty();
      final var contentPath = fs.resolveReadingPath(root, filePath);
      final var relContent = relativize(root, contentPath);

      try (Repository repo = openRepo(root)) {
        final var match = findRevision(repo, repo.resolve(Constants.HEAD), relContent, rev);
        if (match.isEmpty()) return Optional.empty();
        final var r = match.get();

        final byte[] bytes;
        try (RevWalk rw = new RevWalk(repo)) {
          bytes = readBlobAtPath(repo, rw.parseCommit(ObjectId.fromString(r.commitSha())), r.path());
        }
        if (bytes == null) return Optional.empty();

        Files.write(contentPath, bytes); // overwrite working copy — a plain file write, never a merge

        // Record the restore as a save-commit (keeps the working tree == git; no-op if bytes were unchanged).
        // last-edited is derived from git, so no sidecar timestamp update is needed here.
        final var metaPath = fs.resolveMetadataPath(root, filePath);
        final byte[] metaBytes = Files.isRegularFile(metaPath) ? Files.readAllBytes(metaPath) : null;
        commitSaveLocked(repo, root, filePath, bytes, metaBytes, personIdent(userId));

        return Optional.of(new RestoreResult(contentToken(bytes), r.number(), r.name()));
      }
    } finally {
      lock.unlock();
    }
  }

  // region Tag-based revision helpers (Approach 2)

  /** The latest commit reachable from {@code head} that modified {@code path}, or null if none. */
  private RevCommit latestCommitTouching(final Repository repo, final ObjectId head, final String path)
  throws IOException {
    if (head == null) return null;
    try (RevWalk rw = new RevWalk(repo)) {
      rw.setTreeFilter(AndTreeFilter.create(PathFilterGroup.createFromStrings(path), TreeFilter.ANY_DIFF));
      rw.markStart(rw.parseCommit(head));
      return rw.next();
    }
  }

  /**
   * The set of paths the file currently at {@code currentPath} has occupied over its history, following
   * renames via {@code git log --follow}. Used so a revision tagged before a rename still lists after it.
   */
  private Set<String> historicalPaths(final Repository repo, final ObjectId head, final String currentPath)
  throws IOException {
    final Set<String> paths = new HashSet<>();
    paths.add(currentPath);
    if (head == null) return paths;
    try (RevWalk rw = new RevWalk(repo)) {
      final FollowFilter follow = FollowFilter.create(currentPath, repo.getConfig().get(DiffConfig.KEY));
      follow.setRenameCallback(new RenameCallback() {
        @Override public void renamed(final DiffEntry entry) {
          paths.add(entry.getOldPath());
          paths.add(entry.getNewPath());
        }
      });
      rw.setTreeFilter(follow);
      rw.markStart(rw.parseCommit(head));
      for (final RevCommit ignored : rw) { /* drain the walk so the rename callback fires */ }
    }
    return paths;
  }

  /**
   * All revisions belonging to the file currently at {@code currentPath}: every {@code refs/tags/seqdev/rev/}
   * annotated tag whose recorded path is one this file has occupied ({@link #historicalPaths}). Sorted by the
   * frozen per-file number.
   */
  private List<WorkspaceFileRevision> revisionsForFile(final Repository repo, final ObjectId head, final String currentPath)
  throws IOException {
    if (head == null) return List.of();
    final Set<String> historical = historicalPaths(repo, head, currentPath);
    final var out = new ArrayList<WorkspaceFileRevision>();
    try (RevWalk rw = new RevWalk(repo)) {
      for (final var ref : repo.getRefDatabase().getRefsByPrefix(REV_TAG_PREFIX)) {
        final RevTag tag;
        try {
          tag = rw.parseTag(ref.getObjectId());
        } catch (IncorrectObjectTypeException e) {
          continue; // a lightweight (non-annotated) tag under our namespace — skip
        }
        final var trailers = parseTrailers(tag.getFullMessage());
        final String revPath = trailers.get(TRAILER_REV_PATH);
        if (revPath == null || !historical.contains(revPath)) continue;

        final RevCommit target = rw.parseCommit(tag.getObject());
        final Integer parsed = tryParseInt(trailers.getOrDefault(TRAILER_NUMBER, ""));
        final int number = parsed == null ? 0 : parsed;
        final String name = trailers.getOrDefault(TRAILER_NAME, RevisionName.forNumber(Math.max(1, number)));
        out.add(new WorkspaceFileRevision(
            number, name, revPath, trailers.getOrDefault(TRAILER_CONTENT_HASH, ""),
            tag.getTaggerIdent().getName(),
            tag.getTaggerIdent().getWhen().toInstant().toString(),
            userMessageFromTag(tag.getFullMessage()),
            target.getName()));
      }
    }
    out.sort(Comparator.comparingInt(WorkspaceFileRevision::number));
    return out;
  }

  /** Find one of this file's revisions by number ("3") or name ("c"). */
  private Optional<WorkspaceFileRevision> findRevision(
      final Repository repo, final ObjectId head, final String currentPath, final String rev)
  throws IOException {
    final Integer n = tryParseInt(rev);
    return revisionsForFile(repo, head, currentPath).stream()
        .filter(r -> n != null ? r.number() == n : r.name().equals(rev))
        .findFirst();
  }

  /** Extract the free-form user message from an annotated tag's body (subject line + trailers stripped). */
  private String userMessageFromTag(final String fullMessage) {
    final var lines = fullMessage.split("\n");
    final var sb = new StringBuilder();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].startsWith("Seqdev-")) continue;
      sb.append(lines[i]).append("\n");
    }
    return sb.toString().trim();
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

      // Approach 2: identity is the path (git log --follow), so no synthetic fileId is minted.
      git.add().addFilepattern(".").call();
      final RevCommit baseline = git.commit()
          .setMessage("Baseline\n\nSeqdev baseline snapshot for workspace versioning.")
          .setAuthor(personIdent(userId))
          .setCommitter(personIdent(userId))
          .setAllowEmpty(true)
          .call();

      // Approach 2: migration establishes git *history* (this baseline commit), but does NOT create a
      // revision — revisions are user-triggered tags. A freshly-migrated file has zero revisions until
      // someone creates one; the baseline is its restore-able starting point in history regardless.
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
    // Give the repo a default identity so plumbing/merge commits never fail for lack of user.* config.
    cfg.setString("user", null, "name", "seqdev");
    cfg.setString("user", null, "email", "seqdev@seqdev.local");
    cfg.save();

    // Approach 2: never line-merge workspace content. With every path marked non-mergeable, a git merge
    // resolves at the FILE level only — a file changed on one side is taken whole; a file changed on BOTH
    // sides conflicts (→ merge-or-abort punts it), so git never silently blends two sequences. We write this
    // to .git/info/attributes (repo-local): it is honored for PlanDev's own merges without adding a
    // working-tree/mirror-visible .gitattributes file.
    final var infoDir = repo.getDirectory().toPath().resolve("info");
    Files.createDirectories(infoDir);
    Files.writeString(infoDir.resolve("attributes"), "* -merge\n");
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

  private byte[] readBlobAtPath(final Repository repo, final RevCommit commit, final String pathStr)
  throws IOException {
    try (TreeWalk tw = TreeWalk.forPath(repo, pathStr, commit.getTree())) {
      if (tw == null) return null;
      final ObjectId blobId = tw.getObjectId(0);
      return repo.open(blobId, Constants.OBJ_BLOB).getBytes();
    }
  }

  /** Every non-metadata content path in a commit's tree (recursive). Used by workspace-checkpoint diffing. */
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

  /** Every non-metadata content path currently in the working copy (excluding {@code .git}). */
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

  /** The SHA-256 content token of a path in a commit's tree, or null if absent. */
  private String tokenInTree(final Repository repo, final RevCommit commit, final String path) throws IOException {
    if (commit == null) return null;
    final byte[] bytes = readBlobAtPath(repo, commit, path);
    return bytes == null ? null : contentToken(bytes);
  }

  /** The SHA-256 content token of a working-copy path, or null if absent. */
  private String tokenWorkingCopy(final Path root, final String path) throws IOException {
    final var p = root.resolve(path);
    if (!Files.isRegularFile(p)) return null;
    return contentToken(Files.readAllBytes(p));
  }


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
   * {@code removedPaths} existed but weren't in the checkpoint (created since) and were soft-deleted so the
   * workspace matches the checkpoint — recoverable from git history.
   */
  public record RestoreCheckpointResult(List<String> restoredPaths, List<String> removedPaths) {}

  private record PendingFile(String path, String contentHash) {}

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
        // A checkpoint captures the whole workspace as it is now, so "N files" is the number of files the
        // checkpoint CONTAINS (= the current working-copy file set), not the number changed since HEAD.
        // (Under commit-on-save nothing is ever "dirty" at snapshot time, so a changed-count would read 0.)
        final int fileCount = workingPaths.size();

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
            final byte[] content = Files.readAllBytes(contentPath);
            upserts.put(relativize(root, contentPath), inserter.insert(Constants.OBJ_BLOB, content));
            if (Files.isRegularFile(metaPath)) {
              upserts.put(relativize(root, metaPath), inserter.insert(Constants.OBJ_BLOB, Files.readAllBytes(metaPath)));
            }
            pending.add(new PendingFile(relativize(root, contentPath), contentToken(content)));
          }

          // Tree = HEAD's tree with the dirty files' new blobs and the deleted files removed
          // (no changes at all → an empty marker commit).
          final ObjectId treeId = buildTreeApplying(repo, inserter, head, upserts, treeDeletes);
          final var commit = new CommitBuilder();
          commit.setTreeId(treeId);
          if (head != null) commit.setParentId(head);
          commit.setAuthor(ident);
          commit.setCommitter(ident);
          commit.setMessage(buildWsMessage(wsName, wsNumber, fileCount, message));
          checkpointCommit = inserter.insert(commit);
          inserter.flush();

          advanceBranch(repo, head, checkpointCommit);

          try (RevWalk rw = new RevWalk(repo)) {
            final RevCommit checkpointRev = rw.parseCommit(checkpointCommit);
            for (final var p : pending) {
              // Each dirty file gets its next revision as a tag on the shared checkpoint commit; the tag
              // records that file's own path, so per-file listing (git log --follow) resolves it correctly.
              fileRevisions.add(tagRevisionOnCommit(
                  repo, p.path(), checkpointRev, checkpointCommit, ident, Optional.empty(), message.orElse(null)));
            }
          }
        }

        writeRef(repo, wsRef(wsNumber), checkpointCommit);
        refreshIndex(repo);

        final var checkpoint = new WorkspaceCheckpoint(
            wsNumber, wsName, checkpointCommit.getName(), ident.getName(),
            Instant.now().toString(), message.orElse(""), fileCount);
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
   * Restore the workspace to a checkpoint so it MATCHES that checkpoint. Every file in the checkpoint is reset
   * to its checkpoint content + metadata (recreating any deleted since), and files created since the checkpoint
   * are soft-deleted (removed from the working copy; content stays in git history → recoverable). A plain
   * working-copy operation — history is untouched. Takes the per-workspace lock.
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

          // Files present now but absent from the checkpoint were created since → remove them so the
          // workspace actually MATCHES the checkpoint. Soft-delete: the content remains in git history
          // (reachable from prior commits), so a removed file is recoverable.
          final var removed = new ArrayList<String>();
          for (final var path : currentPaths) {
            if (checkpointPaths.contains(path)) continue;
            Files.deleteIfExists(fs.resolveReadingPath(root, Path.of(path)));
            Files.deleteIfExists(fs.resolveMetadataPath(root, Path.of(path)));
            removed.add(path);
          }

          return Optional.of(new RestoreCheckpointResult(restored, removed));
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

  // region Inbound merge-or-abort (Approach 2) — bring external changes back via a real git merge.
  //
  // PlanDev pulls a fetched external branch (here the refs/seqdev/incoming ref, populated by the test helper
  // below that stands in for the network fetch) and attempts a real git merge into the workspace branch.
  // Because .git/info/attributes marks every path non-mergeable (* -merge), git merges at the FILE level:
  // a file changed on only one side is taken whole; a file changed on BOTH sides is a conflict. On ANY
  // conflict we `git merge --abort` (hard-reset to the pre-merge HEAD — safe because commit-on-save means the
  // working copy already == HEAD, so nothing uncommitted is lost) and punt to the external git users to
  // resolve upstream and re-push. No conflict markers ever land on disk, and PlanDev never runs a line merge.

  static final String REF_INCOMING = "refs/seqdev/incoming";

  public enum MergeOutcome {
    /** No incoming branch staged — nothing to pull. */
    NO_INCOMING,
    /** The workspace branch already contains the incoming commit — no-op. */
    ALREADY_UP_TO_DATE,
    /** The merge applied cleanly (file-level); the working copy now reflects the merged result. */
    MERGED,
    /** A file changed on both sides — the merge was aborted (no markers) and must be resolved upstream. */
    ABORTED_CONFLICTS
  }

  /** Outcome of an inbound pull. {@code changedPaths} on MERGED; {@code conflictedPaths} on ABORTED_CONFLICTS. */
  public record MergeReport(MergeOutcome outcome, List<String> changedPaths, List<String> conflictedPaths) {}

  public MergeReport mergeIncoming(final int workspaceId, final String userId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return mergeIncoming(rootOf(workspaceId), userId);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Approach 2 derives created/last-edited from git rather than storing them in the sidecar: the earliest
   * commit touching the file's path ({@code git log --follow}) is its creation, the latest is its most recent
   * edit. Lock-free. Empty when the workspace isn't a repo or the current path has no committed history (e.g.
   * a soft-deleted file), so callers fall back to any stored sidecar values.
   */
  @Override
  public Optional<FileHistoryInfo> fileHistory(final int workspaceId, final Path filePath)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return fileHistory(rootOf(workspaceId), filePath);
  }

  Optional<FileHistoryInfo> fileHistory(final Path root, final Path filePath)
  throws IOException, WorkspaceFileOpException {
    if (!isRepo(root)) return Optional.empty();
    final var relContent = relativize(root, fs.resolveReadingPath(root, filePath));
    try (Repository repo = openRepo(root); RevWalk rw = new RevWalk(repo)) {
      final ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) return Optional.empty();
      rw.setTreeFilter(FollowFilter.create(relContent, repo.getConfig().get(DiffConfig.KEY)));
      rw.markStart(rw.parseCommit(head));
      RevCommit newest = null;
      RevCommit oldest = null;
      for (final RevCommit c : rw) {
        if (newest == null) newest = c; // walk yields newest-first
        oldest = c;
      }
      if (newest == null) return Optional.empty();
      return Optional.of(historyInfo(oldest, newest));
    }
  }

  @Override
  public Map<String, FileHistoryInfo> fileHistories(final int workspaceId)
  throws NoSuchWorkspaceException, IOException, WorkspaceFileOpException {
    return fileHistories(rootOf(workspaceId));
  }

  Map<String, FileHistoryInfo> fileHistories(final Path root) throws IOException {
    if (!isRepo(root)) return Map.of();
    try (Repository repo = openRepo(root); RevWalk rw = new RevWalk(repo)) {
      final ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) return Map.of();
      final RevCommit headCommit = rw.parseCommit(head);
      final Set<String> live = contentPathsInTree(repo, headCommit); // only files still present

      // One newest-first pass: the first commit that touches a path is its last edit; the last is its creation.
      final Map<String, RevCommit> newest = new HashMap<>();
      final Map<String, RevCommit> oldest = new HashMap<>();
      rw.markStart(headCommit);
      for (final RevCommit c : rw) {
        for (final String path : changedPathsAgainstParent(repo, c)) {
          if (!live.contains(path)) continue;
          newest.putIfAbsent(path, c);
          oldest.put(path, c);
        }
      }

      final Map<String, FileHistoryInfo> out = new HashMap<>();
      for (final String path : live) {
        final RevCommit o = oldest.get(path);
        final RevCommit n = newest.get(path);
        if (o != null && n != null) out.put(path, historyInfo(o, n));
      }
      return out;
    }
  }

  /** Build a {@link FileHistoryInfo} from the oldest (created) and newest (last-edited) commits touching a path. */
  private FileHistoryInfo historyInfo(final RevCommit oldest, final RevCommit newest) {
    return new FileHistoryInfo(
        oldest.getAuthorIdent().getName(), oldest.getAuthorIdent().getWhen().toInstant().toString(),
        newest.getAuthorIdent().getName(), newest.getAuthorIdent().getWhen().toInstant().toString());
  }

  /** Non-metadata content paths changed by {@code c} relative to its first parent (all paths for a root commit). */
  private Set<String> changedPathsAgainstParent(final Repository repo, final RevCommit c) throws IOException {
    if (c.getParentCount() == 0) {
      try (RevWalk rw = new RevWalk(repo)) {
        return contentPathsInTree(repo, rw.parseCommit(c.getId()));
      }
    }
    return new HashSet<>(changedContentPaths(repo, c.getParent(0).getId(), c.getId()));
  }

  MergeReport mergeIncoming(final Path root, final String userId)
  throws IOException, WorkspaceFileOpException {
    final var lock = lockFor(root);
    lock.lock();
    try {
      if (!isRepo(root)) return new MergeReport(MergeOutcome.NO_INCOMING, List.of(), List.of());
      try (Repository repo = openRepo(root)) {
        final var incomingRef = repo.exactRef(REF_INCOMING);
        if (incomingRef == null) return new MergeReport(MergeOutcome.NO_INCOMING, List.of(), List.of());
        final ObjectId incoming = incomingRef.getObjectId();
        final ObjectId preMerge = repo.resolve(Constants.HEAD);

        setRepoUser(repo, userId); // attribute the merge commit to the puller
        final Git git = Git.wrap(repo);
        final MergeResult mr;
        try {
          mr = git.merge()
              .include(incoming)
              .setCommit(true)
              .setStrategy(MergeStrategy.RECURSIVE)
              .setMessage("Merge incoming external changes")
              .call();
        } catch (GitAPIException e) {
          throw new IOException("Failed to merge incoming changes for workspace at " + root, e);
        }

        switch (mr.getMergeStatus()) {
          case ALREADY_UP_TO_DATE -> {
            return new MergeReport(MergeOutcome.ALREADY_UP_TO_DATE, List.of(), List.of());
          }
          case FAST_FORWARD, MERGED -> {
            refreshIndex(repo);
            final var changed = changedContentPaths(repo, preMerge, repo.resolve(Constants.HEAD));
            return new MergeReport(MergeOutcome.MERGED, changed, List.of());
          }
          default -> {
            // CONFLICTING / FAILED / CHECKOUT_CONFLICT → abort cleanly (no markers), punt upstream.
            final var conflicts = new TreeSet<String>();
            if (mr.getConflicts() != null) conflicts.addAll(mr.getConflicts().keySet());
            if (mr.getFailingPaths() != null) conflicts.addAll(mr.getFailingPaths().keySet());
            try {
              git.reset().setMode(ResetCommand.ResetType.HARD).setRef(preMerge.getName()).call();
            } catch (GitAPIException e) {
              throw new IOException("Failed to abort a conflicted merge for workspace at " + root, e);
            }
            return new MergeReport(MergeOutcome.ABORTED_CONFLICTS, List.of(), List.copyOf(conflicts));
          }
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /** Set the repo's committer identity (used for the merge commit) to the acting user. */
  private void setRepoUser(final Repository repo, final String userId) throws IOException {
    final var who = (userId == null || userId.isBlank()) ? "seqdev" : userId;
    final var cfg = repo.getConfig();
    cfg.setString("user", null, "name", who);
    cfg.setString("user", null, "email", who + "@seqdev.local");
    cfg.save();
  }

  /** Non-metadata content paths that differ between two commits (informational, for the merge report). */
  private List<String> changedContentPaths(final Repository repo, final ObjectId a, final ObjectId b)
  throws IOException {
    final var out = new ArrayList<String>();
    try (RevWalk rw = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
      tw.addTree(rw.parseCommit(a).getTree());
      tw.addTree(rw.parseCommit(b).getTree());
      tw.setRecursive(true);
      tw.setFilter(TreeFilter.ANY_DIFF);
      while (tw.next()) {
        final var path = tw.getPathString();
        final var name = path.substring(path.lastIndexOf('/') + 1);
        if (!RenderType.isAerieMetadataFile(name)) out.add(path);
      }
    }
    return out;
  }

  /**
   * TEST/SPIKE ONLY — stands in for the network fetch of an external branch. Builds a commit on top of the
   * current HEAD applying {@code changes} (path → bytes) and points {@link #REF_INCOMING} at it, so a later
   * {@link #mergeIncoming} sees "an external writer committed and we fetched." To model divergence, call this
   * before making the PlanDev-side commit so both derive from the same base.
   */
  void stageIncomingBranch(final Path root, final Map<String, byte[]> changes, final String authorName, final String message)
  throws IOException {
    try (Repository repo = openRepo(root); ObjectInserter inserter = repo.newObjectInserter()) {
      final ObjectId head = repo.resolve(Constants.HEAD);
      final var upserts = new HashMap<String, ObjectId>();
      for (final var e : changes.entrySet()) {
        upserts.put(e.getKey(), inserter.insert(Constants.OBJ_BLOB, e.getValue()));
      }
      final ObjectId tree = buildTreeWith(repo, inserter, head, upserts);
      final var commit = new CommitBuilder();
      commit.setTreeId(tree);
      if (head != null) commit.setParentId(head);
      final var who = new PersonIdent(authorName, authorName + "@ext.local");
      commit.setAuthor(who);
      commit.setCommitter(who);
      commit.setMessage(message);
      final ObjectId cid = inserter.insert(commit);
      inserter.flush();
      writeRef(repo, REF_INCOMING, cid);
    }
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

  private PersonIdent personIdent(final String userId) {
    final var who = (userId == null || userId.isBlank()) ? "seqdev" : userId;
    return new PersonIdent(who, who + "@seqdev.local");
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
