package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 * workspace is trusted, clean and locked, which also serializes ordinal assignment.
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
      ObjectId commit)
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

  private WorkspaceRevisions() {}

  /** A file's revisions, newest first. Lock-free. */
  static List<Revision> list(final Path root, final String key) throws IOException {
    if (!Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR), LinkOption.NOFOLLOW_LINKS)) return List.of();
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      final var head = repo.resolve(Constants.HEAD);
      final var byCommit = revisionsByCommit(repo, walk);
      final var found = new ArrayList<Revision>();
      if (head == null || byCommit.isEmpty()) return found;

      // ponytail: walks first-parent history back to the file's creation or the oldest revision, one tree lookup per
      //  commit. Index revisions by lineage if workspaces grow very long histories.
      var remaining = byCommit.size();
      var path = key;
      var commit = walk.parseCommit(head);
      while (path != null && remaining > 0) {
        final var here = blobs(repo, commit.getTree(), path);
        if (here == null) break; // not part of this file's lineage
        final var tagged = byCommit.get(commit);
        if (tagged != null) {
          remaining--;
          for (final var revision : tagged) {
            if (revision.path().equals(path)) found.add(revision);
          }
        }
        if (commit.getParentCount() == 0) break;
        final var parent = walk.parseCommit(commit.getParent(0));
        final var before = blobs(repo, parent.getTree(), path);
        if (!here.equals(before)) {
          final var source = renameSource(repo, parent.getTree(), commit.getTree(), path, here);
          if (source != null) path = source;
          else if (before == null) path = null;
        }
        commit = parent;
      }
      found.sort(Comparator.comparingInt(Revision::ordinal).reversed());
      return found;
    }
  }

  static Optional<Revision> find(final Path root, final String key, final String name) throws IOException {
    return list(root, key).stream().filter(r -> r.name().equals(name)).findFirst();
  }

  /** Record the file's committed state at HEAD as its next revision. Caller holds the workspace lock. */
  static Revision create(final Path root, final String key, final String message, final String userId)
  throws IOException, GitAPIException
  {
    final var existing = list(root, key);
    final var ordinal = existing.isEmpty() ? 1 : existing.getFirst().ordinal() + 1;
    try (final var git = Git.open(root.toFile()); final var walk = new RevWalk(git.getRepository())) {
      final var head = walk.parseCommit(git.getRepository().resolve(Constants.HEAD));
      final var createdBy = userId == null || userId.isBlank() ? null : userId;
      final var revision = new Revision(name(ordinal), ordinal, key, message, createdBy, Instant.now(), head.copy());
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
   * lastEditedBy/lastEditedAt derived from history up to the revision's commit, exactly as live metadata is derived
   * from history up to HEAD. Lock-free.
   */
  static HistoricalFile read(final Path root, final Revision revision) throws IOException {
    try (final var repo = open(root); final var walk = new RevWalk(repo)) {
      final var tree = walk.parseCommit(revision.commit()).getTree();
      final var content = blob(repo, tree, revision.path());
      if (content == null) throw new IOException("Revision %s of %s is missing from commit %s"
                                                     .formatted(revision.name(), revision.path(), revision.commit().name()));
      final var sidecar = blob(repo, tree, sidecarKey(revision.path()));
      var metadata = JsonValue.EMPTY_JSON_OBJECT;
      if (sidecar != null) {
        try (final var reader = Json.createReader(new StringReader(new String(repo.open(sidecar).getBytes(), StandardCharsets.UTF_8)))) {
          metadata = reader.readObject();
        }
      }
      final var edit = lastEditAt(repo, walk, revision.commit(), revision.path());
      return new HistoricalFile(
          revision,
          repo.open(content).getBytes(),
          WorkspaceFileSystemService.effectiveMetadata(metadata, edit));
    }
  }

  /**
   * The newest commit at or before {@code start} that changed {@code path}, with the same rules as
   * {@link WorkspaceHistory#lastEdits}: a commit is compared with its parent (the root commit with an empty tree, marking
   * the edit as the baseline), so a move counts as an edit at the new path.
   */
  private static WorkspaceHistory.LastEdit lastEditAt(
      final Repository repo,
      final RevWalk walk,
      final ObjectId start,
      final String path) throws IOException
  {
    for (var commit = walk.parseCommit(start); ; commit = walk.parseCommit(commit.getParent(0))) {
      try (final var tw = new TreeWalk(repo)) {
        tw.setRecursive(true);
        tw.setFilter(AndTreeFilter.create(TreeFilter.ANY_DIFF, PathFilter.create(path)));
        if (commit.getParentCount() > 0) tw.addTree(walk.parseCommit(commit.getParent(0)).getTree());
        else tw.addTree(new EmptyTreeIterator());
        tw.addTree(commit.getTree());
        if (tw.next()) {
          final var author = commit.getAuthorIdent();
          return new WorkspaceHistory.LastEdit(author.getName(), author.getWhenAsInstant(), commit.getParentCount() == 0);
        }
      }
      if (commit.getParentCount() == 0) return null;
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
    return json.build() + "\n";
  }

  /** Every well-formed revision tag, grouped by the commit it points to. Anything else under the prefix is ignored. */
  private static Map<ObjectId, List<Revision>> revisionsByCommit(final Repository repo, final RevWalk walk) throws IOException {
    final var byCommit = new HashMap<ObjectId, List<Revision>>();
    for (final var ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS + TAG_PREFIX)) {
      try {
        final var tag = walk.parseTag(ref.getObjectId());
        if (!(walk.peel(tag) instanceof RevCommit commit)) continue;
        final JsonObject json;
        try (final var reader = Json.createReader(new StringReader(tag.getFullMessage()))) {
          json = reader.readObject();
        }
        if (!TYPE.equals(json.getString("type", null))) continue;
        final var revision = new Revision(
            json.getString("name"),
            json.getInt("ordinal"),
            json.getString("path"),
            json.getString("message", ""),
            json.get("createdBy") instanceof JsonString by ? by.getString() : null,
            Instant.parse(json.getString("createdAt")),
            commit.copy());
        byCommit.computeIfAbsent(revision.commit(), k -> new ArrayList<>()).add(revision);
      } catch (IncorrectObjectTypeException | JsonException | ClassCastException | NullPointerException
               | java.time.format.DateTimeParseException e) {
        logger.warn("Ignoring malformed revision tag {} in {}", ref.getName(), repo.getWorkTree(), e);
      }
    }
    return byCommit;
  }

  /** The content and sidecar blobs of a file in a tree, or null if the file is not there. */
  private static List<ObjectId> blobs(final Repository repo, final RevTree tree, final String key) throws IOException {
    final var content = blob(repo, tree, key);
    if (content == null) return null;
    final var sidecar = blob(repo, tree, sidecarKey(key));
    return List.of(content, sidecar == null ? ObjectId.zeroId() : sidecar);
  }

  private static ObjectId blob(final Repository repo, final RevTree tree, final String path) throws IOException {
    try (final var tw = TreeWalk.forPath(repo, path, tree)) {
      return tw == null ? null : tw.getObjectId(0);
    }
  }

  /**
   * The path deleted by this commit whose content and sidecar blobs both equal {@code target}'s, preferring one with
   * the same file name if several do; null if there is no unambiguous one.
   */
  private static String renameSource(
      final Repository repo,
      final RevTree parent,
      final RevTree commit,
      final String path,
      final List<ObjectId> target) throws IOException
  {
    final var candidates = new ArrayList<String>();
    try (final var tw = new TreeWalk(repo)) {
      tw.setRecursive(true);
      tw.setFilter(TreeFilter.ANY_DIFF);
      tw.addTree(parent);
      tw.addTree(commit);
      while (tw.next()) {
        final var deleted = tw.getPathString();
        if (tw.getRawMode(1) != 0 || !tw.getObjectId(0).equals(target.get(0))) continue;
        if (RenderType.isAerieMetadataFile(tw.getNameString())) continue;
        if (target.equals(blobs(repo, parent, deleted))) candidates.add(deleted);
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
