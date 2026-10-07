package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * The single owner of workspace mutations and of the Git history that records them.
 *
 * <p><b>Invariant.</b> After every successful workspace mutation, the workspace working tree is represented by Git
 * history and the repository is clean. Every mutation of workspace-managed files (file and directory create, save,
 * move, copy, delete, metadata edits, cross-workspace operations) runs inside {@link #mutate}; the mutating methods of
 * {@link WorkspaceFileSystemService} call {@link #requireLocked} so a path that bypasses this class fails loudly.
 *
 * <p><b>Flow.</b> {@link #mutate} takes each involved workspace's lock (in ascending id order, so two cross-workspace
 * operations cannot deadlock), brings each repository to a clean, initialized state, records the pre-operation state,
 * runs the mutation (which performs its own validation, e.g. existence, readOnly and ETag checks, under the lock),
 * stages the complete resulting delta ({@code git add -A}), commits it if anything changed, and verifies the
 * repository is clean before returning. A request never reports success if the history update failed.
 *
 * <p><b>Clean.</b> A repository is clean when {@code git status} reports no staged, modified, missing, conflicting or
 * untracked files, and no ignored file exists outside {@code .seqdev/}. Ignore rules are fully controlled here:
 * {@code core.excludesFile} points at {@code /dev/null}, {@code .git/info/exclude} contains only {@code /.seqdev/},
 * and {@code .gitignore}/{@code .gitattributes} cannot exist in the tree ({@link WorkspacePaths}). Outside the
 * definition by design: {@code .git/} itself, {@code .seqdev/} (runtime state, never committed) and empty
 * directories (not representable in Git; creating or deleting one produces no commit).
 *
 * <p><b>Failure.</b> If the mutation throws, reports failure, or its history cannot be recorded, the workspace is
 * restored to its pre-operation state: tracked files and the index are checked out from the pre-operation HEAD, the
 * paths this operation created (untracked or newly added after it ran; equivalent to "created by this operation"
 * because the repository was verified clean beforehand under the same lock) are removed along with any parent
 * directories that leaves empty, and {@code .seqdev/state.json} is restored. Empty directories are not versioned
 * state, so a pre-existing empty directory the operation removed is not recreated. If restoration itself fails, a
 * {@link Kind#REPOSITORY_INCONSISTENT} error is raised rather than claiming success; nothing is ever {@code git clean}ed
 * wholesale or reset on the strength of an unverified assumption.
 *
 * <p><b>Cross-workspace.</b> Two repositories cannot share a transaction. Commits are made in the order the caller
 * lists the workspaces (destination first for moves). If the first commit fails, every workspace is restored. If a
 * later commit fails, the workspaces already committed keep their change and the remaining ones are restored, raising
 * {@link Kind#PARTIALLY_APPLIED}: for a move this means it degraded to a copy, which is preferable to losing data.
 *
 * <p><b>Adoption.</b> A workspace is <i>managed</i> once its repository config carries {@code plandev.managed=true},
 * which is written only after adoption has fully succeeded. An unmanaged workspace (new, pre-history, or a repository
 * PlanDev did not finish adopting) is adopted on its first mutation: its tree must contain no reserved names and no
 * symbolic links; legacy sidecar metadata is migrated (resumably); a repository is created if missing; and the current
 * state is recorded by a commit authored by {@link #systemIdent()} ("Initialize workspace history" for a new
 * repository, "Adopt workspace into history" for an existing one with uncommitted state).
 *
 * <p><b>Trust.</b> Before every mutation of a managed workspace the repository must be exactly what this class left
 * behind: no in-progress Git operation, HEAD attached to {@code main}, an index of plain files outside reserved names,
 * runtime state present, and a clean working tree. Anything else (an out-of-band edit, residue of a crash
 * mid-operation, a manual checkout) is rejected with {@link Kind#REPOSITORY_INCONSISTENT} naming what is wrong. It is
 * never committed, reset or repaired automatically.
 *
 * <p><b>Deployment assumption.</b> Locks are in-process. This is correct only while a single workspace-server
 * instance owns the workspace volume (the current deployment: one replica, no other service mounts the volume) and
 * nothing outside this class writes to a workspace's working tree. Reads do not take the lock.
 */
public class WorkspaceHistory {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceHistory.class);

  static final String INIT_MESSAGE = "Initialize workspace history";
  static final String ADOPT_MESSAGE = "Adopt workspace into history";
  static final String BRANCH = "main";
  private static final String MANAGED_SECTION = "plandev";
  private static final String MANAGED_KEY = "managed";

  public enum Kind {
    /** The history could not be recorded; the workspace was restored to its pre-operation state. */
    HISTORY_NOT_RECORDED("WORKSPACE_HISTORY_ERROR"),
    /** A cross-workspace mutation was applied to some workspaces only; see {@link PartialMutationException}. */
    PARTIALLY_APPLIED("WORKSPACE_MUTATION_PARTIAL"),
    /** The repository is not in a state this class can vouch for, and was not (or could not be) repaired. */
    REPOSITORY_INCONSISTENT("WORKSPACE_REPOSITORY_INCONSISTENT"),
    /** The revision catalog and the repository disagree (see {@link WorkspaceRevisionService}). */
    REVISION_CATALOG_INCONSISTENT("WORKSPACE_REVISION_CATALOG_INCONSISTENT"),
    /** A recognized PlanDev revision tag is malformed or inconsistent (see {@link GitFileRevisions}). */
    REVISION_TAG_INVALID("WORKSPACE_REVISION_TAG_INVALID"),
    /** A revision was created (its tag exists) but could not be recorded in the catalog; a reindex repairs it. */
    REVISION_NOT_INDEXED("WORKSPACE_REVISION_NOT_INDEXED"),
    /** A remote's history or refs were refused (see {@link WorkspaceGitRemoteService}); the workspace is unchanged. */
    REMOTE_REJECTED("WORKSPACE_REMOTE_REJECTED");

    public final String errorType;

    Kind(final String errorType) {
      this.errorType = errorType;
    }
  }

  public static class WorkspaceHistoryException extends RuntimeException {
    public final Kind kind;

    WorkspaceHistoryException(final Kind kind, final String message, final Throwable cause) {
      super(message, cause);
      this.kind = kind;
    }
  }

  /** A later commit of a cross-workspace mutation failed after earlier workspaces were committed. */
  public static final class PartialMutationException extends WorkspaceHistoryException {
    public final List<Integer> committedWorkspaceIds;
    public final int restoredWorkspaceId;

    PartialMutationException(final List<Integer> committed, final int restored, final Throwable cause) {
      super(Kind.PARTIALLY_APPLIED,
            "Workspace(s) %s were updated but workspace %d could not be and was restored".formatted(committed, restored),
            cause);
      this.committedWorkspaceIds = List.copyOf(committed);
      this.restoredWorkspaceId = restored;
    }
  }

  @FunctionalInterface
  public interface Mutation<T> {
    T apply() throws Exception;
  }

  /** A file's most recent content change, derived from history. {@code baseline} marks the history's root commit. */
  public record LastEdit(String by, Instant at, boolean baseline) {}

  private record Before(ObjectId head, byte[] state) {}

  private record LastEditIndex(ObjectId head, Map<String, LastEdit> edits) {}

  private final WorkspaceRoots roots;
  // ponytail: one lock per workspace id, never evicted; a few bytes per workspace ever touched by this process.
  private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final Map<Path, LastEditIndex> lastEditCache = new ConcurrentHashMap<>();
  private final Set<Path> configured = ConcurrentHashMap.newKeySet();

  public WorkspaceHistory(final WorkspaceRoots roots) {
    this.roots = roots;
  }

  //region Mutation boundary
  /** Run a single-workspace mutation. See {@link #mutate(LinkedHashMap, String, Mutation, Predicate)}. */
  public <T> T mutate(
      final int workspaceId,
      final String userId,
      final String message,
      final Mutation<T> mutation,
      final Predicate<T> succeeded) throws Exception
  {
    final var commits = new LinkedHashMap<Integer, String>();
    commits.put(workspaceId, message);
    return mutate(commits, userId, mutation, succeeded);
  }

  /**
   * Run a mutation across one or more workspaces under their locks, then record it in each workspace's history.
   *
   * @param commits the involved workspaces, in commit order, each with its commit message
   * @param userId the PlanDev user the commits are authored by
   * @param mutation performs validation and the filesystem change; may return a failure result instead of throwing
   * @param succeeded whether a returned result is a success; a failure result is rolled back and returned as-is
   * @return the mutation's result
   * @throws WorkspaceHistoryException if history could not be recorded (the change was rolled back), was recorded in
   *     only some workspaces, or the repository could not be restored
   * @throws Exception anything the mutation throws, after the workspaces have been restored
   */
  public <T> T mutate(
      final LinkedHashMap<Integer, String> commits,
      final String userId,
      final Mutation<T> mutation,
      final Predicate<T> succeeded) throws Exception
  {
    final var ids = List.copyOf(commits.keySet());
    final var lockOrder = ids.stream().sorted().map(this::lockFor).toList();
    lockOrder.forEach(ReentrantLock::lock);
    try {
      final var rootsById = new LinkedHashMap<Integer, Path>();
      final var before = new HashMap<Integer, Before>();
      for (final var id : ids) {
        final var root = roots.workspaceRootPath(id).normalize();
        rootsById.put(id, root);
        ensureReady(root);
        before.put(id, capture(root));
      }

      final T result;
      try {
        result = mutation.apply();
      } catch (Exception e) {
        restoreAll(ids, rootsById, before, e);
        throw e;
      }
      if (!succeeded.test(result)) {
        restoreAll(ids, rootsById, before, null);
        return result;
      }

      final var committed = new ArrayList<Integer>();
      for (final var id : ids) {
        final var root = rootsById.get(id);
        try (final var git = open(root)) {
          if (stageAll(git)) {
            requireValidIndex(git.getRepository());
            commit(git, commits.get(id), userIdent(userId));
          }
          requireClean(git, root);
          committed.add(id);
        } catch (Exception e) {
          logger.error("Recording history for workspace {} failed; restoring", id, e);
          restoreAll(ids.subList(committed.size(), ids.size()), rootsById, before, e);
          if (committed.isEmpty()) {
            throw new WorkspaceHistoryException(
                Kind.HISTORY_NOT_RECORDED,
                "The change could not be recorded in workspace history and was rolled back.",
                e);
          }
          throw new PartialMutationException(committed, id, e);
        }
      }
      return result;
    } finally {
      lockOrder.reversed().forEach(ReentrantLock::unlock);
    }
  }

  /**
   * Run an action that may change Git refs (such as creating a tag) but never the working tree, under the workspace's
   * lock, on a workspace brought to "managed, trusted and clean" exactly as {@link #mutate} does. Nothing is committed,
   * and the working tree must still be clean afterwards. The lock is reentrant, so the action may itself call
   * {@link #mutate} on the same workspace; no other mutation can land between that commit and the rest of the action.
   */
  public <T> T withTrustedWorkspace(final int workspaceId, final Mutation<T> action) throws Exception {
    final var lock = lockFor(workspaceId);
    lock.lock();
    try {
      final var root = roots.workspaceRootPath(workspaceId).normalize();
      ensureReady(root);
      final var result = action.apply();
      try (final var git = open(root)) {
        requireClean(git, root);
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  /** Run an action under a workspace's lock without recording history (e.g. deleting the whole workspace). */
  public <T> T withLock(final int workspaceId, final Mutation<T> action) throws Exception {
    final var lock = lockFor(workspaceId);
    lock.lock();
    try {
      final var root = roots.workspaceRootPath(workspaceId).normalize(); // before the action may delete the record
      final var result = action.apply();
      lastEditCache.remove(root);
      configured.remove(root);
      return result;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Move {@code main} forward to {@code target}, a descendant of HEAD already in the repository (such as a fetched or
   * merged commit), checking it out into the index and working tree; then run {@code then}. Nothing is committed. If
   * any step fails, the workspace is restored exactly as {@link #mutate} restores a failed mutation (HEAD, tracked
   * files, created paths, runtime state). The caller holds the lock on a trusted, clean workspace
   * ({@link #withTrustedWorkspace}); {@code then} must leave the working tree alone.
   */
  <T> T advance(final int workspaceId, final ObjectId target, final Mutation<T> then) throws Exception {
    requireLocked(workspaceId);
    final var root = roots.workspaceRootPath(workspaceId).normalize();
    final var before = capture(root);
    try {
      try (final var git = open(root); final var walk = new RevWalk(git.getRepository())) {
        final var repo = git.getRepository();
        final var head = walk.parseCommit(before.head());
        final var to = walk.parseCommit(target);
        if (!walk.isMergedInto(head, to)) throw new IllegalArgumentException(target.name() + " does not descend from HEAD");
        final var checkout = new DirCacheCheckout(repo, head.getTree(), repo.lockDirCache(), to.getTree());
        checkout.setFailOnConflict(true);
        checkout.checkout();
        final var update = repo.updateRef(Constants.R_HEADS + BRANCH);
        update.setExpectedOldObjectId(before.head());
        update.setNewObjectId(target);
        final var result = update.update(walk);
        if (result != RefUpdate.Result.FAST_FORWARD) throw new IOException("Could not advance " + BRANCH + ": " + result);
        requireValidIndex(repo);
        requireClean(git, root);
      }
      return then.apply();
    } catch (Exception e) {
      logger.error("Advancing workspace {} to {} failed; restoring", workspaceId, target.name(), e);
      restoreAll(List.of(workspaceId), Map.of(workspaceId, root), Map.of(workspaceId, before), e);
      throw e;
    }
  }

  /** Throw unless the current thread holds every given workspace's mutation lock. */
  public void requireLocked(final int... workspaceIds) {
    for (final var id : workspaceIds) {
      final var lock = locks.get(id);
      if (lock == null || !lock.isHeldByCurrentThread()) {
        throw new IllegalStateException(
            "Workspace %d was mutated outside WorkspaceHistory.mutate; every workspace mutation must go through it."
                .formatted(id));
      }
    }
  }

  private ReentrantLock lockFor(final int workspaceId) {
    return locks.computeIfAbsent(workspaceId, k -> new ReentrantLock());
  }

  /** The Git commit step. Overridable only so tests can inject a deterministic failure. */
  protected void commit(final Git git, final String message, final PersonIdent author) throws GitAPIException {
    git.commit()
       .setMessage(message)
       .setAuthor(author)
       .setCommitter(systemIdent())
       .setAllowEmpty(true)
       .setSign(false)
       .setNoVerify(true)
       .call();
  }
  //endregion

  //region Adoption and trust
  /** Bring a workspace to "managed, trusted and clean", adopting it first if it is not managed yet. Caller holds the lock. */
  private void ensureReady(final Path root) throws IOException, GitAPIException {
    if (!Files.isDirectory(root)) {
      throw inconsistent("Workspace directory " + root + " is missing.");
    }
    if (isManaged(root)) {
      requireTrusted(root);
    } else {
      adopt(root);
    }
  }

  /** Fail closed unless a managed workspace is exactly as this class left it. */
  private void requireTrusted(final Path root) throws IOException, GitAPIException {
    requireValidRuntimeState(root, true);
    try (final var git = open(root)) {
      requireExpectedGitState(git.getRepository(), false);
      configure(root, git.getRepository());
      requireValidIndex(git.getRepository());
      final var dirty = dirtyPaths(git.status().call());
      if (!dirty.isEmpty()) {
        throw inconsistent("Workspace at %s has changes that are not in its history: %s. They were left untouched."
                               .formatted(root, summarize(dirty)));
      }
    }
    WorkspacePaths.removeStaleTempFiles(root); // only once trusted: a rejected workspace is left exactly as found
  }

  /**
   * Put a workspace under history. Every step is idempotent and the managed marker is written last, so an adoption
   * interrupted at any point is simply redone by the next mutation.
   */
  private void adopt(final Path root) throws IOException, GitAPIException {
    // Validate everything before writing anything
    requireValidRuntimeState(root, false);
    requireAdoptable(root);
    final var hasRepo = Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR));
    if (hasRepo) {
      try (final var git = open(root)) {
        requireExpectedGitState(git.getRepository(), true);
      }
    }
    WorkspacePaths.removeStaleTempFiles(root);
    WorkspaceState.migrateFromSidecars(root); // validates every sidecar before writing anything
    if (!hasRepo) Git.init().setDirectory(root.toFile()).setInitialBranch(BRANCH).call().close();
    configured.remove(root); // a new or newly adopted repository gets its config (re)applied

    try (final var git = open(root)) {
      final var repo = git.getRepository();
      configure(root, repo);
      final var unborn = repo.resolve(Constants.HEAD) == null;
      if (stageAll(git) || unborn) {
        requireValidIndex(repo);
        commit(git, unborn ? INIT_MESSAGE : ADOPT_MESSAGE, systemIdent());
      }
      requireValidIndex(repo);
      requireClean(git, root);

      final var cfg = repo.getConfig();
      cfg.setBoolean(MANAGED_SECTION, null, MANAGED_KEY, true);
      cfg.save();
      logger.info("Workspace at {} is now under history", root);
    }
  }

  /** Whether a workspace has been fully adopted (see the class Javadoc). Never discovers a parent repository. */
  boolean isManaged(final Path root) throws IOException {
    if (!Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR), LinkOption.NOFOLLOW_LINKS)) return false;
    try (final var git = open(root)) {
      return git.getRepository().getConfig().getBoolean(MANAGED_SECTION, null, MANAGED_KEY, false);
    }
  }

  /**
   * {@code .seqdev} must be a real directory holding a regular {@code state.json} in the current schema. Required for
   * a managed workspace; for one being adopted, whatever already exists must be valid. Never repaired automatically:
   * runtime state holds locks, so "unreadable" must not turn into "unlocked".
   */
  private static void requireValidRuntimeState(final Path root, final boolean required) {
    final var dir = root.resolve(WorkspacePaths.STATE_DIR);
    final var file = WorkspaceState.file(root);
    final String problem;
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      problem = required ? dir + " is missing" : null;
    } else if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      problem = dir + " is not a directory";
    } else if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      problem = required ? file + " is missing" : null;
    } else {
      String loadProblem = null;
      try {
        WorkspaceState.load(root);
      } catch (IOException e) {
        loadProblem = e.getMessage();
      }
      problem = loadProblem;
    }
    if (problem != null) {
      throw inconsistent("Workspace at %s has invalid runtime state: %s. It was left untouched.".formatted(root, problem));
    }
  }

  /**
   * PlanDev uses a deliberately small subset of Git: one branch, always checked out, no merges or other multi-step
   * operations. Refuse to touch a repository outside that subset rather than switching branches or aborting anything.
   */
  private static void requireExpectedGitState(final Repository repo, final boolean allowUnborn) throws IOException {
    final var root = repo.getWorkTree();
    final var state = repo.getRepositoryState();
    if (state != RepositoryState.SAFE) {
      throw inconsistent("Workspace at %s has a Git operation in progress (%s); finish or abort it outside PlanDev."
                             .formatted(root, state.getDescription()));
    }
    final var head = repo.exactRef(Constants.HEAD);
    if (head == null || !head.isSymbolic()) {
      throw inconsistent("Workspace at %s has a detached HEAD; check out branch '%s' outside PlanDev.".formatted(root, BRANCH));
    }
    if (!head.getTarget().getName().equals(Constants.R_HEADS + BRANCH)) {
      throw inconsistent("Workspace at %s has '%s' checked out; PlanDev only uses branch '%s'."
                             .formatted(root, Repository.shortenRefName(head.getTarget().getName()), BRANCH));
    }
    if (!allowUnborn && head.getObjectId() == null) {
      throw inconsistent("Workspace at %s has no commits on branch '%s'.".formatted(root, BRANCH));
    }
  }

  /**
   * Make every repository behavior that could change file bytes or what is tracked explicit at repository level,
   * rather than inheriting it from the server's $HOME or system Git config. Applied once per process per workspace.
   */
  void configure(final Path root, final Repository repo) throws IOException {
    if (configured.contains(root)) return;
    final var cfg = repo.getConfig();
    cfg.setBoolean("core", null, "autocrlf", false);
    cfg.setBoolean("core", null, "safecrlf", false);
    cfg.setString("core", null, "excludesFile", "/dev/null");
    cfg.setString("core", null, "attributesFile", "/dev/null");
    cfg.setString("core", null, "hooksPath", "/dev/null");
    cfg.setBoolean("commit", null, "gpgSign", false);
    cfg.setBoolean("tag", null, "gpgSign", false);
    cfg.save();

    final var info = repo.getDirectory().toPath().resolve("info");
    Files.createDirectories(info);
    Files.writeString(info.resolve("exclude"), "/" + WorkspacePaths.STATE_DIR + "/\n", StandardCharsets.UTF_8);
    Files.deleteIfExists(info.resolve("attributes"));
    configured.add(root);
  }

  /**
   * Fail closed if the tree contains reserved names that would make Git skip or rewrite user files, or symbolic links
   * (unsupported workspace content: one could point into {@code .git} or outside the workspace). Only possible for
   * workspaces created or edited outside the file API, which rejects both. Links are never followed.
   */
  private static void requireAdoptable(final Path root) throws IOException {
    final var found = new ArrayList<String>();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
        if (dir.equals(root)) return FileVisitResult.CONTINUE;
        final var name = dir.getFileName().toString();
        if (dir.getParent().equals(root) && (name.equals(WorkspacePaths.GIT_DIR) || name.equals(WorkspacePaths.STATE_DIR))) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        if (WorkspacePaths.isReservedName(name)) found.add(WorkspacePaths.key(root, dir));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink()) {
          found.add(WorkspacePaths.key(root, file) + " (symbolic link)");
        } else if (WorkspacePaths.isReservedName(file.getFileName().toString())) {
          found.add(WorkspacePaths.key(root, file));
        }
        return FileVisitResult.CONTINUE;
      }
    });
    if (!found.isEmpty()) {
      throw inconsistent("Workspace at %s contains unsupported paths %s; remove them before it can be modified."
                             .formatted(root, summarize(found)));
    }
  }

  /** The index (== HEAD once clean) holds only regular files outside reserved names: no symlinks or submodules. */
  private static void requireValidIndex(final Repository repo) throws IOException {
    final var bad = new ArrayList<String>();
    final var index = repo.readDirCache();
    for (int i = 0; i < index.getEntryCount(); i++) {
      final var entry = index.getEntry(i);
      final var path = entry.getPathString();
      final var mode = entry.getRawMode();
      if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode)) {
        bad.add(path + " (" + entry.getFileMode() + ")");
      } else if (Arrays.stream(path.split("/")).anyMatch(WorkspacePaths::isReservedName)) {
        bad.add(path);
      }
    }
    if (!bad.isEmpty()) {
      throw inconsistent("Workspace at %s tracks unsupported paths %s.".formatted(repo.getWorkTree(), summarize(bad)));
    }
  }

  static WorkspaceHistoryException inconsistent(final String message) {
    return new WorkspaceHistoryException(Kind.REPOSITORY_INCONSISTENT, message, null);
  }

  static String summarize(final Collection<String> paths) {
    final var shown = paths.stream().limit(20).toList();
    return paths.size() > shown.size() ? shown + " and " + (paths.size() - shown.size()) + " more" : shown.toString();
  }
  //endregion

  //region Git helpers
  private static Git open(final Path root) throws IOException {
    // Opens <root>/.git only; never discovers a repository in a parent directory. Closing the Git closes the repo.
    return Git.open(root.toFile());
  }

  /** {@code git add -A}. Returns whether anything is staged relative to HEAD. */
  private static boolean stageAll(final Git git) throws GitAPIException {
    git.add().addFilepattern(".").call();
    git.add().addFilepattern(".").setUpdate(true).call();
    final var status = git.status().call();
    return !(status.getAdded().isEmpty() && status.getChanged().isEmpty() && status.getRemoved().isEmpty());
  }

  /** Every path that makes the repository not clean, per the definition in the class Javadoc. */
  static Set<String> dirtyPaths(final Status status) {
    final var dirty = new TreeSet<String>();
    dirty.addAll(status.getAdded());
    dirty.addAll(status.getChanged());
    dirty.addAll(status.getRemoved());
    dirty.addAll(status.getMissing());
    dirty.addAll(status.getModified());
    dirty.addAll(status.getConflicting());
    // Untracked files are listed individually; untracked *folders* add nothing beyond them except empty
    // directories, which are deliberately outside the definition.
    dirty.addAll(status.getUntracked());
    for (final var ignored : status.getIgnoredNotInIndex()) {
      if (!ignored.equals(WorkspacePaths.STATE_DIR) && !ignored.startsWith(WorkspacePaths.STATE_DIR + "/")) {
        dirty.add(ignored);
      }
    }
    return dirty;
  }

  private static void requireClean(final Git git, final Path root) throws GitAPIException {
    final var dirty = dirtyPaths(git.status().call());
    if (!dirty.isEmpty()) throw inconsistent("Workspace at %s is not clean: %s".formatted(root, summarize(dirty)));
  }

  static PersonIdent systemIdent() {
    return new PersonIdent("PlanDev", "");
  }

  static PersonIdent userIdent(final String userId) {
    return userId == null || userId.isBlank() ? systemIdent() : new PersonIdent(userId, "");
  }
  //endregion

  //region Rollback
  private Before capture(final Path root) throws IOException {
    try (final var git = open(root)) {
      final var head = git.getRepository().resolve(Constants.HEAD);
      final var stateFile = WorkspaceState.file(root);
      final var state = Files.isRegularFile(stateFile) ? Files.readAllBytes(stateFile) : null;
      return new Before(head, state);
    }
  }

  /** Restore each workspace; if any cannot be restored, fail with REPOSITORY_INCONSISTENT. */
  private void restoreAll(
      final List<Integer> ids,
      final Map<Integer, Path> rootsById,
      final Map<Integer, Before> before,
      final Exception cause)
  {
    final var failed = new ArrayList<Integer>();
    final var errors = new ArrayList<Exception>();
    for (final var id : ids) {
      try {
        restore(rootsById.get(id), before.get(id));
      } catch (Exception e) {
        logger.error("Could not restore workspace {} after a failed mutation", id, e);
        failed.add(id);
        errors.add(e);
      }
    }
    if (!errors.isEmpty()) {
      final var ex = new WorkspaceHistoryException(
          Kind.REPOSITORY_INCONSISTENT,
          "A failed change could not be fully rolled back in workspace(s) %s; the workspace may not match its history."
              .formatted(failed),
          errors.getFirst());
      errors.stream().skip(1).forEach(ex::addSuppressed);
      if (cause != null) ex.addSuppressed(cause);
      throw ex;
    }
  }

  private void restore(final Path root, final Before before) throws IOException, GitAPIException {
    try (final var git = open(root); final var walk = new RevWalk(git.getRepository())) {
      final var repo = git.getRepository();

      // Record what this operation created. The repository was clean before it ran (verified under this lock),
      // so every untracked or newly added path now is one it created.
      final var status = git.status().call();
      final var created = new TreeSet<String>();
      created.addAll(status.getUntracked());
      created.addAll(status.getAdded());

      // Move the branch back only if the operation's commit actually landed.
      if (!before.head().equals(repo.resolve(Constants.HEAD))) {
        final var update = repo.updateRef(Constants.HEAD);
        update.setNewObjectId(before.head());
        final var result = update.forceUpdate();
        if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
          throw new IOException("Could not move HEAD back to " + before.head().name() + ": " + result);
        }
      }

      // Index and tracked files back to the pre-operation commit.
      final RevCommit commit = walk.parseCommit(before.head());
      final var checkout = new DirCacheCheckout(repo, repo.lockDirCache(), commit.getTree());
      checkout.setFailOnConflict(false);
      checkout.checkout();

      // Remove what the operation created, then any parent directory that leaves empty (empty directories are not
      // versioned state; this only avoids leaving a failed save's new folders behind).
      for (final var path : created) {
        var p = root.resolve(path);
        Files.deleteIfExists(p);
        for (p = p.getParent(); !p.equals(root) && isEmptyDirectory(p); p = p.getParent()) Files.delete(p);
      }

      final var stateFile = WorkspaceState.file(root);
      if (before.state() == null) {
        Files.deleteIfExists(stateFile);
      } else {
        WorkspacePaths.writeAtomically(root, stateFile, before.state());
      }

      requireClean(git, root);
    }
  }
  private static boolean isEmptyDirectory(final Path dir) throws IOException {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false;
    try (final var entries = Files.list(dir)) {
      return entries.findAny().isEmpty();
    }
  }
  //endregion

  //region Reads (lock-free)
  /**
   * The most recent commit that changed each content path (sidecars excluded), keyed by '/'-separated
   * workspace-relative path. Cached per workspace by HEAD; when HEAD advances only the new commits are walked.
   */
  public Map<String, LastEdit> lastEdits(final Path root) throws IOException {
    if (!Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR))) return Map.of();
    try (final var git = open(root); final var walk = new RevWalk(git.getRepository())) {
      final var repo = git.getRepository();
      final var head = repo.resolve(Constants.HEAD);
      if (head == null) return Map.of();
      final var cached = lastEditCache.get(root);
      if (cached != null && cached.head().equals(head)) return cached.edits();

      final var edits = new HashMap<String, LastEdit>();
      var reachedCache = false;
      walk.markStart(walk.parseCommit(head));
      for (final var commit : walk) {
        if (cached != null && commit.equals(cached.head())) {
          reachedCache = true;
          break;
        }
        try (final var tw = new TreeWalk(repo)) {
          tw.setRecursive(true);
          tw.setFilter(TreeFilter.ANY_DIFF);
          if (commit.getParentCount() > 0) {
            tw.addTree(walk.parseCommit(commit.getParent(0)).getTree());
          } else {
            tw.addTree(new EmptyTreeIterator());
          }
          tw.addTree(commit.getTree());
          final var author = commit.getAuthorIdent();
          final var edit = new LastEdit(author.getName(), author.getWhenAsInstant(), commit.getParentCount() == 0);
          while (tw.next()) {
            if (tw.getRawMode(1) == 0) continue; // deleted in this commit
            final var path = tw.getPathString();
            if (RenderType.isAerieMetadataFile(path.substring(path.lastIndexOf('/') + 1))) continue;
            edits.putIfAbsent(path, edit);
          }
        }
      }
      if (reachedCache) cached.edits().forEach(edits::putIfAbsent);

      final var result = Map.copyOf(edits);
      lastEditCache.put(root, new LastEditIndex(head.copy(), result));
      return result;
    }
  }
  //endregion
}
