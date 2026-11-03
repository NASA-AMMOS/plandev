package gov.nasa.ammos.plandev.workspace.server.versioning;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceFileSystemService;
import gov.nasa.ammos.plandev.workspace.server.WorkspacePaths;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceRevisionService;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import gov.nasa.ammos.plandev.workspace.server.types.RevisionNamingConvention;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.utils.FlakyHistory;
import gov.nasa.ammos.plandev.workspace.server.utils.MockBindings;
import gov.nasa.ammos.plandev.workspace.server.utils.WorkspaceTestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.Constants;

import java.nio.file.FileAlreadyExistsException;
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

/** {@link WorkspaceGitGarbageCollector}: eligibility, skipping, failure, and recovery from an interrupted GC. */
class WorkspaceGitGarbageCollectorTest {
  private static final Path fileA = Path.of("a.txt");

  /*
   * JGit will only run garbage collection if there is more than one file inside ".git/objects/17"
   * These two files are example files names from this folder and can be used to force GC.
   */
  private static final Path referenceFile1 = Path.of("255694a1a217b6db04e89611bbd8af6b8a475d");
  private static final Path referenceFile2 = Path.of("2f08b5e364f0ffb4668436d9f1d56cd3c000c0");
  private static final Path destinationPath = Path.of("objects/17");

  @TempDir static Path tmp;
  private static WorkspaceTestRepository wsRepo;
  private FlakyHistory history;
  private WorkspaceFileSystemService fs;
  private MockBindings bindings;
  private WorkspaceGitGarbageCollector gc;
  private Workspace WS1;
  private Workspace WS2;
  private int files = 0;

  @BeforeAll
  static void beforeAll() throws IOException {
    wsRepo = new WorkspaceTestRepository(tmp.toRealPath());
  }

  @AfterAll
  static void afterAll() {
    wsRepo.close();
  }

  @BeforeEach
  void setUp() throws IOException {
    WS1 = wsRepo.createWorkspace("gcWorkspace1");
    WS2 = wsRepo.createWorkspace("gcWorkspace2");
    restartServer();
  }

  @AfterEach
  void teardown() {
    wsRepo.deleteWorkspace(WS1);
    wsRepo.deleteWorkspace(WS2);
  }

  //region Helper Methods
  private void restartServer() {
    history = new FlakyHistory();
    gc = history.garbageCollector;
    fs = new WorkspaceFileSystemService(wsRepo, history);
    bindings = new MockBindings(fs, history, RevisionNamingConvention.ALPHABETICAL);
  }

  /** Wait for every GC check queued so far: the GC thread runs them in order. */
  private void awaitGc() throws Exception {
    gc.executor.submit(() -> {}).get(1, TimeUnit.MINUTES);
  }

  /** One mutation adding n new files. */
  private void addFiles(final Workspace ws, final int n) throws Exception {
    history.mutate(ws, "add files", DEFAULT_USER, () -> {
      for (var i = 0; i < n; i++, files++) Files.writeString(ws.rootPath().resolve("f" + files + ".txt"), "file " + files);
      return true;
    }, r -> r);
  }

  private boolean forceRunGC(final Workspace ws) throws Exception {
    awaitGc();
    final var dest = gitFile(ws, destinationPath);
    Files.createDirectories(dest);

    // If there are less than 2 files in the objects/17 folder, add blank dummy files
    if(dest.toFile().list().length < 2) {
      // If these files already exist, do nothing
      try {
        Files.createFile(dest.resolve(referenceFile1));
      }
      catch (FileAlreadyExistsException fae) {}

      try {
        Files.createFile(dest.resolve(referenceFile2));
      }
      catch (FileAlreadyExistsException fae) {}
    }
    return gc.gcIfIdle(ws);
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
    awaitGc();
  }

  private static long looseIn17(final Workspace ws) throws IOException {
    final var dir = ws.rootPath().resolve(".git/objects/17");
    if (!Files.isDirectory(dir)) return 0;
    try (final var entries = Files.list(dir)) {
      return entries.count();
    }
  }

  private static long packs(final Workspace ws) throws IOException {
    try (final var entries = Files.list(gitFile(ws, Path.of("objects", "pack")))) {
      return entries.filter(p -> p.toString().endsWith(".pack")).count();
    }
  }

  private static Path gitFile(final Workspace ws, final String subpath) {
    return WorkspacePaths.gitDirectory(ws).resolve(Path.of(subpath));
  }

