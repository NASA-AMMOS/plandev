package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.versioning.LastEdit;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-file revisions: explicit, immutable, named bookmarks (a, b, ..., z, aa, ...) of a file's committed state.
 *
 * Representation:
 * A save makes a commit, never a revision. A revision is an annotated tag {@code plandev/revisions/<uuid>} on the
 * newest commit that changed the file or its sidecar when it was made (so its tree holds exactly the recorded state,
 * and revisions with nothing changed between them share a commit), whose message is a small JSON record (path,
 * ordinal, message, createdBy, createdAt, lastEdit). Git is the only store. Historical content and sidecar metadata
 * are read from the tagged commit's tree; nothing is checked out, so HEAD, the index and the working tree are never
 * touched by a read.
 *
 * Identity:
 * A file's revisions are those whose commit and recorded path lie on the file's lineage. Walking first parents back
 * from HEAD, the file keeps its path until a commit that changed its content or sidecar. If that commit also deleted a
 * file with identical content and sidecar blobs (an exact rename, which is what every same-workspace move produces,
 * including a move over an existing file), the walk continues at the old path. Otherwise it continues at the same path,
 * or stops if the path did not exist before. So revisions follow renames, and copies, re-created paths and files
 * arriving from another workspace start with none.
 */
public final class WorkspaceRevisions {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceRevisions.class);
  static final String TAG_PREFIX = "plandev/revisions/";
  private static final String TYPE = "plandev-file-revision";
  private static final int VERSION = 1;

  public record Revision(
      int ordinal,
      String path,
      String message,
      String createdBy,
      Instant createdAt,
      ObjectId commit,
      LastEdit lastEdit,
      UUID fileId)
  {
    public String name() {
      return WorkspaceRevisions.name(ordinal);
    }

    /**
     * The API form. lastEditedBy/At are the file's last edit when the revision was made, null for a baseline edit,
     * whose attribution lives in the sidecar (see {@link #read}).
     */
    public JsonObject toJson() {
      final var json = Json.createObjectBuilder()
                           .add("name", name())
                           .add("pathAtRevision", path)
                           .add("message", message)
                           .add("createdAt", createdAt.toString())
                           .add("createdBy", createdBy)
                           .add("fileId", fileId.toString());
      if (lastEdit == null || lastEdit.baseline()) {
        json.addNull("lastEditedBy").addNull("lastEditedAt");
      } else {
        json.add("lastEditedBy", lastEdit.by()).add("lastEditedAt", lastEdit.at().toString());
      }
      return json.build();
    }
  }

  /** A revision's content and its metadata as of that revision (see {@link #read}). */
  public record HistoricalFile(Revision revision, byte[] content, JsonObject metadata) {}

  /**
   * The result of one {@link #getFileId} call, kept so the next call can stop early.
   *
   * @param computedAtHead the HEAD commit the list was computed at; it covers that commit and everything older
   * @param fileId the file's fileID as of that HEAD, or empty if none was found
   */
  private record CachedFileId(ObjectId computedAtHead, Optional<UUID> fileId) {}

  /**
   * The ids of a file's two Git blobs in one tree: its content and its sidecar (zero id when it has no sidecar). A
   * blob is the stored bytes of a file; its id is the hash of those bytes. So two equal FileBlobIds mean byte-identical
   * content and metadata, without reading either blob.
   */
  private record FileBlobIds(ObjectId contentBlobId, ObjectId sidecarBlobId) {}

  // TODO: Evict from both caches if their memory ever matters. They grow with the paths listed and the tags parsed and
  // are never evicted; entries are small.
  /**
   * File ID Cache:
   *
   * Workspace root -> file path -> that file's last computed file id. A {@link CachedFileId} says "at commit {@code
   * computedAtHead}, the file at this key had this file id (or none)".
   *
   * A non-empty cached file id cannot be invalidated by the addition of new revisions alone; new revisions for the same
   * file will have the same id. The only way a given path's file id can change is if new commit(s) have been added which
   * delete or replace the given file, which would give it a new id. Deleting and creating files moves the HEAD
   * commit: this means that the commit an entry was computed at is sufficient to determine the validity of a non-empty
   * cache entry.
   *
   * On the other hand, an `empty` entry, i.e. one that says that this file has no revisions and therefore no file id,
   * can be invalidated by a new revision. When a new revision is created for a given path, we unconditionally update
   * this cache to have a non-empty cache entry.
   *
   * On concurrency: the "create revision" operation is expected to be mutually exclusive with itself; however, it can
   * be concurrent with any number of getFileId operations. Both operations write to the cache. Non-empty cache entries
   * are always correct because they indicate the commit for which they are valid, so it is valid to overwrite a newer
   * non-empty cache entry with an older one. This means that race conditions between multiple non-empty writes are safe.
   * An `empty` cache entry, however, can be invalidated by a new revision at or before that commit. This makes race
   * conditions between getFileId and "create revision" potentially dangerous: "create revision" could write a non-empty
   * cache entry that immediately gets overwritten with an `empty` one by a concurrent getFileId operation. To avoid this
   * race condition, getFileId performs a compare-and-swap using reference equality of cache entries: if any writes to the
   * cache have occurred concurrently with getFileId, it will not write an `empty` cache entry.
   *
   * Assumptions. Commits, tags and a file's id are immutable; revisions are well ordered by commit; a tag sits only
   * on a commit where the file existed.
   */
  private final Map<Path, Map<String, CachedFileId>> fileIdCache = new ConcurrentHashMap<>();
  /**
   * Tag object id -> the revision parsed from its message, or empty if the tag is malformed. Tag objects are
   * immutable, so a parse is valid forever and each tag is parsed once per process.
   */
  private final Map<ObjectId, Optional<Revision>> parsedTagCacheByRefId = new ConcurrentHashMap<>();

  /**
   * A file's revisions, newest first. Lock-free.
   */
  List<Revision> list(final Path root, final String key) throws IOException {
    if (!Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR), LinkOption.NOFOLLOW_LINKS)) return List.of();
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      // Resolve HEAD before listing tags, so every listed tag is on a commit at or before it.
      final var headCommitId = repo.resolve(Constants.HEAD);
      if (headCommitId == null) return List.of();

      var fileId = getFileId(repo, walk, key);
      // fileId will be empty if the file doesn't have any revisions yet
      if (fileId.isEmpty()) return List.of();

      // Every revision tag in the repository, for any file, by its file id
      final var revisionsByFileId = revisionsByFileId(repo, walk);
      var fileRevisions = revisionsByFileId.get(fileId.get());
      if (fileRevisions == null) return List.of();
      return fileRevisions.stream().sorted(Comparator.comparingInt(Revision::ordinal).reversed()).toList();
    }
  }

  /**
   * Get the UUID that corresponds with the given file path by finding its most recent revision
   * The ID will remain the same across revisions even if the file path changes due to renames/moves
   *
   * How it works:
   * 1. Load every revision tag in the repository, grouped by the commit each one points at.
   * 2. Walk the file's lineage back from HEAD, one first-parent commit at a time, tracking the path the file had at
   *    each commit (it changes when the walk crosses an exact rename). Any revision tagged on a visited commit for the
   *    path the file had there is one of this file's revisions. The revision tag's metadata contains the UUID for this file.
   * 3. Stop when the walk passes the file's creation, when no unvisited commit has a tag, or when it reaches the
   *    latest commit for which we have cached the file ID
   * 4. Cache the result at this HEAD for the next call.
   *
   * @param key the file's workspace-relative path at HEAD, '/'-separated
   */
  private Optional<UUID> getFileId(Repository repo, RevWalk walk, String key) throws IOException {
    final var workspaceFileIdCache =
        fileIdCache.computeIfAbsent(repo.getDirectory().toPath().getParent().toAbsolutePath(), unused -> new ConcurrentHashMap<>());

    CachedFileId initialCacheEntry = workspaceFileIdCache.get(key);

    // Resolve HEAD before listing tags, so every listed tag is on a commit at or before it.
    final var headCommitId = Objects.requireNonNull(repo.resolve(Constants.HEAD), "No HEAD ref found");

    if (initialCacheEntry != null && initialCacheEntry.computedAtHead.equals(headCommitId)) {
      // No need to traverse commit history if we already have the answer at HEAD
      return initialCacheEntry.fileId;
    }

    final var revisionsByTaggedCommit = revisionsByCommit(repo, walk);

    var numUnvisitedTaggedCommits = revisionsByTaggedCommit.size(); // once zero, no older commit can add a revision
    var pathAtCommit = key;                                      // the file's path at the commit being walked

    var commit = walk.parseCommit(headCommitId);

    final var reader = walk.getObjectReader();
    // Blob ids of file and metadata file. null once the walk passes the creation of the file
    var blobIdsAtCommit = fileBlobIds(reader, commit.getTree(), pathAtCommit);

    while (blobIdsAtCommit != null && numUnvisitedTaggedCommits > 0) {
      // Revisions tagged on this commit belong to this file if they were made at the path it had here.
      final var revisionsTaggedAtThisCommit = revisionsByTaggedCommit.get(commit);

      if (revisionsTaggedAtThisCommit != null) {
        numUnvisitedTaggedCommits--;

        for (final var revision : revisionsTaggedAtThisCommit) {
          if (revision.path().equals(pathAtCommit)) {
            // Found it! Update cache and return
            var fileId = Optional.of(revision.fileId());
            workspaceFileIdCache.put(key, new CachedFileId(headCommitId, fileId));
            return fileId;
          }
        }
      }

      // We didn't find the file id at this commit, but maybe it's in the cache:
      var cacheEntry = workspaceFileIdCache.get(pathAtCommit);
      if (cacheEntry != null && cacheEntry.computedAtHead.equals(commit)) {
        // Update the cache with the current head commit so we don't have to search as far next time
        CachedFileId newCacheEntry = new CachedFileId(headCommitId, cacheEntry.fileId());
        if (cacheEntry.fileId().isPresent()) {
          // Unconditional put
          workspaceFileIdCache.put(key, newCacheEntry);
        } else {
          // Compare-and-swap
          workspaceFileIdCache.compute(key, (k, oldCacheEntry) -> oldCacheEntry == initialCacheEntry ? newCacheEntry : oldCacheEntry);
        }
        return cacheEntry.fileId();
      }

      // It's not in the cache. We must search previous commits.

      // If this is the very first commit, we're done.
      if (commit.getParentCount() == 0) break;

      // --- Prepare to move on to parent commit --- //

      final var parent = walk.parseCommit(commit.getParent(0)); // TODO consider branching history

      // Get the contents of this file and its sidecar at the previous commit
      var blobIdsAtParent = fileBlobIds(reader, parent.getTree(), pathAtCommit);

      if (
          blobIdsAtParent == null // File was created or renamed
          || !blobIdsAtParent.equals(blobIdsAtCommit) // It's possible that another file was moved over pathAtCommit. That, too, is considered a rename.
      ) {
        final var renamedFrom = renameSource(reader, parent.getTree(), commit.getTree(), pathAtCommit, blobIdsAtCommit);
        if (renamedFrom != null) {
          // It was indeed a rename. Update pathAtCommit to track the name of the file at the time.
          pathAtCommit = renamedFrom;
          // a rename source has the same blob ids -- this is an invariant of renameSource
          // future todo: determine how this works with external git commits that may contain a rename + modification in same commit
          blobIdsAtParent = blobIdsAtCommit;
        }
      }

      // Move on to the parent commit
      blobIdsAtCommit = blobIdsAtParent;
      commit = parent;
    }

    // We haven't found a revision for this file. Let's cache the empty result.
    workspaceFileIdCache.compute(
        key, (k, cachedFileId) -> {
          // Compare-and-swap. If anyone has updated the cache since this method started, we leave it alone. This avoids clobbering the cache update made by a concurrent `create` revision
          if (cachedFileId == initialCacheEntry) { // Using object equality under the assumption that any cache update creates a new CachedFileId
            return new CachedFileId(headCommitId, Optional.empty());
          } else {
            return cachedFileId; // no change
          }
        });
    return Optional.empty();
  }

  /**
   * The newest of {@code revisions} (the file's at {@code key}, newest first) whose content and sidecar are byte-identical to the
   * file's at HEAD, or empty if none is. A save that reverts the file to an older revision matches that revision.
   */
  Optional<Revision> matching(final Path root, final String key, final List<Revision> revisions) throws IOException {
    if (revisions.isEmpty()) return Optional.empty();
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      final var head = repo.resolve(Constants.HEAD);
      if (head == null) return Optional.empty();
      final var reader = walk.getObjectReader();
      final var current = fileBlobIds(reader, walk.parseCommit(head).getTree(), key);
      if (current == null) return Optional.empty();
      for (final var revision : revisions) {
        final var atRevision = fileBlobIds(reader, walk.parseCommit(revision.commit()).getTree(), revision.path());
        if (current.equals(atRevision)) return Optional.of(revision);
      }
      return Optional.empty();
    }
  }

  Optional<Revision> find(final Path root, final String key, final String name) throws IOException {
    return list(root, key).stream().filter(r -> r.name().equals(name)).findFirst();
  }

  /**
   * Record the file's committed state at HEAD as its next revision. Makes no commit. Caller holds the workspace lock,
   * which serializes ordinal assignment, and passes the file's last edit at HEAD, which becomes the revision's. The tag
   * goes on the newest commit that changed the file or its sidecar ({@code git log -1 -- <file> <sidecar>}), whose
   * tree holds that same state.
   *
   * @param startAtRevision the name to give the file's first revision instead of "a", or null. Only allowed while the file has
   *                no revisions; later revisions continue from it.
   */
  Revision create(
      final Path root,
      final String key,
      final String message,
      final String userId,
      final LastEdit lastEdit,
      final String startAtRevision)
  throws IOException, GitAPIException, WorkspaceFileOpException
  {
    var mostRecentRevision = getMostRecentRevision(root, key);
    if (startAtRevision != null && mostRecentRevision.isPresent()) {
      throw new WorkspaceFileOpException("%s already has revisions; startAtRevision is only allowed for its first revision.".formatted(key));
    }
    final var ordinal = startAtRevision != null ? ordinal(startAtRevision) : mostRecentRevision.map(value -> value.ordinal() + 1).orElse(1);
    final var fileId = mostRecentRevision.map(Revision::fileId).orElseGet(UUID::randomUUID);
    try (final var git = Git.open(root.toFile()); final var walk = new RevWalk(git.getRepository())) {
      final var head = git.getRepository().resolve(Constants.HEAD);
      requireOnDiskAsRecorded(root, key, fileBlobIds(walk.getObjectReader(), walk.parseCommit(head).getTree(), key));
      // TODO: Cache this if creating revisions of rarely edited files in long histories gets slow. It walks back,
      // uncached and under the lock, to the file's last change.
      final var changed = walk.parseCommit(
          git.log().add(head).addPath(key).addPath(sidecarKey(key)).setMaxCount(1).call().iterator().next());
      final var createdBy = (userId == null || userId.isBlank()) ? WorkspaceGitService.systemIdent().getName() : userId;
      final var revision = new Revision(ordinal, key, message, createdBy, Instant.now(), changed.copy(), lastEdit, fileId);

      // Create the git tag
      git.tag()
         .setName(TAG_PREFIX + UUID.randomUUID())
         .setObjectId(changed)
         .setAnnotated(true)
         .setSigned(false)
         .setTagger(new PersonIdent(createdBy, "", revision.createdAt(), ZoneOffset.UTC))
         .setMessage(annotation(revision))
         .call();

      // Update the file id cache
      final var workspaceFileIdCache = fileIdCache.get(root.normalize().resolve(WorkspacePaths.GIT_DIR).getParent());
      if (workspaceFileIdCache != null) {
        workspaceFileIdCache.put(key, new CachedFileId(head, Optional.of(revision.fileId())));
      }
      return revision;
    }
  }

  /**
   * Verify that the file's content and sidecar (if it exists) on disk match the blobs from the given commit
   */
  private static void requireOnDiskAsRecorded(final Path root, final String key, final FileBlobIds recorded)
  throws IOException
  {
    final var hasher = new ObjectInserter.Formatter();
    final var sidecar = root.resolve(sidecarKey(key));
    final var onDisk = new FileBlobIds(
        hasher.idFor(Constants.OBJ_BLOB, Files.readAllBytes(root.resolve(key))),
        Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
            ? hasher.idFor(Constants.OBJ_BLOB, Files.readAllBytes(sidecar))
            : ObjectId.zeroId());
    if (!onDisk.equals(recorded)) {
      throw new WorkspaceHistoryException(WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT,
          "%s was changed outside PlanDev and differs from its history; save it before creating a revision.".formatted(key));
    }
  }

  private Optional<Revision> getMostRecentRevision(Path root, String key) throws IOException {
    final var existing = list(root, key);
    return existing.isEmpty() ? Optional.empty() : Optional.of(existing.getFirst());
  }


  /**
   * A revision's content and its sidecar metadata as the API presented it at that revision: the committed sidecar with
   * lastEditedBy/lastEditedAt from the last edit recorded with the revision, exactly as live metadata uses the last
   * edit at HEAD. Lock-free.
   */
  static HistoricalFile read(final Path root, final Revision revision) throws IOException {
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      final var tree = walk.parseCommit(revision.commit()).getTree();
      final var blobIds = fileBlobIds(walk.getObjectReader(), tree, revision.path());
      if (blobIds == null) throw new IOException("Revision %s of %s is missing from commit %s"
                                                   .formatted(revision.name(), revision.path(), revision.commit().name()));
      var metadata = JsonValue.EMPTY_JSON_OBJECT;
      if (!blobIds.sidecarBlobId().equals(ObjectId.zeroId())) {
        try (final var reader = Json.createReader(new StringReader(new String(repo.open(blobIds.sidecarBlobId()).getBytes(), StandardCharsets.UTF_8)))) {
          metadata = reader.readObject();
        }
      }
      return new HistoricalFile(
          revision,
          repo.open(blobIds.contentBlobId()).getBytes(),
          WorkspaceGitService.applyLastEdit(metadata, revision.lastEdit()));
    }
  }

  /** 1 -> a, 26 -> z, 27 -> aa, ... (bijective base 26). */
  public static String name(final int ordinal) {
    final var name = new StringBuilder();
    for (var n = ordinal; n > 0; n = (n - 1) / 26) name.append((char) ('a' + (n - 1) % 26));
    return name.reverse().toString();
  }

  /** The inverse of {@link #name}: a -> 1, z -> 26, aa -> 27, ... */
  public static int ordinal(final String name) throws WorkspaceFileOpException {
    if (!name.matches("[a-z]{1,6}")) { // "zzzzzz" is the longest name that fits an int ordinal comfortably
      throw new WorkspaceFileOpException("Invalid revision name \"%s\": expected 1-6 lowercase letters.".formatted(name));
    }
    var ordinal = 0;
    for (final var c : name.toCharArray()) ordinal = ordinal * 26 + (c - 'a' + 1);
    return ordinal;
  }

  static String sidecarKey(final String key) {
    final var slash = key.lastIndexOf('/');
    return key.substring(0, slash + 1) + RenderType.toMetadataFileName(key.substring(slash + 1));
  }

  static String annotation(final Revision revision) {
    final var json = Json.createObjectBuilder()
                         .add("type", TYPE)
                         .add("version", VERSION)
                         .add("path", revision.path())
                         .add("ordinal", revision.ordinal())
                         .add("message", revision.message())
                         .add("createdAt", revision.createdAt().toString())
                         .add("createdBy", revision.createdBy())
                         .add("fileId", revision.fileId().toString());
    final var edit = revision.lastEdit();
    if (edit == null) json.addNull("lastEdit");
    else json.add("lastEdit", Json.createObjectBuilder()
                                  .add("by", edit.author().getName())
                                  .add("email", edit.author().getEmailAddress())
                                  .add("at", edit.at().toString())
                                  .add("commit", edit.commitHash().name())
                                  .add("baseline", edit.baseline()));
    return json.build() + "\n";
  }

  /** Every well-formed revision tag, grouped by the commit it points to. Anything else under the prefix is ignored. */
  private Map<ObjectId, List<Revision>> revisionsByCommit(final Repository repo, final RevWalk walk) throws IOException {
    final var byCommit = new HashMap<ObjectId, List<Revision>>();
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS + TAG_PREFIX)) {
      var revision = getParsedTagAndUpdateCache(repo, walk, ref);
      revision.ifPresent(r -> byCommit.computeIfAbsent(r.commit(), k -> new ArrayList<>()).add(r));
    }
    return byCommit;
  }

  /** Every well-formed revision tag, grouped by its file id. Anything else under the prefix is ignored. */
  private Map<UUID, List<Revision>> revisionsByFileId(final Repository repo, final RevWalk walk) throws IOException {
    final var byFileId = new HashMap<UUID, List<Revision>>();
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS + TAG_PREFIX)) {
      var revision = getParsedTagAndUpdateCache(repo, walk, ref);
      revision.ifPresent(r -> byFileId.computeIfAbsent(r.fileId(), k -> new ArrayList<>()).add(r));
    }
    return byFileId;
  }

  /** Given a ref (tag), get the parsed version of the tag + metadata, updating the cache if necessary */
  private Optional<Revision> getParsedTagAndUpdateCache(Repository repo, RevWalk walk, Ref ref) throws IOException {
    var revision = parsedTagCacheByRefId.get(ref.getObjectId());
    if (revision == null) {
      revision = parseTag(repo, walk, ref.getObjectId());
      if (revision.isEmpty()) logger.warn("Ignoring malformed revision tag {} in {}", ref.getName(), repo.getWorkTree());
      parsedTagCacheByRefId.put(ref.getObjectId().copy(), revision);
    }
    return revision;
  }

  private static Optional<Revision> parseTag(final Repository repo, final RevWalk walk, final ObjectId id) throws IOException {
    try {
      final var tag = walk.parseTag(id);
      if (!(walk.peel(tag) instanceof RevCommit commit)) return Optional.empty();
      final JsonObject json;
      try (final var reader = Json.createReader(new StringReader(tag.getFullMessage()))) {
        json = reader.readObject();
      }
      if (!TYPE.equals(json.getString("type", null))) return Optional.empty();
      final LastEdit edit;
      if (json.get("lastEdit") instanceof JsonObject e) {
        // The ident's time is the edit time so a parsed LastEdit equals the one it was written from.
        final var at = Instant.parse(e.getString("at"));
        final var author = new PersonIdent(e.getString("by"), e.getString("email", ""), at, ZoneOffset.UTC);
        edit = new LastEdit(author, at, ObjectId.fromString(e.getString("commit")), e.getBoolean("baseline"));
      } else {
        edit = null;
      }
      return Optional.of(new Revision(
          json.getInt("ordinal"),
          json.getString("path"),
          json.getString("message", ""),
          json.getString("createdBy"),
          Instant.parse(json.getString("createdAt")),
          commit.copy(),
          edit,
          UUID.fromString(json.getString("fileId"))));
    } catch (IncorrectObjectTypeException | JsonException | ClassCastException | NullPointerException
             | java.time.format.DateTimeParseException e) {
      logger.debug("Malformed revision tag {} in {}", id.name(), repo.getWorkTree(), e);
      return Optional.empty();
    }
  }

  /**
   * The ids of a file's blobs in a tree, or null if the file is not there. One walk reads both, since they share a
   * directory.
   */
  private static FileBlobIds fileBlobIds(final ObjectReader reader, final RevTree tree, final String key)
  throws IOException
  {
    final var sidecarKey = sidecarKey(key);
    ObjectId content = null;
    var sidecar = ObjectId.zeroId();
    try (final var tw = new TreeWalk(reader)) {
      tw.setRecursive(true);
      tw.setFilter(PathFilterGroup.createFromStrings(key, sidecarKey));
      tw.addTree(tree);
      while (tw.next()) {
        if (tw.getPathString().equals(key)) content = tw.getObjectId(0);
        else if (tw.getPathString().equals(sidecarKey)) sidecar = tw.getObjectId(0);
      }
    }
    return content == null ? null : new FileBlobIds(content, sidecar);
  }

  /**
   * The path deleted by this commit whose content and sidecar blob ids both equal {@code target}'s, preferring one with
   * the same file name if several do; null if there is no unambiguous one.
   */
  private static String renameSource(
      final ObjectReader reader,
      final RevTree parent,
      final RevTree commit,
      final String path,
      final FileBlobIds target) throws IOException
  {
    final var candidates = new ArrayList<String>();
    try (final var tw = new TreeWalk(reader)) {
      tw.setRecursive(true);
      tw.setFilter(TreeFilter.ANY_DIFF);
      tw.addTree(parent);
      tw.addTree(commit);
      while (tw.next()) {
        final var deleted = tw.getPathString();
        if (tw.getRawMode(1) != 0 || !tw.getObjectId(0).equals(target.contentBlobId())) continue;
        if (RenderType.isAerieMetadataFile(tw.getNameString())) continue;
        if (target.equals(fileBlobIds(reader, parent, deleted))) candidates.add(deleted);
      }
    }
    if (candidates.size() == 1) return candidates.getFirst();
    final var name = path.substring(path.lastIndexOf('/') + 1);
    final var sameName = candidates.stream().filter(c -> c.endsWith("/" + name) || c.equals(name)).toList();
    return sameName.size() == 1 ? sameName.getFirst() : null;
  }

  private static Repository open(final Path root) throws IOException {
    return Git.open(root.toFile()).getRepository();
  }
}
