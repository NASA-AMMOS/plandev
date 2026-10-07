package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistory.Kind;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistory.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceRevisionStore.Revision;
import gov.nasa.ammos.plandev.workspace.server.exceptions.FileLockedException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchRevisionException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.StaleFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SeqDev file revisions: explicit, immutable, per-file bookmarks of a file's committed state.
 *
 * <p>Git commits are internal workspace history; a save makes a commit, never a revision. A revision is made only on
 * request. It records the file's state at the current {@code HEAD} (content plus versioned sidecar metadata), so
 * making one changes no file and makes no commit, except that a file without an identity first gets one: a single
 * internal "Assign file identity" commit. Revisions belong to a file's stable {@code fileId} (kept in its sidecar),
 * not its path, so a rename within the workspace keeps them; a copy, a re-created path, or a file arriving from another
 * workspace is a new file with none.
 *
 * <p><b>Representation.</b> Git is the authority: a revision exists because its annotated tag
 * {@code plandev/revisions/<revision id>} exists ({@link GitFileRevisions}). The {@link WorkspaceRevisionStore} is a
 * projection of those tags for queries; reads go through it, and {@link #reindexRevisionsFromGit} rebuilds it.
 *
 * <p><b>Consistency.</b> Under the workspace lock, the next ordinal is derived from the tags, the tag is created, and
 * then the row is projected. A tag failure means no revision. A projection failure after the tag exists does not undo
 * it: the revision exists, a {@link RevisionNotIndexedException} names it, and a reindex makes it visible.
 *
 * <p><b>Reads</b> of historical content use the revision's commit tree directly; nothing is checked out, so HEAD, the
 * index and the working tree are never touched. A row whose commit or file is missing from the repository is a
 * {@link Kind#REVISION_CATALOG_INCONSISTENT} error, not a missing revision.
 *
 * <p><b>Restore</b> writes a revision's content and versioned metadata to the file's <i>current</i> path as an
 * ordinary workspace mutation: it respects readOnly and If-Match (against the content-plus-versioned-metadata
 * {@link RevisionList#workingCopyETag}, not the content-only save ETag), keeps the file's identity and runtime
 * state, and makes a normal commit, not a revision.
 */
public class WorkspaceRevisionService {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceRevisionService.class);

  /** The revision was created (its tag exists) but is not yet in the catalog; {@link #reindexRevisionsFromGit} repairs it. */
  public static final class RevisionNotIndexedException extends WorkspaceHistoryException {
    public final UUID revisionId;

    RevisionNotIndexedException(final Revision revision, final Exception cause) {
      super(Kind.REVISION_NOT_INDEXED,
            "Revision %s (%s) of %s was created, but could not be added to the revision index; reindex the workspace's revisions."
                .formatted(revision.name(), revision.id(), revision.pathAtRevision()),
            cause);
      this.revisionId = revision.id();
    }
  }

  /**
   * A file's revisions, oldest first; {@code changedSinceLatest} is empty when it has none. {@code workingCopyETag} is
   * the token {@link #restore} compares If-Match against (content plus versioned metadata).
   */
  public record RevisionList(
      Optional<UUID> fileId,
      List<Revision> revisions,
      Optional<Boolean> changedSinceLatest,
      String workingCopyETag) {}

  /** A revision's content and its versioned sidecar metadata. */
  public record HistoricalFile(Revision revision, byte[] content, JsonObject metadata) {}

  public record Restored(Revision revision, String etag) {}

  private final WorkspaceRoots roots;
  private final WorkspaceHistory history;
  private final WorkspaceFileSystemService files;
  private final WorkspaceRevisionStore store;

  public WorkspaceRevisionService(
      final WorkspaceRoots roots,
      final WorkspaceHistory history,
      final WorkspaceFileSystemService files,
      final WorkspaceRevisionStore store)
  {
    this.roots = roots;
    this.history = history;
    this.files = files;
    this.store = store;
  }

  /** Record the file's current committed state as its next revision. */
  public Revision create(final int workspaceId, final Path filePath, final String userId) throws Exception {
    return history.withTrustedWorkspace(workspaceId, () -> {
      final var root = root(workspaceId);
      final var key = requireFile(workspaceId, root, filePath);
      final var existing = files.fileId(root, filePath);
      final var fileId = existing.isPresent()
          ? existing.get()
          : history.mutate(workspaceId, null, "Assign file identity " + key, // internal, system-authored commit
                           () -> files.ensureFileId(workspaceId, filePath, userId), id -> true);

      final Revision revision;
      try (final var git = Git.open(root.toFile())) {
        final var repo = git.getRepository();
        // ponytail: parses and validates every revision tag on each create; cache an index if workspaces get large
        final var ordinal = 1 + GitFileRevisions.readAll(repo, workspaceId).stream()
            .filter(r -> r.fileId().equals(fileId))
            .mapToLong(Revision::ordinal)
            .max().orElse(0);
        final var head = repo.resolve(Constants.HEAD);
        // Microseconds: the catalog's timestamp precision, so a row and its tag hold the same instant
        revision = new Revision(UUID.randomUUID(), workspaceId, fileId, ordinal, WorkspaceRevisionStore.revisionName(ordinal),
                                key, head.name(), userId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        createTag(git, revision, head); // from here on, the revision exists
      }
      try {
        store.insert(revision);
      } catch (Exception e) {
        logger.error("Revision {} was created but not indexed", revision.id(), e);
        throw new RevisionNotIndexedException(revision, e);
      }
      return revision;
    });
  }

  /**
   * Rebuild the workspace's catalog from its revision tags. Every tag is validated first; if any is invalid, the
   * catalog is left untouched. Otherwise it is replaced, in one transaction, by exactly what the tags record.
   * @return the revisions now in the catalog
   */
  public List<Revision> reindexRevisionsFromGit(final int workspaceId) throws Exception {
    return history.withTrustedWorkspace(workspaceId, () -> {
      final List<Revision> revisions;
      try (final var repo = Git.open(root(workspaceId).toFile()).getRepository()) {
        revisions = GitFileRevisions.readAll(repo, workspaceId);
      }
      store.replaceWorkspaceRevisions(workspaceId, revisions);
      return revisions;
    });
  }

  /** The file's revisions, and whether its committed state differs from the latest one. Lock-free. */
  public RevisionList list(final int workspaceId, final Path filePath) throws Exception {
    final var root = root(workspaceId);
    final var key = requireFile(workspaceId, root, filePath);
    final var fileId = files.fileId(root, filePath);
    final var etag = files.revisionStateETag(root, filePath);
    if (fileId.isEmpty()) return new RevisionList(fileId, List.of(), Optional.empty(), etag);

    final var revisions = store.list(workspaceId, fileId.get());
    if (revisions.isEmpty()) return new RevisionList(fileId, revisions, Optional.empty(), etag);
    final var latest = revisions.getLast();
    try (final var repo = Git.open(root.toFile()).getRepository(); final var walk = new RevWalk(repo)) {
      final var head = repo.resolve(Constants.HEAD);
      if (head == null) return new RevisionList(fileId, revisions, Optional.of(true), etag);
      final var headTree = walk.parseCommit(head).getTree();
      final var revisionTree = revisionTree(repo, walk, latest);
      final var changed = !(blobId(repo, headTree, key).equals(requireBlobId(repo, revisionTree, latest, latest.pathAtRevision()))
                            && blobId(repo, headTree, GitFileRevisions.sidecarKey(key))
                                .equals(requireBlobId(repo, revisionTree, latest, GitFileRevisions.sidecarKey(latest.pathAtRevision()))));
      return new RevisionList(fileId, revisions, Optional.of(changed), etag);
    }
  }

  public Revision get(final int workspaceId, final UUID revisionId) throws Exception {
    root(workspaceId); // NoSuchWorkspaceException before NoSuchRevisionException
    return store.get(workspaceId, revisionId).orElseThrow(() -> new NoSuchRevisionException(workspaceId, revisionId));
  }

  /** A revision's content and versioned metadata, read from its commit without touching the working tree. Lock-free. */
  public HistoricalFile read(final int workspaceId, final UUID revisionId) throws Exception {
    return read(root(workspaceId), get(workspaceId, revisionId));
  }

  /**
   * Replace the file's working copy (content and versioned metadata) with one of its revisions, as a normal commit.
   * @param ifMatch the {@link RevisionList#workingCopyETag} the client is replacing (content plus versioned metadata),
   *                or {@code "*"} to replace whatever is there
   */
  public Restored restore(
      final int workspaceId,
      final Path filePath,
      final UUID revisionId,
      final String ifMatch,
      final String userId) throws Exception
  {
    if (ifMatch == null) throw new IllegalArgumentException("Restoring a revision requires an If-Match ETag.");
    final var root = root(workspaceId);
    final var revision = get(workspaceId, revisionId); // rows are immutable, so reading it outside the lock is safe
    final var key = WorkspacePaths.key(root, files.resolveReadingPath(root, filePath));
    final var message = "Restore %s to revision %s".formatted(key, revision.name());

    return history.mutate(workspaceId, userId, message, () -> {
      requireFile(workspaceId, root, filePath);
      if (!files.fileId(root, filePath).equals(Optional.of(revision.fileId()))) {
        throw new NoSuchRevisionException(revisionId, key);
      }
      if (files.isReadOnly(workspaceId, filePath)) throw new FileLockedException(filePath);
      if (!ifMatch.equals("*")) {
        final var current = files.revisionStateETag(root, filePath);
        if (!current.equals(ifMatch)) {
          final var lastEdit = files.getLastEditInfo(workspaceId, filePath);
          throw new StaleFileException(current, lastEdit.lastEditedBy(), lastEdit.lastEditedAt());
        }
      }
      final var historical = read(root, revision);
      return new Restored(revision, files.restoreFile(workspaceId, filePath, historical.content(), historical.metadata()));
    }, restored -> true);
  }

  //region Git (overridable only so tests can inject failures)
  protected void createTag(final Git git, final Revision revision, final ObjectId commit) throws Exception {
    try (final var walk = new RevWalk(git.getRepository())) {
      final var name = revision.createdBy() == null || revision.createdBy().isBlank()
          ? WorkspaceHistory.systemIdent().getName()
          : revision.createdBy();
      git.tag()
         .setName(GitFileRevisions.TAG_PREFIX + revision.id())
         .setObjectId(walk.parseCommit(commit))
         .setAnnotated(true)
         .setSigned(false)
         .setTagger(new PersonIdent(name, "", revision.createdAt(), ZoneOffset.UTC))
         .setMessage(GitFileRevisions.annotation(revision))
         .call();
    }
  }
  //endregion

  private HistoricalFile read(final Path root, final Revision revision) throws IOException {
    try (final var repo = Git.open(root.toFile()).getRepository(); final var walk = new RevWalk(repo)) {
      final var tree = revisionTree(repo, walk, revision);
      final var content = repo.open(requireBlobId(repo, tree, revision, revision.pathAtRevision())).getBytes();
      final var sidecar = new String(
          repo.open(requireBlobId(repo, tree, revision, GitFileRevisions.sidecarKey(revision.pathAtRevision()))).getBytes(),
          StandardCharsets.UTF_8);
      final JsonObject metadata;
      try (final var reader = Json.createReader(new StringReader(sidecar))) {
        metadata = WorkspaceFileSystemService.versionedSidecar(reader.readObject());
      } catch (JsonException e) {
        throw inconsistent(revision, "its metadata is not a JSON object");
      }
      return new HistoricalFile(revision, content, metadata);
    }
  }

  private static RevTree revisionTree(final Repository repo, final RevWalk walk, final Revision revision) throws IOException {
    try {
      return walk.parseCommit(ObjectId.fromString(revision.commitSha())).getTree();
    } catch (MissingObjectException | IllegalArgumentException e) {
      throw inconsistent(revision, "its commit " + revision.commitSha() + " is missing from " + repo.getWorkTree());
    }
  }

  /** The blob at a path in a tree, or {@link ObjectId#zeroId()} if there is none. */
  private static ObjectId blobId(final Repository repo, final RevTree tree, final String path) throws IOException {
    try (final var tw = TreeWalk.forPath(repo, path, tree)) {
      return tw == null ? ObjectId.zeroId() : tw.getObjectId(0);
    }
  }

  private static ObjectId requireBlobId(final Repository repo, final RevTree tree, final Revision revision, final String path)
  throws IOException
  {
    final var id = blobId(repo, tree, path);
    if (id.equals(ObjectId.zeroId())) throw inconsistent(revision, path + " is missing from its commit");
    return id;
  }

  private static WorkspaceHistoryException inconsistent(final Revision revision, final String problem) {
    return new WorkspaceHistoryException(
        Kind.REVISION_CATALOG_INCONSISTENT,
        "Revision %s (%s) of %s cannot be read: %s.".formatted(revision.name(), revision.id(), revision.pathAtRevision(), problem),
        null);
  }

  private Path root(final int workspaceId) throws NoSuchWorkspaceException {
    return roots.workspaceRootPath(workspaceId).normalize();
  }

  /** The workspace key of a regular content file; directories, sidecars and missing files have no revisions. */
  private String requireFile(final int workspaceId, final Path root, final Path filePath)
  throws NoSuchFileException, WorkspaceFileOpException
  {
    final var path = files.resolveReadingPath(root, filePath);
    if (RenderType.isAerieMetadataFile(path.getFileName().toString())) {
      throw new WorkspaceFileOpException("Metadata files do not have revisions.");
    }
    if (Files.isDirectory(path)) throw new WorkspaceFileOpException("Directories do not have revisions.");
    if (!Files.isRegularFile(path)) throw new NoSuchFileException(workspaceId, filePath);
    return WorkspacePaths.key(root, path);
  }
}
