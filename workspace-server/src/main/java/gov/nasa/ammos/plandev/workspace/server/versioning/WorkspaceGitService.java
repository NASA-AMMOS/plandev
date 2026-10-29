package gov.nasa.ammos.plandev.workspace.server.versioning;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceFileSystemService;
import gov.nasa.ammos.plandev.workspace.server.WorkspacePaths;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceService;
import gov.nasa.ammos.plandev.workspace.server.exceptions.PartialMutationException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonObject;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.gitDirectory;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.internalDirectory;
import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.toGitPath;

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
 * {@link WorkspaceHistoryException.Kind#REPOSITORY_INCONSISTENT} error is raised rather than claiming success. git clean is never run wholesale,
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
 * {@link WorkspaceHistoryException.Kind#PARTIALLY_APPLIED}: for a move this means it degraded to a copy, which is preferable to losing data.
 *
 * Adoption:
 * A workspace is managed once its repository config carries plandev.managed=true,
 * which is written only after initialization or adoption has fully succeeded. New workspaces are initialized before
 * their database row makes them visible. An existing unmanaged workspace (pre-history, or a repository PlanDev did
 * not finish adopting) is adopted on its first mutation: its tree must contain no reserved names and no symbolic
 * links; a repository is created if missing; and the current state is recorded by a commit authored by
 * {@link #systemIdentifier} ("Initialize workspace history" for a new repository, "Adopt workspace into history" for an
 * existing one with uncommitted state).
 * An adoption marker in .seqdev makes initialization restartable without treating an arbitrary broken repository as
 * one PlanDev is allowed to repair.
 *
 * Trust:
 * Before every mutation of a managed workspace the repository must be exactly what this class left
 * behind: no in-progress Git operation, HEAD attached to main, an index of plain files outside reserved names,
 * and no staged changes. Ordinary filesystem drift is preserved in .seqdev/recovery before restoring the whole
 * workspace and index to HEAD. A marked interrupted PlanDev mutation also preserves staged residue before recovery.
 * Structural Git problems and unsupported filesystem entries fail closed with {@link WorkspaceHistoryException.Kind#REPOSITORY_INCONSISTENT}.
 *
 * Deployment assumption:
 * Locks are in-process. This is correct only while a single workspace-server
 * instance owns the workspace volume (the current deployment: one replica, no other service mounts the volume) and
 * nothing outside this class writes to a workspace's working tree. Reads do not take the lock.
 */
public class WorkspaceGitService implements WorkspaceVersioningService {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceGitService.class);

  static final String INIT_MESSAGE = "Initialize workspace history";
  static final String ADOPT_MESSAGE = "Adopt workspace into history";
  protected static final String MUTATION_MARKER = "seqdev-mutation-in-progress";
  static final String ADOPTION_MARKER = "adoption-in-progress";
  static final String BRANCH = "main";
  private static final String MANAGED_SECTION = "SeqDev";
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

  @FunctionalInterface
  public interface Mutation<T> {
    T apply() throws Exception;
  }

  /**
   * A record representing a cached LastEdit value
   * @param validAt the last commit this cache entry was validated at
   * @param edit the edit data for the commit
   */
  private record CachedLastEdit(ObjectId validAt, LastEdit edit) {}

  // ponytail: one lock per workspace id, never evicted; a few bytes per workspace ever touched by this process.
  private final Map<Workspace, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final Map<Workspace, Map<Path, CachedLastEdit>> lastEditCache = new ConcurrentHashMap<>();
  private final Set<Workspace> configured = ConcurrentHashMap.newKeySet();
  // Each request thread gets its own flag: nested entry on that thread sees true, while unrelated threads still see
  // their own initial false. inLockScope removes the thread's value when the outer operation finishes.
  private final ThreadLocal<Boolean> lockScopeActive = ThreadLocal.withInitial(() -> false);

  //region Mutation boundary
  public <T> T mutate(
      final Workspace workspaceId,
      final String message, final String userId,
      final Mutation<T> mutation,
      final Predicate<T> succeeded) throws Exception
  {
    final var commits = new LinkedHashMap<Workspace, String>();
    commits.put(workspaceId, message);
    return mutate(commits, userId, mutation, succeeded);
  }

  public <T> T mutate(
      final LinkedHashMap<Workspace, String> commits,
      final String userId,
      final Mutation<T> mutation,
      final Predicate<T> succeeded) throws Exception
  {
    return inLockScope(() -> mutateUnderLocks(commits, userId, mutation, succeeded));
  }

  private <T> T mutateUnderLocks(
      final LinkedHashMap<Workspace, String> commits,
      final String userId,
      final Mutation<T> mutation,
      final Predicate<T> succeeded) throws Exception
  {
    final var workspaces = commits.keySet();
    // Get a reentrant lock for each unique workspace in the commits map
    final var wsLocks = workspaces
        .stream()
        .sorted(Comparator.comparingInt(Workspace::id))
        .map(this::lockFor)
        .toList();
    wsLocks.forEach(ReentrantLock::lock);

    try {
      final var before = new HashMap<Workspace, ObjectId>();
      for(final var ws : workspaces) {
        ensureReady(ws);
        before.put(ws, capture(ws));
      }

      beginMutation(workspaces);

      final T result;
      final boolean successful;
      try {
        result = mutation.apply();
        successful = succeeded.test(result);
      } catch (Exception e) {
        restoreAll(workspaces, before, e);
        finishMutation(workspaces, e);
        throw e;
      }
      if (!successful) {
        restoreAll(workspaces, before, null);
        finishMutation(workspaces, null);
        return result;
      }

      final var committed = new ArrayList<Workspace>();
      for (final var ws : workspaces) {
        try (final var git = open(ws)) {
          if (stageAll(git)) {
            requireValidIndex(git.getRepository());
            commit(git, commits.get(ws), userIdent(userId));
          }
          requireClean(git, ws);
          committed.add(ws);
        } catch (Exception e) {
          logger.error("Recording history for workspace {} failed; restoring", ws.id(), e);
          // Revert changes to any workspaces that have not yet been committed
          final var toRestore = new HashSet<>(workspaces);
          committed.forEach(toRestore::remove);
          restoreAll(toRestore, before, e);
          finishMutation(workspaces, e);
          if (committed.isEmpty()) {
            throw new WorkspaceHistoryException(
                WorkspaceHistoryException.Kind.HISTORY_NOT_RECORDED,
                "The change could not be recorded in workspace history and was rolled back.",
                e);
          }
          throw new PartialMutationException(committed, ws, e);
        }
      }
      finishMutation(workspaces, null);
      return result;
    } finally {
      wsLocks.reversed().forEach(ReentrantLock::unlock);
    }
  }

  public boolean deleteWorkspace(final Workspace workspace, final WorkspaceService service) throws Exception {
    return inLockScope(() -> {
      final var lock = lockFor(workspace);
      lock.lock();
      try {
        final var result = service.deleteWorkspace(workspace);
        lastEditCache.remove(workspace);
        configured.remove(workspace);
        return result;
      } finally {
        lock.unlock();
        locks.remove(workspace); // We no longer need the entry if this is workspace delete
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
  @Override
  public void requireLocked(final Workspace... workspaces) throws IllegalStateException {
    for (final var ws : workspaces) {
      final var lock = locks.get(ws);
      if (lock == null || !lock.isHeldByCurrentThread()) {
        throw new IllegalStateException(
            "Workspace %d was mutated outside WorkspaceHistory.mutate; every workspace mutation must go through it."
                .formatted(ws.id()));
      }
    }
  }

  private ReentrantLock lockFor(final Workspace workspace) {
    return locks.computeIfAbsent(workspace, k -> new ReentrantLock());
  }

  /** The Git commit step. Overridable only so tests can inject a deterministic failure. */
  protected void commit(final Git git, final String message, final PersonIdent author) throws GitAPIException {
    git.commit()
       .setMessage(message)
       .setAuthor(author)
       .setCommitter(systemIdentifier)
       .setAllowEmpty(true)
       .setSign(false)
       .setNoVerify(true)
       .call();
  }
  //endregion

  //region Adoption and trust
  /**
   * Initialize a newly created workspace.
   */
  @Override
  public void initializeWorkspace(final Workspace workspace) throws IOException, GitAPIException {
    final var normalizedRoot = workspace.rootPath().normalize();
    if (!Files.isDirectory(normalizedRoot)) {
      throw new IOException("New workspace directory " + normalizedRoot + " is missing.");
    }
    try (final var entries = Files.list(normalizedRoot)) {
      if (entries.findAny().isPresent()) {
        throw new IOException("New workspace directory " + normalizedRoot + " is not empty.");
      }
    }

    try {
      inLockScope(() -> {
        final var lock = lockFor(workspace);
        lock.lock();
        try {
          adopt(workspace);
          return null;
        } finally {
          lock.unlock();
        }
      });
    } catch (IOException | GitAPIException e) {
      throw e;
    } catch (Exception e) {
      // Adopt only throws IO and GitAPI, so this branch should be unhit
      throw new RuntimeException(e);
    }
  }

  /** Bring a workspace to "managed, trusted and clean", adopting it first if it is not managed yet. Caller holds the lock. */
  private void ensureReady(final Workspace workspace) throws IOException, GitAPIException {
    if (!Files.isDirectory(workspace.rootPath())) {
      throw inconsistent("Workspace directory " + workspace.rootPath() + " is missing.");
    }
    if (hasMarker(mutationMarker(workspace))) {
      if (!isManaged(workspace)) {
        throw inconsistent("Workspace at " + workspace.rootPath() + " has an interrupted-mutation marker but is not managed.");
      }
      recoverInterruptedMutation(workspace);
      requireTrusted(workspace);
    } else if (hasMarker(adoptionMarker(workspace))) {
      adopt(workspace);
    } else if (isManaged(workspace)) {
      requireTrusted(workspace);
    } else {
      adopt(workspace);
    }
  }

  /** Validate managed Git state, then preserve and heal all ordinary filesystem drift before any mutation. */
  private void requireTrusted(final Workspace workspace) throws IOException, GitAPIException {
    // configure() runs before the mutation marker and saves through .git/config.lock. If the process dies during that
    // FileBasedConfig.save(), normal cleanup never runs and no marker exists to trigger general recovery. Under the
    // single-owner deployment contract, a config lock found here is therefore stale and safe to remove before retrying.
    removeStaleConfigLock(workspace);
    requireNoGitLocks(workspace);
    try (final var git = open(workspace)) {
      requireExpectedGitState(git.getRepository(), false);
      configure(workspace, git.getRepository());
      requireValidIndex(git.getRepository());
      heal(workspace, git, false);
    }
    WorkspacePaths.removeStaleTempFiles(workspace); // only once trusted: a rejected workspace is left exactly as found
  }

  /** Staged residue is recoverable only with a trusted crash marker; normal requests reject it untouched. */
  private void heal(final Workspace workspace, final Git git, final boolean interrupted) throws IOException, GitAPIException {
    final var status = git.status().call();
    final var dirty = dirtyPaths(status);
    if (dirty.isEmpty()) return;
    final var head = git.getRepository().resolve(Constants.HEAD);
    final var reason = interrupted ? "interrupted mutation recovery" : "external filesystem change";
    final var incident = preserve(workspace, head, status, reason);
    try {
      if (!interrupted) createMarker(mutationMarker(workspace));
      restore(workspace, head); // existing whole-tree/index restore, including its clean postcondition
    } catch (IOException | GitAPIException | RuntimeException e) {
      throw new WorkspaceHistoryException(WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT,
          "Workspace %d: %s were preserved in %s but the workspace could not be restored to HEAD."
              .formatted(workspace.id(), summarize(dirty), incident), e);
    }
    if (!interrupted) finishMutation(Set.of(workspace), null);
    logger.warn("Workspace {}: preserved {} in {} ({}) and restored HEAD {}",
                workspace.id(), summarize(dirty), incident, reason, head.name());
  }

  /**
   * Where drifted bytes are kept: one directory per incident, named by time and a UUID, holding {@code incident.json}
   * and the files under {@code files/} at their workspace-relative paths. Runtime state: ignored by Git and reserved
   * from the file API.
   */
  static Path recoveryDir(final Workspace workspace) {
    return internalDirectory(workspace).resolve("recovery");
  }

  /**
   * Move each drifted file into a new incident (a rename within the workspace's filesystem) and record what was found.
   * A deleted file has no bytes to keep; its absence is recorded. If anything fails, files already moved are moved
   * back; only if all of them were is the incident removed. Otherwise it is kept, holding what could not be returned.
   */
  private Path preserve(
      final Workspace workspace,
      final ObjectId head,
      final Status status,
      final String reason)
  {
    final var drift = dirtyPaths(status);
    final var now = Instant.now();
    final var recoveryDir = recoveryDir(workspace);
    final var incidentDir = recoveryDir.resolve(INCIDENT_TIME.format(now) + "-" + UUID.randomUUID());
    final var root = workspace.rootPath();
    final var moved = new LinkedHashMap<Path, Path>();

    try {
      if (Files.isSymbolicLink(recoveryDir)) {
        throw new IOException(recoveryDir + " is not a directory");
      }

      // Create the incident directory
      Files.createDirectories(incidentDir);

      // Generate the incident report
      final var paths = Json.createArrayBuilder();
      for (final var key : drift) {
        final var indexChange =
                  status.getConflicting().contains(key) ? "conflicting"
                : status.getAdded().contains(key) ? "added"
                : status.getChanged().contains(key) ? "modified"
                : status.getRemoved().contains(key) ? "deleted"
                : "unchanged";
        final var worktreeChange =
                  status.getMissing().contains(key) ? "deleted"
                : status.getModified().contains(key) ? "modified"
                : status.getUntracked().contains(key) ? "untracked"
                : "unchanged";

        final var file = WorkspacePaths.resolveReadingPath(workspace, Path.of(key));

        // Validate that the file, if it still exists, is regular (not, ie, a pipe)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Not a regular file: " + file);
        }

        final var path = Json.createObjectBuilder()
                             .add("path", key)
                             .add("change", worktreeChange.equals("unchanged") ? indexChange : worktreeChange)
                             .add("indexChange", indexChange)
                             .add("worktreeChange", worktreeChange)
                             .add("present", Files.exists(file))
                             .add("executable", Files.isExecutable(file));
        paths.add(path);
      }
      final var json = Json.createObjectBuilder()
                           .add("version", INCIDENT_VERSION)
                           .add("timestamp", now.toString())
                           .add("head", head.name())
                           .add("reason", reason)
                           .add("paths", paths);

      // Write the incident report to the incident directory
      WorkspacePaths.writeAtomically(workspace, incidentDir.resolve("incident.json"), json.build().toString().getBytes(StandardCharsets.UTF_8));

      // Move existing files to the incident directory
      for (final var key : drift) {
        final var file = root.resolve(key);

        // If the file doesn't exist (ie was deleted), skip it.
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;

        final var target = incidentDir.resolve("files").resolve(key);
        Files.createDirectories(target.getParent());
        move(file, target);
        moved.put(file, target);
      }
    } catch (SecurityException | IOException e) {
      final var stranded = new ArrayList<String>();
      for (final var m : moved.reversed().entrySet()) {
        try {
          move(m.getValue(), m.getKey());
        } catch (IOException back) {
          e.addSuppressed(back);
          stranded.add(WorkspacePaths.toWorkspaceRelativeGitPath(workspace, m.getKey()));
        }
      }
      if (stranded.isEmpty()) {
        try {
          deleteTree(incidentDir);
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
        throw new WorkspaceHistoryException(
            WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT,
            "Workspace at %s: %s differ from managed history and could not be preserved, so they were left untouched."
                .formatted(root, summarize(drift)), e);
      }
      logger.error("Workspace at {}: preserving {} failed and {} could not be moved back; they remain in {}",
                   root, summarize(drift), stranded, incidentDir, e);
      throw new WorkspaceHistoryException(
          WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT,
          "Workspace at %s: %s differ from managed history and could not be preserved; %s could not be moved back and "
              .formatted(root, summarize(drift), summarize(stranded))
          + "remain in " + incidentDir + ".", e);
    }
    pruneIncidents(workspace, incidentDir);
    return incidentDir;
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
  private static void pruneIncidents(final Workspace workspace, final Path currentIncident) {
    try (final var entries = Files.list(recoveryDir(workspace))) {
      final var incidents = entries
          .filter(p -> !p.equals(currentIncident))  // Exclude the current incident from the set so we don't accidentally delete it
          .sorted(Comparator.reverseOrder()) // Sort the incidents newest to oldest
          .toList();
      // Remove the oldest incidents (-1 because currentIncident is not included
      for(int i = MAX_INCIDENTS-1; i < incidents.size(); ++i) {
        deleteTree(incidents.get(i));
      }
    } catch (IOException | UncheckedIOException | SecurityException e) {
      logger.warn("Could not prune old recovery incidents in {}", recoveryDir(workspace), e);
    }
  }

  /** Delete a tree without following symbolic links. */
  private static void deleteTree(final Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
    try (final var paths = Files.walk(dir)) {
      for (final var p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  /**
   * Put a workspace under history. The adoption marker is written before any Git change and the managed setting is
   * written last, so an interrupted adoption can remove its own stale locks and safely rerun these idempotent steps.
   */
  private void adopt(final Workspace workspace) throws IOException, GitAPIException {
    // Validate everything before writing anything
    requireAdoptable(workspace);
    final var marker = adoptionMarker(workspace);
    final var resuming = hasMarker(marker);
    final var gitDir = gitDirectory(workspace);
    final var config = gitDir.resolve("config");
    var hasRepo = Files.isDirectory(gitDir, LinkOption.NOFOLLOW_LINKS);
    if (resuming) {
      removeGitLocks(workspace);
      // JGit writes config last during init. An existing repository must have passed the config check below before
      // its marker was created, so a marked repository without config can only be our interrupted initialization.
      if (hasRepo && Files.notExists(config, LinkOption.NOFOLLOW_LINKS)) {
        removePartialRepository(workspace);
        hasRepo = false;
      }
    } else if (hasRepo) {
      requireRepositoryConfig(config);
      requireNoGitLocks(workspace);
      try (final var git = open(workspace)) {
        requireExpectedGitState(git.getRepository(), true);
      }
    }
    WorkspacePaths.removeStaleTempFiles(workspace);
    if (!resuming) createMarker(marker);

    if (!hasRepo) Git.init().setDirectory(workspace.rootPath().toFile()).setInitialBranch(BRANCH).call().close();
    requireRepositoryConfig(config);
    configured.remove(workspace); // a new or newly adopted repository gets its config (re)applied

    try (final var git = open(workspace)) {
      final var repo = git.getRepository();
      requireExpectedGitState(repo, true);
      configure(workspace, repo);
      final var unborn = repo.resolve(Constants.HEAD) == null;
      if (stageAll(git) || unborn) {
        requireValidIndex(repo);
        commit(git, unborn ? INIT_MESSAGE : ADOPT_MESSAGE, systemIdentifier);
      }
      requireValidIndex(repo);
      requireClean(git, workspace);

      final var cfg = repo.getConfig();
      cfg.setBoolean(MANAGED_SECTION, null, MANAGED_KEY, true);
      cfg.save();
    }
    Files.delete(marker);
    logger.info("Workspace {} is now under history", workspace.id());
  }

  /**
   * Whether a workspace has been fully initialized or adopted (see the class Javadoc). Never discovers a parent
   * repository.
   */
  @Override
  public boolean isManaged(final Workspace workspace) throws IOException {
    if (!Files.isDirectory(gitDirectory(workspace))) {
      return false;
    }
    try (final var git = open(workspace)) {
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
  private void configure(final Workspace workspace, final Repository repo) throws IOException {
    if (configured.contains(workspace)) return;
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
    configured.add(workspace);
  }

  protected static Path mutationMarker(final Workspace workspace) {
    return gitDirectory(workspace).resolve(MUTATION_MARKER);
  }

  protected static Path adoptionMarker(final Workspace workspace) {
    return internalDirectory(workspace).resolve(ADOPTION_MARKER);
  }

  private static void requireRepositoryConfig(final Path config) {
    if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
      throw inconsistent("Git repository at " + config.getParent() + " has no regular config file.");
    }
  }

  private static void removePartialRepository(final Workspace workspace) throws IOException {
    final var gitDir = gitDirectory(workspace);
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
  private static void beginMutation(final Set<Workspace> workspaces) {
    final var created = new ArrayList<Path>();
    try {
      for (final var ws : workspaces) {
        final var marker = mutationMarker(ws);
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
          WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT,
          "Crash recovery could not be established, so the workspace was not changed.",
          e);
    }
  }

  /** Remove markers only after all involved repositories have reached a verified clean state. */
  private static void finishMutation(final Set<Workspace> workspaces, final Exception cause) {
    final var failures = new ArrayList<IOException>();
    for (final var ws : workspaces) {
      try {
        Files.deleteIfExists(mutationMarker(ws));
      } catch (IOException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      final var ex = new WorkspaceHistoryException(
          WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT,
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
  private void recoverInterruptedMutation(final Workspace workspace) throws IOException, GitAPIException {
    removeGitLocks(workspace);
    final ObjectId head;
    try (final var git = open(workspace)) {
      final var repo = git.getRepository();
      requireExpectedGitState(repo, false);
      configure(workspace, repo);
      requireValidIndex(repo);
      head = repo.resolve(Constants.HEAD);
      heal(workspace, git, true);
    }
    Files.delete(mutationMarker(workspace));
    logger.warn("Recovered interrupted workspace mutation at {} to HEAD {}", workspace.rootPath(), head.name());
  }

  private static List<Path> existingGitLocks(final Workspace workspace) {
    final var gitDir = gitDirectory(workspace);
    return GIT_LOCKS.stream()
        .map(gitDir::resolve)
        .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        .toList();
  }

  private static void requireNoGitLocks(final Workspace workspace) {
    final var locks = existingGitLocks(workspace);
    if (locks.isEmpty()) return;
    final var gitDir = gitDirectory(workspace);
    final var names = locks.stream().map(path -> WorkspacePaths.GIT_DIR + "/" + WorkspacePaths.key(gitDir, path)).toList();
    throw inconsistent("Workspace %d has Git lock files %s without a PlanDev recovery marker; they were left untouched."
                           .formatted(workspace.id(), names));
  }

  private void removeStaleConfigLock(final Workspace workspace) throws IOException {
    final var lock = gitDirectory(workspace).resolve("config.lock");
    if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
      throw inconsistent("Git lock " + lock + " is not a regular file and was left untouched.");
    }
    Files.delete(lock);
    configured.remove(workspace);
  }

  /** A recovery marker proves these exact locks can only belong to the interrupted operation. */
  private static void removeGitLocks(final Workspace workspace) throws IOException {
    for (final var lock : existingGitLocks(workspace)) {
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
  private static void requireAdoptable(final Workspace workspace) throws IOException {
    final var issuesFound = new ArrayList<String>();
    final var root = workspace.rootPath();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
        if (dir.equals(root)) return FileVisitResult.CONTINUE;
        final var name = dir.getFileName().toString();
        if (dir.getParent().equals(root) && (name.equals(WorkspacePaths.GIT_DIR) || name.equals(WorkspacePaths.INTERNAL_DIR))) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        if (WorkspacePaths.filenameIsReserved(Path.of(name))) issuesFound.add(WorkspacePaths.key(root, dir));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink()) {
          issuesFound.add(WorkspacePaths.key(root, file) + " (symbolic link)");
        } else if (WorkspacePaths.filenameIsReserved(file)) {
          issuesFound.add(WorkspacePaths.key(root, file));
        }
        return FileVisitResult.CONTINUE;
      }
    });
    if (!issuesFound.isEmpty()) {
      throw inconsistent("Workspace at %s contains unsupported paths %s; remove them before it can be modified."
                             .formatted(root, summarize(issuesFound)));
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
      } else if (Arrays.stream(path.split("/")).anyMatch(p -> WorkspacePaths.filenameIsReserved(Path.of(p)))) {
        bad.add(path);
      }
    }
    if (!bad.isEmpty()) {
      throw inconsistent("Workspace at %s tracks unsupported paths %s.".formatted(repo.getWorkTree(), summarize(bad)));
    }
  }

  static WorkspaceHistoryException inconsistent(final String message) {
    return new WorkspaceHistoryException(WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT, message, null);
  }

  static String summarize(final Collection<String> paths) {
    final var shown = paths.stream().limit(20).toList();
    return paths.size() > shown.size() ? shown + " and " + (paths.size() - shown.size()) + " more" : shown.toString();
  }
  //endregion

  //region Git helpers
  protected static Git open(final Workspace workspace) throws IOException {
    // Opens <root>/.git only; never discovers a repository in a parent directory. Closing the Git closes the repo.
    return Git.open(workspace.rootPath().toFile());
  }

  /** Runs git add -A. Returns whether anything is staged relative to HEAD. */
  private static boolean stageAll(final Git git) throws GitAPIException {
    git.add().addFilepattern(".").call();
    git.add().addFilepattern(".").setUpdate(true).call();
    final var status = git.status().call();
    return !(status.getAdded().isEmpty() && status.getChanged().isEmpty() && status.getRemoved().isEmpty());
  }

  /** Every path that makes the repository not clean, per the definition in the class Javadoc. */
  protected static Set<String> dirtyPaths(final Status status) {
    final var dirty = status.getUncommittedChanges();
    // Untracked files are listed individually; untracked *folders* add nothing beyond them except empty
    // directories, which are deliberately outside the definition.
    dirty.addAll(status.getUntracked());
    // The system doesn't check for or handle ignored files, so if there are any there,
    // they're problematic and must be cleaned up
    for (final var ignored : status.getIgnoredNotInIndex()) {
      if (!ignored.equals(WorkspacePaths.INTERNAL_DIR) && !ignored.startsWith(WorkspacePaths.INTERNAL_DIR + "/")) {
        dirty.add(ignored);
      }
    }
    return dirty;
  }

  private static void requireClean(final Git git, final Workspace workspace) throws GitAPIException {
    final var dirty = dirtyPaths(git.status().call());
    if (!dirty.isEmpty()) throw inconsistent("Workspace %d is not clean: %s".formatted(workspace.id(), summarize(dirty)));
  }

  private static final PersonIdent systemIdentifier = new PersonIdent("SeqDev", "Workspaces@SeqDev");

  private static PersonIdent userIdent(final String userId) {
    return new PersonIdent(userId, userId+"@SeqDev");
  }
  //endregion

  //region Rollback
  private ObjectId capture(final Workspace workspace) throws IOException {
    try (final var git = open(workspace)) {
      return git.getRepository().resolve(Constants.HEAD);
    }
  }

  /** Restore each workspace; if any cannot be restored, fail with REPOSITORY_INCONSISTENT. */
  private void restoreAll(
      final Set<Workspace> workspaces,
      final Map<Workspace, ObjectId> before,
      final Exception cause)
  {
    final var failed = new ArrayList<Integer>();
    final var errors = new ArrayList<Exception>();
    for (final var ws : workspaces) {
      try {
        restore(ws, before.get(ws));
      } catch (Exception e) {
        logger.error("Could not restore workspace {} after a failed mutation", ws.id(), e);
        failed.add(ws.id());
        errors.add(e);
      }
    }
    if (!errors.isEmpty()) {
      final var ex = new WorkspaceHistoryException(
          WorkspaceHistoryException.Kind.REPOSITORY_INCONSISTENT,
          "A failed change could not be fully rolled back in workspace(s) %s; the workspace may not match its history."
              .formatted(failed),
          errors.getFirst());
      errors.stream().skip(1).forEach(ex::addSuppressed);
      if (cause != null) ex.addSuppressed(cause);
      throw ex;
    }
  }

  private void restore(final Workspace workspace, final ObjectId before) throws IOException, GitAPIException {
    try (final var git = open(workspace); final var walk = new RevWalk(git.getRepository())) {
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
      final var directoriesToKeep = WorkspacePaths.walk(workspace.rootPath(), Integer.MAX_VALUE).stream()
          .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .sorted(Comparator.comparingInt(Path::getNameCount))
          .toList();

      // Move the branch back only if the operation's commit actually landed.
      if (!before.equals(repo.resolve(Constants.HEAD))) {
        final var update = repo.updateRef(Constants.HEAD);
        update.setNewObjectId(before);
        final var result = update.forceUpdate();
        if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
          throw new IOException("Could not move HEAD back to " + before.name() + ": " + result);
        }
      }

      // Index and tracked files back to the target commit (pre-operation HEAD for rollback, current HEAD for recovery).
      final RevCommit commit = walk.parseCommit(before);
      final var checkout = new DirCacheCheckout(repo, repo.lockDirCache(), commit.getTree());
      checkout.setFailOnConflict(false);
      checkout.checkout();

      // Remove the collected paths. Do not prune empty parents: Git cannot tell whether a directory was created
      // by an operation or already existed, and restoration must not delete a directory it cannot safely attribute.
      for (final var path : created) {
        Files.deleteIfExists(workspace.rootPath().resolve(path));
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

      requireClean(git, workspace);
    }
  }
  //endregion

  //region Reads (lock-free)
  /**
   * The most recent commit that changed each requested content path, keyed by '/'-separated workspace-relative path.
   * Results are cached per path and HEAD. A cached result from an older HEAD is reused when the walk reaches that
   * commit, so an unchanged path normally requires walking only the new commits.
   */
  @Override
  public Map<Path, LastEdit> lastEdits(final Workspace workspace, final Set<Path> paths)
  throws IOException, WorkspaceHistoryException
  {
    if (paths.isEmpty() || !isManaged(workspace)) {
      return Map.of();
    }

    final var edits = new HashMap<Path, LastEdit>();

    try (final var git = open(workspace)) {
      final var repo = git.getRepository();

      // Grab the current ObjectId for HEAD. Otherwise, a mutation in another request can update this value underfoot.
      final var head = repo.resolve(Constants.HEAD);
      if (head == null) {
        throw new WorkspaceHistoryException(
            WorkspaceHistoryException.Kind.OTHER,
            "Git-tracked workspace %d has no head.".formatted(workspace.id()));
      }

      final var cache = lastEditCache.computeIfAbsent(workspace, wId -> new ConcurrentHashMap<>());

      // Paths not cached at HEAD, keyed by git path, and the commits their stale cache entries were validated at
      final var pending = new HashMap<String, Path>();
      final var validatedAt = new HashMap<ObjectId, List<String>>();
      for (final var path : paths) {
        final var cachedEdit = cache.get(path);
        // Cache entries record the HEAD at which they were last validated.
        // If that is the current HEAD, reuse the result.
        if(cachedEdit != null && cachedEdit.validAt().equals(head)) {
          edits.put(path, cachedEdit.edit());
        } else {
          final var gitPath = toGitPath(workspace.rootPath().relativize(path));
          pending.put(gitPath, path);
          if (cachedEdit != null) {
            validatedAt.computeIfAbsent(cachedEdit.validAt(), c -> new ArrayList<>()).add(gitPath);
          }
        }
      }

      // Quick return if everything was cached
      if(pending.isEmpty()) {
        return edits;
      }

      // One walk back from HEAD resolves every pending path: each commit is diffed against its parent once for all of
      // them, rather than once per path by a log per path. The walk stops when every path has been resolved.
      final var walk = new RevWalk(repo);
      walk.markStart(walk.parseCommit(head));
      try {
        // The diff command's path filter should list only the paths still pending.
        // Rebuilding the filter sorts every pending path, so we should only rebuild it when we've removed paths from pending
        var filterStale = false;
        var currentFilter = AndTreeFilter.create(PathFilterGroup.createFromStrings(pending.keySet()), TreeFilter.ANY_DIFF);

        for (RevCommit commit = walk.next(); !pending.isEmpty() && commit != null; commit = walk.next() ) {
          // Top of the loop: rebuild the filter if needed
          if(filterStale) {
            currentFilter = AndTreeFilter.create(PathFilterGroup.createFromStrings(pending.keySet()), TreeFilter.ANY_DIFF);
            filterStale = false;
          }

          // If we reach the commit that the cache entry was last validated at without getting a new value, then the
          // old cache entry is still valid and can be put back in the cache with an updated validation
          if(validatedAt.containsKey(commit)) {
            final int oldPendingSize = pending.size();
            validatedAt.get(commit).forEach(gitPath -> {
              final var path = pending.remove(gitPath);
              if(path == null) return;
              final var cachedEdit = cache.get(path);
              cache.put(path, new CachedLastEdit(head, cachedEdit.edit()));
              edits.put(path, cachedEdit.edit());
            });
            // The filter is only stale if we actually removed elements from pending
            // (If all files in a specific validateAt list were edited in a later commit, pending would not be edited by the above loop)
            filterStale = oldPendingSize != pending.size();

            if (pending.isEmpty()){
              break;
            }
          }

          // Generate the diff for this commit using the
          final var diffCommand = git.diff()
                                     .setShowNameAndStatusOnly(true)
                                     .setPathFilter(currentFilter);

          // Get a tree iterator for this commit
          final var newTree = new CanonicalTreeParser();
          try (ObjectReader reader = repo.newObjectReader()) {
            newTree.reset(reader, walk.parseTree(commit.getTree().getId()));
          }
          diffCommand.setNewTree(newTree);

          // Get a tree iterator for this commit's parent, or use the empty tree iterator if this is the first commit
          if(commit.getParentCount() == 0) {
            diffCommand.setOldTree(new EmptyTreeIterator());
          } else {
            final var oldTreeParser = new CanonicalTreeParser();
            final var parent = commit.getParent(0);
            try (ObjectReader reader = repo.newObjectReader()) {
              oldTreeParser.reset(reader, walk.parseTree(parent.getTree().getId()));
            }
            diffCommand.setOldTree(oldTreeParser);
          }

          final var diffs = diffCommand.call();
          if(!diffs.isEmpty()){
            filterStale = true;

            final var author = commit.getAuthorIdent();
            final var edit = new LastEdit(author, author.getWhenAsInstant(), commit, commit.getParentCount() == 0);
            commit.disposeBody(); // We don't need the commit body anymore, so we can save the memory

            for(final var diffEntry : diffs) {
              // Since this method just getting the last commit, we only care about one of the paths
              // By default, that's the NEW path, but if the file was deleted in this commit, we will use the OLD path
              final var editedPath = diffEntry.getNewPath().equals("/dev/null")
                  ? diffEntry.getOldPath()
                  : diffEntry.getNewPath();

              final var path = pending.remove(editedPath);
              if (path == null) continue;

              cache.put(path, new CachedLastEdit(head, edit));
              edits.put(path, edit);
            }
          }
        }
      } catch (GitAPIException gapi) {
        throw new WorkspaceHistoryException(WorkspaceHistoryException.Kind.OTHER, "Unable to determine last edits", gapi);
      } finally {
        // RevWalk does not dispose itself on close for some reason,
        // so it must be manually disposed in order to close its ObjectReader
        walk.dispose();
        walk.close();
      }

      // Anything left was never changed in history reachable from HEAD, not even by a baseline commit
      if (!pending.isEmpty()) {
        final var errorMsg = "The following file(s) in Workspace %d have no revision history:".formatted(workspace.id())
                             + String.join("\n\t- ", pending.keySet());
        throw new WorkspaceHistoryException(WorkspaceHistoryException.Kind.OTHER, errorMsg);
      }

      return edits;
    }
  }

  /**
   * Get the most recent commit that edited the file, or null if the workspace is not managed by Git.
   */
  @Override
  public LastEdit lastEdit(final Workspace workspace, final Path filePath)
  throws IOException, WorkspaceHistoryException
  {
    return lastEdits(workspace, Set.of(filePath)).get(filePath);
  }

  /**
   * Apply the by/at information from a LastEdit to a metadata object.
   * @param originalMetadata The original metadata file contents
   * @param edit the last edited information from the versioning service to be applied
   * @return If edit is null, returns originalMetadata. Otherwise, returns originalMetadata with its
   *    "lastEditedBy" and "lastEditedAt" fields overwritten to the values of "by" and "at" from edit
   */
  public static JsonObject applyLastEdit(final JsonObject originalMetadata, final LastEdit edit) {
    if(edit == null) { return originalMetadata; }
    // If the edit is not the baseline, or if the edit is the baseline but one of the "lastEdited" fields are missing, overwrite the original metadata
    if(!edit.baseline() || !originalMetadata.containsKey("lastEditedBy") || !originalMetadata.containsKey("lastEditedAt")) {
      return Json.createObjectBuilder(originalMetadata)
                 .add("lastEditedBy", edit.by())
                 .add("lastEditedAt", edit.at().toString())
                 .build();
    }
    return originalMetadata;
  }
  //endregion
}