  private static Path gitFile(final Workspace ws, final Path subpath) {
    return WorkspacePaths.gitDirectory(ws).resolve(subpath);
  }

  private void assertCollectedAndUsable(final Workspace ws, final List<RevCommit> history) throws Exception {
    // Await GC to finish
    awaitGc();

    assertEquals(0, looseIn17(ws));
    assertTrue(packs(ws) > 0);
    assertFalse(Files.exists(WorkspaceGitGarbageCollector.marker(ws)));
    assertEquals(List.of(), WorkspaceGitService.existingGitLocks(ws));
    bindings.saveAndGetEtag(ws, fileA, "after gc");
    assertEquals("after gc", bindings.readFile(ws, fileA));
    assertEquals(history, log(ws).subList(1, history.size() + 1));
    assertClean(ws);
  }

  private static void setWritable(final Path dir, final boolean writable) throws IOException {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(writable ? "rwxr-xr-x" : "r-xr-xr-x"));
  }
  //endregion

  @Test
  void aRepositoryBelowTheThresholdIsLeftAlone() throws Exception {
    addFiles(WS1, 20);
    assertFalse(gc.gcIfIdle(WS1));
    assertEquals(0, packs(WS1));
    assertFalse(Files.exists(WorkspaceGitGarbageCollector.marker(WS1)));
  }

  @Test
  void anEligibleRepositoryIsPackedAndKeepsItsHistoryAndRevisions() throws Exception {
    makeEligible(WS1);
    bindings.saveAndGetEtag(WS1, fileA, "v1");
    assertInstanceOf(HandlerResult.Success.class, bindings.handleCreateRevision(WS1.id(), fileA, "*", "", null, DEFAULT_USER));

    // The edit
    final var before = log(WS1);
    assertCollectedAndUsable(WS1, before);

    assertInstanceOf(HandlerResult.Success.class, bindings.handleCreateRevision(WS1.id(), fileA, "*", "", null, DEFAULT_USER));
    assertEquals(List.of("b", "a"), fs.listRevisions(WS1, fileA).stream().map(WorkspaceRevisionService.Revision::name).toList());
  }

  @Test
  void aBusyWorkspaceIsSkippedWithoutWaiting() throws Exception {
    makeEligible(WS1);
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    try (final var executor = Executors.newSingleThreadExecutor()) {
      try {
        final var mutation = executor.submit(() -> history.mutate(
            WS1, "slow", DEFAULT_USER, () -> {
              entered.countDown();
              release.await();
              Files.writeString(WS1.rootPath().resolve(fileA), "slow");
              return true;
            }, r -> r));
        entered.await();
        final var start = Instant.now();
        assertFalse(gc.gcIfIdle(WS1));
        assertTrue(Duration.between(start, Instant.now()).toMillis() < 1000);
        // 1 pack from GC that happened after `makeEligible`
        assertEquals(1, packs(WS1));
        release.countDown();
        assertTrue(mutation.get());
      } finally {
        // Ensure that the countDown locks are actually released in the event of a failure
        entered.countDown();
        release.countDown();
      }
    }
    // Nothing new to pack
    assertFalse(gc.gcIfIdle(WS1));
    assertEquals(1, packs(WS1));
    // File has been updated
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
    assertEquals(1, packs(WS1));

    Files.writeString(gitFile(WS1, lock), "");
    assertFalse(forceRunGC(WS1));
    assertTrue(Files.exists(gitFile(WS1, lock)));
    assertEquals(1, packs(WS1));
    Files.delete(gitFile(WS1, lock));
    assertTrue(forceRunGC(WS1));
  }

  /** GC repairs nothing itself: an interrupted mutation's marker is left for the next mutation to recover. */
  @Test
  void anInterruptedMutationIsLeftForTheMutationPath() throws Exception {
    makeEligible(WS1);
    createMarker(mutationMarker(WS1));
    final var before = log(WS1);
    assertTrue(forceRunGC(WS1));
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
      assertInstanceOf(HandlerResult.Success.class, bindings.handleCreateRevision(WS1.id(), fileA, "*", "", null, DEFAULT_USER));
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
        final var temp = gitFile(WS1, Path.of("main.tmp"));
        Files.writeString(temp, repo.resolve(Constants.HEAD).name() + "\n");
        Files.move(temp, gitFile(WS1, Path.of("refs", "heads", "main")), StandardCopyOption.ATOMIC_MOVE);
        assertTrue(gc.gcIfIdle(WS1));
        assertTrue(Files.exists(gitFile(WS1, Path.of("refs", "heads", "main"))), "main stays loose, so a read never straddles its packing");
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
    createMarker(WorkspaceGitGarbageCollector.marker(ws));
    Files.writeString(gitFile(ws, Path.of("packed-refs.lock")), "# pack-refs with: peeled fully-peeled sorted\n");
    Files.writeString(gitFile(ws, Path.of("refs", "heads", "main.lock")), "");
    Files.writeString(gitFile(ws, Path.of("gc.pid")), "1 crashed-host");
    Files.writeString(gitFile(ws, Path.of("objects/pack/gc_1.pack_tmp")), "partial pack");
  }

  @Test
  void anInterruptedGcIsRecoveredByTheNextMutation() throws Exception {
    makeEligible(WS1);
    // GC ran during make eligible
    assertEquals(1, packs(WS1));

    // Make an interrupted state
    simulateInterruptedGc(WS1);
    restartServer();

    // Saving a file reruns GC
    bindings.saveAndGetEtag(WS1, fileA, "after crash");
    awaitGc();
    assertFalse(Files.exists(WorkspaceGitGarbageCollector.marker(WS1)));
    assertFalse(Files.exists(gitFile(WS1, Path.of("packed-refs.lock"))));
    assertFalse(Files.exists(gitFile(WS1, Path.of("refs", "heads", "main.lock"))));
    assertCollectedAndUsable(WS1, log(WS1));
  }

  @Test
  void anInterruptedGcIsRecoveredByTheNextGc() throws Exception {
    makeEligible(WS1);
    // GC ran during make eligible
    assertEquals(1, packs(WS1));

    // Make an interrupted state
    simulateInterruptedGc(WS1);
    Files.delete(gitFile(WS1, "gc.pid"));
    restartServer();

    // Rerun GC
    assertTrue(forceRunGC(WS1));
    assertCollectedAndUsable(WS1, log(WS1));
  }

  /** GC's marker accounts only for the locks GC takes, and only regular files. */
  @Test
  void aGcMarkerDoesNotExplainOtherLocks() throws Exception {
    bindings.saveAndGetEtag(WS1, fileA, "original");
    createMarker(WorkspaceGitGarbageCollector.marker(WS1));
    Files.writeString(gitFile(WS1, "index.lock"), "");
    assertFailure(bindings.saveFile(WS1, fileA, "changed", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
    assertTrue(Files.exists(gitFile(WS1, "index.lock")));
    Files.delete(gitFile(WS1, "index.lock"));

    createMarker(WorkspaceGitGarbageCollector.marker(WS1));
    Files.createDirectory(gitFile(WS1, "packed-refs.lock"));
    assertFailure(bindings.saveFile(WS1, fileA, "changed", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
    assertTrue(Files.isDirectory(gitFile(WS1, "packed-refs.lock")));
    assertEquals("original", bindings.readFile(WS1, fileA));
  }

  @Test
  void aFailedGcLeavesTheWorkspaceUsable() throws Exception {
    makeEligible(WS1);
    awaitGc();
    final var packDir = gitFile(WS1, "objects/pack");
    Files.createDirectories(packDir);
    setWritable(packDir, false);
    assumeTrue(!Files.isWritable(packDir), "permissions are not enforced for this user");
    try {
      assertThrows(IOException.class, () -> forceRunGC(WS1));
      assertFalse(Files.exists(WorkspaceGitGarbageCollector.marker(WS1)));
      assertEquals(List.of(), WorkspaceGitService.existingGitLocks(WS1));
      bindings.saveAndGetEtag(WS1, fileA, "after failed gc");
      assertClean(WS1);
    } finally {
      setWritable(packDir, true);
    }
    final var before = log(WS1);
    assertTrue(forceRunGC(WS1));
    assertCollectedAndUsable(WS1, before);
  }

  @Test
  void eachChangeQueuesAGcCheckAndAFailedOneDoesNotStopLaterOnes() throws Exception {
    makeEligible(WS1);
    makeEligible(WS2);
    final var packDir = gitFile(WS2, "objects/pack");
    Files.createDirectories(packDir);
    setWritable(packDir, false);
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
}
