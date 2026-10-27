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
 * Daily Git garbage collection (GC) of workspace repositories.
 *
 * Why: Git writes every new version of a file, folder listing, commit and tag as its own small file under .git/objects,
 * and every branch and tag pointer as its own small file under .git/refs. These one-file-each entries are called
 * "loose". Over thousands of saves they pile up and slow reads. GC compresses the objects into a single "pack" file
 * and gathers the tag pointers into a single packed-refs file.
 *
 * When: once a day, for every workspace changed since the server started (only changes create Git objects). JGit's
 * automatic mode does nothing until a workspace has about 6,700 loose objects (gc.auto) or more than 50 pack files
 * (gc.autoPackLimit), so most daily runs are a quick check. Objects nothing refers to are deleted only once they are
 * two weeks old. A workspace that is busy saving is skipped until the next day.
 *
 * Interruptions: GC deletes old files only after their replacements are fully written, so a crash or restart mid-GC
 * leaves a valid repository. What it can leave behind is lock files (packed-refs.lock and the main branch's lock),
 * which make later saves refuse to run. GC writes a marker file in .git before it starts; a marker still there later
 * proves those locks were GC's, so {@link #removeInterruptedGcLocks} deletes them and the marker before the next save
 * or GC. JGit's own "GC running" file, gc.pid, is ignored once it is 12 hours old.
 */
public final class WorkspaceGc {
  private static final Logger logger = LoggerFactory.getLogger(WorkspaceGc.class);
  static final String GC_MARKER = "seqdev-gc-in-progress";
  // The locks a GC holds that WorkspaceGitService rejects: JGit locks packed-refs while rewriting it (and each loose
  // tag file it deletes, which nothing checks or reads), and gcLocked locks main.
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
      // Keep main's pointer in its own loose file. A read looks for it in packed-refs and then in that file, so a read
      // that fell between GC copying main into packed-refs and deleting the loose file would find no current commit,
      // or a stale one. JGit leaves a pointer it cannot lock where it is. Taken after the marker, so an interrupted
      // GC's lock is recovered with the others.
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
