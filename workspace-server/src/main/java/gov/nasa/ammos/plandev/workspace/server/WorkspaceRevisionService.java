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
import gov.nasa.ammos.plandev.workspace.server.types.MetadataKeys;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><b>Representation.</b> Each revision is a row in the {@link WorkspaceRevisionStore} (the authoritative catalog)
 * mirrored by an annotated tag {@code plandev/revisions/<revision id>} on its commit, for discoverability and export.
 * Reads go through the catalog only; tags are never enumerated as revisions.
 *
 * <p><b>Consistency.</b> The catalog and Git cannot share a transaction. The row is inserted in an open transaction,
 * then the tag is created, then the transaction commits. A tag failure rolls the row back. A commit failure deletes
 * the new tag; if that also fails, a {@link Kind#REVISION_CATALOG_INCONSISTENT} error names the orphaned tag. A
 * revision is never reported as created unless both exist.
 *
 * <p><b>Reads</b> of historical content use the revision's commit tree directly; nothing is checked out, so HEAD, the
 * index and the working tree are never touched. A row whose commit or file is missing from the repository is a
 * {@link Kind#REVISION_CATALOG_INCONSISTENT} error, not a missing revision.
 *
 * <p><b>Restore</b> writes a revision's content and versioned metadata to the file's <i>current</i> path as an
 * ordinary workspace mutation: it respects readOnly and the If-Match ETag, keeps the file's identity and runtime
 * state, and makes a normal commit, not a revision.
 */
public class WorkspaceRevisionService {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceRevisionService.class);
  static final String TAG_PREFIX = "plandev/revisions/";

  /** A file's revisions, oldest first; {@code changedSinceLatest} is empty when it has none. */
  public record RevisionList(Optional<UUID> fileId, List<Revision> revisions, Optional<Boolean> changedSinceLatest) {}

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

      try (final var git = Git.open(root.toFile())) {
        final var head = git.getRepository().resolve(Constants.HEAD);
        final var tagged = new AtomicReference<Revision>();
        try {
          return store.create(workspaceId, fileId, key, head.name(), userId, revision -> {
            createTag(git, revision, head);
            tagged.set(revision);
          });
        } catch (Exception e) {
          if (tagged.get() != null) removeOrphanedTag(git, tagged.get(), e);
          throw e;
        }
      }
    });
  }

  /** The file's revisions, and whether its committed state differs from the latest one. Lock-free. */
  public RevisionList list(final int workspaceId, final Path filePath) throws Exception {
    final var root = root(workspaceId);
    final var key = requireFile(workspaceId, root, filePath);
    final var fileId = files.fileId(root, filePath);
    if (fileId.isEmpty()) return new RevisionList(fileId, List.of(), Optional.empty());

    final var revisions = store.list(workspaceId, fileId.get());
    if (revisions.isEmpty()) return new RevisionList(fileId, revisions, Optional.empty());
    final var latest = revisions.getLast();
    try (final var repo = Git.open(root.toFile()).getRepository(); final var walk = new RevWalk(repo)) {
      final var head = repo.resolve(Constants.HEAD);
      if (head == null) return new RevisionList(fileId, revisions, Optional.of(true));
      final var headTree = walk.parseCommit(head).getTree();
      final var revisionTree = revisionTree(repo, walk, latest);
      final var changed = !(blobId(repo, headTree, key).equals(requireBlobId(repo, revisionTree, latest, latest.pathAtRevision()))
                            && blobId(repo, headTree, sidecarKey(key))
                                .equals(requireBlobId(repo, revisionTree, latest, sidecarKey(latest.pathAtRevision()))));
      return new RevisionList(fileId, revisions, Optional.of(changed));
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
   * @param ifMatch the ETag of the working copy the client is replacing, or {@code "*"} to replace whatever is there
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
        final var current = files.getETag(workspaceId, filePath);
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
         .setName(TAG_PREFIX + revision.id())
         .setObjectId(walk.parseCommit(commit))
         .setAnnotated(true)
         .setSigned(false)
         .setTagger(new PersonIdent(name, "", revision.createdAt(), ZoneOffset.UTC))
         .setMessage(annotation(revision))
         .call();
    }
  }

  protected void deleteTag(final Git git, final String tagName) throws Exception {
    if (git.tagDelete().setTags(tagName).call().isEmpty()) throw new IOException("Tag " + tagName + " was not deleted");
  }
  //endregion

  /** The annotated tag's message: a deliberately small, versioned JSON record of the revision. */
  static String annotation(final Revision revision) {
    final var json = Json.createObjectBuilder()
        .add("type", "plandev-file-revision")
        .add("version", 1)
        .add("revisionId", revision.id().toString())
        .add("fileId", revision.fileId().toString())
        .add("path", revision.pathAtRevision())
        .add("name", revision.name())
        .add("createdAt", revision.createdAt().toString());
    if (revision.createdBy() == null) json.addNull("createdBy");
    else json.add("createdBy", revision.createdBy());
    return json.build().toString() + "\n";
  }

  private void removeOrphanedTag(final Git git, final Revision revision, final Exception cause) {
    final var tag = TAG_PREFIX + revision.id();
    try {
      deleteTag(git, tag);
    } catch (Exception cleanup) {
      logger.error("Revision {} was not recorded and its tag {} in {} could not be removed; the tag is orphaned",
                   revision.id(), tag, git.getRepository().getWorkTree(), cleanup);
      final var ex = new WorkspaceHistoryException(
          Kind.REVISION_CATALOG_INCONSISTENT,
          "The revision could not be recorded, and its Git tag %s could not be removed.".formatted(tag),
          cause);
      ex.addSuppressed(cleanup);
      throw ex;
    }
  }

  private HistoricalFile read(final Path root, final Revision revision) throws IOException {
    try (final var repo = Git.open(root.toFile()).getRepository(); final var walk = new RevWalk(repo)) {
      final var tree = revisionTree(repo, walk, revision);
      final var content = repo.open(requireBlobId(repo, tree, revision, revision.pathAtRevision())).getBytes();
      final var sidecar = new String(
          repo.open(requireBlobId(repo, tree, revision, sidecarKey(revision.pathAtRevision()))).getBytes(),
          StandardCharsets.UTF_8);
      final JsonObject metadata;
      try (final var reader = Json.createReader(new StringReader(sidecar))) {
        metadata = Json.createObjectBuilder(reader.readObject())
                       .remove(MetadataKeys.readOnly.name())
                       .remove(MetadataKeys.lastEditedBy.name())
                       .remove(MetadataKeys.lastEditedAt.name())
                       .build();
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

  private static String sidecarKey(final String key) {
    final var slash = key.lastIndexOf('/');
    return key.substring(0, slash + 1) + RenderType.toMetadataFileName(key.substring(slash + 1));
  }
}
