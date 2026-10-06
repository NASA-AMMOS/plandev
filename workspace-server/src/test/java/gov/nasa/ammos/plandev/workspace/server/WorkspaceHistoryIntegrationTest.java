package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.exceptions.ReservedPathException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataMergeBehavior;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.Part;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.json.Json;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises workspace mutations end to end below HTTP: the binding handlers (validation, readOnly, ETag), the
 * {@link WorkspaceHistory} mutation boundary, {@link WorkspaceFileSystemService}, and a real JGit repository on disk.
 */
class WorkspaceHistoryIntegrationTest {
  private static final String USER = "alice";
  private static final int WS1 = 1;
  private static final int WS2 = 2;

  @TempDir Path tmp;
  private Path base;
  private FlakyHistory history;
  private WorkspaceFileSystemService fs;
  private WorkspaceBindings bindings;

  /** A WorkspaceHistory whose commit step can be made to fail for chosen workspaces (deterministic injection). */
  static final class FlakyHistory extends WorkspaceHistory {
    final Set<Path> failCommitsIn = ConcurrentHashMap.newKeySet();

    FlakyHistory(final WorkspaceRoots roots) {
      super(roots);
    }

    @Override
    protected void commit(final Git git, final String message, final PersonIdent author) throws GitAPIException {
      try {
        if (failCommitsIn.contains(git.getRepository().getWorkTree().toPath().toRealPath())) {
          throw new GitAPIException("injected commit failure") {};
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      super.commit(git, message, author);
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    base = tmp.toRealPath();
    Files.createDirectories(base.resolve("ws1"));
    Files.createDirectories(base.resolve("ws2"));
    final WorkspaceRoots roots = id -> base.resolve("ws" + id);
    history = new FlakyHistory(roots);
    fs = new WorkspaceFileSystemService(roots, null, history);
    bindings = new WorkspaceBindings(null, fs, history, null, "");
  }

  //region Helpers
  private Path root(final int ws) {
    return base.resolve("ws" + ws);
  }

  private static UploadedFile upload(final String name, final String content) {
    final var bytes = content.getBytes(StandardCharsets.UTF_8);
    final var part = (Part) Proxy.newProxyInstance(Part.class.getClassLoader(), new Class<?>[]{Part.class}, (p, m, a) ->
        switch (m.getName()) {
          case "getInputStream" -> new ByteArrayInputStream(bytes);
          case "getSubmittedFileName", "getName" -> name;
          case "getSize" -> (long) bytes.length;
          case "getContentType" -> "application/octet-stream";
          default -> null;
        });
    return new UploadedFile(part);
  }

  private HandlerResult save(final int ws, final String path, final String content, final String ifMatch) {
    final var p = Path.of(path);
    return bindings.handleFileUpload(ws, p, upload(p.getFileName().toString(), content), ifMatch == null, ifMatch, USER);
  }

  private String saveOk(final int ws, final String path, final String content) {
    final var result = save(ws, path, content, null);
    assertInstanceOf(HandlerResult.Success.class, result, () -> result.jsonResponse().toString());
    return ((HandlerResult.Success) result).etag().orElseThrow();
  }

  private static void assertSuccess(final HandlerResult result) {
    assertInstanceOf(HandlerResult.Success.class, result, () -> result.jsonResponse().toString());
  }

  private static void assertFailure(final HandlerResult result, final int status, final String type) {
    assertInstanceOf(HandlerResult.Failure.class, result, () -> result.jsonResponse().toString());
    assertEquals(status, result.status(), () -> result.jsonResponse().toString());
    if (type != null) assertEquals(type, result.jsonResponse().asJsonObject().getString("type"));
  }

  private Git git(final int ws) throws IOException {
    return Git.open(root(ws).toFile());
  }

  private void assertClean(final int ws) throws Exception {
    try (final var git = git(ws)) {
      assertEquals(Set.of(), WorkspaceHistory.dirtyPaths(git.status().call()), "workspace " + ws + " is dirty");
    }
  }

  private List<RevCommit> log(final int ws) throws Exception {
    try (final var git = git(ws)) {
      final var commits = new ArrayList<RevCommit>();
      git.log().call().forEach(commits::add);
      return commits;
    }
  }

  private RevCommit head(final int ws) throws Exception {
    return log(ws).getFirst();
  }

  private Set<String> headTree(final int ws) throws Exception {
    try (final var git = git(ws); final var tw = new TreeWalk(git.getRepository())) {
      tw.addTree(head(ws).getTree());
      tw.setRecursive(true);
      final var paths = new TreeSet<String>();
      while (tw.next()) paths.add(tw.getPathString());
      return paths;
    }
  }

  private List<DiffEntry> headDiff(final int ws) throws Exception {
    try (final var git = git(ws); final var df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
      df.setRepository(git.getRepository());
      df.setDetectRenames(true);
      final var headCommit = head(ws);
      return df.scan(headCommit.getParent(0).getTree(), headCommit.getTree());
    }
  }

  private String read(final int ws, final String path) throws IOException {
    return Files.readString(root(ws).resolve(path));
  }

  private static String sidecar(final String path) {
    final var p = Path.of(path);
    final var name = "." + p.getFileName() + ".meta.seqdev";
    return p.getParent() == null ? name : p.getParent().resolve(name).toString();
  }

  private void setReadOnly(final int ws, final String path, final boolean readOnly) throws Exception {
    history.mutate(ws, USER, "lock", () -> fs.updateMetadataKeys(
        ws, Path.of(path), new MetadataUpdates.Builder(USER).readOnly(readOnly).build(), MetadataMergeBehavior.deepMerge),
        r -> true);
  }

  private boolean isReadOnly(final int ws, final String path) throws Exception {
    return history.mutate(ws, USER, "check", () -> fs.isReadOnly(ws, Path.of(path)), r -> true);
  }

  /** Initialize a workspace's history (an empty directory creation is a no-op mutation). */
  private void init(final int ws) {
    assertSuccess(bindings.handleCreateDirectory(ws, Path.of("scratch"), USER));
  }
  //endregion

  @Nested
  class Save {
    @Test
    void saveCreatesACommitAndLeavesTheRepoClean() throws Exception {
      final var etag = saveOk(WS1, "seq/a.txt", "command A;");

      final var head = head(WS1);
      assertEquals("Update seq/a.txt", head.getFullMessage());
      assertEquals(USER, head.getAuthorIdent().getName());
      assertEquals(Set.of("seq/a.txt", "seq/.a.txt.meta.seqdev"), headTree(WS1));
      assertEquals(WorkspaceService.computeETag("command A;".getBytes(StandardCharsets.UTF_8)), etag);
      assertClean(WS1);
    }

    @Test
    void reSavingOnlyCommitsTheContentNotTheSidecar() throws Exception {
      saveOk(WS1, "a.txt", "one");
      saveOk(WS1, "a.txt", "two");
      assertEquals(List.of("a.txt"), headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
      assertClean(WS1);
    }

    @Test
    void identicalSaveCreatesNoCommit() throws Exception {
      saveOk(WS1, "a.txt", "same");
      final var before = log(WS1).size();
      saveOk(WS1, "a.txt", "same");
      assertEquals(before, log(WS1).size());
      assertClean(WS1);
    }

    @Test
    void etagChangesAndAStaleEtagIsRejectedBeforeAnyMutation() throws Exception {
      final var etag1 = saveOk(WS1, "a.txt", "v1");
      final var result2 = save(WS1, "a.txt", "v2", etag1);
      assertSuccess(result2);
      final var etag2 = ((HandlerResult.Success) result2).etag().orElseThrow();
      assertNotEquals(etag1, etag2);
      final var commits = log(WS1).size();

      final var stale = save(WS1, "a.txt", "v3", etag1);
      assertFailure(stale, 412, null);
      assertEquals(etag2, stale.jsonResponse().asJsonObject().getJsonObject("data").getString("currentETag"));
      assertEquals(USER, stale.jsonResponse().asJsonObject().getJsonObject("data").getString("lastEditedBy"));
      assertEquals("v2", read(WS1, "a.txt"));
      assertEquals(commits, log(WS1).size());
      assertClean(WS1);

      assertSuccess(save(WS1, "a.txt", "v4", "*")); // explicit overwrite still allowed
      assertEquals("v4", read(WS1, "a.txt"));
    }

    @Test
    void readOnlyFileIsLockedAndTheLockIsNotVersioned() throws Exception {
      saveOk(WS1, "a.txt", "v1");
      final var commits = log(WS1).size();
      setReadOnly(WS1, "a.txt", true);
      assertEquals(commits, log(WS1).size(), "readOnly is runtime state and must not create a commit");
      assertFalse(Files.readString(root(WS1).resolve(".a.txt.meta.seqdev")).contains("readOnly"));
      assertFailure(save(WS1, "a.txt", "v2", "*"), 423, "FILE_LOCKED");
      assertEquals("v1", read(WS1, "a.txt"));
      assertClean(WS1);
    }

    @Test
    void userMetadataIsVersionedAndLastEditedIsDerivedFromHistory() throws Exception {
      saveOk(WS1, "a.txt", "v1");
      final var savedAt = head(WS1).getAuthorIdent().getWhenAsInstant();
      history.mutate(WS1, "bob", "Update metadata for a.txt", () -> fs.updateMetadataKeys(
          WS1, Path.of("a.txt"),
          new MetadataUpdates.Builder("bob").user(Json.createObjectBuilder().add("status", "draft").build()).build(),
          MetadataMergeBehavior.deepMerge), r -> true);

      assertEquals(List.of(".a.txt.meta.seqdev"), headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
      final var info = fs.getLastEditInfo(WS1, Path.of("a.txt"));
      assertEquals(USER, info.lastEditedBy(), "a metadata-only change is not a content edit");
      assertEquals(savedAt.toString(), info.lastEditedAt());
      assertClean(WS1);
    }
  }

  @Nested
  class FailureSemantics {
    /** Deterministic injection at the mutation boundary: every kind of filesystem change is rolled back. */
    @Test
    void injectedCommitFailureRestoresThePreOperationState() throws Exception {
      saveOk(WS1, "keep.txt", "original");
      saveOk(WS1, "gone.txt", "will be deleted");
      Files.createDirectories(root(WS1).resolve("emptyDir")); // pre-existing empty directory must survive
      final var headBefore = head(WS1);
      final var stateBefore = Files.readAllBytes(WorkspaceState.file(root(WS1)));

      history.failCommitsIn.add(root(WS1));
      final var ex = assertThrows(WorkspaceHistory.WorkspaceHistoryException.class, () ->
          history.mutate(WS1, USER, "doomed", () -> {
            Files.writeString(root(WS1).resolve("keep.txt"), "modified");
            Files.delete(root(WS1).resolve("gone.txt"));
            Files.createDirectories(root(WS1).resolve("new/nested"));
            Files.writeString(root(WS1).resolve("new/nested/file.txt"), "new");
            Files.writeString(root(WS1).resolve("untracked.txt"), "new");
            Files.delete(root(WS1).resolve("emptyDir"));
            final var state = WorkspaceState.load(root(WS1));
            state.setReadOnly("keep.txt", true);
            state.save();
            return true;
          }, r -> true));
      assertEquals(WorkspaceHistory.Kind.HISTORY_NOT_RECORDED, ex.kind);

      assertEquals("original", read(WS1, "keep.txt"));
      assertEquals("will be deleted", read(WS1, "gone.txt"));
      assertFalse(Files.exists(root(WS1).resolve("new")), "directories created by the failed operation are removed");
      assertFalse(Files.exists(root(WS1).resolve("untracked.txt")));
      assertTrue(Files.isDirectory(root(WS1).resolve("emptyDir")), "directories removed by it are recreated");
      assertArrayEquals(stateBefore, Files.readAllBytes(WorkspaceState.file(root(WS1))));
      assertEquals(headBefore, head(WS1));
      assertClean(WS1);
    }

    @Test
    void aMutationThatThrowsIsRolledBackAndTheExceptionPropagates() throws Exception {
      saveOk(WS1, "a.txt", "original");
      final var thrown = assertThrows(IOException.class, () -> history.mutate(WS1, USER, "x", () -> {
        Files.writeString(root(WS1).resolve("a.txt"), "half-written");
        throw new IOException("disk full");
      }, r -> true));
      assertEquals("disk full", thrown.getMessage());
      assertEquals("original", read(WS1, "a.txt"));
      assertClean(WS1);
    }

    @Test
    void aFailureResultIsRolledBack() throws Exception {
      saveOk(WS1, "a.txt", "original");
      final var result = history.mutate(WS1, USER, "x", () -> {
        Files.writeString(root(WS1).resolve("a.txt"), "partial");
        return false;
      }, r -> r);
      assertFalse(result);
      assertEquals("original", read(WS1, "a.txt"));
      assertClean(WS1);
    }

    /** A real JGit failure (the branch ref is locked) takes the same path through the save handler. */
    @Test
    void realGitFailureDoesNotReportASuccessfulSave() throws Exception {
      saveOk(WS1, "a.txt", "original");
      final var headBefore = head(WS1);
      final var refLock = root(WS1).resolve(".git/refs/heads/main.lock");
      Files.writeString(refLock, "");

      assertFailure(save(WS1, "a.txt", "changed", "*"), 500, "WORKSPACE_HISTORY_ERROR");
      assertFailure(save(WS1, "b.txt", "new file", null), 500, "WORKSPACE_HISTORY_ERROR");
      assertEquals("original", read(WS1, "a.txt"));
      assertFalse(Files.exists(root(WS1).resolve("b.txt")));
      assertFalse(Files.exists(root(WS1).resolve(".b.txt.meta.seqdev")));
      assertEquals(headBefore, head(WS1));
      assertClean(WS1);

      Files.delete(refLock);
      saveOk(WS1, "a.txt", "changed");
      assertEquals("changed", read(WS1, "a.txt"));
      assertClean(WS1);
    }
  }

  @Nested
  class FileOperations {
    @Test
    void renameIsRecordedAsARename() throws Exception {
      saveOk(WS1, "a.txt", "content");
      assertSuccess(bindings.handleMove(Path.of("a.txt"), Path.of("b.txt"), WS1, WS1, false, USER));

      final var renames = headDiff(WS1).stream().filter(d -> d.getChangeType() == DiffEntry.ChangeType.RENAME).toList();
      assertTrue(renames.stream().anyMatch(d -> d.getOldPath().equals("a.txt") && d.getNewPath().equals("b.txt")), renames::toString);
      assertEquals(Set.of("b.txt", ".b.txt.meta.seqdev"), headTree(WS1));
      assertClean(WS1);
    }

    @Test
    void renameCarriesTheLock() throws Exception {
      saveOk(WS1, "dir/a.txt", "content");
      setReadOnly(WS1, "dir/a.txt", true);
      assertSuccess(bindings.handleMove(Path.of("dir"), Path.of("renamed"), WS1, WS1, false, USER));
      assertTrue(isReadOnly(WS1, "renamed/a.txt"));
      assertClean(WS1);
    }

    @Test
    void copyLeavesTheRepoClean() throws Exception {
      saveOk(WS1, "a.txt", "content");
      assertSuccess(bindings.handleCopy(Path.of("a.txt"), Path.of("copies/c.txt"), WS1, WS1, false, USER));
      assertEquals(Set.of("a.txt", ".a.txt.meta.seqdev", "copies/c.txt", "copies/.c.txt.meta.seqdev"), headTree(WS1));
      assertEquals("Copy a.txt -> copies/c.txt", head(WS1).getFullMessage());
      assertClean(WS1);
    }

    @Test
    void deleteLeavesTheRepoClean() throws Exception {
      saveOk(WS1, "a.txt", "content");
      saveOk(WS1, "b.txt", "content");
      assertSuccess(bindings.handleDelete(WS1, Path.of("a.txt"), USER));
      assertEquals(Set.of("b.txt", ".b.txt.meta.seqdev"), headTree(WS1));
      assertClean(WS1);
    }
  }

  @Nested
  class DirectoryOperations {
    @BeforeEach
    void populate() {
      saveOk(WS1, "d/x.txt", "x");
      saveOk(WS1, "d/sub/y.txt", "y");
    }

    @Test
    void directoryMoveIsOneCommitWithEveryFile() throws Exception {
      final var commits = log(WS1).size();
      assertSuccess(bindings.handleMove(Path.of("d"), Path.of("e"), WS1, WS1, false, USER));
      assertEquals(commits + 1, log(WS1).size());
      assertEquals(Set.of("e/x.txt", "e/.x.txt.meta.seqdev", "e/sub/y.txt", "e/sub/.y.txt.meta.seqdev"), headTree(WS1));
      final var diff = headDiff(WS1);
      assertEquals(4, diff.size());
      assertTrue(diff.stream().allMatch(d -> d.getChangeType() == DiffEntry.ChangeType.RENAME), diff::toString);
      assertClean(WS1);
    }

    @Test
    void directoryCopyLeavesTheRepoClean() throws Exception {
      assertSuccess(bindings.handleCopy(Path.of("d"), Path.of("e"), WS1, WS1, false, USER));
      assertTrue(headTree(WS1).containsAll(Set.of("d/x.txt", "d/sub/y.txt", "e/x.txt", "e/sub/y.txt", "e/sub/.y.txt.meta.seqdev")));
      assertEquals(4, headDiff(WS1).size());
      assertClean(WS1);
    }

    @Test
    void directoryDeleteLeavesTheRepoClean() throws Exception {
      assertSuccess(bindings.handleDelete(WS1, Path.of("d"), USER));
      assertEquals(Set.of(), headTree(WS1));
      assertEquals(4, headDiff(WS1).size());
      assertClean(WS1);
    }

    @Test
    void theWorkspaceRootCannotBeDeletedOrCopied() throws Exception {
      // The handlers cannot address the root by name; the service still refuses it outright.
      for (final var rootPath : List.of(Path.of(""), Path.of("."), Path.of("d/.."))) {
        assertThrows(WorkspaceFileOpException.class, () -> history.mutate(WS1, USER, "x",
            () -> fs.deleteDirectory(WS1, rootPath), r -> r));
        final var commits = new LinkedHashMap<Integer, String>();
        commits.put(WS2, "x");
        commits.put(WS1, "x");
        assertThrows(WorkspaceFileOpException.class, () -> history.mutate(commits, USER,
            () -> fs.copyDirectory(WS1, rootPath, WS2, Path.of("x")), r -> r));
      }
      assertTrue(Files.isDirectory(root(WS1).resolve(".git")));
      assertFalse(Files.exists(root(WS2).resolve("x")));
      assertClean(WS1);
    }
  }

  @Nested
  class CrossWorkspace {
    @BeforeEach
    void initBoth() {
      init(WS1);
      init(WS2);
    }

    @Test
    void copyUpdatesOnlyTheDestinationHistory() throws Exception {
      saveOk(WS1, "a.txt", "content");
      final var sourceHead = head(WS1);
      assertSuccess(bindings.handleCopy(Path.of("a.txt"), Path.of("in/a.txt"), WS1, WS2, false, USER));
      assertEquals(Set.of("in/a.txt", "in/.a.txt.meta.seqdev"), headTree(WS2));
      assertEquals(sourceHead, head(WS1));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void moveUpdatesBothHistories() throws Exception {
      saveOk(WS1, "a.txt", "content");
      assertSuccess(bindings.handleMove(Path.of("a.txt"), Path.of("a.txt"), WS1, WS2, false, USER));
      assertEquals(Set.of("a.txt", ".a.txt.meta.seqdev"), headTree(WS2));
      assertEquals(Set.of(), headTree(WS1));
      assertEquals("Move a.txt -> workspace 2:a.txt", head(WS1).getFullMessage());
      assertEquals("Move workspace 1:a.txt -> a.txt", head(WS2).getFullMessage());
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void directoryMoveUpdatesBothHistories() throws Exception {
      saveOk(WS1, "d/x.txt", "x");
      assertSuccess(bindings.handleMove(Path.of("d"), Path.of("d"), WS1, WS2, false, USER));
      assertEquals(Set.of("d/x.txt", "d/.x.txt.meta.seqdev"), headTree(WS2));
      assertEquals(Set.of(), headTree(WS1));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void sourceCommitFailureDegradesTheMoveToACopy() throws Exception {
      saveOk(WS1, "a.txt", "content");
      final var sourceHead = head(WS1);
      history.failCommitsIn.add(root(WS1));

      final var result = bindings.handleMove(Path.of("a.txt"), Path.of("b.txt"), WS1, WS2, false, USER);
      assertFailure(result, 500, "WORKSPACE_MUTATION_PARTIAL");
      final var message = result.jsonResponse().asJsonObject().getString("message");
      assertTrue(message.contains("degraded to a copy") && message.contains("a.txt") && message.contains("b.txt"), message);

      // Destination keeps the committed copy, with its metadata; the source is restored, with its metadata.
      assertEquals(Set.of("b.txt", ".b.txt.meta.seqdev"), headTree(WS2));
      assertEquals("content", read(WS2, "b.txt"));
      assertEquals("content", read(WS1, "a.txt"));
      assertTrue(Files.exists(root(WS1).resolve(".a.txt.meta.seqdev")));
      assertEquals(sourceHead, head(WS1));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void destinationCommitFailureRestoresBothWorkspaces() throws Exception {
      saveOk(WS1, "a.txt", "content");
      final var sourceHead = head(WS1);
      final var destHead = head(WS2);
      history.failCommitsIn.add(root(WS2));

      assertFailure(bindings.handleMove(Path.of("a.txt"), Path.of("b.txt"), WS1, WS2, false, USER), 500, "WORKSPACE_HISTORY_ERROR");
      assertEquals("content", read(WS1, "a.txt"));
      assertFalse(Files.exists(root(WS2).resolve("b.txt")));
      assertFalse(Files.exists(root(WS2).resolve(".b.txt.meta.seqdev")));
      assertEquals(sourceHead, head(WS1));
      assertEquals(destHead, head(WS2));
      assertClean(WS1);
      assertClean(WS2);
    }

    /** Opposite-direction cross-workspace moves would deadlock without a global lock order. */
    @Test
    void locksAreAcquiredInADeterministicOrder() throws Exception {
      for (int i = 0; i < 20; i++) {
        saveOk(WS1, "one" + i + ".txt", "1");
        saveOk(WS2, "two" + i + ".txt", "2");
      }
      final var pool = Executors.newFixedThreadPool(2);
      try {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
          final var a = pool.submit(() -> {
            for (int i = 0; i < 20; i++) assertSuccess(bindings.handleMove(Path.of("one" + i + ".txt"), Path.of("one" + i + ".txt"), WS1, WS2, false, USER));
          });
          final var b = pool.submit(() -> {
            for (int i = 0; i < 20; i++) assertSuccess(bindings.handleMove(Path.of("two" + i + ".txt"), Path.of("two" + i + ".txt"), WS2, WS1, false, USER));
          });
          a.get();
          b.get();
        });
      } finally {
        pool.shutdownNow();
      }
      assertEquals(20, headTree(WS1).stream().filter(p -> p.startsWith("two")).count());
      assertEquals(20, headTree(WS2).stream().filter(p -> p.startsWith("one")).count());
      assertClean(WS1);
      assertClean(WS2);
    }
  }

  @Nested
  class InternalPaths {
    @BeforeEach
    void initWs() {
      saveOk(WS1, "a.txt", "content");
    }

    @Test
    void readsOfGitInternalsAreRejected() {
      for (final var path : List.of(".git/HEAD", ".git/config", ".git/refs/heads/main", "foo/.git/config",
                                    ".GIT/HEAD", "a/../.git/HEAD", ".seqdev/state.json", ".gitignore")) {
        assertThrows(ReservedPathException.class, () -> fs.loadFile(WS1, Path.of(path)), path);
        assertThrows(ReservedPathException.class, () -> fs.checkFileExists(WS1, Path.of(path)), path);
      }
    }

    @Test
    void writesOfGitInternalsAreRejected() throws Exception {
      final var config = Files.readString(root(WS1).resolve(".git/config"));
      for (final var path : List.of(".git/config", ".git/hooks/post-commit", "foo/.git/HEAD", ".gitattributes", ".gitignore", ".seqdev/state.json")) {
        assertFailure(save(WS1, path, "evil", "*"), 400, "RESERVED_PATH");
      }
      assertFailure(bindings.handleCreateDirectory(WS1, Path.of("x/.git"), USER), 400, "RESERVED_PATH");
      assertEquals(config, Files.readString(root(WS1).resolve(".git/config")));
      assertFalse(Files.exists(root(WS1).resolve(".gitignore")));
      assertClean(WS1);
    }

    @Test
    void moveCopyAndDeleteInvolvingGitInternalsAreRejected() throws Exception {
      final var head = head(WS1);
      assertFailure(bindings.handleMove(Path.of(".git/HEAD"), Path.of("HEAD"), WS1, WS1, false, USER), 400, "RESERVED_PATH");
      assertFailure(bindings.handleMove(Path.of("a.txt"), Path.of(".git/a.txt"), WS1, WS1, true, USER), 400, "RESERVED_PATH");
      assertFailure(bindings.handleCopy(Path.of(".git"), Path.of("leak"), WS1, WS2, false, USER), 400, "RESERVED_PATH");
      assertFailure(bindings.handleCopy(Path.of("a.txt"), Path.of(".git/objects/x"), WS1, WS1, true, USER), 400, "RESERVED_PATH");
      assertFailure(bindings.handleDelete(WS1, Path.of(".git"), USER), 400, "RESERVED_PATH");
      assertFailure(bindings.handleDelete(WS1, Path.of(".seqdev/state.json"), USER), 400, "RESERVED_PATH");
      assertEquals(head, head(WS1));
      assertTrue(Files.isRegularFile(root(WS1).resolve(".git/HEAD")));
      assertFalse(Files.exists(root(WS2).resolve("leak")));
      assertClean(WS1);
    }

    @Test
    void listingsNeverIncludeInternalDirectories() throws IOException {
      final var listed = WorkspacePaths.walk(root(WS1), Integer.MAX_VALUE).stream()
                                       .map(p -> root(WS1).relativize(p).toString()).toList();
      assertTrue(listed.contains("a.txt"));
      assertTrue(listed.stream().noneMatch(p -> p.startsWith(".git") || p.startsWith(".seqdev")), listed::toString);
    }
  }

  @Nested
  class Concurrency {
    /** A second mutation cannot run between the first one's filesystem write and its commit. */
    @Test
    void mutationsDoNotInterleaveBetweenWriteAndCommit() throws Exception {
      init(WS1);
      final var firstWrote = new CountDownLatch(1);
      final var releaseFirst = new CountDownLatch(1);
      final var secondEntered = new AtomicBoolean(false);
      final var pool = Executors.newFixedThreadPool(2);
      try {
        final var first = pool.submit(() -> history.mutate(WS1, "first", "first", () -> {
          Files.writeString(root(WS1).resolve("a.txt"), "first");
          firstWrote.countDown();
          assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
          return true;
        }, r -> true));
        assertTrue(firstWrote.await(10, TimeUnit.SECONDS));
        final var second = pool.submit(() -> history.mutate(WS1, "second", "second", () -> {
          secondEntered.set(true);
          Files.writeString(root(WS1).resolve("a.txt"), "second");
          return true;
        }, r -> true));

        Thread.sleep(300);
        assertFalse(secondEntered.get(), "the second mutation started before the first was committed");
        releaseFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }

      // A valid serial order: first's commit, then second's on top of it.
      final var commits = log(WS1);
      assertEquals("second", commits.get(0).getFullMessage());
      assertEquals("first", commits.get(1).getFullMessage());
      assertEquals("second", read(WS1, "a.txt"));
      assertClean(WS1);
    }

    @Test
    void concurrentSavesAllLandInHistory() throws Exception {
      init(WS1);
      final var commitsBefore = log(WS1).size();
      final var pool = Executors.newFixedThreadPool(4);
      final var tasks = new ArrayList<java.util.concurrent.Future<?>>();
      for (int t = 0; t < 4; t++) {
        final var thread = t;
        tasks.add(pool.submit(() -> {
          for (int i = 0; i < 10; i++) saveOk(WS1, "shared.txt", "t" + thread + "-" + i);
        }));
      }
      for (final var task : tasks) task.get(60, TimeUnit.SECONDS);
      pool.shutdown();

      // Every save had distinct content, so every save is one commit (the first also adds the sidecar).
      assertEquals(commitsBefore + 40, log(WS1).size());
      try (final var git = git(WS1)) {
        final var committed = new String(
            git.getRepository().open(TreeWalk.forPath(git.getRepository(), "shared.txt", head(WS1).getTree()).getObjectId(0)).getBytes(),
            StandardCharsets.UTF_8);
        assertEquals(read(WS1, "shared.txt"), committed);
      }
      assertClean(WS1);
    }
  }

  @Nested
  class Migration {
    private void writeLegacyWorkspace() throws IOException {
      Files.createDirectories(root(WS1).resolve("seq"));
      Files.writeString(root(WS1).resolve("seq/a.txt"), "legacy A");
      Files.writeString(root(WS1).resolve("seq/.a.txt.meta.seqdev"), """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z",\
          "lastEditedBy":"dave","lastEditedAt":"2025-02-02T00:00:00Z","readOnly":true,"user":{"status":"final"}}""");
      Files.writeString(root(WS1).resolve("b.txt"), "legacy B");
    }

    @Test
    void aWorkspaceWithoutGitIsInitializedOnItsFirstMutation() throws Exception {
      writeLegacyWorkspace();
      init(WS1);

      final var commits = log(WS1);
      assertEquals(1, commits.size(), "creating an empty directory adds nothing beyond the baseline");
      assertEquals(WorkspaceHistory.INIT_MESSAGE, commits.getFirst().getFullMessage());
      assertEquals("PlanDev", commits.getFirst().getAuthorIdent().getName());
      assertEquals(Set.of("b.txt", "seq/a.txt", "seq/.a.txt.meta.seqdev"), headTree(WS1));
      assertClean(WS1);

      // Only versioned fields stay in the sidecar; readOnly and the pre-history lastEdited move to runtime state.
      final var sidecarJson = Files.readString(root(WS1).resolve("seq/.a.txt.meta.seqdev"));
      assertFalse(sidecarJson.contains("readOnly") || sidecarJson.contains("lastEdited"), sidecarJson);
      assertTrue(sidecarJson.contains("carol") && sidecarJson.contains("final"), sidecarJson);
      assertTrue(isReadOnly(WS1, "seq/a.txt"));
      final var info = fs.getLastEditInfo(WS1, Path.of("seq/a.txt"));
      assertEquals("dave", info.lastEditedBy());
      assertEquals("2025-02-02T00:00:00Z", info.lastEditedAt());
    }

    @Test
    void initializationIsIdempotent() throws Exception {
      writeLegacyWorkspace();
      init(WS1);
      final var head = head(WS1);
      init(WS1);
      assertSuccess(bindings.handleCreateDirectory(WS1, Path.of("another"), USER));
      assertEquals(head, head(WS1));
      assertEquals(1, log(WS1).size());
      assertClean(WS1);
    }

    @Test
    void aNewWorkspaceStartsWithAnEmptyBaseline() throws Exception {
      init(WS2);
      assertEquals(1, log(WS2).size());
      assertEquals(Set.of(), headTree(WS2));
      assertClean(WS2);
    }

    @Test
    void reservedFilesInALegacyWorkspaceFailClosed() throws Exception {
      writeLegacyWorkspace();
      Files.writeString(root(WS1).resolve("seq/.gitignore"), "*.txt\n");
      assertFailure(bindings.handleCreateDirectory(WS1, Path.of("x"), USER), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFalse(Files.exists(root(WS1).resolve(".git")));
      assertFalse(Files.exists(root(WS1).resolve("x")));
    }

    @Test
    void outOfBandChangesAreReconciledNotDiscarded() throws Exception {
      saveOk(WS1, "a.txt", "v1");
      Files.writeString(root(WS1).resolve("a.txt"), "edited on disk");
      Files.writeString(root(WS1).resolve("dropped-in.txt"), "new on disk");

      saveOk(WS1, "b.txt", "v1");
      final var commits = log(WS1);
      assertEquals("Update b.txt", commits.get(0).getFullMessage());
      assertEquals(WorkspaceHistory.RECONCILE_MESSAGE, commits.get(1).getFullMessage());
      assertEquals("PlanDev", commits.get(1).getAuthorIdent().getName());
      assertEquals("edited on disk", read(WS1, "a.txt"));
      assertTrue(headTree(WS1).contains("dropped-in.txt"));
      assertClean(WS1);
    }
  }

  @Test
  void everyFileSystemMutationRequiresTheLock() {
    assertThrows(IllegalStateException.class, () -> fs.createDirectory(WS1, Path.of("x")));
    assertThrows(IllegalStateException.class, () -> fs.deleteFile(WS1, Path.of("x")));
    assertThrows(IllegalStateException.class, () -> fs.saveFile(WS1, Path.of("x"), upload("x", "x"), USER));
    assertThrows(IllegalStateException.class, () -> fs.copyDirectory(WS1, Path.of("x"), WS2, Path.of("y")));
    final var commits = new LinkedHashMap<Integer, String>();
    commits.put(WS1, "only ws1");
    // Holding one workspace's lock does not authorize mutating another
    assertThrows(IllegalStateException.class, () -> history.mutate(commits, USER,
        () -> fs.moveDirectory(WS1, Path.of("a"), WS2, Path.of("b")), r -> true));
    assertFalse(Files.exists(root(WS1).resolve("x")));
  }
}
