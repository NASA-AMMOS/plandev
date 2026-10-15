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
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
  private WorkspaceRoots roots;
  private FlakyHistory history;
  private WorkspaceFileSystemService fs;
  private WorkspaceBindings bindings;

  /** A WorkspaceHistory whose commit step can be made to fail for chosen workspaces (deterministic injection). */
  static final class FlakyHistory extends WorkspaceHistory {
    final Set<Path> failCommitsIn = ConcurrentHashMap.newKeySet();

    /** Moves during drift healing fail if their destination is listed or ends with this (incident names vary). */
    final Set<Path> failMovesTo = ConcurrentHashMap.newKeySet();
    volatile String failMovesEndingWith;


    FlakyHistory(final WorkspaceRoots roots) {
      super(roots);
    }

    @Override
    protected void move(final Path from, final Path to) throws IOException {
      if (failMovesTo.contains(to) || (failMovesEndingWith != null && to.endsWith(failMovesEndingWith))) {
        throw new IOException("injected move failure: " + to);
      }
      super.move(from, to);
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
    roots = id -> base.resolve("ws" + id);
    restartServer();
  }

  /** Recreate all in-memory coordination state while keeping the workspace disks, as a process restart would. */
  private void restartServer() {
    history = new FlakyHistory(roots);
    fs = new WorkspaceFileSystemService(roots, null, history);
    bindings = new WorkspaceBindings(null, fs, history, null, "");
  }

  //region Helpers
  private List<Path> incidents(final int ws) throws IOException {
    final var dir = WorkspaceHistory.recoveryDir(root(ws));
    if (!Files.isDirectory(dir)) return List.of();
    try (final var entries = Files.list(dir)) {
      return entries.sorted().toList();
    }
  }

  private Path onlyIncident(final int ws) throws IOException {
    final var all = incidents(ws);
    assertEquals(1, all.size(), all::toString);
    return all.getFirst();
  }

  private static JsonObject incidentJson(final Path incident) throws IOException {
    try (final var reader = Json.createReader(Files.newBufferedReader(incident.resolve("incident.json")))) {
      return reader.readObject();
    }
  }

  private static java.util.Map<String, String> changesIn(final Path incident) throws IOException {
    final var changes = new java.util.TreeMap<String, String>();
    for (final var p : incidentJson(incident).getJsonArray("paths").getValuesAs(JsonObject.class)) {
      changes.put(p.getString("path"), p.getString("change"));
    }
    return changes;
  }

  private static String preserved(final Path incident, final String path) throws IOException {
    return Files.readString(incident.resolve("files").resolve(path));
  }

  private Path root(final int ws) {
    return base.resolve("ws" + ws);
  }

  private Path mutationMarker(final int ws) {
    return root(ws).resolve(".git").resolve(WorkspaceHistory.MUTATION_MARKER);
  }

  private Path adoptionMarker(final int ws) {
    return root(ws).resolve(".seqdev").resolve(WorkspaceHistory.ADOPTION_MARKER);
  }

  private static void createMarker(final Path marker) throws IOException {
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, "interrupted");
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

  private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

  private void deleteSidecar(final int ws, final String path) throws Exception {
    history.mutate(ws, USER, "drop metadata", () -> fs.deleteMetadataFile(ws, Path.of(path)), r -> r);
  }

  private void setUserMetadata(final int ws, final String path, final String status) throws Exception {
    history.mutate(ws, USER, "meta", () -> fs.updateMetadataKeys(
        ws, Path.of(path),
        new MetadataUpdates.Builder(USER).user(Json.createObjectBuilder().add("status", status).build()).build(),
        MetadataMergeBehavior.deepMerge), r -> true);
  }

  private JsonObject metadata(final int ws, final String path) throws Exception {
    try (final var in = fs.loadMetadataFile(ws, Path.of(path)).readingStream();
         final var reader = Json.createReader(in)) {
      return reader.readObject();
    }
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
    void readOnlyFileIsLockedAndTheLockIsStoredInItsSidecar() throws Exception {
      saveOk(WS1, "a.txt", "v1");
      final var commits = log(WS1).size();
      setReadOnly(WS1, "a.txt", true);
      assertEquals(commits + 1, log(WS1).size());
      assertEquals(List.of(".a.txt.meta.seqdev"), headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
      assertTrue(Files.readString(root(WS1).resolve(".a.txt.meta.seqdev")).contains("readOnly"));
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
  class LastEdits {
    @Test
    void resolvesOnlyRequestedContentPathsAndReusesPerPathCache() throws Exception {
      saveOk(WS1, "a.txt", "a1");
      saveOk(WS1, "b.txt", "b1");

      final var onlyA = history.lastEdits(root(WS1), Set.of("a.txt"));
      assertEquals(Set.of("a.txt"), onlyA.keySet());
      assertEquals(USER, onlyA.get("a.txt").by());
      final var originalB = history.lastEdit(root(WS1), "b.txt").orElseThrow();

      history.mutate(WS1, "bob", "Update a.txt", () -> {
        Files.writeString(root(WS1).resolve("a.txt"), "a2");
        return true;
      }, result -> result);

      final var both = history.lastEdits(root(WS1), Set.of("a.txt", "b.txt"));
      assertEquals("bob", both.get("a.txt").by());
      assertEquals(originalB, both.get("b.txt"));

      assertSuccess(bindings.handleDelete(WS1, Path.of("b.txt"), USER));
      assertTrue(history.lastEdit(root(WS1), "b.txt").isEmpty());
      assertTrue(history.lastEdit(root(WS1), ".a.txt.meta.seqdev").isEmpty());
      assertClean(WS1);
    }

    @Test
    void resolvesWorkspaceRelativePathsBelowTheRoot() throws Exception {
      saveOk(WS1, "one/a.txt", "A");
      saveOk(WS1, "two/b.txt", "B");

      final var edits = history.lastEdits(root(WS1), Set.of("one/a.txt"));

      assertEquals(Set.of("one/a.txt"), edits.keySet());
      assertEquals(USER, edits.get("one/a.txt").by());
      assertClean(WS1);
    }
  }

  @Nested
  class FailureSemantics {
    /**
     * Deterministic injection at the mutation boundary: every kind of file change is rolled back. Empty directories
     * are not versioned state, so the layout of empty directories is not part of what rollback guarantees.
     */
    @Test
    void injectedCommitFailureRestoresFileStateWithoutPruningEmptyDirectories() throws Exception {
      saveOk(WS1, "keep.txt", "original");
      saveOk(WS1, "gone.txt", "will be deleted");
      Files.createDirectories(root(WS1).resolve("existing-empty"));
      final var headBefore = head(WS1);
      final var sidecarBefore = Files.readAllBytes(root(WS1).resolve(".keep.txt.meta.seqdev"));

      history.failCommitsIn.add(root(WS1));
      final var ex = assertThrows(WorkspaceHistory.WorkspaceHistoryException.class, () ->
          history.mutate(WS1, USER, "doomed", () -> {
            Files.writeString(root(WS1).resolve("keep.txt"), "modified");
            Files.delete(root(WS1).resolve("gone.txt"));
            Files.createDirectories(root(WS1).resolve("new/nested"));
            Files.writeString(root(WS1).resolve("new/nested/file.txt"), "new");
            Files.writeString(root(WS1).resolve("existing-empty/file.txt"), "new");
            Files.writeString(root(WS1).resolve("untracked.txt"), "new");
            Files.writeString(root(WS1).resolve(".keep.txt.meta.seqdev"), "{\"readOnly\":true}");
            return true;
          }, r -> true));
      assertEquals(WorkspaceHistory.Kind.HISTORY_NOT_RECORDED, ex.kind);

      assertEquals("original", read(WS1, "keep.txt"));
      assertEquals("will be deleted", read(WS1, "gone.txt"));
      assertTrue(Files.isDirectory(root(WS1).resolve("new/nested")),
                 "rollback leaves directories it cannot prove were created by the failed operation");
      assertTrue(Files.isDirectory(root(WS1).resolve("existing-empty")),
                 "rollback preserves a directory that existed before the failed operation");
      assertFalse(Files.exists(root(WS1).resolve("new/nested/file.txt")));
      assertFalse(Files.exists(root(WS1).resolve("existing-empty/file.txt")));
      assertFalse(Files.exists(root(WS1).resolve("untracked.txt")));
      assertArrayEquals(sidecarBefore, Files.readAllBytes(root(WS1).resolve(".keep.txt.meta.seqdev")));
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

    /** An unexplained Git lock is rejected before file bytes change; only a recovery marker authorizes its removal. */
    @Test
    void gitLockWithoutARecoveryMarkerIsLeftUntouched() throws Exception {
      saveOk(WS1, "a.txt", "original");
      final var headBefore = head(WS1);
      final var refLock = root(WS1).resolve(".git/refs/heads/main.lock");
      Files.writeString(refLock, "");

      assertFailure(save(WS1, "a.txt", "changed", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFailure(save(WS1, "b.txt", "new file", null), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertEquals("original", read(WS1, "a.txt"));
      assertFalse(Files.exists(root(WS1).resolve("b.txt")));
      assertFalse(Files.exists(root(WS1).resolve(".b.txt.meta.seqdev")));
      assertTrue(Files.exists(refLock));
      assertEquals(headBefore, head(WS1));
      assertClean(WS1);
      try (final var internal = Files.list(root(WS1).resolve(".seqdev"))) {
        assertEquals(List.of(), internal.map(p -> p.getFileName().toString()).toList(), "no upload temp files left");
      }

      Files.delete(refLock);
      saveOk(WS1, "a.txt", "changed");
      assertEquals("changed", read(WS1, "a.txt"));
      assertClean(WS1);
    }
  }

  @Nested
  class CrashRecovery {
    @Test
    void stagedAndWorktreeResiduePreservesCurrentBytesAndBothStates() throws Exception {
      saveOk(WS1, "a.txt", "original");
      saveOk(WS1, "gone.txt", "original gone");
      final var before = head(WS1);
      createMarker(mutationMarker(WS1));
      Files.writeString(root(WS1).resolve("a.txt"), "staged version");
      Files.writeString(root(WS1).resolve("new.txt"), "staged new");
      Files.delete(root(WS1).resolve("gone.txt"));
      try (final var git = git(WS1)) {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
      }
      Files.writeString(root(WS1).resolve("a.txt"), "current worktree bytes");
      Files.delete(root(WS1).resolve("new.txt"));
      Files.writeString(root(WS1).resolve("gone.txt"), "recreated after staged deletion");
      restartServer();

      assertTrue(history.mutate(WS1, USER, "probe", () -> {
        assertClean(WS1);
        assertEquals(before, head(WS1));
        assertEquals("original", read(WS1, "a.txt"));
        assertEquals("original gone", read(WS1, "gone.txt"));
        assertFalse(Files.exists(root(WS1).resolve("new.txt")));
        return true;
      }, r -> r));
      final var incident = onlyIncident(WS1);
      assertEquals("current worktree bytes", preserved(incident, "a.txt"));
      assertEquals("recreated after staged deletion", preserved(incident, "gone.txt"));
      assertFalse(Files.exists(incident.resolve("files/new.txt")));
      final var paths = incidentJson(incident).getJsonArray("paths").getValuesAs(JsonObject.class);
      final var changed = paths.stream().filter(p -> p.getString("path").equals("a.txt")).findFirst().orElseThrow();
      assertEquals("modified", changed.getString("indexChange"));
      assertEquals("modified", changed.getString("worktreeChange"));
      final var missing = paths.stream().filter(p -> p.getString("path").equals("new.txt")).findFirst().orElseThrow();
      assertEquals("added", missing.getString("indexChange"));
      assertEquals("deleted", missing.getString("worktreeChange"));
      assertFalse(missing.getBoolean("present"));
      final var removed = paths.stream().filter(p -> p.getString("path").equals("gone.txt")).findFirst().orElseThrow();
      assertEquals("deleted", removed.getString("indexChange"));
      assertTrue(removed.getBoolean("present"));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertEquals(before, head(WS1));
    }

    @Test
    void preservationFailureKeepsTheCrashMarkerAndDirtyBytesForRetry() throws Exception {
      saveOk(WS1, "a.txt", "original");
      createMarker(mutationMarker(WS1));
      Files.writeString(root(WS1).resolve("a.txt"), "unfinished bytes");
      try (final var git = git(WS1)) {
        git.add().addFilepattern("a.txt").call();
      }
      restartServer();
      history.failMovesEndingWith = "files/a.txt";
      assertFailure(save(WS1, "b.txt", "next request", null), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(Files.exists(mutationMarker(WS1)));
      assertEquals("unfinished bytes", read(WS1, "a.txt"));
      try (final var git = git(WS1)) {
        assertEquals(Set.of("a.txt"), git.status().call().getChanged());
      }
      assertEquals(List.of(), incidents(WS1));
      history.failMovesEndingWith = null;
      saveOk(WS1, "b.txt", "next request");
      assertEquals("unfinished bytes", preserved(onlyIncident(WS1), "a.txt"));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertClean(WS1);
    }

    @Test
    void aConfigLockLeftBeforeMutationMarkingIsRemoved() throws Exception {
      saveOk(WS1, "a.txt", "original");
      final var configLock = root(WS1).resolve(".git/config.lock");
      Files.writeString(configLock, "stale");

      restartServer();
      saveOk(WS1, "b.txt", "next request");

      assertFalse(Files.exists(configLock));
      assertEquals("original", read(WS1, "a.txt"));
      assertClean(WS1);
    }

    @Test
    void anUncommittedMutationIsRolledBackBeforeTheNextWrite() throws Exception {
      saveOk(WS1, "a.txt", "original");
      createMarker(mutationMarker(WS1));
      Files.writeString(root(WS1).resolve("a.txt"), "partial");
      Files.writeString(root(WS1).resolve("new.txt"), "partial new file");
      try (final var git = git(WS1)) {
        git.add().addFilepattern(".").call();
      }
      final var indexLock = root(WS1).resolve(".git/index.lock");
      Files.writeString(indexLock, "stale");

      restartServer();
      saveOk(WS1, "b.txt", "next request");

      assertEquals("original", read(WS1, "a.txt"));
      assertFalse(Files.exists(root(WS1).resolve("new.txt")));
      assertFalse(Files.exists(indexLock));
      assertFalse(Files.exists(mutationMarker(WS1)));
      final var incident = onlyIncident(WS1);
      assertEquals("partial", preserved(incident, "a.txt"));
      assertEquals("partial new file", preserved(incident, "new.txt"));
      assertEquals("interrupted mutation recovery", incidentJson(incident).getString("reason"));
      assertClean(WS1);
    }

    @Test
    void aLandedCommitIsKeptAndItsUnfinishedWorkingTreeIsCleaned() throws Exception {
      saveOk(WS1, "a.txt", "original");
      createMarker(mutationMarker(WS1));
      Files.writeString(root(WS1).resolve("a.txt"), "committed");
      try (final var git = git(WS1)) {
        final var ident = new PersonIdent("bob", "");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("landed before crash").setAuthor(ident).setCommitter(ident).setSign(false).call();
      }
      Files.writeString(root(WS1).resolve("a.txt"), "unfinished later bytes");
      Files.writeString(root(WS1).resolve("untracked.txt"), "unfinished");
      final var landedHead = head(WS1);

      restartServer();
      saveOk(WS1, "b.txt", "next request");

      assertEquals("committed", read(WS1, "a.txt"));
      assertFalse(Files.exists(root(WS1).resolve("untracked.txt")));
      assertEquals(landedHead, log(WS1).get(1));
      final var incident = onlyIncident(WS1);
      assertEquals("unfinished later bytes", preserved(incident, "a.txt"));
      assertEquals("unfinished", preserved(incident, "untracked.txt"));
      assertEquals(landedHead.name(), incidentJson(incident).getString("head"));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertClean(WS1);
    }

    @Test
    void aCrossWorkspaceCrashAfterTheDestinationCommitRecoversAsACopy() throws Exception {
      init(WS1);
      init(WS2);
      saveOk(WS1, "a.txt", "content");
      final var sourceHead = head(WS1);
      createMarker(mutationMarker(WS1));
      createMarker(mutationMarker(WS2));

      Files.move(root(WS1).resolve("a.txt"), root(WS2).resolve("b.txt"));
      Files.move(root(WS1).resolve(".a.txt.meta.seqdev"), root(WS2).resolve(".b.txt.meta.seqdev"));
      try (final var git = git(WS2)) {
        final var ident = new PersonIdent(USER, "");
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        git.commit().setMessage("destination landed").setAuthor(ident).setCommitter(ident).setSign(false).call();
      }
      final var destinationHead = head(WS2);

      restartServer();
      final var both = new LinkedHashMap<Integer, String>();
      both.put(WS2, "probe destination");
      both.put(WS1, "probe source");
      assertFalse(history.mutate(both, USER, () -> false, result -> result));

      assertEquals("content", read(WS1, "a.txt"));
      assertEquals("content", read(WS2, "b.txt"));
      assertEquals(sourceHead, head(WS1));
      assertEquals(destinationHead, head(WS2));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertFalse(Files.exists(mutationMarker(WS2)));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void anInterruptedAdoptionRemovesItsLockAndResumes() throws Exception {
      Files.writeString(root(WS1).resolve("legacy.txt"), "legacy");
      createMarker(adoptionMarker(WS1));
      Git.init().setDirectory(root(WS1).toFile()).setInitialBranch(WorkspaceHistory.BRANCH).call().close();
      final var indexLock = root(WS1).resolve(".git/index.lock");
      Files.writeString(indexLock, "stale");

      restartServer();
      init(WS1);

      assertTrue(history.isManaged(root(WS1)));
      assertTrue(headTree(WS1).contains("legacy.txt"));
      assertFalse(Files.exists(indexLock));
      assertFalse(Files.exists(adoptionMarker(WS1)));
      assertClean(WS1);
    }

    @Test
    void anAdoptionInterruptedAfterCreatingTheObjectDatabaseRestartsInitialization() throws Exception {
      Files.writeString(root(WS1).resolve("legacy.txt"), "legacy");
      createMarker(adoptionMarker(WS1));
      final var partialGit = root(WS1).resolve(".git");
      Files.createDirectories(partialGit.resolve("objects"));
      Files.writeString(partialGit.resolve("unfinished"), "partial initialization");

      restartServer();
      init(WS1);

      assertTrue(history.isManaged(root(WS1)));
      assertTrue(headTree(WS1).contains("legacy.txt"));
      assertFalse(Files.exists(partialGit.resolve("unfinished")));
      assertFalse(Files.exists(adoptionMarker(WS1)));
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
            () -> fs.copyDirectory(WS1, rootPath, WS2, Path.of("x"), USER), r -> r));
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
    @Test
    void nestedWorkspaceLockAcquisitionIsRejected() throws Exception {
      init(WS1);
      init(WS2);

      final var nestedMutation = assertThrows(IllegalStateException.class, () ->
          history.mutate(WS1, USER, "outer", () ->
              history.mutate(WS2, USER, "inner", () -> true, result -> result), result -> result));
      assertTrue(nestedMutation.getMessage().contains("cannot be nested"));

      assertThrows(IllegalStateException.class, () -> history.withLock(WS1, () ->
          history.mutate(WS2, USER, "inner", () -> true, result -> result)));
      assertClean(WS1);
      assertClean(WS2);
    }

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
  class WorkspaceInitialization {
    @Test
    void newWorkspaceGetsManagedHistoryBeforeItsFirstMutation() throws Exception {
      final var newRoot = Files.createDirectory(base.resolve("new"));

      history.initializeNewWorkspace(newRoot);

      assertTrue(history.isManaged(newRoot));
      try (final var git = Git.open(newRoot.toFile())) {
        final var commits = new ArrayList<RevCommit>();
        git.log().call().forEach(commits::add);
        assertEquals(1, commits.size());
        assertEquals(WorkspaceHistory.INIT_MESSAGE, commits.getFirst().getFullMessage());
        assertEquals(Set.of(), WorkspaceHistory.dirtyPaths(git.status().call()));
      }
    }

    @Test
    void newWorkspaceInitializationDoesNotAdoptExistingFiles() throws Exception {
      final var newRoot = Files.createDirectory(base.resolve("not-new"));
      Files.writeString(newRoot.resolve("existing.txt"), "existing");

      assertThrows(IOException.class, () -> history.initializeNewWorkspace(newRoot));

      assertFalse(Files.exists(newRoot.resolve(WorkspacePaths.GIT_DIR)));
      assertEquals("existing", Files.readString(newRoot.resolve("existing.txt")));
    }

    @Test
    void failedInitializationDoesNotMarkTheWorkspaceManaged() throws Exception {
      final var newRoot = Files.createDirectory(base.resolve("failed"));
      history.failCommitsIn.add(newRoot.toRealPath());

      assertThrows(GitAPIException.class, () -> history.initializeNewWorkspace(newRoot));

      assertFalse(history.isManaged(newRoot));
      try (final var git = Git.open(newRoot.toFile())) {
        assertNull(git.getRepository().resolve("HEAD"));
      }
    }
  }

  @Nested
  class LegacyWorkspaceAdoption {
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
      assertTrue(history.isManaged(root(WS1)));
      try (final var git = git(WS1)) {
        assertEquals("/dev/null", git.getRepository().getConfig().getString("core", null, "attributesFile"),
                     "global gitattributes must not apply to workspaces");
      }

      // Legacy metadata stays with its content file. The baseline commit does not replace its real last-edit data.
      final var sidecarJson = Files.readString(root(WS1).resolve("seq/.a.txt.meta.seqdev"));
      assertTrue(sidecarJson.contains("readOnly") && sidecarJson.contains("lastEdited"), sidecarJson);
      assertTrue(sidecarJson.contains("carol") && sidecarJson.contains("dave") && sidecarJson.contains("final"), sidecarJson);
      assertTrue(isReadOnly(WS1, "seq/a.txt"));
      final var info = fs.getLastEditInfo(WS1, Path.of("seq/a.txt"));
      assertEquals("dave", info.lastEditedBy());
      assertEquals("2025-02-02T00:00:00Z", info.lastEditedAt());
    }

    @Test
    void lockScanIgnoresMetadataSuffixWithoutAContentName() throws Exception {
      writeLegacyWorkspace();
      Files.writeString(root(WS1).resolve("seq/.meta.seqdev"), "{\"readOnly\":true}");
      init(WS1);

      final var readOnly = history.mutate(WS1, USER, "check", () ->
          fs.getReadOnlyFiles(WS1, Path.of("seq")), ignored -> true);

      assertEquals(List.of(root(WS1).resolve("seq/a.txt")), readOnly);
      assertClean(WS1);
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
    void metadataOnlyUpdatesPreserveLegacyLastEditAndReadOnly() throws Exception {
      writeLegacyWorkspace();
      init(WS1);

      setUserMetadata(WS1, "seq/a.txt", "approved");

      final var sidecar = metadata(WS1, "seq/a.txt");
      assertTrue(sidecar.getBoolean("readOnly"));
      assertEquals("approved", sidecar.getJsonObject("user").getString("status"));
      assertEquals("dave", sidecar.getString("lastEditedBy"));
      assertEquals("2025-02-02T00:00:00Z", sidecar.getString("lastEditedAt"));
      assertClean(WS1);
    }

    @Test
    void contentMutationMakesGitHistoryAuthoritative() throws Exception {
      writeLegacyWorkspace();
      init(WS1);
      setReadOnly(WS1, "seq/a.txt", false);
      assertEquals("dave", fs.getLastEditInfo(WS1, Path.of("seq/a.txt")).lastEditedBy(),
                   "changing metadata is not a content edit");

      saveOk(WS1, "seq/a.txt", "changed");

      assertEquals(USER, fs.getLastEditInfo(WS1, Path.of("seq/a.txt")).lastEditedBy());
      assertClean(WS1);
    }
  }

  /** Managed Git structure must remain trusted before drift can be healed. */
  @Nested
  class Trust {
    private static final String INCONSISTENT = "WORKSPACE_REPOSITORY_INCONSISTENT";

    @BeforeEach
    void initWs() {
      saveOk(WS1, "a.txt", "v1");
    }

    /** Asserts a save is refused and changes nothing: no file, no commit. */
    private String assertRefused() throws Exception {
      final var commits = log(WS1).size();
      final var result = save(WS1, "b.txt", "v1", null);
      assertFailure(result, 500, INCONSISTENT);
      assertFalse(Files.exists(root(WS1).resolve("b.txt")));
      assertEquals(commits, log(WS1).size());
      return result.jsonResponse().asJsonObject().getString("message");
    }

    @Test
    void detachedHeadIsRejected() throws Exception {
      try (final var git = git(WS1)) {
        git.checkout().setName(head(WS1).name()).call();
      }
      assertTrue(assertRefused().contains("detached"));
    }

    @Test
    void anotherBranchIsRejected() throws Exception {
      try (final var git = git(WS1)) {
        git.checkout().setCreateBranch(true).setName("other").call();
      }
      assertTrue(assertRefused().contains("'other'"));
    }

    @Test
    void inProgressGitOperationsAreRejected() throws Exception {
      final var head = head(WS1).name() + "\n";
      for (final var marker : List.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "rebase-merge/head-name")) {
        final var file = root(WS1).resolve(".git").resolve(marker);
        Files.createDirectories(file.getParent());
        Files.writeString(file, head);
        assertTrue(assertRefused().contains("in progress"), marker);
        Files.delete(file);
      }
      Files.delete(root(WS1).resolve(".git/rebase-merge"));
      saveOk(WS1, "b.txt", "v1");
    }

    @Test
    void internalDirectoryHasNoRequiredPersistentSchema() throws Exception {
      final var arbitrary = root(WS1).resolve(".seqdev/state.json");
      Files.writeString(arbitrary, "no persistent schema");
      saveOk(WS1, "b.txt", "v1");
      assertEquals("no persistent schema", Files.readString(arbitrary));
      assertClean(WS1);
    }

    @Test
    void staleTempFilesAreRemovedAndNothingElseIs() throws Exception {
      final var seqdev = root(WS1).resolve(".seqdev");
      final var stale = seqdev.resolve("tmp-" + java.util.UUID.randomUUID());
      Files.writeString(stale, "left by a crash");
      final var others = List.of(seqdev.resolve("tmp-notauuid"), seqdev.resolve("notes.tmp"));
      for (final var other : others) Files.writeString(other, "not ours");

      saveOk(WS1, "b.txt", "v1");
      assertFalse(Files.exists(stale));
      others.forEach(o -> assertTrue(Files.exists(o), o::toString));
      assertClean(WS1);
    }

    @Test
    void anAdoptionThatDidNotFinishIsRedoneNotRejected() throws Exception {
      try (final var git = git(WS1)) {
        final var cfg = git.getRepository().getConfig();
        cfg.unset("plandev", null, "managed");
        cfg.save();
      }
      Files.writeString(root(WS1).resolve("a.txt"), "written before the crash");
      saveOk(WS1, "b.txt", "v1");
      assertEquals(WorkspaceHistory.ADOPT_MESSAGE, log(WS1).get(1).getFullMessage());
      assertTrue(history.isManaged(root(WS1)));
      assertClean(WS1);
    }
  }

  /** Recovery always heals the whole workspace, including drift unrelated to the upcoming mutation. */
  @Nested
  class Drift {
    @BeforeEach
    void initWs() {
      for (final var path : List.of("a.txt", "b.txt", "gone.txt", "d/x.txt")) saveOk(WS1, path, "v1");
    }

    private void assertFailedWith(final HandlerResult result, final String message) {
      assertFailure(result, 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(result.jsonResponse().asJsonObject().getString("message").contains(message),
                 () -> result.jsonResponse().toString());
    }

    @Test
    void allDriftIsPreservedAndCleanBeforeTheMutationStarts() throws Exception {
      final var before = head(WS1);
      Files.writeString(root(WS1).resolve("a.txt"), "external modified bytes");
      Files.createDirectories(root(WS1).resolve("unrelated/nested"));
      Files.writeString(root(WS1).resolve("unrelated/nested/new.txt"), "external untracked bytes");
      Files.delete(root(WS1).resolve("gone.txt"));

      assertTrue(history.mutate(WS1, USER, "save b", () -> {
        assertClean(WS1);
        assertEquals(before, head(WS1));
        assertEquals("v1", read(WS1, "a.txt"));
        assertEquals("v1", read(WS1, "gone.txt"));
        assertFalse(Files.exists(root(WS1).resolve("unrelated/nested/new.txt")));
        Files.writeString(root(WS1).resolve("b.txt"), "intended mutation");
        return true;
      }, r -> r));

      final var incident = onlyIncident(WS1);
      final var json = incidentJson(incident);
      assertEquals(1, json.getInt("version"));
      java.time.Instant.parse(json.getString("timestamp"));
      assertEquals(before.name(), json.getString("head"));
      assertEquals("out-of-band filesystem change", json.getString("reason"));
      assertTrue(incident.getFileName().toString().matches("[0-9]{8}T[0-9]{9}Z-[0-9a-f-]{36}"));
      assertEquals(java.util.Map.of("a.txt", "modified", "gone.txt", "deleted", "unrelated/nested/new.txt", "untracked"),
                   changesIn(incident));
      assertEquals("external modified bytes", preserved(incident, "a.txt"));
      assertEquals("external untracked bytes", preserved(incident, "unrelated/nested/new.txt"));
      assertFalse(Files.exists(incident.resolve("files/gone.txt")));
      assertEquals(List.of("b.txt"), headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
      assertClean(WS1);

      // Check every reachable commit, not just HEAD: preserved bytes must never be staged by git add -A.
      try (final var git = git(WS1)) {
        for (final var commit : log(WS1)) {
          try (final var tree = new TreeWalk(git.getRepository())) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
              assertFalse(tree.getPathString().startsWith(".seqdev/"));
              final var bytes = new String(git.getRepository().open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
              assertFalse(bytes.startsWith("external "), tree.getPathString());
            }
          }
        }
      }
    }

    @Test
    void modifiedTargetIsPreservedBeforeAnOrdinarySave() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "external bytes");
      saveOk(WS1, "a.txt", "intended bytes");
      assertEquals("intended bytes", read(WS1, "a.txt"));
      assertEquals("external bytes", preserved(onlyIncident(WS1), "a.txt"));
      assertClean(WS1);
    }

    @Test
    void healingDoesNotChangeWholeWorkspaceRollback() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "external bytes");
      assertFalse(history.mutate(WS1, USER, "failing mutation", () -> {
        assertClean(WS1);
        Files.writeString(root(WS1).resolve("b.txt"), "partial mutation");
        Files.writeString(root(WS1).resolve("created.txt"), "partial new file");
        return false;
      }, r -> r));
      assertEquals("v1", read(WS1, "a.txt"));
      assertEquals("v1", read(WS1, "b.txt"));
      assertFalse(Files.exists(root(WS1).resolve("created.txt")));
      assertEquals("external bytes", preserved(onlyIncident(WS1), "a.txt"));
      assertClean(WS1);
    }

    @Test
    void symlinksFailClosedBeforeAnyBytesAreMoved() throws Exception {
      final var outside = tmp.resolve("outside.txt");
      Files.writeString(outside, "outside bytes");
      Files.writeString(root(WS1).resolve("a.txt"), "external bytes");
      Files.delete(root(WS1).resolve("d/x.txt"));
      Files.delete(root(WS1).resolve("d/.x.txt.meta.seqdev"));
      Files.delete(root(WS1).resolve("d"));
      Files.createSymbolicLink(root(WS1).resolve("d"), tmp);
      final var before = head(WS1);

      assertFailedWith(save(WS1, "b.txt", "intended bytes", "*"), "could not be preserved");
      assertEquals(before, head(WS1));
      assertEquals("external bytes", read(WS1, "a.txt"));
      assertEquals("outside bytes", Files.readString(outside));
      assertTrue(Files.isSymbolicLink(root(WS1).resolve("d")));
      assertEquals(List.of(), incidents(WS1));
    }

    @Test
    void aSymlinkedRecoveryDirectoryIsNeverFollowed() throws Exception {
      final var outside = Files.createDirectory(tmp.resolve("outside"));
      Files.createSymbolicLink(WorkspaceHistory.recoveryDir(root(WS1)), outside);
      Files.writeString(root(WS1).resolve("a.txt"), "external bytes");
      assertFailedWith(save(WS1, "b.txt", "intended bytes", "*"), "could not be preserved");
      assertEquals("external bytes", read(WS1, "a.txt"));
      try (final var entries = Files.list(outside)) {
        assertEquals(0, entries.count());
      }
    }

    @Test
    void aSpecialFileIsRejectedWithoutMovingOtherDrift() throws Exception {
      assumeTrue(POSIX);
      Files.writeString(root(WS1).resolve("a.txt"), "external bytes");
      final var socket = root(WS1).resolve("special.sock");
      try (final var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
        server.bind(java.net.UnixDomainSocketAddress.of(socket));
        assertFailure(save(WS1, "b.txt", "intended bytes", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
        assertEquals("external bytes", read(WS1, "a.txt"));
        assertTrue(Files.exists(socket));
        assertEquals(List.of(), incidents(WS1));
      }
    }

    @Test
    void movesFallBackWhenAtomicMoveIsUnsupported() throws Exception {
      final var source = tmp.resolve("source.txt");
      Files.writeString(source, "preserved bytes");
      final var uri = java.net.URI.create("jar:" + tmp.resolve("archive.zip").toUri());
      try (final var zip = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"))) {
        final var target = zip.getPath("/preserved.txt");
        // Different filesystem providers reject ATOMIC_MOVE deterministically, exercising the fallback.
        history.move(source, target);
        assertEquals("preserved bytes", Files.readString(target));
        assertFalse(Files.exists(source));
      }
    }

    @Test
    void modeOnlyDriftPreservesTheExecutableFile() throws Exception {
      assumeTrue(POSIX);
      final var file = root(WS1).resolve("a.txt");
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
      saveOk(WS1, "b.txt", "intended bytes");
      final var incident = onlyIncident(WS1);
      assertEquals("v1", preserved(incident, "a.txt"));
      assertTrue(Files.isExecutable(incident.resolve("files/a.txt")));
      assertTrue(incidentJson(incident).getJsonArray("paths").getJsonObject(0).getBoolean("executable"));
      assertFalse(Files.isExecutable(file));
      assertClean(WS1);
    }

    @Test
    void stagedChangesStillBlockTheWholeWorkspaceWithoutAnIncident() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "X");
      try (final var git = git(WS1)) {
        git.add().addFilepattern("a.txt").call();
      }
      assertFailedWith(save(WS1, "a.txt", "Y", "*"), "staged changes");
      assertFailedWith(save(WS1, "b.txt", "Y", "*"), "staged changes");
      assertEquals("X", read(WS1, "a.txt"));
      assertFalse(Files.exists(WorkspaceHistory.recoveryDir(root(WS1))));
    }

    @Test
    void ifTheBytesCannotBePreservedNothingIsTouched() throws Exception {
      final var commits = log(WS1).size();
      Files.writeString(root(WS1).resolve("a.txt"), "X");
      Files.writeString(WorkspaceHistory.recoveryDir(root(WS1)), "in the way");

      assertFailedWith(save(WS1, "a.txt", "Y", "*"), "could not be preserved");
      assertEquals("X", read(WS1, "a.txt"));
      assertEquals(commits, log(WS1).size());
      assertFalse(Files.exists(mutationMarker(WS1)), "failed preservation must not authorize crash recovery");

      Files.delete(WorkspaceHistory.recoveryDir(root(WS1)));
      saveOk(WS1, "a.txt", "Y");
      assertEquals("X", preserved(onlyIncident(WS1), "a.txt"));
    }

    private WorkspaceHistory.WorkspaceHistoryException healFailingOnB() {
      history.failMovesEndingWith = "files/b.txt";
      try {
        return assertThrows(WorkspaceHistory.WorkspaceHistoryException.class, () ->
            history.mutate(WS1, USER, "x", () -> true, r -> r));
      } finally {
        history.failMovesEndingWith = null;
        history.failMovesTo.clear();
      }
    }

    @Test
    void aPartlyPreservedWorkspaceIsPutBack() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "X");
      Files.writeString(root(WS1).resolve("b.txt"), "Y");

      assertTrue(healFailingOnB().getMessage().contains("left untouched"));
      assertEquals("X", read(WS1, "a.txt"));
      assertEquals("Y", read(WS1, "b.txt"));
      assertEquals(List.of(), incidents(WS1));
    }

    @Test
    void aFileThatCannotBeMovedBackStaysInTheIncident() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "X");
      Files.writeString(root(WS1).resolve("b.txt"), "Y");
      history.failMovesTo.add(root(WS1).resolve("a.txt"));

      final var message = healFailingOnB().getMessage();
      assertTrue(message.contains("could not be moved back") && message.contains("a.txt"), message);
      assertFalse(Files.exists(root(WS1).resolve("a.txt")));
      assertEquals("X", preserved(onlyIncident(WS1), "a.txt"));
      assertEquals("Y", read(WS1, "b.txt"));
      assertEquals(java.util.Map.of("a.txt", "modified", "b.txt", "modified"), changesIn(onlyIncident(WS1)));
    }

    @Test
    void aPruneFailureDoesNotFailTheMutation() throws Exception {
      assumeTrue(POSIX);
      final var recovery = Files.createDirectories(WorkspaceHistory.recoveryDir(root(WS1)));
      for (int i = 0; i < WorkspaceHistory.MAX_INCIDENTS; i++) {
        Files.createDirectories(recovery.resolve("00000000T000000000Z-old-%02d/files".formatted(i)));
        Files.writeString(recovery.resolve("00000000T000000000Z-old-%02d/files/f".formatted(i)), "old");
      }
      final var locked = recovery.resolve("00000000T000000000Z-old-00/files");
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
      try {
        Files.writeString(root(WS1).resolve("a.txt"), "X");
        saveOk(WS1, "a.txt", "Y");
      } finally {
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
      assertEquals("Y", read(WS1, "a.txt"));
      assertEquals("X", preserved(incidents(WS1).getLast(), "a.txt"));
      assertTrue(Files.exists(locked.resolve("f")), "the old incident could not be pruned; that was only logged");
    }

    @Test
    void failedOobHealRestoreLeavesAMarkerAndTheNextRequestRecovers() throws Exception {
      assumeTrue(POSIX);
      final var commits = log(WS1).size();
      final var dir = root(WS1).resolve("d");
      Files.delete(dir.resolve("x.txt"));
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x")); // x.txt cannot be rewritten
      try {
        assertFailedWith(save(WS1, "d/x.txt", "Y", "*"), "could not be restored");
      } finally {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
      assertEquals(commits, log(WS1).size());
      assertFalse(Files.exists(dir.resolve("x.txt")));
      final var incident = onlyIncident(WS1);
      assertEquals(java.util.Map.of("d/x.txt", "deleted"), changesIn(incident));
      assertTrue(Files.exists(mutationMarker(WS1)), "restore failed after preservation; recovery must remain authorized");

      // Simulate the stale lock and index residue a crash during checkout can leave behind.
      Files.writeString(root(WS1).resolve("a.txt"), "interrupted restore bytes");
      try (final var git = git(WS1)) {
        git.add().addFilepattern("a.txt").call();
      }
      final var indexLock = root(WS1).resolve(".git/index.lock");
      Files.writeString(indexLock, "stale");
      restartServer();
      saveOk(WS1, "b.txt", "next request");

      assertEquals("v1", read(WS1, "a.txt"));
      assertEquals("v1", read(WS1, "d/x.txt"));
      assertEquals("next request", read(WS1, "b.txt"));
      assertFalse(Files.exists(indexLock));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertTrue(incidents(WS1).contains(incident));
      final var recovery = incidents(WS1).stream().filter(p -> !p.equals(incident)).toList();
      assertEquals(1, recovery.size());
      assertEquals("interrupted restore bytes", preserved(recovery.getFirst(), "a.txt"));
      assertEquals("interrupted mutation recovery", incidentJson(recovery.getFirst()).getString("reason"));
      assertClean(WS1);
    }

    @Test
    void recoveryArtifactsAreInvisibleToGitAndTheFileApi() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "X");
      saveOk(WS1, "a.txt", "Y");
      final var incident = root(WS1).relativize(onlyIncident(WS1));

      assertClean(WS1);
      assertTrue(headTree(WS1).stream().noneMatch(p -> p.startsWith(".seqdev")));
      assertTrue(WorkspacePaths.walk(root(WS1), Integer.MAX_VALUE).stream()
                               .noneMatch(p -> root(WS1).relativize(p).startsWith(".seqdev")));
      for (final var path : List.of(incident.resolve("incident.json"), incident.resolve("files/a.txt"))) {
        assertThrows(ReservedPathException.class, () -> fs.loadFile(WS1, path), path::toString);
        assertFailure(save(WS1, path.toString(), "evil", "*"), 400, "RESERVED_PATH");
      }
    }

    @Test
    void onlyTheNewestIncidentsAreKept() throws Exception {
      for (int i = 0; i < WorkspaceHistory.MAX_INCIDENTS + 3; i++) {
        Files.writeString(root(WS1).resolve("a.txt"), "drift " + i);
        saveOk(WS1, "a.txt", "v1");
      }
      final var kept = incidents(WS1);
      assertEquals(WorkspaceHistory.MAX_INCIDENTS, kept.size());
      assertEquals("drift 3", preserved(kept.getFirst(), "a.txt"));
      assertEquals("drift " + (WorkspaceHistory.MAX_INCIDENTS + 2), preserved(kept.getLast(), "a.txt"));
    }

    @Test
    void theCurrentIncidentIsNeverPruned() throws Exception {
      final var recovery = Files.createDirectories(WorkspaceHistory.recoveryDir(root(WS1)));
      for (int i = 0; i < WorkspaceHistory.MAX_INCIDENTS; i++) {
        Files.createDirectories(recovery.resolve("99991231T235959999Z-future-%02d".formatted(i)));
      }
      Files.writeString(root(WS1).resolve("a.txt"), "X");
      saveOk(WS1, "a.txt", "Y");

      final var kept = incidents(WS1);
      assertEquals(WorkspaceHistory.MAX_INCIDENTS, kept.size());
      final var current = kept.stream().filter(p -> !p.getFileName().toString().startsWith("9999")).toList();
      assertEquals(1, current.size(), kept::toString);
      assertEquals("X", preserved(current.getFirst(), "a.txt"));
    }
  }

  @Nested
  class Adoption {
    private Git foreignRepo(final int ws, final String branch) throws GitAPIException, IOException {
      final var git = Git.init().setDirectory(root(ws).toFile()).setInitialBranch(branch).call();
      final var cfg = git.getRepository().getConfig();
      cfg.setString("gpg", null, "format", "openpgp");
      cfg.save();
      return git;
    }

    private static void commitAll(final Git git) throws GitAPIException {
      git.add().addFilepattern(".").call();
      final var someone = new PersonIdent("someone", "");
      git.commit().setMessage("external").setAuthor(someone).setCommitter(someone).setSign(false).call();
    }

    @Test
    void aCleanExistingRepoWithGitControlFilesIsRejected() throws Exception {
      var ws = 10;
      for (final var name : List.of(".gitignore", ".gitattributes", ".gitmodules", "sub/.gitignore")) {
        ws++;
        Files.createDirectories(root(ws).resolve(name).getParent());
        try (final var git = foreignRepo(ws, "main")) {
          Files.writeString(root(ws).resolve("a.txt"), "A");
          Files.writeString(root(ws).resolve(name), "x\n");
          commitAll(git);
        }
        assertClean(ws);
        final var commits = log(ws).size();

        assertFailure(bindings.handleCreateDirectory(ws, Path.of("x"), USER), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
        assertEquals(commits, log(ws).size(), name);
        assertFalse(history.isManaged(root(ws)), name);
      }
    }

    @Test
    void anExistingRepoIsAdoptedWithItsUncommittedState() throws Exception {
      try (final var git = foreignRepo(WS1, "main")) {
        Files.writeString(root(WS1).resolve("a.txt"), "committed");
        commitAll(git);
      }
      Files.writeString(root(WS1).resolve("a.txt"), "uncommitted");
      saveOk(WS1, "b.txt", "B");

      final var commits = log(WS1);
      assertEquals(List.of("Update b.txt", WorkspaceHistory.ADOPT_MESSAGE, "external"),
                   commits.stream().map(RevCommit::getFullMessage).toList());
      assertEquals("uncommitted", read(WS1, "a.txt"));
      assertClean(WS1);
    }

    @Test
    void existingInternalScratchFilesDoNotBlockAdoption() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "A");
      Files.createDirectories(root(WS1).resolve(".seqdev"));
      final var arbitrary = root(WS1).resolve(".seqdev/state.json");
      Files.writeString(arbitrary, "no schema");

      init(WS1);

      assertTrue(history.isManaged(root(WS1)));
      assertEquals("no schema", Files.readString(arbitrary));
      assertClean(WS1);
    }

    @Test
    void anExistingRepoOnAnotherBranchIsRejected() throws Exception {
      final var legacySidecar = """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z","readOnly":true}""";
      try (final var git = foreignRepo(WS1, "master")) {
        Files.writeString(root(WS1).resolve("a.txt"), "A");
        Files.writeString(root(WS1).resolve(".a.txt.meta.seqdev"), legacySidecar);
        commitAll(git);
      }
      assertFailure(save(WS1, "b.txt", "B", null), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFalse(Files.exists(root(WS1).resolve("b.txt")));
      assertFalse(history.isManaged(root(WS1)));
      assertEquals(legacySidecar, read(WS1, ".a.txt.meta.seqdev"), "nothing is migrated before validation passes");
      assertClean(WS1);
    }
  }

  @Nested
  class Symlinks {
    private Path outside;

    @BeforeEach
    void setUpLinks() throws IOException {
      outside = Files.createDirectories(base.resolve("outside"));
      Files.writeString(outside.resolve("secret.txt"), "secret");
    }

    @Test
    void anUnmanagedWorkspaceWithASymlinkIsNotAdopted() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "A");
      Files.createSymbolicLink(root(WS1).resolve("link"), outside);
      final var result = bindings.handleCreateDirectory(WS1, Path.of("x"), USER);
      assertFailure(result, 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(result.jsonResponse().asJsonObject().getString("message").contains("link (symbolic link)"));
      assertFalse(Files.exists(root(WS1).resolve(".git")));
    }

    @Test
    void pathsThroughASymlinkCannotBeReadOrWritten() throws Exception {
      saveOk(WS1, "d/x.txt", "x");
      final var head = head(WS1);
      Files.createSymbolicLink(root(WS1).resolve("toGit"), Path.of(".git"));
      Files.createSymbolicLink(root(WS1).resolve("toOutside"), outside);
      Files.createSymbolicLink(root(WS1).resolve("toInside"), Path.of("d"));

      for (final var path : List.of("toGit/config", "toOutside/secret.txt", "toInside/x.txt", "toGit", "toInside")) {
        assertThrows(ReservedPathException.class, () -> fs.loadFile(WS1, Path.of(path)), path);
        assertThrows(ReservedPathException.class, () -> fs.checkFileExists(WS1, Path.of(path)), path);
      }
      // The workspace is no longer as PlanDev left it, so nothing is written anywhere, inside or outside it.
      assertFailure(save(WS1, "toOutside/secret.txt", "overwritten", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFailure(save(WS1, "toGit/config", "overwritten", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
      assertEquals(head, head(WS1));
    }

    @Test
    void aSymlinkCommittedOutOfBandIsRejected() throws Exception {
      saveOk(WS1, "a.txt", "A");
      Files.createSymbolicLink(root(WS1).resolve("link"), outside);
      try (final var git = git(WS1)) {
        git.add().addFilepattern("link").call();
        git.commit().setMessage("sneak").setSign(false).call();
      }
      assertClean(WS1);
      final var result = save(WS1, "b.txt", "B", null);
      assertFailure(result, 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(result.jsonResponse().asJsonObject().getString("message").contains("link"));
    }

    @Test
    void scansDoNotFollowOrReturnSymlinks() throws IOException {
      Files.createDirectories(root(WS1).resolve("d"));
      Files.writeString(root(WS1).resolve("d/x.txt"), "x");
      Files.createSymbolicLink(root(WS1).resolve("toOutside"), outside);
      Files.createSymbolicLink(root(WS1).resolve("toInside"), Path.of("d"));
      final var listed = WorkspacePaths.walk(root(WS1), Integer.MAX_VALUE).stream()
                                       .map(p -> WorkspacePaths.key(root(WS1), p)).toList();
      assertEquals(List.of("d", "d/x.txt"), listed.stream().sorted().toList());
    }
  }

  /**
   * A copied file is a new file, whether it is copied on its own or inside a directory; a copied or moved file never
   * inherits metadata from a file it overwrites.
   */
  @Nested
  class CopySemantics {
    private JsonObject metadata(final String path) throws Exception {
      return WorkspaceHistoryIntegrationTest.this.metadata(WS1, path);
    }

    /** Destination has carol's sidecar with user metadata; source has no sidecar at all. */
    private void sourceWithoutSidecarAndDestinationWithOne() throws Exception {
      assertSuccess(bindings.handleFileUpload(WS1, Path.of("dest.txt"), upload("dest.txt", "old"), true, null, "carol"));
      setUserMetadata(WS1, "dest.txt", "final");
      saveOk(WS1, "src.txt", "new");
      deleteSidecar(WS1, "src.txt");
      assertFalse(Files.exists(root(WS1).resolve(".src.txt.meta.seqdev")));
    }

    @Test
    void aCopyDoesNotInheritTheOverwrittenFilesMetadata() throws Exception {
      sourceWithoutSidecarAndDestinationWithOne();
      assertSuccess(bindings.handleCopy(Path.of("src.txt"), Path.of("dest.txt"), WS1, WS1, true, USER));
      final var meta = metadata("dest.txt");
      assertFalse(meta.containsKey("user"), meta::toString);
      assertEquals(USER, meta.getString("createdBy"));
      assertEquals("new", read(WS1, "dest.txt"));
      assertClean(WS1);
    }

    @Test
    void aMoveDoesNotInheritTheOverwrittenFilesMetadata() throws Exception {
      sourceWithoutSidecarAndDestinationWithOne();
      assertSuccess(bindings.handleMove(Path.of("src.txt"), Path.of("dest.txt"), WS1, WS1, true, USER));
      final var meta = metadata("dest.txt");
      assertFalse(meta.containsKey("user"), meta::toString);
      assertEquals(USER, meta.getString("createdBy"));
      assertEquals("new", read(WS1, "dest.txt"));
      assertClean(WS1);
    }

    @Test
    void theExecutableBitSurvivesSavesAndCopies() throws Exception {
      assumeTrue(POSIX, "needs POSIX permissions");
      saveOk(WS1, "run.sh", "v1");
      Files.setPosixFilePermissions(root(WS1).resolve("run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
      try (final var git = git(WS1)) { // record the mode as PlanDev would find it in a managed workspace
        git.add().addFilepattern("run.sh").call();
        git.commit().setMessage("chmod").setSign(false).call();
      }

      saveOk(WS1, "run.sh", "v2");
      assertTrue(Files.isExecutable(root(WS1).resolve("run.sh")));
      final var diff = headDiff(WS1);
      assertEquals(1, diff.size(), diff::toString);
      assertEquals(FileMode.EXECUTABLE_FILE, diff.getFirst().getOldMode());
      assertEquals(FileMode.EXECUTABLE_FILE, diff.getFirst().getNewMode(), "a save changes content, not mode");

      assertSuccess(bindings.handleCopy(Path.of("run.sh"), Path.of("copy.sh"), WS1, WS1, false, USER));
      assertTrue(Files.isExecutable(root(WS1).resolve("copy.sh")));
      assertClean(WS1);
    }

    @Test
    void fileAndDirectoryCopiesProduceTheSameMetadata() throws Exception {
      assertSuccess(bindings.handleFileUpload(WS1, Path.of("d/x.txt"), upload("x.txt", "x"), true, null, "carol"));
      setUserMetadata(WS1, "d/x.txt", "final");
      setReadOnly(WS1, "d/x.txt", true);

      assertSuccess(bindings.handleCopy(Path.of("d/x.txt"), Path.of("single.txt"), WS1, WS1, false, USER));
      assertSuccess(bindings.handleCopy(Path.of("d"), Path.of("e"), WS1, WS1, false, USER));

      final var single = metadata("single.txt");
      final var inDir = metadata("e/x.txt");
      for (final var copy : List.of(single, inDir)) {
        assertEquals(USER, copy.getString("createdBy"), copy::toString);
        assertEquals(USER, copy.getString("lastEditedBy"), copy::toString);
        assertEquals("final", copy.getJsonObject("user").getString("status"), copy::toString);
        assertFalse(copy.getBoolean("readOnly"), copy::toString);
      }
      final var ignoringTimes = (java.util.function.Function<JsonObject, JsonObject>) o ->
          Json.createObjectBuilder(o).remove("createdAt").remove("lastEditedAt").build();
      assertEquals(ignoringTimes.apply(single), ignoringTimes.apply(inDir));
      assertTrue(isReadOnly(WS1, "d/x.txt"), "the source keeps its lock");
      assertFalse(isReadOnly(WS1, "e/x.txt"));
      assertEquals("carol", metadata("d/x.txt").getString("createdBy"));
      assertClean(WS1);
    }
  }

  @Test
  void everyFileSystemMutationRequiresTheLock() {
    assertThrows(IllegalStateException.class, () -> fs.createDirectory(WS1, Path.of("x")));
    assertThrows(IllegalStateException.class, () -> fs.deleteFile(WS1, Path.of("x")));
    assertThrows(IllegalStateException.class, () -> fs.saveFile(WS1, Path.of("x"), upload("x", "x"), USER));
    assertThrows(IllegalStateException.class, () -> fs.copyDirectory(WS1, Path.of("x"), WS2, Path.of("y"), USER));
    final var commits = new LinkedHashMap<Integer, String>();
    commits.put(WS1, "only ws1");
    // Holding one workspace's lock does not authorize mutating another
    assertThrows(IllegalStateException.class, () -> history.mutate(commits, USER,
        () -> fs.moveDirectory(WS1, Path.of("a"), WS2, Path.of("b")), r -> true));
    assertFalse(Files.exists(root(WS1).resolve("x")));
  }

  @Nested
  class Revisions {
    private String revise(final String path, final String message) {
      final var result = bindings.handleCreateRevision(WS1, Path.of(path), "*", message, USER);
      assertSuccess(result);
      return ((HandlerResult.Success) result).response();
    }

    private List<String> names(final String path) throws Exception {
      return fs.listRevisions(WS1, Path.of(path)).stream().map(WorkspaceRevisions.Revision::name).toList();
    }

    private String contentAt(final String path, final String name) throws Exception {
      return new String(fs.readRevision(WS1, Path.of(path), name).orElseThrow().content(), StandardCharsets.UTF_8);
    }

    @Test
    void namesAreBijectiveBase26() {
      assertEquals(List.of("a", "z", "aa", "az", "ba", "zz", "aaa"),
                   java.util.stream.IntStream.of(1, 26, 27, 52, 53, 702, 703).mapToObj(WorkspaceRevisions::name).toList());
    }

    @Test
    void createListReadAndRestore() throws Exception {
      saveOk(WS1, "a.txt", "v1");
      setUserMetadata(WS1, "a.txt", "draft");
      final var commits = log(WS1).size();
      assertEquals("a", revise("a.txt", "first"));
      assertEquals(commits, log(WS1).size(), "a revision makes no commit");
      assertClean(WS1);

      final var etag = saveOk(WS1, "a.txt", "v2");
      setUserMetadata(WS1, "a.txt", "final");
      assertEquals("b", revise("a.txt", ""));

      final var revisions = fs.listRevisions(WS1, Path.of("a.txt"));
      assertEquals(List.of("b", "a"), revisions.stream().map(WorkspaceRevisions.Revision::name).toList());
      assertEquals("first", revisions.getLast().message());
      assertEquals(USER, revisions.getLast().toJson().getString("createdBy"));
      assertEquals("v1", contentAt("a.txt", "a"));
      assertEquals("draft", fs.readRevision(WS1, Path.of("a.txt"), "a").orElseThrow()
                              .metadata().getJsonObject("user").getString("status"));

      setReadOnly(WS1, "a.txt", true);
      assertFailure(bindings.handleRestoreRevision(WS1, Path.of("a.txt"), "a", etag, USER), 423, null);
      setReadOnly(WS1, "a.txt", false);

      final var restored = bindings.handleRestoreRevision(WS1, Path.of("a.txt"), "a", etag, USER);
      assertSuccess(restored);
      assertEquals("v1", read(WS1, "a.txt"));
      assertEquals(WorkspaceService.computeETag("v1".getBytes(StandardCharsets.UTF_8)),
                   ((HandlerResult.Success) restored).etag().orElseThrow());
      assertEquals("Restore a.txt to revision a", head(WS1).getFullMessage());
      assertEquals("draft", metadata(WS1, "a.txt").getJsonObject("user").getString("status"));
      assertFalse(metadata(WS1, "a.txt").getBoolean("readOnly", false), "readOnly is not restored");
      assertEquals(List.of("b", "a"), names("a.txt"), "a restore makes no revision");
      assertClean(WS1);
    }

    @Test
    void preconditions() throws Exception {
      final var etag = saveOk(WS1, "a.txt", "v1");
      revise("a.txt", "");
      saveOk(WS1, "a.txt", "v2");
      final var commits = log(WS1).size();

      assertFailure(bindings.handleCreateRevision(WS1, Path.of("a.txt"), etag, "", USER), 412, null);
      assertFailure(bindings.handleRestoreRevision(WS1, Path.of("a.txt"), "a", etag, USER), 412, null);
      assertFailure(bindings.handleRestoreRevision(WS1, Path.of("a.txt"), "zz", "*", USER), 404, "NO_SUCH_REVISION");
      assertFailure(bindings.handleCreateRevision(WS1, Path.of("gone.txt"), etag, "", USER), 412, null);
      assertFailure(bindings.handleCreateRevision(WS1, Path.of("gone.txt"), "*", "", USER), 404, null);
      assertFailure(bindings.handleCreateRevision(WS1, Path.of(sidecar("a.txt")), "*", "", USER), 405, null);
      assertEquals("v2", read(WS1, "a.txt"));
      assertEquals(List.of("a"), names("a.txt"));
      assertEquals(commits, log(WS1).size());
      assertClean(WS1);
    }

    @Test
    void revisionsFollowRenamesButNotCopiesOrRecreation() throws Exception {
      saveOk(WS1, "a.txt", "v1");
      revise("a.txt", "");
      assertSuccess(bindings.handleCreateDirectory(WS1, Path.of("dir"), USER));
      assertSuccess(bindings.handleMove(Path.of("a.txt"), Path.of("dir/b.txt"), WS1, WS1, false, USER));
      saveOk(WS1, "dir/b.txt", "v2");
      assertSuccess(bindings.handleMove(Path.of("dir"), Path.of("moved"), WS1, WS1, false, USER));
      assertEquals(List.of("a"), names("moved/b.txt"));
      assertEquals("v1", contentAt("moved/b.txt", "a"));
      assertEquals("b", revise("moved/b.txt", ""));

      assertSuccess(bindings.handleCopy(Path.of("moved/b.txt"), Path.of("copy.txt"), WS1, WS1, false, USER));
      assertEquals(List.of(), names("copy.txt"));

      assertSuccess(bindings.handleDelete(WS1, Path.of("moved/b.txt"), USER));
      saveOk(WS1, "moved/b.txt", "new file");
      assertEquals(List.of(), names("moved/b.txt"));
    }

    @Test
    void aMoveOverAnExistingFileBringsTheMovedFilesRevisions() throws Exception {
      saveOk(WS1, "x.txt", "x");
      revise("x.txt", "x1");
      saveOk(WS1, "y.txt", "y");
      revise("y.txt", "y1");
      revise("y.txt", "y2");
      assertSuccess(bindings.handleMove(Path.of("x.txt"), Path.of("y.txt"), WS1, WS1, true, USER));
      assertEquals(List.of("x1"), fs.listRevisions(WS1, Path.of("y.txt")).stream()
                                    .map(WorkspaceRevisions.Revision::message).toList());
    }
  }
}
