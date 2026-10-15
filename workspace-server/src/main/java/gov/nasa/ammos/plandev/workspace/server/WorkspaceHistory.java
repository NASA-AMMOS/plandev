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
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The single owner of workspace mutations and of the Git history that records them.
 *
 * Invariant:
 * After every successful workspace mutation, the workspace working tree is represented by Git
 * history and the repository is clean. Every mutation of workspace-managed files (file and directory create, save,
 * move, copy, delete, metadata edits, cross-workspace operations) runs inside {@link #mutate}; the mutating methods of
 * {@link WorkspaceFileSystemService} call {@link #requireLocked} so a path that bypasses this class fails loudly.
 *
 * Flow:
 * {@link #mutate} takes each involved workspace's lock (in ascending id order, so two cross-workspace
 * operations cannot deadlock), brings each repository to a clean, initialized state, records the pre-operation state,
 * runs the mutation (which performs its own validation, e.g. existence, readOnly and ETag checks, under the lock),
 * stages the complete resulting delta with git add -A, commits it if anything changed, and verifies the
 * repository is clean before returning. A request never reports success if the history update failed.
 *
 * Clean:
 * A repository is clean when git status reports no staged, modified, missing, conflicting or
 * untracked files, and no ignored file exists outside .seqdev/. Ignore rules are fully controlled here:
 * core.excludesFile points at /dev/null, .git/info/exclude contains only /.seqdev/,
 * and .gitignore or .gitattributes cannot exist in the tree ({@link WorkspacePaths}). Outside the
 * definition by design: .git/ itself, .seqdev/ (temporary files and recovery incidents, never committed) and empty
 * directories (not representable in Git; creating or deleting one produces no commit).
 *
 * Failure:
 * If the mutation throws, reports failure, or its history cannot be recorded, the workspace is
 * restored to its pre-operation state: tracked files and the index are checked out from the pre-operation HEAD, the
 * paths this operation created (untracked or newly added after it ran; equivalent to "created by this operation"
 * because the repository was verified clean beforehand under the same lock) are removed. Empty directories are not
 * versioned state and cannot be attributed to one operation after the fact, so rollback leaves them in place rather
 * than risk deleting a pre-existing directory. It also cannot recreate an empty directory the operation removed.
 * If restoration itself fails, a
 * {@link Kind#REPOSITORY_INCONSISTENT} error is raised rather than claiming success. git clean is never run wholesale,
 * nor is the repository reset on the strength of an unverified assumption.
 * A small marker in .git distinguishes a mutation interrupted by a process crash from an unexplained external edit.
 * On the next mutation, a marked repository is restored to its current HEAD: this rolls back work whose commit did
 * not land and completes work whose commit did land, preserving unfinished bytes in .seqdev/recovery first.
 * The marker is removed only after the repository is clean.
 *
 * Cross-workspace:
 * Two repositories cannot share a transaction. Commits are made in the order the caller
 * lists the workspaces (destination first for moves). If the first commit fails, every workspace is restored. If a
 * later commit fails, the workspaces already committed keep their change and the remaining ones are restored, raising
 * {@link Kind#PARTIALLY_APPLIED}: for a move this means it degraded to a copy, which is preferable to losing data.
 *
 * Adoption:
 * A workspace is managed once its repository config carries plandev.managed=true,
 * which is written only after initialization or adoption has fully succeeded. New workspaces are initialized before
 * their database row makes them visible. An existing unmanaged workspace (pre-history, or a repository PlanDev did
 * not finish adopting) is adopted on its first mutation: its tree must contain no reserved names and no symbolic
 * links; a repository is created if missing; and the current state is recorded by a commit authored by
 * {@link #systemIdent()} ("Initialize workspace history" for a new repository, "Adopt workspace into history" for an
 * existing one with uncommitted state).
 * An adoption marker in .seqdev makes initialization restartable without treating an arbitrary broken repository as
 * one PlanDev is allowed to repair.
 *
 * Trust:
 * Before every mutation of a managed workspace the repository must be exactly what this class left
 * behind: no in-progress Git operation, HEAD attached to main, an index of plain files outside reserved names,
 * and no staged changes. Ordinary filesystem drift is preserved in .seqdev/recovery before restoring the whole
 * workspace and index to HEAD. A marked interrupted PlanDev mutation also preserves staged residue before recovery.
 * Structural Git problems and unsupported filesystem entries fail closed with {@link Kind#REPOSITORY_INCONSISTENT}.
 *
 * Deployment assumption:
 * Locks are in-process. This is correct only while a single workspace-server
 * instance owns the workspace volume (the current deployment: one replica, no other service mounts the volume) and
 * nothing outside this class writes to a workspace's working tree. Reads do not take the lock.
 */
public class WorkspaceHistory {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceHistory.class);

  static final String INIT_MESSAGE = "Initialize workspace history";
  static final String ADOPT_MESSAGE = "Adopt workspace into history";
  static final String MUTATION_MARKER = "plandev-mutation-in-progress";
  static final String ADOPTION_MARKER = "adoption-in-progress";
  static final String BRANCH = "main";
  private static final String MANAGED_SECTION = "plandev";
  private static final String MANAGED_KEY = "managed";
  private static final int INCIDENT_VERSION = 1;
  /** Recovery incidents kept per workspace; older ones are deleted when a new one is written. */
  static final int MAX_INCIDENTS = 20;
  private static final DateTimeFormatter INCIDENT_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

  // Explicit allowlist for the Git files this class may write: config, index, HEAD, main/packed refs, and their logs.
  // JGit generally locks a target by appending ".lock"; the reflog entries are conservative for Git compatibility.
  // Never replace this with a *.lock scan: recovery must not delete an unrelated lock it cannot attribute to PlanDev.
  private static final List<String> GIT_LOCKS = List.of(
      "config.lock",
      "index.lock",
      "HEAD.lock",
      "packed-refs.lock",
      "refs/heads/" + BRANCH + ".lock",
      "logs/HEAD.lock",
      "logs/refs/heads/" + BRANCH + ".lock");

  public enum Kind {
    /** The history could not be recorded; the workspace was restored to its pre-operation state. */
    HISTORY_NOT_RECORDED("WORKSPACE_HISTORY_ERROR"),
    /** A cross-workspace mutation was applied to some workspaces only; see {@link PartialMutationException}. */
    PARTIALLY_APPLIED("WORKSPACE_MUTATION_PARTIAL"),
    /** The repository is not in a state this class can vouch for, and was not (or could not be) repaired. */
    REPOSITORY_INCONSISTENT("WORKSPACE_REPOSITORY_INCONSISTENT");

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

  /** A file's most recent content change, derived from history. baseline marks the history's root commit. */
  public record LastEdit(String by, Instant at, boolean baseline) {}

  private record Before(ObjectId head) {}

  private record CachedLastEdit(ObjectId head, Optional<LastEdit> edit) {}

  private final WorkspaceRoots roots;
  // ponytail: one lock per workspace id, never evicted; a few bytes per workspace ever touched by this process.
  private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final Map<Path, Map<String, CachedLastEdit>> lastEditCache = new ConcurrentHashMap<>();
  private final Set<Path> configured = ConcurrentHashMap.newKeySet();
  // Each request thread gets its own flag: nested entry on that thread sees true, while unrelated threads still see
  // their own initial false. inLockScope removes the thread's value when the outer operation finishes.
  private final ThreadLocal<Boolean> lockScopeActive = ThreadLocal.withInitial(() -> false);

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
    return inLockScope(() -> mutateUnderLocks(commits, userId, mutation, succeeded));
  }

  private <T> T mutateUnderLocks(
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
      beginMutation(rootsById.values());

      final T result;
      final boolean successful;
      try {
        result = mutation.apply();
        successful = succeeded.test(result);
      } catch (Exception e) {
        restoreAll(ids, rootsById, before, e);
        finishMutation(rootsById.values(), e);
        throw e;
      }
      if (!successful) {
        restoreAll(ids, rootsById, before, null);
        finishMutation(rootsById.values(), null);
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
          finishMutation(rootsById.values(), e);
          if (committed.isEmpty()) {
            throw new WorkspaceHistoryException(
                Kind.HISTORY_NOT_RECORDED,
                "The change could not be recorded in workspace history and was rolled back.",
                e);
          }
          throw new PartialMutationException(committed, id, e);
        }
      }
      finishMutation(rootsById.values(), null);
      return result;
    } finally {
      lockOrder.reversed().forEach(ReentrantLock::unlock);
    }
  }

  /** Run an action under a workspace's lock without recording history (e.g. deleting the whole workspace). */
  public <T> T withLock(final int workspaceId, final Mutation<T> action) throws Exception {
    return inLockScope(() -> {
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
    });
  }

  private <T> T inLockScope(final Mutation<T> action) throws Exception {
    // Global ordering prevents deadlock only when every lock is declared up front. A callback that enters mutate or
    // withLock again would already hold an outer lock and could acquire the next one out of order.
    if (lockScopeActive.get()) {
      throw new IllegalStateException(
          "Workspace lock operations cannot be nested; declare every workspace in one mutation.");
    }
    lockScopeActive.set(true);
    try {
      return action.apply();
    } finally {
      lockScopeActive.remove();
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
  /**
   * Initialize a newly created directory before its database row makes it visible as a workspace. No workspace lock is
   * needed yet because no other request can resolve the directory; migrated workspaces still use adoption on first
   * mutation through ensureReady().
   */
  void initializeNewWorkspace(final Path root) throws IOException, GitAPIException {
    final var normalizedRoot = root.normalize();
    if (!Files.isDirectory(normalizedRoot)) {
      throw new IOException("New workspace directory " + normalizedRoot + " is missing.");
    }
    try (final var entries = Files.list(normalizedRoot)) {
      if (entries.findAny().isPresent()) {
        throw new IOException("New workspace directory " + normalizedRoot + " is not empty.");
      }
    }
    adopt(normalizedRoot);
  }

  /** Bring a workspace to "managed, trusted and clean", adopting it first if it is not managed yet. Caller holds the lock. */
  private void ensureReady(final Path root) throws IOException, GitAPIException {
    if (!Files.isDirectory(root)) {
      throw inconsistent("Workspace directory " + root + " is missing.");
    }
    if (hasMarker(mutationMarker(root))) {
      if (!isManaged(root)) {
        throw inconsistent("Workspace at " + root + " has an interrupted-mutation marker but is not managed.");
      }
      recoverInterruptedMutation(root);
      requireTrusted(root);
    } else if (hasMarker(adoptionMarker(root))) {
      adopt(root);
    } else if (isManaged(root)) {
      requireTrusted(root);
    } else {
      adopt(root);
    }
  }

  /** Validate managed Git state, then preserve and heal all ordinary filesystem drift before any mutation. */
  private void requireTrusted(final Path root) throws IOException, GitAPIException {
    // configure() runs before the mutation marker and saves through .git/config.lock. If the process dies during that
    // FileBasedConfig.save(), normal cleanup never runs and no marker exists to trigger general recovery. Under the
    // single-owner deployment contract, a config lock found here is therefore stale and safe to remove before retrying.
    removeStaleConfigLock(root);
    requireNoGitLocks(root);
    try (final var git = open(root)) {
      requireExpectedGitState(git.getRepository(), false);
      configure(root, git.getRepository());
      requireValidIndex(git.getRepository());
      heal(root, git, false);
    }
    WorkspacePaths.removeStaleTempFiles(root); // only once trusted: a rejected workspace is left exactly as found
  }

  /** Staged residue is recoverable only with a trusted crash marker; normal requests reject it untouched. */
  private void heal(final Path root, final Git git, final boolean interrupted) throws IOException, GitAPIException {
    final var status = git.status().call();
    final var staged = new TreeSet<String>();
    staged.addAll(status.getAdded());
    staged.addAll(status.getChanged());
    staged.addAll(status.getRemoved());
    staged.addAll(status.getConflicting());
    if (!interrupted && !staged.isEmpty()) {
      throw inconsistent("Workspace at %s has staged changes that are not in its history: %s. They were left untouched."
                             .formatted(root, summarize(staged)));
    }
    final var dirty = dirtyPaths(status);
    if (dirty.isEmpty()) return;
    final var head = git.getRepository().resolve(Constants.HEAD);
    final var reason = interrupted ? "interrupted mutation recovery" : "out-of-band filesystem change";
    final var incident = preserve(root, head, status, reason);
    try {
      if (!interrupted) createMarker(mutationMarker(root));
      restore(root, new Before(head)); // existing whole-tree/index restore, including its clean postcondition
    } catch (IOException | GitAPIException | RuntimeException e) {
      throw new WorkspaceHistoryException(Kind.REPOSITORY_INCONSISTENT,
          "Workspace at %s: %s were preserved in %s but the workspace could not be restored to HEAD."
              .formatted(root, summarize(dirty), incident), e);
    }
    if (!interrupted) finishMutation(List.of(root), null);
    logger.warn("Workspace at {}: preserved {} in {} ({}) and restored HEAD {}",
                root, summarize(dirty), incident, reason, head.name());
  }

  /**
   * Where drifted bytes are kept: one directory per incident, named by time and a UUID, holding {@code incident.json}
   * and the files under {@code files/} at their workspace-relative paths. Runtime state: ignored by Git and reserved
   * from the file API.
   */
  static Path recoveryDir(final Path root) {
    return root.resolve(WorkspacePaths.INTERNAL_DIR).resolve("recovery");
  }

  /**
   * Move each drifted file into a new incident (a rename within the workspace's filesystem) and record what was found.
   * A deleted file has no bytes to keep; its absence is recorded. If anything fails, files already moved are moved
   * back; only if all of them were is the incident removed. Otherwise it is kept, holding what could not be returned.
   */
  private Path preserve(
      final Path root,
      final ObjectId head,
      final Status status,
      final String reason) throws IOException
  {
    final var drift = dirtyPaths(status);
    final var now = Instant.now();
    final var dir = recoveryDir(root).resolve(INCIDENT_TIME.format(now) + "-" + UUID.randomUUID());
    final var moved = new LinkedHashMap<Path, Path>();
    try {
      // Check ancestors as well as the entry: NOFOLLOW_LINKS alone does not protect intermediate components.
      for (final var key : drift) {
        final var file = root.resolve(key);
        for (var ancestor = file; !ancestor.equals(root); ancestor = ancestor.getParent()) {
          if (Files.isSymbolicLink(ancestor)) throw new IOException("Unsupported symbolic link: " + ancestor);
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Not a regular file: " + file);
        }
      }
      for (final var directory : List.of(root.resolve(WorkspacePaths.INTERNAL_DIR), recoveryDir(root))) {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException(directory + " is not a directory");
        }
      }
      Files.createDirectories(recoveryDir(root));
      Files.createDirectory(dir);
      final var paths = Json.createArrayBuilder();
      for (final var key : drift) {
        final var indexChange = status.getConflicting().contains(key) ? "conflicting"
            : status.getAdded().contains(key) ? "added" : status.getChanged().contains(key) ? "modified"
            : status.getRemoved().contains(key) ? "deleted" : "unchanged";
        final var worktreeChange = status.getMissing().contains(key) ? "deleted"
            : status.getModified().contains(key) ? "modified" : status.getUntracked().contains(key) ? "untracked" : "unchanged";
        final var file = root.resolve(key);
        final var present = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
        final var path = Json.createObjectBuilder().add("path", key)
            .add("change", worktreeChange.equals("unchanged") ? indexChange : worktreeChange)
            .add("indexChange", indexChange).add("worktreeChange", worktreeChange).add("present", present);
        if (present) path.add("executable", Files.isExecutable(file));
        paths.add(path);
      }
      final var json = Json.createObjectBuilder()
                           .add("version", INCIDENT_VERSION)
                           .add("timestamp", now.toString())
                           .add("head", head.name())
                           .add("reason", reason)
                           .add("paths", paths);
      // Write intent before moving bytes so a failed partial rollback still has an incident manifest.
      WorkspacePaths.writeAtomically(root, dir.resolve("incident.json"), json.build().toString().getBytes(StandardCharsets.UTF_8));
      for (final var key : drift) {
        final var file = root.resolve(key);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue; // deletion: metadata only
        final var target = dir.resolve("files").resolve(key);
        Files.createDirectories(target.getParent());
        move(file, target);
        moved.put(file, target);
      }
    } catch (IOException e) {
      final var stranded = new ArrayList<String>();
      for (final var m : moved.reversed().entrySet()) {
        try {
          move(m.getValue(), m.getKey());
        } catch (IOException back) {
          e.addSuppressed(back);
          stranded.add(WorkspacePaths.key(root, m.getKey()));
        }
      }
      if (stranded.isEmpty()) {
        try {
          deleteTree(dir);
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
        throw new WorkspaceHistoryException(Kind.REPOSITORY_INCONSISTENT,
            "Workspace at %s: %s differ from managed history and could not be preserved, so they were left untouched."
                .formatted(root, summarize(drift)), e);
      }
      logger.error("Workspace at {}: preserving {} failed and {} could not be moved back; they remain in {}",
                   root, summarize(drift), stranded, dir, e);
      throw new WorkspaceHistoryException(Kind.REPOSITORY_INCONSISTENT,
          "Workspace at %s: %s differ from managed history and could not be preserved; %s could not be moved back and "
              .formatted(root, summarize(drift), summarize(stranded))
          + "remain in " + dir + ".", e);
    }
    pruneIncidents(root, dir);
    return dir;
  }

  /**
   * Rename within the workspace's filesystem, atomically where the filesystem supports it. Overridable only so tests
   * can inject a failure.
   */
  protected void move(final Path from, final Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to);
    }
  }

  /**
   * Keep the newest {@link #MAX_INCIDENTS} incidents (names start with their time), never {@code current}, whatever the
   * clock did. Best effort: the current incident is already written, so a failure here is logged and never fails the
   * mutation.
   */
  private static void pruneIncidents(final Path root, final Path current) {
    try (final var entries = Files.list(recoveryDir(root))) {
      final var newestFirst = Stream.concat(Stream.of(current), entries.filter(p -> !p.equals(current))
                                                                       .sorted(Comparator.reverseOrder())).toList();
      for (final var old : newestFirst.subList(Math.min(MAX_INCIDENTS, newestFirst.size()), newestFirst.size())) deleteTree(old);
    } catch (IOException | UncheckedIOException | SecurityException e) {
      logger.warn("Could not prune old recovery incidents in {}", recoveryDir(root), e);
    }
  }

  /** Delete a tree without following symbolic links. */
  private static void deleteTree(final Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
    try (final var paths = Files.walk(dir)) {
      for (final var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  /**
   * Put a workspace under history. The adoption marker is written before any Git change and the managed setting is
   * written last, so an interrupted adoption can remove its own stale locks and safely rerun these idempotent steps.
   */
  private void adopt(final Path root) throws IOException, GitAPIException {
    // Validate everything before writing anything
    requireAdoptable(root);
    final var marker = adoptionMarker(root);
    final var resuming = hasMarker(marker);
    final var gitDir = gitDirectory(root);
    final var config = gitDir.resolve("config");
    var hasRepo = Files.isDirectory(gitDir, LinkOption.NOFOLLOW_LINKS);
    if (resuming) {
      removeGitLocks(root);
      // JGit writes config last during init. An existing repository must have passed the config check below before
      // its marker was created, so a marked repository without config can only be our interrupted initialization.
      if (hasRepo && Files.notExists(config, LinkOption.NOFOLLOW_LINKS)) {
        removePartialRepository(gitDir);
        hasRepo = false;
      }
    } else if (hasRepo) {
      requireRepositoryConfig(config);
      requireNoGitLocks(root);
      try (final var git = open(root)) {
        requireExpectedGitState(git.getRepository(), true);
      }
    }
    WorkspacePaths.removeStaleTempFiles(root);
    if (!resuming) createMarker(marker);

    if (!hasRepo) Git.init().setDirectory(root.toFile()).setInitialBranch(BRANCH).call().close();
    requireRepositoryConfig(config);
    configured.remove(root); // a new or newly adopted repository gets its config (re)applied

    try (final var git = open(root)) {
      final var repo = git.getRepository();
      requireExpectedGitState(repo, true);
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
    }
    Files.delete(marker);
    logger.info("Workspace at {} is now under history", root);
  }

  /**
   * Whether a workspace has been fully initialized or adopted (see the class Javadoc). Never discovers a parent
   * repository.
   */
  boolean isManaged(final Path root) throws IOException {
    if (!Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR), LinkOption.NOFOLLOW_LINKS)) return false;
    try (final var git = open(root)) {
      return git.getRepository().getConfig().getBoolean(MANAGED_SECTION, null, MANAGED_KEY, false);
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
  private void configure(final Path root, final Repository repo) throws IOException {
    if (configured.contains(root)) return;
    final var cfg = repo.getConfig();
    cfg.setBoolean("core", null, "autocrlf", false);
    cfg.setBoolean("core", null, "safecrlf", false);
    cfg.setString("core", null, "excludesFile", "/dev/null");
    cfg.setString("core", null, "attributesFile", "/dev/null");
    cfg.setString("core", null, "hooksPath", "/dev/null");
    cfg.setBoolean("commit", null, "gpgSign", false);
    cfg.setBoolean("tag", null, "gpgSign", false);
    cfg.setString("gpg", null, "format", "openpgp");
    cfg.save();

    final var info = repo.getDirectory().toPath().resolve("info");
    Files.createDirectories(info);
    Files.writeString(info.resolve("exclude"), "/" + WorkspacePaths.INTERNAL_DIR + "/\n", StandardCharsets.UTF_8);
    Files.deleteIfExists(info.resolve("attributes"));
    configured.add(root);
  }

  private static Path gitDirectory(final Path root) {
    return root.resolve(WorkspacePaths.GIT_DIR);
  }

  private static Path mutationMarker(final Path root) {
    return gitDirectory(root).resolve(MUTATION_MARKER);
  }

  private static Path adoptionMarker(final Path root) {
    return root.resolve(WorkspacePaths.INTERNAL_DIR).resolve(ADOPTION_MARKER);
  }

  private static void requireRepositoryConfig(final Path config) {
    if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
      throw inconsistent("Git repository at " + config.getParent() + " has no regular config file.");
    }
  }

  private static void removePartialRepository(final Path gitDir) throws IOException {
    // Java has no recursive delete. Walk bottom-up without following symbolic links; JGit's shorter recursive helper
    // uses File.isDirectory(), which could follow a link in a damaged .git directory and delete outside it.
    Files.walkFileTree(gitDir, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(final Path directory, final IOException error) throws IOException {
        if (error != null) throw error;
        Files.delete(directory);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** A marker is trusted only at its exact server-owned path, and never through a symbolic link. */
  private static boolean hasMarker(final Path marker) {
    if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
      throw inconsistent("Crash-recovery marker " + marker + " is not a regular file.");
    }
    return true;
  }

  /** Create the marker before workspace bytes change. Forcing this tiny file narrows the host-crash window cheaply. */
  private static void createMarker(final Path marker) throws IOException {
    Files.createDirectories(marker.getParent());
    try (final var channel = FileChannel.open(
        marker,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE))
    {
      channel.force(true);
    }
  }

  /** Mark every repository before the callback can change any of them. A partial setup is harmless and cleaned up. */
  private static void beginMutation(final Collection<Path> roots) {
    final var created = new ArrayList<Path>();
    try {
      for (final var root : roots) {
        final var marker = mutationMarker(root);
        createMarker(marker);
        created.add(marker);
      }
    } catch (IOException e) {
      for (final var marker : created) {
        try {
          Files.deleteIfExists(marker);
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
      }
      throw new WorkspaceHistoryException(
          Kind.REPOSITORY_INCONSISTENT,
          "Crash recovery could not be established, so the workspace was not changed.",
          e);
    }
  }

  /** Remove markers only after all involved repositories have reached a verified clean state. */
  private static void finishMutation(final Collection<Path> roots, final Exception cause) {
    final var failures = new ArrayList<IOException>();
    for (final var root : roots) {
      try {
        Files.deleteIfExists(mutationMarker(root));
      } catch (IOException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      final var ex = new WorkspaceHistoryException(
          Kind.REPOSITORY_INCONSISTENT,
          "The workspace is clean, but its crash-recovery marker could not be removed.",
          failures.getFirst());
      failures.stream().skip(1).forEach(ex::addSuppressed);
      if (cause != null) ex.addSuppressed(cause);
      throw ex;
    }
  }

  /**
   * After a crash, the pre-mutation HEAD captured in memory is gone, so recovery uses the repository's current HEAD as
   * the durable result. If the crash happened before the commit updated HEAD, this is still the old commit and the
   * mutation is rolled back. If HEAD was updated first, this is the new commit; recovery keeps it and preserves
   * unfinished index or working-tree residue before restoring the index and working tree to HEAD.
   */
  private void recoverInterruptedMutation(final Path root) throws IOException, GitAPIException {
    removeGitLocks(root);
    final ObjectId head;
    try (final var git = open(root)) {
      final var repo = git.getRepository();
      requireExpectedGitState(repo, false);
      configure(root, repo);
      requireValidIndex(repo);
      head = repo.resolve(Constants.HEAD);
      heal(root, git, true);
    }
    Files.delete(mutationMarker(root));
    logger.warn("Recovered interrupted workspace mutation at {} to HEAD {}", root, head.name());
  }

  private static List<Path> existingGitLocks(final Path root) {
    final var gitDir = gitDirectory(root);
    return GIT_LOCKS.stream()
        .map(gitDir::resolve)
        .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        .toList();
  }

  private static void requireNoGitLocks(final Path root) {
    final var locks = existingGitLocks(root);
    if (locks.isEmpty()) return;
    final var gitDir = gitDirectory(root);
    final var names = locks.stream().map(path -> WorkspacePaths.GIT_DIR + "/" + WorkspacePaths.key(gitDir, path)).toList();
    throw inconsistent("Workspace at %s has Git lock files %s without a PlanDev recovery marker; they were left untouched."
                           .formatted(root, names));
  }

  private void removeStaleConfigLock(final Path root) throws IOException {
    final var lock = gitDirectory(root).resolve("config.lock");
    if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
      throw inconsistent("Git lock " + lock + " is not a regular file and was left untouched.");
    }
    Files.delete(lock);
    configured.remove(root);
  }

  /** A recovery marker proves these exact locks can only belong to the interrupted operation. */
  private static void removeGitLocks(final Path root) throws IOException {
    for (final var lock : existingGitLocks(root)) {
      if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
        throw inconsistent("Git lock " + lock + " is not a regular file and was left untouched.");
      }
      Files.delete(lock);
    }
  }

  /**
   * Fail closed if the tree contains reserved names that would make Git skip or rewrite user files, or symbolic links
   * (unsupported workspace content: one could point into .git or outside the workspace). Only possible for
   * workspaces created or edited outside the file API, which rejects both. Links are never followed.
   */
  private static void requireAdoptable(final Path root) throws IOException {
    final var found = new ArrayList<String>();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
        if (dir.equals(root)) return FileVisitResult.CONTINUE;
        final var name = dir.getFileName().toString();
        if (dir.getParent().equals(root) && (name.equals(WorkspacePaths.GIT_DIR) || name.equals(WorkspacePaths.INTERNAL_DIR))) {
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

  /** Runs git add -A. Returns whether anything is staged relative to HEAD. */
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
      if (!ignored.equals(WorkspacePaths.INTERNAL_DIR) && !ignored.startsWith(WorkspacePaths.INTERNAL_DIR + "/")) {
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

  private static PersonIdent userIdent(final String userId) {
    return userId == null || userId.isBlank() ? systemIdent() : new PersonIdent(userId, "");
  }
  //endregion

  //region Rollback
  private Before capture(final Path root) throws IOException {
    try (final var git = open(root)) {
      final var head = git.getRepository().resolve(Constants.HEAD);
      return new Before(head);
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

      // Collect untracked or newly added paths to remove. For rollback they came from the mutation; healing and
      // crash recovery preserve dirty bytes before calling restore, so those paths can also be removed safely.
      final var status = git.status().call();
      final var created = new TreeSet<String>();
      created.addAll(status.getUntracked());
      created.addAll(status.getAdded());

      // DirCacheCheckout may remove empty parents while restoring files. Snapshot the directories still present before
      // restoration so checkout cannot make the unsafe guess that they were all created by a failed operation.
      // This restore-only walk intentionally preserves both old directories and harmless new empty ones.
      final var directoriesToKeep = WorkspacePaths.walk(root, Integer.MAX_VALUE).stream()
          .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .sorted(Comparator.comparingInt(Path::getNameCount))
          .toList();

      // Move the branch back only if the operation's commit actually landed.
      if (!before.head().equals(repo.resolve(Constants.HEAD))) {
        final var update = repo.updateRef(Constants.HEAD);
        update.setNewObjectId(before.head());
        final var result = update.forceUpdate();
        if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
          throw new IOException("Could not move HEAD back to " + before.head().name() + ": " + result);
        }
      }

      // Index and tracked files back to the target commit (pre-operation HEAD for rollback, current HEAD for recovery).
      final RevCommit commit = walk.parseCommit(before.head());
      final var checkout = new DirCacheCheckout(repo, repo.lockDirCache(), commit.getTree());
      checkout.setFailOnConflict(false);
      checkout.checkout();

      // Remove the collected paths. Do not prune empty parents: Git cannot tell whether a directory was created
      // by an operation or already existed, and restoration must not delete a directory it cannot safely attribute.
      for (final var path : created) {
        Files.deleteIfExists(root.resolve(path));
      }

      // Recreate directories removed by checkout, shallowest first. This may leave directories created by the failed
      // operation, which is preferable to deleting a pre-existing directory that Git cannot identify or restore.
      for (final var directory : directoriesToKeep) {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
            && Files.isDirectory(directory.getParent(), LinkOption.NOFOLLOW_LINKS))
        {
          Files.createDirectory(directory);
        }
      }

      requireClean(git, root);
    }
  }
  //endregion

  //region Reads (lock-free)
  /**
   * The most recent commit that changed each requested content path, keyed by '/'-separated workspace-relative path.
   * Results are cached per path and HEAD. A cached result from an older HEAD is reused when the walk reaches that
   * commit, so an unchanged path normally requires walking only the new commits.
   */
  public Map<String, LastEdit> lastEdits(final Path root, final Set<String> paths) throws IOException {
    if (paths.isEmpty() || !Files.isDirectory(root.resolve(WorkspacePaths.GIT_DIR))) return Map.of();

    // Sidecars describe content but are not content themselves; their housekeeping commits must not become file edits.
    final var requested = new HashSet<String>();
    for (final var path : paths) {
      final var name = path.substring(path.lastIndexOf('/') + 1);
      if (!RenderType.isAerieMetadataFile(name)) requested.add(path);
    }
    if (requested.isEmpty()) return Map.of();

    // Use one spelling of the workspace path so all callers share the same cache.
    final var normalizedRoot = root.normalize();
    try (final var git = open(normalizedRoot)) {
      final var repo = git.getRepository();
      // Work against one immutable history snapshot. If a mutation advances HEAD concurrently, the next read sees it.
      final var head = repo.resolve(Constants.HEAD);
      if (head == null) return Map.of();
      final var cache = lastEditCache.computeIfAbsent(normalizedRoot, unused -> new ConcurrentHashMap<>());

      // Commits are visited newest to oldest. unresolved contains paths whose latest change has not been found; the
      // first matching commit is therefore the latest edit, so the path is removed before older commits are examined.
      final var edits = new HashMap<String, LastEdit>();
      final var unresolved = new HashSet<>(requested);
      final var olderCacheByHead = new HashMap<ObjectId, Map<String, CachedLastEdit>>();

      // Each cached result records the HEAD at which it was computed. If that is still the current HEAD, reuse the
      // result, including "no edit found." Older results are grouped by their HEAD so one walk can check everything
      // committed since each result was computed.
      for (final var path : requested) {
        final var cached = cache.get(path);
        if (cached == null) continue;
        if (cached.head().equals(head)) {
          cached.edit().ifPresent(edit -> edits.put(path, edit));
          unresolved.remove(path);
        } else {
          olderCacheByHead.computeIfAbsent(cached.head(), unused -> new HashMap<>()).put(path, cached);
        }
      }

      if (!unresolved.isEmpty()) {
        // A single history walk handles all remaining paths. Resolved paths are removed from later comparisons.
        try (final var walk = new RevWalk(repo)) {
          walk.markStart(walk.parseCommit(head));
          for (final var commit : walk) {
            // Reaching a cached HEAD without finding a newer change proves that cached result is still correct. Reuse
            // it before comparing this commit because that result already includes this commit and everything older.
            final var cachedAtCommit = olderCacheByHead.remove(commit);
            if (cachedAtCommit != null) {
              for (final var entry : cachedAtCommit.entrySet()) {
                if (!unresolved.remove(entry.getKey())) continue;
                entry.getValue().edit().ifPresent(edit -> edits.put(entry.getKey(), edit));
              }
            }
            if (unresolved.isEmpty()) break;

            // Compare only paths still needing an answer. Every path found was changed by this commit, so they share
            // its edit.
            final var edit = lastEditOf(commit);
            for (final var changed : changedPaths(repo, walk, commit, unresolved).entrySet()) {
              unresolved.remove(changed.getKey());
              // A deleted path's lookup is finished, but there is no current file to attribute. Remembering that miss
              // prevents an older creation from being returned.
              if (changed.getValue()) edits.put(changed.getKey(), edit);
            }
          }
        }
      }

      // Cache every request, not just hits: Optional.empty distinguishes a known absence from an uncached path.
      final var cachedHead = head.copy();
      for (final var path : requested) {
        cache.put(path, new CachedLastEdit(cachedHead, Optional.ofNullable(edits.get(path))));
      }
      return Map.copyOf(edits);
    }
  }

  public Optional<LastEdit> lastEdit(final Path root, final String path) throws IOException {
    return Optional.ofNullable(lastEdits(root, Set.of(path)).get(path));
  }

  /**
   * The single definition of a content edit, shared with {@link WorkspaceRevisions}: the requested paths this commit
   * changed compared with its first parent, each mapped to whether it still exists after the commit. The root commit
   * has no parent, so comparing it with an empty tree treats every file there as newly added.
   */
  static Map<String, Boolean> changedPaths(
      final Repository repo,
      final RevWalk walk,
      final RevCommit commit,
      final Collection<String> paths) throws IOException
  {
    final var changed = new HashMap<String, Boolean>();
    try (final var tw = new TreeWalk(repo)) {
      tw.setRecursive(true);
      tw.setFilter(AndTreeFilter.create(TreeFilter.ANY_DIFF, PathFilterGroup.createFromStrings(paths)));
      if (commit.getParentCount() > 0) {
        tw.addTree(walk.parseCommit(commit.getParent(0)).getTree());
      } else {
        tw.addTree(new EmptyTreeIterator());
      }
      tw.addTree(commit.getTree());
      // Tree 1 is this commit; mode zero means the path was deleted.
      while (tw.next()) changed.put(tw.getPathString(), tw.getRawMode(1) != 0);
    }
    return changed;
  }

  /** An edit made by this commit; one made by the root commit is the baseline. */
  static LastEdit lastEditOf(final RevCommit commit) {
    final var author = commit.getAuthorIdent();
    return new LastEdit(author.getName(), author.getWhenAsInstant(), commit.getParentCount() == 0);
  }
  //endregion
}
