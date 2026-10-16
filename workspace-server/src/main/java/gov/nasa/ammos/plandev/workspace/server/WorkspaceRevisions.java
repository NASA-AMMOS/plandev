package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
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
import javax.json.JsonString;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-file revisions: explicit, immutable, named bookmarks (a, b, ..., z, aa, ...) of a file's committed state.
 *
 * Representation:
 * A save makes a commit, never a revision. A revision is an annotated tag {@code plandev/revisions/<uuid>} on the
 * commit that was HEAD when it was made, whose message is a small JSON record (path, ordinal, name, message, createdBy,
 * createdAt). Git is the only store. Historical content and sidecar metadata are read from the tagged commit's tree;
 * nothing is checked out, so HEAD, the index and the working tree are never touched by a read.
 *
 * Identity:
 * A file's revisions are those whose commit and recorded path lie on the file's lineage. Walking first parents back
 * from HEAD, the file keeps its path until a commit that changed its content or sidecar. If that commit also deleted a
 * file with identical content and sidecar blobs (an exact rename, which is what every same-workspace move produces,
 * including a move over an existing file), the walk continues at the old path. Otherwise it continues at the same path,
 * or stops if the path did not exist before. So revisions follow renames, and copies, re-created paths and files
 * arriving from another workspace start with none.
 *
 * Creating a revision changes no file and makes no commit; callers run it inside {@link WorkspaceHistory#mutate} so the
 * workspace is trusted, clean and locked, which also serializes ordinal assignment. The file's last edit as of the
 * revision is recorded in the tag then, so reading a revision never searches history for it.
 */
public final class WorkspaceRevisions {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceRevisions.class);
  static final String TAG_PREFIX = "plandev/revisions/";
  private static final String TYPE = "plandev-file-revision";
  private static final int VERSION = 1;

  public record Revision(
      String name,
      int ordinal,
      String path,
      String message,
      String createdBy,
      Instant createdAt,
      ObjectId commit,
      WorkspaceHistory.LastEdit lastEdit)
  {
    public JsonObject toJson() {
      return Json.createObjectBuilder()
                 .add("name", name)
                 .add("message", message)
                 .add("createdAt", createdAt.toString())
                 .add("createdBy", createdBy == null ? WorkspaceHistory.systemIdent().getName() : createdBy)
                 .build();
    }
  }

  /** A revision's content and its metadata as of that revision (see {@link #read}). */
  public record HistoricalFile(Revision revision, byte[] content, JsonObject metadata) {}

  /** A file's revisions as of one HEAD. */
  private record CachedList(ObjectId head, List<Revision> revisions) {}

  // ponytail: both caches grow with the paths listed and the tags parsed and are never evicted; entries are small.
  private final Map<Path, Map<String, CachedList>> lists = new ConcurrentHashMap<>();
  // Tag objects are immutable, so a parsed tag (or the fact that it is malformed) is valid forever.
  private final Map<ObjectId, Optional<Revision>> parsedTags = new ConcurrentHashMap<>();

  /**
   * A file's revisions, newest first. Lock-free.
   *
   * Each result is cached per path and HEAD. A later call walks back from the new HEAD only until it reaches a commit
   * with a cached result for the path the file had there, which already covers that commit and everything older. So
   * normally only the commits since the last call are walked; the first call for a file walks its whole lineage.
   */
  List<Revision> list(final Path root, final String key) throws IOException {
    if (!Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR), LinkOption.NOFOLLOW_LINKS)) return List.of();
    final var cache = lists.computeIfAbsent(root.normalize(), unused -> new ConcurrentHashMap<>());
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      final var reader = walk.getObjectReader();
      // Resolve HEAD before listing tags. A tag is always made on the HEAD of its time, so any tag missing from the
      // listing below is on this HEAD or a newer one, never on an older commit a cached result has already covered.
      final var head = repo.resolve(Constants.HEAD);
      if (head == null) return List.of();
      final var byCommit = revisionsByCommit(repo, walk);
      final var current = new HashSet<Revision>();
      byCommit.values().forEach(current::addAll);

      final var found = new LinkedHashSet<Revision>();
      var remaining = byCommit.size();
      var path = key;
      var commit = walk.parseCommit(head);
      var here = blobs(reader, commit.getTree(), path);
      while (here != null && remaining > 0) {
        final var tagged = byCommit.get(commit);
        if (tagged != null) {
          remaining--;
          for (final var revision : tagged) {
            if (revision.path().equals(path)) found.add(revision);
          }
        }
        final var cached = cache.get(path);
        if (cached != null && cached.head().equals(commit)) {
          for (final var revision : cached.revisions()) {
            if (current.contains(revision)) found.add(revision); // skip tags deleted since
          }
          break;
        }
        if (commit.getParentCount() == 0) break;
        final var parent = walk.parseCommit(commit.getParent(0));
        var before = blobs(reader, parent.getTree(), path);
        if (!here.equals(before)) {
          final var source = renameSource(reader, parent.getTree(), commit.getTree(), path, here);
          if (source != null) {
            path = source;
            before = here; // a rename source has the same blobs by definition
          }
        }
        here = before; // null once the walk passes the file's creation
        commit = parent;
      }
      final var revisions = found.stream().sorted(Comparator.comparingInt(Revision::ordinal).reversed()).toList();
      cache.put(key, new CachedList(head.copy(), revisions));
      return revisions;
    }
  }

  Optional<Revision> find(final Path root, final String key, final String name) throws IOException {
    return list(root, key).stream().filter(r -> r.name().equals(name)).findFirst();
  }

  /**
   * Record the file's committed state at HEAD as its next revision. Caller holds the workspace lock and passes the
   * file's last edit at HEAD, which becomes the revision's.
   */
  Revision create(
      final Path root,
      final String key,
      final String message,
      final String userId,
      final WorkspaceHistory.LastEdit lastEdit)
  throws IOException, GitAPIException
  {
    final var existing = list(root, key);
    final var ordinal = existing.isEmpty() ? 1 : existing.getFirst().ordinal() + 1;
    try (final var git = Git.open(root.toFile()); final var walk = new RevWalk(git.getRepository())) {
      final var head = walk.parseCommit(git.getRepository().resolve(Constants.HEAD));
      final var createdBy = userId == null || userId.isBlank() ? null : userId;
      final var revision = new Revision(
          name(ordinal), ordinal, key, message, createdBy, Instant.now(), head.copy(), lastEdit);
      final var tagger = createdBy == null ? WorkspaceHistory.systemIdent().getName() : createdBy;
      git.tag()
         .setName(TAG_PREFIX + UUID.randomUUID())
         .setObjectId(head)
         .setAnnotated(true)
         .setSigned(false)
         .setTagger(new PersonIdent(tagger, "", revision.createdAt(), ZoneOffset.UTC))
         .setMessage(annotation(revision))
         .call();
      return revision;
    }
  }

  /**
   * A revision's content and its sidecar metadata as the API presented it at that revision: the committed sidecar with
   * lastEditedBy/lastEditedAt from the last edit recorded with the revision, exactly as live metadata uses the last
   * edit at HEAD. Lock-free.
   */
  static HistoricalFile read(final Path root, final Revision revision) throws IOException {
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      final var tree = walk.parseCommit(revision.commit()).getTree();
      final var blobs = blobs(walk.getObjectReader(), tree, revision.path());
      if (blobs == null) throw new IOException("Revision %s of %s is missing from commit %s"
                                                   .formatted(revision.name(), revision.path(), revision.commit().name()));
      var metadata = JsonValue.EMPTY_JSON_OBJECT;
      if (!blobs.get(1).equals(ObjectId.zeroId())) {
        try (final var reader = Json.createReader(new StringReader(new String(repo.open(blobs.get(1)).getBytes(), StandardCharsets.UTF_8)))) {
          metadata = reader.readObject();
        }
      }
      return new HistoricalFile(
          revision,
          repo.open(blobs.get(0)).getBytes(),
          WorkspaceFileSystemService.effectiveMetadata(metadata, revision.lastEdit()));
    }
  }

  /** 1 -> a, 26 -> z, 27 -> aa, ... (bijective base 26). */
  static String name(final int ordinal) {
    final var name = new StringBuilder();
    for (var n = ordinal; n > 0; n = (n - 1) / 26) name.append((char) ('a' + (n - 1) % 26));
    return name.reverse().toString();
  }

  static String sidecarKey(final String key) {
    final var slash = key.lastIndexOf('/');
    return key.substring(0, slash + 1) + RenderType.toMetadataFileName(key.substring(slash + 1));
  }

  private static String annotation(final Revision revision) {
    final var json = Json.createObjectBuilder()
                         .add("type", TYPE)
                         .add("version", VERSION)
                         .add("path", revision.path())
                         .add("ordinal", revision.ordinal())
                         .add("name", revision.name())
                         .add("message", revision.message())
                         .add("createdAt", revision.createdAt().toString());
    if (revision.createdBy() == null) json.addNull("createdBy");
    else json.add("createdBy", revision.createdBy());
    final var edit = revision.lastEdit();
    if (edit == null) json.addNull("lastEdit");
    else json.add("lastEdit", Json.createObjectBuilder()
                                  .add("by", edit.by())
                                  .add("at", edit.at().toString())
                                  .add("baseline", edit.baseline()));
    return json.build() + "\n";
  }

  /** Every well-formed revision tag, grouped by the commit it points to. Anything else under the prefix is ignored. */
  private Map<ObjectId, List<Revision>> revisionsByCommit(final Repository repo, final RevWalk walk) throws IOException {
    final var byCommit = new HashMap<ObjectId, List<Revision>>();
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS + TAG_PREFIX)) {
      var revision = parsedTags.get(ref.getObjectId());
      if (revision == null) {
        revision = parseTag(repo, walk, ref.getObjectId());
        if (revision.isEmpty()) logger.warn("Ignoring malformed revision tag {} in {}", ref.getName(), repo.getWorkTree());
        parsedTags.put(ref.getObjectId().copy(), revision);
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
      final var edit = json.get("lastEdit") instanceof JsonObject e
          ? new WorkspaceHistory.LastEdit(e.getString("by"), Instant.parse(e.getString("at")), e.getBoolean("baseline"))
          : null;
      return Optional.of(new Revision(
          json.getString("name"),
          json.getInt("ordinal"),
          json.getString("path"),
          json.getString("message", ""),
          json.get("createdBy") instanceof JsonString by ? by.getString() : null,
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
   * The content and sidecar blobs of a file in a tree (zero id for a missing sidecar), or null if the file is not
   * there. One walk reads both, since they share a directory.
   */
  private static List<ObjectId> blobs(final ObjectReader reader, final RevTree tree, final String key) throws IOException {
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
    return content == null ? null : List.of(content, sidecar);
  }

  /**
   * The path deleted by this commit whose content and sidecar blobs both equal {@code target}'s, preferring one with
   * the same file name if several do; null if there is no unambiguous one.
   */
  private static String renameSource(
      final ObjectReader reader,
      final RevTree parent,
      final RevTree commit,
      final String path,
      final List<ObjectId> target) throws IOException
  {
    final var candidates = new ArrayList<String>();
    try (final var tw = new TreeWalk(reader)) {
      tw.setRecursive(true);
      tw.setFilter(TreeFilter.ANY_DIFF);
      tw.addTree(parent);
      tw.addTree(commit);
      while (tw.next()) {
        final var deleted = tw.getPathString();
        if (tw.getRawMode(1) != 0 || !tw.getObjectId(0).equals(target.get(0))) continue;
        if (RenderType.isAerieMetadataFile(tw.getNameString())) continue;
        if (target.equals(blobs(reader, parent, deleted))) candidates.add(deleted);
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
