package gov.nasa.ammos.plandev.workspace.server.versioning;

import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.internal.storage.file.LockFile;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.gitDirectory;

/**
 * Periodic Git garbage collection of workspace repositories, off the request path.
 *
 * Policy:
 * Daily, for workspaces mutated since startup (only mutations create objects). JGit auto GC: a no-op under gc.auto
 * (6700) loose objects and gc.autoPackLimit (50) packs; default two-week prune.
 *
 * Crash safety:
 * GC replaces objects only after their new pack is in place, and replaces packed-refs by an atomic rename, so an
 * interruption leaves a valid repository. What it can leave behind that blocks mutations is the lock files it and
 * {@link #gcLocked} take, which the mutation trust checks reject. A marker in .git, created under the workspace lock
 * before GC starts, attributes those exact locks to GC; {@link #removeInterruptedGcLocks} removes them and the
 * marker before the next mutation, which then validates the repository as usual. JGit's own gc.pid goes stale after
 * 12 hours.
 */
public final class WorkspaceGc {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceGc.class);
  static final String GC_MARKER = "seqdev-gc-in-progress";
  // The locks a GC holds that WorkspaceGitService rejects: RefDirectory.pack locks packed-refs (and each loose tag it
  // deletes, which nothing checks or reads), and gcLocked locks main.
  private static final List<String> GC_LOCKS = List.of("packed-refs.lock", "refs/heads/" + WorkspaceGitService.BRANCH + ".lock");

  private final WorkspaceGitService history;

  public WorkspaceGc(final WorkspaceGitService history) {
    this.history = history;
  }

  /** Start the daily cycle on its own daemon thread. */
  public void start() {
    final var executor = Executors.newSingleThreadScheduledExecutor(r -> {
      final var thread = new Thread(r, "workspace-gc");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(this::gcAll, 1, 24, TimeUnit.HOURS);
  }

  void gcAll() {
    for (final var workspace : history.workspacesMutatedSinceStartup()) {
      try {
        gcIfIdle(workspace);
      } catch (Throwable t) { // a throw out of a scheduled task cancels every later run
        logger.error("Git GC of workspace {} failed; retrying next cycle", workspace.id(), t);
      }
    }
  }

  /** GC one workspace if it is idle, trusted and eligible. */
  boolean gcIfIdle(final Workspace workspace) throws Exception {
    final var ran = history.withLockIfIdle(workspace, () -> gcLocked(workspace));
    if (ran == null) logger.info("Skipped Git GC of workspace {}: busy", workspace.id());
    return Boolean.TRUE.equals(ran);
  }

  private boolean gcLocked(final Workspace workspace) throws Exception {
    if (!history.isManaged(workspace)) return false;
    removeInterruptedGcLocks(workspace);
    // Load-bearing: GC's finally removes packed-refs and main ref locks, so it must not start while anyone else's exist.
    if (!WorkspaceGitService.existingGitLocks(workspace).isEmpty()) {
      logger.warn("Skipped Git GC of workspace {}: it has Git locks", workspace.id());
      return false;
    }
    try (final var repo = WorkspaceGitService.open(workspace).getRepository()) {
      final var gc = new GC((FileRepository) repo);
      gc.setAuto(true);
      final var before = gc.getStatistics();
      final var start = System.nanoTime();
      WorkspaceGitService.createMarker(marker(workspace));
      // Keep main loose. A lock-free read resolves it from packed-refs and then from its loose file, so one that straddled
      // GC packing main and deleting the loose file would find no HEAD, or a stale one. RefDirectory.pack leaves a ref
      // it cannot lock loose. Under the marker, so an interrupted GC's lock is recovered with the others.
      final var mainLock = new LockFile(gitDirectory(workspace).resolve(Constants.R_HEADS + WorkspaceGitService.BRANCH).toFile());
      try {
        if (!mainLock.lock()) return false;
        if (gc.gc().get().isEmpty()) return false; // not eligible (or gc.pid held)
      } finally {
        mainLock.unlock();
        removeInterruptedGcLocks(workspace);
      }
      logger.info("Git GC of workspace {} took {} ms. Before: {}. After: {}",
                  workspace.id(), (System.nanoTime() - start) / 1_000_000, before, gc.getStatistics());
      return true;
    }
  }

  static Path marker(final Workspace workspace) {
    return gitDirectory(workspace).resolve(GC_MARKER);
  }

  /**
   * If a GC of this workspace was interrupted, remove the locks it can leave and then its marker. Caller holds the
   * workspace lock, so no GC of this workspace is running: the marker proves these exact locks were GC's.
   */
  static void removeInterruptedGcLocks(final Workspace workspace) throws IOException {
    final var marker = marker(workspace);
    if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
    for (final var name : GC_LOCKS) {
      final var lock = gitDirectory(workspace).resolve(name);
      if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) continue;
      if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
        throw WorkspaceGitService.inconsistent("Git lock " + lock + " is not a regular file and was left untouched.");
      }
      Files.delete(lock);
      logger.warn("Removed {} left by an interrupted Git GC of workspace {}", name, workspace.id());
    }
    Files.delete(marker);
  }
}
