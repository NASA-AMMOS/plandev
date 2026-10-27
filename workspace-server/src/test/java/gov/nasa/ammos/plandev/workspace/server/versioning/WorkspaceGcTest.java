package gov.nasa.ammos.plandev.workspace.server.versioning;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceFileSystemService;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.utils.FlakyHistory;
import gov.nasa.ammos.plandev.workspace.server.utils.MockBindings;
import gov.nasa.ammos.plandev.workspace.server.utils.WorkspaceTestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.Constants;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.TreeSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static gov.nasa.ammos.plandev.workspace.server.utils.FlakyHistory.assertClean;
import static gov.nasa.ammos.plandev.workspace.server.utils.FlakyHistory.createMarker;
import static gov.nasa.ammos.plandev.workspace.server.utils.FlakyHistory.log;
import static gov.nasa.ammos.plandev.workspace.server.utils.MockBindings.DEFAULT_USER;
import static gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService.mutationMarker;
import static gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService.open;
import static gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitServiceIntegrationTest.assertFailure;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@link WorkspaceGc}: eligibility, skipping, failure, and recovery from an interrupted GC. */
class WorkspaceGcTest {
  private static final Path fileA = Path.of("a.txt");

  @TempDir Path tmp;
  private WorkspaceTestRepository wsRepo;
  private FlakyHistory history;
  private WorkspaceFileSystemService fs;
  private MockBindings bindings;
  private WorkspaceGc gc;
  private Workspace WS1;
  private Workspace WS2;
  private int files = 0;

  @BeforeEach
  void setUp() throws IOException {
    wsRepo = new WorkspaceTestRepository(tmp.toRealPath());
    WS1 = wsRepo.createWorkspace("ws1");
    WS2 = wsRepo.createWorkspace("ws2");
    restartServer();
  }

  private void restartServer() {
    history = new FlakyHistory();
    fs = new WorkspaceFileSystemService(wsRepo, history);
    bindings = new MockBindings(fs, history);
    gc = new WorkspaceGc(history);
  }

  /** One mutation adding n new files. */
  private void addFiles(final Workspace ws, final int n) throws Exception {
    history.mutate(ws, "add files", DEFAULT_USER, () -> {
      for (var i = 0; i < n; i++, files++) Files.writeString(ws.rootPath().resolve("f" + files + ".txt"), "file " + files);
      return true;
    }, r -> r);
  }

  /**
   * Lower gc.auto so a small history is over it: JGit estimates the loose-object count from objects/17 alone, and is
   * eligible above (gc.auto + 255) / 256 objects there, so 1 at gc.auto=256.
   */
  private void makeEligible(final Workspace ws) throws Exception {
    addFiles(ws, 1);
    try (final var git = open(ws)) {
      final var config = git.getRepository().getConfig();
      config.setInt("gc", null, "auto", 256);
      config.save();
    }
    while (looseIn17(ws) < 2) addFiles(ws, 50);
  }

  private static long looseIn17(final Workspace ws) throws IOException {
    final var dir = ws.rootPath().resolve(".git/objects/17");
    if (!Files.isDirectory(dir)) return 0;
    try (final var entries = Files.list(dir)) {
      return entries.count();
    }
  }

  private static long packs(final Workspace ws) throws IOException {
    try (final var entries = Files.list(ws.rootPath().resolve(".git/objects/pack"))) {
      return entries.filter(p -> p.toString().endsWith(".pack")).count();
    }
  }

  private static Path git(final Workspace ws, final String name) {
    return ws.rootPath().resolve(".git").resolve(name);
  }

  private void assertCollectedAndUsable(final Workspace ws, final List<RevCommit> history) throws Exception {
    assertEquals(0, looseIn17(ws));
    assertTrue(packs(ws) > 0);
    assertFalse(Files.exists(WorkspaceGc.marker(ws)));
    assertEquals(List.of(), WorkspaceGitService.existingGitLocks(ws));
    bindings.saveAndGetEtag(ws, fileA, "after gc");
    assertEquals("after gc", bindings.readFile(ws, fileA));
    assertEquals(history, log(ws).subList(1, history.size() + 1));
    assertClean(ws);
  }

