package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistory.Kind;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistory.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataKeys;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonString;
import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Controlled interoperability between a workspace's repository and one ordinary remote Git repository.
 *
 * <p>PlanDev owns its canonical {@code main} and {@code refs/tags/plandev/revisions/*}. The remote is an integration
 * surface, never a writer of canonical state: a fetch lands only in staging refs ({@link #STAGED_MAIN},
 * {@link #STAGED_REVISIONS}), everything is validated against Git objects (the would-be tree, incoming revision tags),
 * and only then is {@code main} moved forward and new revision tags promoted. Any rejection leaves {@code main}, the
 * working tree, runtime state, canonical revision tags and the revision catalog as they were; staging refs may have
 * advanced, and are not authoritative.
 *
 * <p><b>Integration</b> is forward-only Git ancestry: equal or local-ahead is a no-op, remote-ahead fast-forwards, a
 * divergence becomes an ordinary two-parent merge commit if it merges cleanly, and a conflict or unrelated history is
 * rejected. Remote commits are working-copy changes only; they never create revisions.
 *
 * <p><b>Revisions</b> are immutable here: an incoming tag for a known revision must be the same tag (same target and
 * annotation); a tag missing from the remote is not a deletion; a new one must pass {@link GitFileRevisions} validation
 * together with the existing ones and point into the history {@code main} will have. All are promoted, or none.
 *
 * <p>The remote is {@code remote.origin.url} in the repository config: operational state, never workspace content.
 * Every operation runs under the workspace lock ({@link WorkspaceHistory#withTrustedWorkspace}).
 */
public class WorkspaceGitRemoteService {
  static final String REMOTE = "origin";
  static final String MAIN = Constants.R_HEADS + WorkspaceHistory.BRANCH;
  static final String STAGED_MAIN = Constants.R_REMOTES + REMOTE + "/" + WorkspaceHistory.BRANCH;
  static final String STAGED_REVISIONS = Constants.R_REMOTES + REMOTE + "/plandev-revisions/";
  // Fetch into staging only. Prune and force apply to these staging refs alone, never to main or refs/tags.
  private static final List<RefSpec> FETCH = List.of(
      new RefSpec("+" + MAIN + ":" + STAGED_MAIN),
      new RefSpec("+" + GitFileRevisions.REF_PREFIX + "*:" + STAGED_REVISIONS + "*"));
  // Never forced: a remote that moved on, or holds a different tag of the same name, rejects the push
  private static final List<RefSpec> PUSH = List.of(
      new RefSpec(MAIN + ":" + MAIN),
      new RefSpec(GitFileRevisions.REF_PREFIX + "*:" + GitFileRevisions.REF_PREFIX + "*"));
  private static final List<String> RUNTIME_KEYS =
      List.of(MetadataKeys.readOnly.name(), MetadataKeys.lastEditedBy.name(), MetadataKeys.lastEditedAt.name());

  /** The remote's state was refused; nothing canonical changed. */
  public static final class RemoteRejectedException extends WorkspaceHistoryException {
    public final List<String> problems;

    RemoteRejectedException(final String summary, final List<String> problems) {
      super(Kind.REMOTE_REJECTED, summary + ": " + String.join("; ", problems), null);
      this.problems = List.copyOf(problems);
    }
  }

  public enum Outcome { UP_TO_DATE, LOCAL_AHEAD, FAST_FORWARD, MERGED }

  /** What an integration did: how main moved, where it is now, and which revisions arrived. */
  public record Integration(Outcome outcome, String head, List<UUID> importedRevisions) {}

  /** The validated result of a fetch: where main should go, and the new revision tags to promote. */
  private record Plan(Outcome outcome, ObjectId target, Map<String, Ref> newRevisions) {}

  private final WorkspaceRoots roots;
  private final WorkspaceHistory history;
  private final WorkspaceRevisionService revisions;

  public WorkspaceGitRemoteService(
      final WorkspaceRoots roots,
      final WorkspaceHistory history,
      final WorkspaceRevisionService revisions)
  {
    this.roots = roots;
    this.history = history;
    this.revisions = revisions;
  }

  /** Point the workspace at a remote repository, replacing any previous one. */
  public void linkRemote(final int workspaceId, final String remoteUrl) throws Exception {
    final var url = validUrl(remoteUrl);
    history.withTrustedWorkspace(workspaceId, () -> {
      try (final var repo = Git.open(root(workspaceId).toFile()).getRepository()) {
        setRemote(repo, url);
      }
      return null;
    });
  }

  /**
   * Push {@code main} and every revision tag, atomically and never forced. A remote whose main has moved on must be
   * integrated first ({@link #fetchAndIntegrate}).
   */
  public void push(final int workspaceId) throws Exception {
    history.withTrustedWorkspace(workspaceId, () -> {
      try (final var git = Git.open(root(workspaceId).toFile())) {
        requireRemote(git.getRepository());
        final var problems = new ArrayList<String>();
        for (final var result : git.push().setRemote(REMOTE).setRefSpecs(PUSH).setAtomic(true).setForce(false).call()) {
          for (final var update : result.getRemoteUpdates()) {
            if (update.getStatus() != RemoteRefUpdate.Status.OK && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
              problems.add(update.getRemoteName() + ": " + update.getStatus()
                           + (update.getMessage() == null ? "" : " (" + update.getMessage() + ")"));
            }
          }
        }
        if (!problems.isEmpty()) throw new RemoteRejectedException("The remote refused the push", problems);
      }
      return null;
    });
  }

  /** Fetch the remote into staging, validate it, and integrate it forward into the workspace. See the class Javadoc. */
  public Integration fetchAndIntegrate(final int workspaceId, final String userId) throws Exception {
    final var integration = history.withTrustedWorkspace(workspaceId, () -> {
      try (final var git = Git.open(root(workspaceId).toFile())) {
        final var repo = git.getRepository();
        requireRemote(repo);
        fetch(git);
        final var plan = plan(repo, workspaceId, repo.resolve(MAIN), userId);
        if (plan.outcome() == Outcome.UP_TO_DATE || plan.outcome() == Outcome.LOCAL_AHEAD) {
          promote(repo, plan.newRevisions()); // atomic; main and the working tree are untouched
        } else {
          history.advance(workspaceId, plan.target(), () -> {
            promote(repo, plan.newRevisions());
            return null;
          });
        }
        return new Integration(plan.outcome(), repo.resolve(MAIN).name(), ids(plan.newRevisions()));
      }
    });
    if (!integration.importedRevisions().isEmpty()) reindex(workspaceId);
    return integration;
  }

  /**
   * Make an empty, new workspace a clone of a remote: the remote's main and revision tags, validated exactly as for
   * {@link #fetchAndIntegrate}, then adopted and indexed. Revision ids are kept as they are. On failure the directory
   * is emptied again.
   */
  public Integration cloneInto(final int workspaceId, final String remoteUrl, final String userId) throws Exception {
    final var url = validUrl(remoteUrl);
    final var integration = history.withLock(workspaceId, () -> {
      final var root = root(workspaceId);
      try (final var entries = Files.list(root)) {
        if (entries.findAny().isPresent()) throw new IllegalStateException("Workspace " + workspaceId + " is not empty.");
      }
      try (final var git = Git.init().setDirectory(root.toFile()).setInitialBranch(WorkspaceHistory.BRANCH).call()) {
        final var repo = git.getRepository();
        history.configure(root, repo); // byte-exact checkout regardless of the server's Git config
        setRemote(repo, url);
        fetch(git);
        final var plan = plan(repo, workspaceId, null, userId);
        try (final var walk = new RevWalk(repo)) {
          new DirCacheCheckout(repo, null, repo.lockDirCache(), walk.parseCommit(plan.target()).getTree()).checkout();
        }
        final var update = repo.updateRef(MAIN);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(plan.target());
        if (update.update() != RefUpdate.Result.NEW) throw new IOException("Could not create " + MAIN);
        promote(repo, plan.newRevisions());
        return new Integration(plan.outcome(), plan.target().name(), ids(plan.newRevisions()));
      } catch (Exception e) {
        try (final var paths = Files.walk(root)) { // it was empty, so everything here is ours
          for (final var p : paths.sorted((a, b) -> b.compareTo(a)).filter(p -> !p.equals(root)).toList()) Files.delete(p);
        }
        throw e;
      }
    });
    reindex(workspaceId); // adopts the workspace (state, config, managed marker) and builds its catalog
    return integration;
  }

  //region Planning and validation (reads Git objects only)
  /**
   * Decide where main goes and which revision tags are new, validating both. {@code local} is null for a clone.
   * Writes no refs; a merge commit, if one is needed, is only written as an object.
   */
  private static Plan plan(final Repository repo, final int workspaceId, final ObjectId local, final String userId)
  throws IOException
  {
    final var remote = repo.resolve(STAGED_MAIN);
    if (remote == null) throw new RemoteRejectedException("Nothing to integrate", List.of("the remote has no " + MAIN));
    final Outcome outcome;
    final ObjectId target;
    try (final var walk = new RevWalk(repo)) {
      final var theirs = walk.parseCommit(remote);
      final var ours = local == null ? null : walk.parseCommit(local);
      if (ours == null) {
        outcome = Outcome.FAST_FORWARD;
        target = theirs;
      } else if (ours.equals(theirs)) {
        outcome = Outcome.UP_TO_DATE;
        target = ours;
      } else if (walk.isMergedInto(theirs, ours)) {
        outcome = Outcome.LOCAL_AHEAD;
        target = ours;
      } else if (walk.isMergedInto(ours, theirs)) {
        outcome = Outcome.FAST_FORWARD;
        target = theirs;
      } else {
        outcome = Outcome.MERGED;
        target = merge(repo, walk, ours, theirs, userId);
      }
      final var problems = treeProblems(repo, walk.parseCommit(target));
      if (!problems.isEmpty()) throw new RemoteRejectedException("The remote's workspace is not valid", problems);
      return new Plan(outcome, target, newRevisions(repo, walk, workspaceId, walk.parseCommit(target)));
    }
  }

  /** A two-parent merge commit of a clean merge; conflicts and unrelated histories are rejected. */
  private static ObjectId merge(
      final Repository repo,
      final RevWalk walk,
      final RevCommit ours,
      final RevCommit theirs,
      final String userId) throws IOException
  {
    walk.reset();
    walk.setRevFilter(RevFilter.MERGE_BASE);
    walk.markStart(ours);
    walk.markStart(theirs);
    final var base = walk.next();
    walk.reset();
    walk.setRevFilter(RevFilter.ALL);
    if (base == null) throw new RemoteRejectedException("Cannot integrate the remote", List.of("its history is unrelated"));

    final var merger = (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(repo, true);
    if (!merger.merge(ours, theirs)) {
      final var conflicts = new ArrayList<>(merger.getUnmergedPaths());
      if (merger.getFailingPaths() != null) conflicts.addAll(merger.getFailingPaths().keySet());
      throw new RemoteRejectedException("The remote's changes conflict with the workspace's",
                                        conflicts.stream().map(p -> p + " (conflict)").toList());
    }
    final var commit = new CommitBuilder();
    commit.setTreeId(merger.getResultTreeId());
    commit.setParentIds(ours, theirs);
    commit.setAuthor(WorkspaceHistory.userIdent(userId));
    commit.setCommitter(WorkspaceHistory.systemIdent());
    commit.setMessage("Integrate remote " + WorkspaceHistory.BRANCH);
    try (final var inserter = repo.newObjectInserter()) {
      final var id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  /**
   * Why a commit's tree is not acceptable as the workspace, if it is not: the rules PlanDev's own mutations keep
   * (plain files only, no reserved names) plus the sidecar rules revisions rely on (JSON objects, a UUID fileId if
   * any, no runtime fields, each beside its content file, no two files with one fileId).
   */
  static List<String> treeProblems(final Repository repo, final RevCommit commit) throws IOException {
    final var problems = new ArrayList<String>();
    final var files = new HashSet<String>();
    final var sidecars = new TreeMap<String, ObjectId>();
    try (final var tw = new TreeWalk(repo)) {
      tw.addTree(commit.getTree());
      tw.setRecursive(true);
      while (tw.next()) {
        final var path = tw.getPathString();
        final var mode = tw.getFileMode(0);
        if (Arrays.stream(path.split("/")).anyMatch(WorkspacePaths::isReservedName)) {
          problems.add(path + " (reserved path)");
        } else if (mode == FileMode.SYMLINK) {
          problems.add(path + " (symbolic link)");
        } else if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
          problems.add(path + " (not a regular file)");
        } else if (RenderType.isAerieMetadataFile(tw.getNameString())) {
          sidecars.put(path, tw.getObjectId(0));
        } else {
          files.add(path);
        }
      }
    }
    final var claims = new TreeMap<UUID, List<String>>();
    for (final var sidecar : sidecars.entrySet()) {
      final var path = sidecar.getKey();
      final var slash = path.lastIndexOf('/');
      final var name = path.substring(slash + 1);
      final var file = path.substring(0, slash + 1)
                       + name.substring(1, name.length() - RenderType.aerieMetadataExtension.length());
      if (!files.contains(file)) {
        problems.add(path + " (metadata has no corresponding file " + file + ")");
        continue;
      }
      final JsonObject json;
      try (final var reader = Json.createReader(new StringReader(
          new String(repo.open(sidecar.getValue()).getBytes(), StandardCharsets.UTF_8)))) {
        json = reader.readObject();
      } catch (JsonException | IllegalStateException e) {
        problems.add(path + " (metadata is not a JSON object)");
        continue;
      }
      RUNTIME_KEYS.stream().filter(json::containsKey)
                  .forEach(k -> problems.add(path + " (metadata has runtime field " + k + ")"));
      if (!json.containsKey(MetadataKeys.fileId.name())) continue;
      final UUID fileId;
      try {
        fileId = UUID.fromString(((JsonString) json.get(MetadataKeys.fileId.name())).getString());
      } catch (ClassCastException | IllegalArgumentException e) {
        problems.add(path + " (fileId is not a UUID)");
        continue;
      }
      claims.computeIfAbsent(fileId, k -> new ArrayList<>()).add(file);
    }
    claims.forEach((fileId, paths) -> {
      if (paths.size() > 1) problems.add("%s all claim fileId %s (give copies a new identity)".formatted(paths, fileId));
    });
    return problems;
  }

  /**
   * The staged revision tags that are new, after checking that known ones are the exact same tag object and that the
   * new ones are valid alongside the existing ones and point into {@code target}'s history. All or nothing. A tag
   * recreated with the same target and message is still a different object, which a later non-forced push could not
   * reconcile, so it is rejected like any other change.
   */
  private static Map<String, Ref> newRevisions(
      final Repository repo,
      final RevWalk walk,
      final int workspaceId,
      final RevCommit target) throws IOException
  {
    final var canonical = GitFileRevisions.refs(repo, GitFileRevisions.REF_PREFIX);
    final var incoming = new TreeMap<String, Ref>();
    final var problems = new ArrayList<String>();
    for (final var staged : GitFileRevisions.refs(repo, STAGED_REVISIONS).entrySet()) {
      final var known = canonical.get(staged.getKey());
      if (known == null) incoming.put(staged.getKey(), staged.getValue());
      else if (!known.getObjectId().equals(staged.getValue().getObjectId())) {
        problems.add("revision " + staged.getKey() + " differs from the existing one, which is immutable");
      }
    }
    if (!problems.isEmpty()) throw new RemoteRejectedException("The remote's revisions are not acceptable", problems);
    if (incoming.isEmpty()) return incoming;

    final var all = new HashMap<>(canonical);
    all.putAll(incoming);
    final var incomingIds = incoming.keySet();
    for (final var revision : GitFileRevisions.validate(repo, workspaceId, all)) {
      if (!incomingIds.contains(revision.id().toString())) continue;
      if (!walk.isMergedInto(walk.parseCommit(ObjectId.fromString(revision.commitSha())), target)) {
        problems.add("revision %s is on commit %s, which is not in the integrated history"
                         .formatted(revision.id(), revision.commitSha()));
      }
    }
    if (!problems.isEmpty()) throw new RemoteRejectedException("The remote's revisions are not acceptable", problems);
    return incoming;
  }

  //endregion

  //region Git
  private static void fetch(final Git git) throws Exception {
    git.fetch().setRemote(REMOTE).setRefSpecs(FETCH).setTagOpt(TagOpt.NO_TAGS).setRemoveDeletedRefs(true).call();
  }

  /** Create the new revision tags in one atomic ref transaction: all are promoted, or none. Never overwrites a tag. */
  protected void promote(final Repository repo, final Map<String, Ref> newRevisions) throws IOException {
    if (newRevisions.isEmpty()) return;
    final var batch = repo.getRefDatabase().newBatchUpdate().setAtomic(true);
    newRevisions.forEach((id, staged) -> batch.addCommand(
        new ReceiveCommand(ObjectId.zeroId(), staged.getObjectId(), GitFileRevisions.REF_PREFIX + id)));
    try (final var walk = new RevWalk(repo)) {
      batch.execute(walk, NullProgressMonitor.INSTANCE);
    }
    final var failed = batch.getCommands().stream().filter(c -> c.getResult() != ReceiveCommand.Result.OK)
                            .map(c -> c.getRefName() + ": " + c.getResult()).toList();
    if (!failed.isEmpty()) throw new IOException("Could not promote revision tags: " + failed);
  }

  private static void setRemote(final Repository repo, final URIish url) throws IOException {
    final var cfg = repo.getConfig();
    cfg.setString("remote", REMOTE, "url", url.toString());
    cfg.save();
  }

  private static void requireRemote(final Repository repo) {
    if (repo.getConfig().getString("remote", REMOTE, "url") == null) {
      throw new IllegalStateException("The workspace has no linked remote.");
    }
  }

  private static URIish validUrl(final String url) {
    try {
      return new URIish(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Not a Git remote URL: " + url, e);
    }
  }
  //endregion

  private void reindex(final int workspaceId) throws Exception {
    try {
      revisions.reindexRevisionsFromGit(workspaceId);
    } catch (Exception e) {
      throw new WorkspaceHistoryException(
          Kind.REVISION_NOT_INDEXED,
          "The remote was integrated, but the revision index could not be rebuilt; reindex the workspace's revisions.",
          e);
    }
  }

  private static List<UUID> ids(final Map<String, Ref> revisions) {
    return revisions.keySet().stream().map(UUID::fromString).toList();
  }

  private Path root(final int workspaceId) throws Exception {
    return roots.workspaceRootPath(workspaceId).normalize();
  }
}
