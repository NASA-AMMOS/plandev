package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.versioning.LastEdit;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
      LastEdit lastEdit)
  {
    public String name() {
      return WorkspaceRevisions.name(ordinal);
    }

    public JsonObject toJson() {
      return Json.createObjectBuilder()
                 .add("name", name())
                 .add("message", message)
                 .add("createdAt", createdAt.toString())
                 .add("createdBy", createdBy)
                 .build();
    }
  }

  /** A revision's content and its metadata as of that revision (see {@link #read}). */
  public record HistoricalFile(Revision revision, byte[] content, JsonObject metadata) {}

  /**
   * The result of one {@link #list} call, kept so the next call can stop early.
   *
   * @param computedAtHead the HEAD commit the list was computed at; it covers that commit and everything older
   * @param revisions the file's revisions as of that HEAD, newest first
   * @param knownRevisions every revision tag in the repository when the list was computed (or added since by
   *     {@link #remember}). A tag outside this set is new, and may sit on an old commit this list never saw.
   */
  private record CachedRevisionList(ObjectId computedAtHead, List<Revision> revisions, Set<Revision> knownRevisions) {}

  /**
   * The ids of a file's two Git blobs in one tree: its content and its sidecar (zero id when it has no sidecar). A
   * blob is the stored bytes of a file; its id is the hash of those bytes. So two equal FileBlobIds mean byte-identical
   * content and metadata, without reading either blob.
   */
  private record FileBlobIds(ObjectId contentBlobId, ObjectId sidecarBlobId) {}

  // ponytail: both caches grow with the paths listed and the tags parsed and are never evicted; entries are small.
  /** Workspace root -> file key -> that file's last computed revision list. */
  private final Map<Path, Map<String, CachedRevisionList>> revisionListCache = new ConcurrentHashMap<>();
  /**
   * Tag object id -> the revision parsed from its message, or empty if the tag is malformed. Tag objects are
   * immutable, so a parse is valid forever and each tag is parsed once per process.
   */
  private final Map<ObjectId, Optional<Revision>> parsedTagCache = new ConcurrentHashMap<>();

  /**
   * A file's revisions, newest first. Lock-free.
   *
   * How it works:
   * 1. Load every revision tag in the repository, grouped by the commit each one points at.
   * 2. Walk the file's lineage back from HEAD, one first-parent commit at a time, tracking the path the file had at
   *    each commit (it changes when the walk crosses an exact rename). Any revision tagged on a visited commit for the
   *    path the file had there is one of this file's revisions.
   * 3. Stop when the walk passes the file's creation, when no unvisited commit has a tag, or when it reaches the
   *    commit a cached list was computed at. That cached list already covers that commit and everything older, so its
   *    revisions are added and the walk ends. Normally only the commits since the last call are walked.
   * 4. Cache the result at this HEAD for the next call.
   *
   * The cache can only be trusted if no tag appeared on an older commit since it was computed: revisions are tagged on
   * the commit that last changed the file, which can be far behind HEAD. See {@link #cacheKnowsAllUnvisitedRevisions}.
   *
   * @param key the file's workspace-relative path at HEAD, '/'-separated
   */
  List<Revision> list(final Path root, final String key) throws IOException {
    if (!Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR), LinkOption.NOFOLLOW_LINKS)) return List.of();
    // The slice of the list cache belonging to this workspace
    final var workspaceListCache =
        revisionListCache.computeIfAbsent(root.normalize(), unused -> new ConcurrentHashMap<>());
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      final var reader = walk.getObjectReader();
      // Resolve HEAD before listing tags, so every listed tag is on a commit at or before it.
      final var headCommitId = repo.resolve(Constants.HEAD);
      if (headCommitId == null) return List.of();

      // 1. Every revision tag in the repository, for any file, by the commit it points at.
      final var revisionsByTaggedCommit = revisionsByCommit(repo, walk);
      final var allCurrentRevisions = new HashSet<Revision>();
      revisionsByTaggedCommit.values().forEach(allCurrentRevisions::addAll);

      // 2. Walk state.
      final var fileRevisions = new LinkedHashSet<Revision>(); // the result: this file's revisions found so far
      final var visitedRevisions = new HashSet<Revision>();     // every revision tagged on a commit walked so far
      var unvisitedTaggedCommits = revisionsByTaggedCommit.size(); // once zero, no older commit can add a revision
      var pathAtCommit = key;                                    // the file's path at the commit being walked
      var commit = walk.parseCommit(headCommitId);
      // Blob ids of file and metadata file. null once the walk passes the creation of the file
      var blobIdsAtCommit = fileBlobIds(reader, commit.getTree(), pathAtCommit);

      while (blobIdsAtCommit != null && unvisitedTaggedCommits > 0) {
        // Revisions tagged on this commit belong to this file if they were made at the path it had here.
        final var revisionsTaggedAtThisCommit = revisionsByTaggedCommit.get(commit);
        if (revisionsTaggedAtThisCommit != null) {
          unvisitedTaggedCommits--;
          visitedRevisions.addAll(revisionsTaggedAtThisCommit);
          for (final var revision : revisionsTaggedAtThisCommit) {
            if (revision.path().equals(pathAtCommit)) fileRevisions.add(revision);
          }
        }

        // 3. A list cached at this commit for this path covers everything older: use it and stop.
        final var cachedList = workspaceListCache.get(pathAtCommit);
        if (cachedList != null
            && cachedList.computedAtHead().equals(commit)
            && cacheKnowsAllUnvisitedRevisions(cachedList, allCurrentRevisions, visitedRevisions))
        {
          for (final var revision : cachedList.revisions()) {
            if (allCurrentRevisions.contains(revision)) fileRevisions.add(revision); // skip tags deleted since
          }
          break;
        }

        // Step to the first parent. If this commit changed the file's content or sidecar and was an exact rename,
        // follow the file to its old path; otherwise keep the path (and stop once the file did not exist yet).
        if (commit.getParentCount() == 0) break;
        final var parent = walk.parseCommit(commit.getParent(0));
        var blobIdsAtParent = fileBlobIds(reader, parent.getTree(), pathAtCommit);
        if (!blobIdsAtCommit.equals(blobIdsAtParent)) {
          final var renamedFrom =
              renameSource(reader, parent.getTree(), commit.getTree(), pathAtCommit, blobIdsAtCommit);
          if (renamedFrom != null) {
            pathAtCommit = renamedFrom;
            // TODO does this mean that this commit could have only been a rename or could there have potentially been other changes?
            // right now we don't allow this within plandev but external git operations could break this?
            blobIdsAtParent = blobIdsAtCommit; // a rename source has the same blob ids by definition -- TODO why?
          }
        }
        // TODO need to understand this blob id tracking better..
        blobIdsAtCommit = blobIdsAtParent;
        commit = parent;
      }

      // 4. Cache the result at this HEAD, with every tag that existed now.
      final var newestFirst =
          fileRevisions.stream().sorted(Comparator.comparingInt(Revision::ordinal).reversed()).toList();
      workspaceListCache.put(key, new CachedRevisionList(headCommitId.copy(), newestFirst, allCurrentRevisions));
      return newestFirst;
    }
  }

  /**
   * Whether a cached list can stand in for the rest of the walk: true if every revision the walk has not visited
   * yet already existed when the list was computed. A revision tag created since then on an older, unvisited commit
   * could be this file's, and the cached list would not include it.
   */
  private static boolean cacheKnowsAllUnvisitedRevisions(
      final CachedRevisionList cachedList,
      final Set<Revision> allCurrentRevisions,
      final Set<Revision> visitedRevisions)
  {
    for (final var revision : allCurrentRevisions) {
      if (!visitedRevisions.contains(revision) && !cachedList.knownRevisions().contains(revision)) return false;
    }
    return true;
  }

  Optional<Revision> find(final Path root, final String key, final String name) throws IOException {
    return list(root, key).stream().filter(r -> r.name().equals(name)).findFirst();
  }

  /**
   * Record the file's committed state at HEAD as its next revision. Makes no commit. Caller holds the workspace lock,
   * which serializes ordinal assignment, and passes the file's last edit at HEAD, which becomes the revision's. The tag
   * goes on the newest commit that changed the file or its sidecar ({@code git log -1 -- <file> <sidecar>}), whose
   * tree holds that same state.
   */
  Revision create(
      final Path root,
      final String key,
      final String message,
      final String userId,
      final LastEdit lastEdit)
  throws IOException, GitAPIException
  {
    final var existing = list(root, key);
    final var ordinal = existing.isEmpty() ? 1 : existing.getFirst().ordinal() + 1;
    try (final var git = Git.open(root.toFile()); final var walk = new RevWalk(git.getRepository())) {
      final var head = git.getRepository().resolve(Constants.HEAD);
      // ponytail: an uncached walk back to the file's last change, under the lock; cache it if creates on rarely
      // edited files in long histories get slow.
      final var changed = walk.parseCommit(
          git.log().add(head).addPath(key).addPath(sidecarKey(key)).setMaxCount(1).call().iterator().next());
      final var createdBy = userId == null || userId.isBlank() ? WorkspaceGitService.systemIdent().getName() : userId;
      final var revision = new Revision(ordinal, key, message, createdBy, Instant.now(), changed.copy(), lastEdit);
      git.tag()
         .setName(TAG_PREFIX + UUID.randomUUID())
         .setObjectId(changed)
         .setAnnotated(true)
         .setSigned(false)
         .setTagger(new PersonIdent(createdBy, "", revision.createdAt(), ZoneOffset.UTC))
         .setMessage(annotation(revision))
         .call();
      remember(root, key, revision);
      return revision;
    }
  }

  /**
   * Add a revision this process just made to every cached list in the workspace. Without this, the new tag would be
   * unknown to all of them, so the next list of every file would ignore its cache and walk the file's whole lineage
   * once. It is the file's own revision (the list in create just cached the file's revisions at this HEAD) and a known,
   * unrelated tag to every other cached list.
   */
  private void remember(final Path root, final String key, final Revision newRevision) {
    final var workspaceListCache = revisionListCache.get(root.normalize());
    if (workspaceListCache == null) return;
    workspaceListCache.replaceAll((listedKey, cachedList) -> {
      final var knownRevisions = new HashSet<>(cachedList.knownRevisions());
      knownRevisions.add(newRevision);
      if (!listedKey.equals(key)) {
        return new CachedRevisionList(cachedList.computedAtHead(), cachedList.revisions(), knownRevisions);
      }
      final var revisions = new ArrayList<Revision>();
      revisions.add(newRevision); // the newest ordinal
      revisions.addAll(cachedList.revisions());
      return new CachedRevisionList(cachedList.computedAtHead(), List.copyOf(revisions), knownRevisions);
    });
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
                         .add("createdBy", revision.createdBy());
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
      var revision = parsedTagCache.get(ref.getObjectId());
      if (revision == null) {
        revision = parseTag(repo, walk, ref.getObjectId());
        if (revision.isEmpty()) logger.warn("Ignoring malformed revision tag {} in {}", ref.getName(), repo.getWorkTree());
        parsedTagCache.put(ref.getObjectId().copy(), revision);
      }
      revision.ifPresent(r -> byCommit.computeIfAbsent(r.commit(), k -> new ArrayList<>()).add(r));
    }
    return byCommit;
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
          edit));
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