  private static void setWritable(final Path dir, final boolean writable) throws IOException {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(writable ? "rwxr-xr-x" : "r-xr-xr-x"));
  }

  @Test
  void aRepositoryBelowTheThresholdIsLeftAlone() throws Exception {
    addFiles(WS1, 20);
    assertFalse(gc.gcIfIdle(WS1));
    assertEquals(0, packs(WS1));
    assertFalse(Files.exists(WorkspaceGc.marker(WS1)));
  }

  @Test
  void anEligibleRepositoryIsPackedAndKeepsItsHistoryAndRevisions() throws Exception {
    makeEligible(WS1);
    bindings.saveAndGetEtag(WS1, fileA, "v1");
    assertInstanceOf(HandlerResult.Success.class, bindings.handleCreateRevision(WS1.id(), fileA, "*", "", DEFAULT_USER));
    final var before = log(WS1);

    assertTrue(gc.gcIfIdle(WS1));
    assertFalse(gc.gcIfIdle(WS1), "packed, so no longer eligible");
    assertCollectedAndUsable(WS1, before);
    assertInstanceOf(HandlerResult.Success.class, bindings.handleCreateRevision(WS1.id(), fileA, "*", "", DEFAULT_USER));
    assertEquals(List.of("b", "a"), fs.listRevisions(WS1, fileA).stream().map(r -> r.name()).toList());
  }

  @Test
  void aBusyWorkspaceIsSkippedWithoutWaiting() throws Exception {
    makeEligible(WS1);
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    try (final var executor = Executors.newSingleThreadExecutor()) {
      final var mutation = executor.submit(() -> history.mutate(WS1, "slow", DEFAULT_USER, () -> {
        entered.countDown();
        release.await();
        Files.writeString(WS1.rootPath().resolve(fileA), "slow");
        return true;
      }, r -> r));
      entered.await();
      final var start = Instant.now();
      assertFalse(gc.gcIfIdle(WS1));
      assertTrue(Duration.between(start, Instant.now()).toMillis() < 1000);
      assertEquals(0, packs(WS1));
      release.countDown();
      assertTrue(mutation.get());
    }
    assertTrue(gc.gcIfIdle(WS1));
    assertEquals("slow", bindings.readFile(WS1, fileA));
  }

  /**
   * A lock GC did not take blocks it and is left untouched, including one with a name GC uses: GC's own recovery would
   * otherwise delete it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"index.lock", "refs/heads/main.lock"})
  void aForeignGitLockBlocksGcAndIsLeftAlone(final String lock) throws Exception {
    makeEligible(WS1);
    Files.writeString(git(WS1, lock), "");
    assertFalse(gc.gcIfIdle(WS1));
    assertTrue(Files.exists(git(WS1, lock)));
    assertEquals(0, packs(WS1));
    Files.delete(git(WS1, lock));
    assertTrue(gc.gcIfIdle(WS1));
  }

  /** GC repairs nothing itself: an interrupted mutation's marker is left for the next mutation to recover. */
  @Test
  void anInterruptedMutationIsLeftForTheMutationPath() throws Exception {
    makeEligible(WS1);
    createMarker(mutationMarker(WS1));
    final var before = log(WS1);
    assertTrue(gc.gcIfIdle(WS1));
    assertTrue(Files.exists(mutationMarker(WS1)));
    assertCollectedAndUsable(WS1, before);
    assertFalse(Files.exists(mutationMarker(WS1)));
  }

  /** Lock-free reads, cold and warm, racing repeated GCs that replace the pack and pack new tags, but never main. */
  @Test
  void readsDuringGcSeeTheSameHistory() throws Exception {
    makeEligible(WS1);
    for (final var content : List.of("v1", "v2")) {
      bindings.saveAndGetEtag(WS1, fileA, content);
      assertInstanceOf(HandlerResult.Success.class, bindings.handleCreateRevision(WS1.id(), fileA, "*", "", DEFAULT_USER));
    }
    final var all = Set.of(WS1.rootPath().resolve(fileA), WS1.rootPath().resolve("f0.txt"));
    final var expectedList = fs.listRevisions(WS1, fileA);
    final var expectedEdits = history.lastEdits(WS1, all);

    final var stop = new AtomicBoolean();
    final var failures = new ConcurrentLinkedQueue<Throwable>();
    final var reads = new AtomicInteger();
    final var pool = Executors.newFixedThreadPool(4);
    for (var t = 0; t < 4; t++) {
      final var cold = t % 2 == 0;
      pool.submit(() -> {
        WorkspaceGitService reader = history;
        var files = fs;
        while (!stop.get()) {
          try {
            if (cold) {
              reader = new WorkspaceGitService();
              files = new WorkspaceFileSystemService(wsRepo, reader);
            }
            assertEquals(expectedList, files.listRevisions(WS1, fileA));
            assertEquals("v1", new String(files.readRevision(WS1, fileA, "a").orElseThrow().content(), StandardCharsets.UTF_8));
            assertEquals(expectedEdits, reader.lastEdits(WS1, all));
            reads.incrementAndGet();
          } catch (Throwable e) {
            failures.add(e);
          }
        }
      });
    }
    try (final var git = open(WS1)) {
      final var repo = git.getRepository();
      for (var i = 0; i < 20; i++) {
        // A new reachable object makes a new pack (so the old one is deleted), and rewriting main's loose ref, as every
        // commit does, would let GC pack it again. History stays the same, so every read has one right answer.
        try (final var inserter = repo.newObjectInserter(); final var walk = new RevWalk(repo)) {
          final var blob = inserter.insert(Constants.OBJ_BLOB, ("churn " + i).getBytes(StandardCharsets.UTF_8));
          // Unreachable loose objects, enough to make this GC eligible
          for (var j = 0; looseIn17(WS1) < 2; j++) inserter.insert(Constants.OBJ_BLOB, ("unreachable " + i + " " + j).getBytes(StandardCharsets.UTF_8));
          inserter.flush();
          git.tag().setName("churn-" + i).setObjectId(walk.parseAny(blob)).setAnnotated(false).call();
        }
        // Atomically, as a commit does: a reader must never see a partly written ref.
        final var temp = git(WS1, "main.tmp");
        Files.writeString(temp, repo.resolve(Constants.HEAD).name() + "\n");
        Files.move(temp, git(WS1, "refs/heads/main"), StandardCopyOption.ATOMIC_MOVE);
        assertTrue(gc.gcIfIdle(WS1));
        assertTrue(Files.exists(git(WS1, "refs/heads/main")), "main stays loose, so a read never straddles its packing");
      }
    } finally {
      stop.set(true);
      pool.shutdown();
      assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES));
    }
    assertTrue(reads.get() > 0);
    assertTrue(failures.isEmpty(), () -> new TreeSet<>(failures.stream().map(Throwable::toString).toList()).toString());
  }

  /** What a process death during GC's RefDirectory.pack, while a pack was being written, leaves behind. */
  private void simulateInterruptedGc(final Workspace ws) throws IOException {
    createMarker(WorkspaceGc.marker(ws));
    Files.writeString(git(ws, "packed-refs.lock"), "# pack-refs with: peeled fully-peeled sorted\n");
    Files.writeString(git(ws, "refs/heads/main.lock"), "");
    Files.writeString(git(ws, "gc.pid"), "1 crashed-host");
    Files.writeString(git(ws, "objects/pack/gc_1.pack_tmp"), "partial pack");
  }

  @Test
  void anInterruptedGcIsRecoveredByTheNextMutation() throws Exception {
    makeEligible(WS1);
    assertTrue(gc.gcIfIdle(WS1)); // so packed-refs and a pack exist, as they would before a later GC
    makeEligible(WS1);
    final var before = log(WS1);
    simulateInterruptedGc(WS1);
    restartServer();

    bindings.saveAndGetEtag(WS1, fileA, "after crash");
    assertFalse(Files.exists(WorkspaceGc.marker(WS1)));
    assertFalse(Files.exists(git(WS1, "packed-refs.lock")));
    assertFalse(Files.exists(git(WS1, "refs/heads/main.lock")));
    assertEquals(before, log(WS1).subList(1, before.size() + 1));

    assertFalse(gc.gcIfIdle(WS1), "JGit treats a gc.pid under 12 hours old as a running GC");
    // By the next daily cycle, JGit ignores a gc.pid over 12 hours old and deletes temporary packs over a day old.
    final var dayAgo = FileTime.from(Instant.now().minus(Duration.ofHours(25)));
    Files.setLastModifiedTime(git(WS1, "gc.pid"), dayAgo);
    Files.setLastModifiedTime(git(WS1, "objects/pack/gc_1.pack_tmp"), dayAgo);
    assertTrue(gc.gcIfIdle(WS1));
    assertFalse(Files.exists(git(WS1, "gc.pid")));
    assertFalse(Files.exists(git(WS1, "objects/pack/gc_1.pack_tmp")));
    assertCollectedAndUsable(WS1, log(WS1));
  }

  @Test
  void anInterruptedGcIsRecoveredByTheNextGc() throws Exception {
    makeEligible(WS1);
    simulateInterruptedGc(WS1);
    Files.delete(git(WS1, "gc.pid"));
    restartServer();
    final var before = log(WS1);
    assertTrue(gc.gcIfIdle(WS1));
    assertCollectedAndUsable(WS1, before);
  }

  /** GC's marker accounts only for the locks GC takes, and only regular files. */
  @Test
  void aGcMarkerDoesNotExplainOtherLocks() throws Exception {
    bindings.saveAndGetEtag(WS1, fileA, "original");
    createMarker(WorkspaceGc.marker(WS1));
    Files.writeString(git(WS1, "index.lock"), "");
    assertFailure(bindings.saveFile(WS1, fileA, "changed", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
    assertTrue(Files.exists(git(WS1, "index.lock")));
    Files.delete(git(WS1, "index.lock"));

    createMarker(WorkspaceGc.marker(WS1));
    Files.createDirectory(git(WS1, "packed-refs.lock"));
    assertFailure(bindings.saveFile(WS1, fileA, "changed", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
    assertTrue(Files.isDirectory(git(WS1, "packed-refs.lock")));
    assertEquals("original", bindings.readFile(WS1, fileA));
  }

  @Test
  void aFailedGcLeavesTheWorkspaceUsable() throws Exception {
    makeEligible(WS1);
    final var packDir = git(WS1, "objects/pack");
    Files.createDirectories(packDir);
    setWritable(packDir, false);
    assumeTrue(!Files.isWritable(packDir), "permissions are not enforced for this user");
    try {
      assertThrows(IOException.class, () -> gc.gcIfIdle(WS1));
      assertFalse(Files.exists(WorkspaceGc.marker(WS1)));
      assertEquals(List.of(), WorkspaceGitService.existingGitLocks(WS1));
      bindings.saveAndGetEtag(WS1, fileA, "after failed gc");
      assertClean(WS1);
    } finally {
      setWritable(packDir, true);
    }
    final var before = log(WS1);
    assertTrue(gc.gcIfIdle(WS1), "retried on a later cycle");
    assertCollectedAndUsable(WS1, before);
  }

  @Test
  void eachChangeQueuesAGcCheckAndAFailedOneDoesNotStopLaterOnes() throws Exception {
    makeEligible(WS1);
    makeEligible(WS2);
    final var packDir = git(WS2, "objects/pack");
    Files.createDirectories(packDir);
    setWritable(packDir, false);
    gc.start();
    try {
      addFiles(WS2, 1);
      addFiles(WS1, 1);
      awaitGc();
      assertTrue(packs(WS1) > 0);
    } finally {
      setWritable(packDir, true);
    }
    addFiles(WS2, 1);
    awaitGc();
    assertTrue(packs(WS2) > 0);
  }

  /** Wait for every GC check queued so far: the GC thread runs them in order. */
  private void awaitGc() throws Exception {
    gc.executor.submit(() -> {}).get(1, TimeUnit.MINUTES);
  }
}
