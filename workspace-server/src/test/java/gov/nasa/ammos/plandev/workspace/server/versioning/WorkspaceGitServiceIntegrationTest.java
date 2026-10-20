package gov.nasa.ammos.plandev.workspace.server.versioning;

import gov.nasa.ammos.plandev.workspace.server.*;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import gov.nasa.ammos.plandev.workspace.server.types.MetadataMergeBehavior;
import gov.nasa.ammos.plandev.workspace.server.utils.WorkspaceTestRepository;
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
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises workspace mutations end to end below HTTP: the binding handlers (validation, readOnly, ETag), the
 * {@link WorkspaceGitService} mutation boundary, {@link WorkspaceFileSystemService}, and a real JGit repository on disk.
 */
class WorkspaceGitServiceIntegrationTest {
  private static final String USER = "alice";
  private static final String USER_IDENT = "alice <alice@SeqDev>";
  private static final int WS1 = 1;
  private static final int WS2 = 2;

  private static final Path fileA = Path.of("a.txt");
  private static final Path fileB = Path.of("b.txt");
  private static final Path goneFile = Path.of("gone.txt");
  private static final Path fileX = Path.of("d/x.txt");

  @TempDir Path tmp;
  private WorkspaceTestRepository wsRepo;
  private FlakyHistory history;
  private WorkspaceFileSystemService fs;
  private WorkspaceBindings bindings;

  /** A WorkspaceHistory whose commit step can be made to fail for chosen workspaces (deterministic injection). */
  static final class FlakyHistory extends WorkspaceGitService {
    final Set<Path> failCommitsIn = ConcurrentHashMap.newKeySet();

    /** Moves during drift healing fail if their destination is listed or ends with this (incident names vary). */
    final Set<Path> failMovesTo = ConcurrentHashMap.newKeySet();
    volatile String failMovesEndingWith;

    FlakyHistory(final WorkspaceRepository roots) {
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
    wsRepo = new WorkspaceTestRepository(tmp.toRealPath());
    wsRepo.createWorkspace("ws1");
    wsRepo.createWorkspace("ws2");
    restartServer();
  }

  /** Recreate all in-memory coordination state while keeping the workspace disks, as a process restart would. */
  private void restartServer() {
    history = new FlakyHistory(wsRepo);
    fs = new WorkspaceFileSystemService(wsRepo, history);
    bindings = new WorkspaceBindings(null, fs, history, null, "");
  }

  //region Helpers
  private List<Path> incidents(final int ws) throws IOException {
    final var dir = WorkspaceGitService.recoveryDir(wsRepo.workspaceRootPath(ws));
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

  private static Map<String, String> changesIn(final Path incident) throws IOException {
    final var changes = new TreeMap<String, String>();
    for (final var p : incidentJson(incident).getJsonArray("paths").getValuesAs(JsonObject.class)) {
      changes.put(p.getString("path"), p.getString("change"));
    }
    return changes;
  }

  private static String preserved(final Path incident, final Path path) throws IOException {
    return Files.readString(incident.resolve("files").resolve(path));
  }

  private Path mutationMarker(final int ws) {
    return wsRepo.workspaceRootPath(ws).resolve(".git").resolve(WorkspaceGitService.MUTATION_MARKER);
  }

  private Path adoptionMarker(final int ws) {
    return wsRepo.workspaceRootPath(ws).resolve(".seqdev").resolve(WorkspaceGitService.ADOPTION_MARKER);
  }

  private static void createMarker(final Path marker) throws IOException {
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, "interrupted");
  }

  private static UploadedFile upload(final String name, final String content) {
    final var bytes = content.getBytes(StandardCharsets.UTF_8);

    /*
    final var part = new Part() {
      private final InputStream inputStream = new ByteArrayInputStream(bytes);

      @Override
      public InputStream getInputStream() {
        return inputStream;
      }

      @Override
      public String getContentType() {
        return "application/octet-stream";
      }

      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getSubmittedFileName() {
        return name;
      }

      @Override
      public long getSize() {
        return bytes.length;
      }

      @Override
      public void write(final String s) throws IOException {
        inputStream.read();
      }

      @Override
      public void delete() throws IOException {}

      @Override
      public String getHeader(final String s) {
        return "";
      }

      @Override
      public Collection<String> getHeaders(final String s) {
        return List.of("");
      }

      @Override
      public Collection<String> getHeaderNames() {
        return List.of();
      }
    };
     */

    final var pa = (Part) Proxy.newProxyInstance(Part.class.getClassLoader(), new Class<?>[]{Part.class}, (p, m, a) ->
        switch (m.getName()) {
          case "getInputStream" -> new ByteArrayInputStream(bytes);
          case "getSubmittedFileName", "getName" -> name;
          case "getSize" -> (long) bytes.length;
          case "getContentType" -> "application/octet-stream";
          //default -> null;
          default -> throw new IllegalStateException("Unexpected value: " + m.getName());
        });
    return new UploadedFile(pa);
  }

  private HandlerResult save(final int ws, final Path path, final String content, final String ifMatch) {
    return bindings.handleFileUpload(ws, path, upload(path.getFileName().toString(), content), ifMatch == null, ifMatch, USER);
  }

  private String saveOk(final int ws, final String path, final String content) {
    return saveOk(ws, Path.of(path), content);
  }

  private String saveOk(final int ws, final Path path, final String content) {
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
    return Git.open(wsRepo.workspaceRootPath(ws).toFile());
  }

  private void assertClean(final int ws) throws Exception {
    try (final var git = git(ws)) {
      assertEquals(Set.of(), WorkspaceGitService.dirtyPaths(git.status().call()), "workspace " + ws + " is dirty");
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
    return read(ws, Path.of(path));
  }

  private String read(final int ws, final Path path) throws IOException {
    return Files.readString(wsRepo.workspaceRootPath(ws).resolve(path));
  }

  private void setReadOnly(final int ws, final Path path, final boolean readOnly) throws Exception {
    history.mutate(ws, USER, "lock", () -> fs.updateMetadataKeys(
        ws, path, new MetadataUpdates.Builder(USER).readOnly(readOnly).build(), MetadataMergeBehavior.deepMerge),
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

  protected JsonObject metadata(final int ws, final String path) throws Exception {
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
      saveOk(WS1, fileA, "one");
      saveOk(WS1, fileA, "two");
      assertEquals(
          List.of(fileA.toString()),
          headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
      assertClean(WS1);
    }

    @Test
    void identicalSaveCreatesNoCommit() throws Exception {
      saveOk(WS1, fileA, "same");
      final var before = log(WS1).size();
      saveOk(WS1, fileA, "same");
      assertEquals(before, log(WS1).size());
      assertClean(WS1);
    }

    @Test
    void etagChangesAndAStaleEtagIsRejectedBeforeAnyMutation() throws Exception {
      final var etag1 = saveOk(WS1, fileA, "v1");
      final var result2 = save(WS1, fileA, "v2", etag1);
      assertSuccess(result2);
      final var etag2 = ((HandlerResult.Success) result2).etag().orElseThrow();
      assertNotEquals(etag1, etag2);
      final var commits = log(WS1).size();

      final var stale = save(WS1, fileA, "v3", etag1);
      assertFailure(stale, 412, null);
      assertEquals(etag2, stale.jsonResponse().asJsonObject().getJsonObject("data").getString("currentETag"));
      assertEquals(USER_IDENT, stale.jsonResponse().asJsonObject().getJsonObject("data").getString("lastEditedBy"));
      assertEquals("v2", read(WS1, fileA));
      assertEquals(commits, log(WS1).size());
      assertClean(WS1);

      assertSuccess(save(WS1, fileA, "v4", "*")); // explicit overwrite still allowed
      assertEquals("v4", read(WS1, fileA));
    }

    @Test
    void readOnlyFileIsLockedAndTheLockIsStoredInItsSidecar() throws Exception {
      saveOk(WS1, fileA, "v1");
      final var commits = log(WS1).size();
      setReadOnly(WS1, fileA, true);
      assertEquals(commits + 1, log(WS1).size());
      assertEquals(List.of(".a.txt.meta.seqdev"), headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
      assertTrue(Files.readString(wsRepo.workspaceRootPath(WS1).resolve(".a.txt.meta.seqdev")).contains("readOnly"));
      assertFailure(save(WS1, fileA, "v2", "*"), 423, "FILE_LOCKED");
      assertEquals("v1", read(WS1, fileA));
      assertClean(WS1);
    }

    @Test
    void userMetadataIsVersionedAndLastEditedIsDerivedFromHistory() throws Exception {
      saveOk(WS1, fileA, "v1");
      final var savedAt = head(WS1).getAuthorIdent().getWhenAsInstant();
      history.mutate(WS1, "bob", "Update metadata for a.txt", () -> fs.updateMetadataKeys(
          WS1, fileA,
          new MetadataUpdates.Builder("bob").user(Json.createObjectBuilder().add("status", "draft").build()).build(),
          MetadataMergeBehavior.deepMerge), r -> true);

      assertEquals(List.of(".a.txt.meta.seqdev"), headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
      final var info = fs.getLastEditInfo(WS1, fileA);
      assertEquals(USER_IDENT, info.lastEditedBy(), "a metadata-only change is not a content edit");

      // LastEditedAt saves at a higher fidelity than author ident
      assertEquals(savedAt, Instant.parse(info.lastEditedAt()).truncatedTo(ChronoUnit.SECONDS));
      assertClean(WS1);
    }
  }

  @Nested
  class LastEdits {
    @Test
    void resolvesOnlyRequestedContentPathsAndReusesPerPathCache() throws Exception {
      saveOk(WS1, fileA, "a1");
      saveOk(WS1, fileB, "b1");

      final var ws1Root = wsRepo.workspaceRootPath(WS1);
      final var aPath = ws1Root.resolve(fileA);
      final var bPath = ws1Root.resolve(fileB);

      final var onlyA = history.lastEdits(WS1, Set.of(aPath));
      assertEquals(Set.of(aPath), onlyA.keySet());
      assertEquals(USER_IDENT, onlyA.get(aPath).by());

      final var originalB = history.lastEdit(WS1, bPath);

      history.mutate(WS1, "bob", "Update a.txt", () -> {
        Files.writeString(aPath, "a2");
        return true;
      }, result -> result);

      final var both = history.lastEdits(WS1, Set.of(aPath, bPath));
      assertEquals("bob <bob@SeqDev>", both.get(aPath).by());
      assertEquals(originalB, both.get(bPath));

      assertSuccess(bindings.handleDelete(WS1, fileB, USER));
      assertNotNull(history.lastEdit(WS1, bPath));
      assertNotNull(history.lastEdit(WS1, ws1Root.resolve(".a.txt.meta.seqdev")));
      assertClean(WS1);
    }

    @Test
    void resolvesWorkspaceRelativePathsBelowTheRoot() throws Exception {
      saveOk(WS1, "one/a.txt", "A");
      saveOk(WS1, "two/b.txt", "B");

      final var ws1Root = wsRepo.workspaceRootPath(WS1);
      final var path = ws1Root.resolve("one/a.txt");

      final var edits = history.lastEdits(WS1, Set.of(path));

      assertEquals(Set.of(path), edits.keySet());
      assertEquals(USER_IDENT, edits.get(path).by());
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
      saveOk(WS1, goneFile, "will be deleted");
      Files.createDirectories(wsRepo.workspaceRootPath(WS1).resolve("existing-empty"));
      final var headBefore = head(WS1);
      final var sidecarBefore = Files.readAllBytes(wsRepo.workspaceRootPath(WS1).resolve(".keep.txt.meta.seqdev"));

      history.failCommitsIn.add(wsRepo.workspaceRootPath(WS1));
      final var ex = assertThrows(WorkspaceHistoryException.class, () ->
          history.mutate(WS1, USER, "doomed", () -> {
            Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("keep.txt"), "modified");
            Files.delete(wsRepo.workspaceRootPath(WS1).resolve(goneFile));
            Files.createDirectories(wsRepo.workspaceRootPath(WS1).resolve("new/nested"));
            Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("new/nested/file.txt"), "new");
            Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("existing-empty/file.txt"), "new");
            Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("untracked.txt"), "new");
            Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(".keep.txt.meta.seqdev"), "{\"readOnly\":true}");
            return true;
          }, r -> true));
      assertEquals(WorkspaceHistoryException.Kind.HISTORY_NOT_RECORDED, ex.kind);

      assertEquals("original", read(WS1, "keep.txt"));
      assertEquals("will be deleted", read(WS1, goneFile));
      assertTrue(Files.isDirectory(wsRepo.workspaceRootPath(WS1).resolve("new/nested")),
                 "rollback leaves directories it cannot prove were created by the failed operation");
      assertTrue(Files.isDirectory(wsRepo.workspaceRootPath(WS1).resolve("existing-empty")),
                 "rollback preserves a directory that existed before the failed operation");
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("new/nested/file.txt")));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("existing-empty/file.txt")));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("untracked.txt")));
      assertArrayEquals(sidecarBefore, Files.readAllBytes(wsRepo.workspaceRootPath(WS1).resolve(".keep.txt.meta.seqdev")));
      assertEquals(headBefore, head(WS1));
      assertClean(WS1);
    }

    @Test
    void aMutationThatThrowsIsRolledBackAndTheExceptionPropagates() throws Exception {
      saveOk(WS1, fileA, "original");
      final var thrown = assertThrows(IOException.class, () -> history.mutate(WS1, USER, "x", () -> {
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "half-written");
        throw new IOException("disk full");
      }, r -> true));
      assertEquals("disk full", thrown.getMessage());
      assertEquals("original", read(WS1, fileA));
      assertClean(WS1);
    }

    @Test
    void aFailureResultIsRolledBack() throws Exception {
      saveOk(WS1, fileA, "original");
      final var result = history.mutate(WS1, USER, "x", () -> {
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "partial");
        return false;
      }, r -> r);
      assertFalse(result);
      assertEquals("original", read(WS1, fileA));
      assertClean(WS1);
    }

    /** An unexplained Git lock is rejected before file bytes change; only a recovery marker authorizes its removal. */
    @Test
    void gitLockWithoutARecoveryMarkerIsLeftUntouched() throws Exception {
      saveOk(WS1, fileA, "original");
      final var headBefore = head(WS1);
      final var refLock = wsRepo.workspaceRootPath(WS1).resolve(".git/refs/heads/main.lock");
      Files.writeString(refLock, "");

      assertFailure(save(WS1, fileA, "changed", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFailure(save(WS1, fileB, "new file", null), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertEquals("original", read(WS1, fileA));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(fileB)));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(".b.txt.meta.seqdev")));
      assertTrue(Files.exists(refLock));
      assertEquals(headBefore, head(WS1));
      assertClean(WS1);
      try (final var internal = Files.list(wsRepo.workspaceRootPath(WS1).resolve(".seqdev"))) {
        assertEquals(List.of(), internal.map(p -> p.getFileName().toString()).toList(), "no upload temp files left");
      }

      Files.delete(refLock);
      saveOk(WS1, fileA, "changed");
      assertEquals("changed", read(WS1, fileA));
      assertClean(WS1);
    }
  }

  @Nested
  class CrashRecovery {
    @Test
    void stagedAndWorktreeResiduePreservesCurrentBytesAndBothStates() throws Exception {
      saveOk(WS1, fileA, "original");
      saveOk(WS1, goneFile, "original gone");
      final var before = head(WS1);
      createMarker(mutationMarker(WS1));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "staged version");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("new.txt"), "staged new");
      Files.delete(wsRepo.workspaceRootPath(WS1).resolve(goneFile));
      try (final var git = git(WS1)) {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
      }
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "current worktree bytes");
      Files.delete(wsRepo.workspaceRootPath(WS1).resolve("new.txt"));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(goneFile), "recreated after staged deletion");
      restartServer();

      assertTrue(history.mutate(WS1, USER, "probe", () -> {
        assertClean(WS1);
        assertEquals(before, head(WS1));
        assertEquals("original", read(WS1, fileA));
        assertEquals("original gone", read(WS1, goneFile));
        assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("new.txt")));
        return true;
      }, r -> r));
      final var incident = onlyIncident(WS1);
      assertEquals("current worktree bytes", preserved(incident, fileA));
      assertEquals("recreated after staged deletion", preserved(incident, goneFile));
      assertFalse(Files.exists(incident.resolve("files/new.txt")));
      final var paths = incidentJson(incident).getJsonArray("paths").getValuesAs(JsonObject.class);
      final var changed = paths.stream().filter(p -> p.getString("path").equals(fileA.toString())).findFirst().orElseThrow();
      assertEquals("modified", changed.getString("indexChange"));
      assertEquals("modified", changed.getString("worktreeChange"));
      final var missing = paths.stream().filter(p -> p.getString("path").equals("new.txt")).findFirst().orElseThrow();
      assertEquals("added", missing.getString("indexChange"));
      assertEquals("deleted", missing.getString("worktreeChange"));
      assertFalse(missing.getBoolean("present"));
      final var removed = paths.stream().filter(p -> p.getString("path").equals(goneFile.toString())).findFirst().orElseThrow();
      assertEquals("deleted", removed.getString("indexChange"));
      assertTrue(removed.getBoolean("present"));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertEquals(before, head(WS1));
    }

    @Test
    void preservationFailureKeepsTheCrashMarkerAndDirtyBytesForRetry() throws Exception {
      saveOk(WS1, fileA, "original");
      createMarker(mutationMarker(WS1));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "unfinished bytes");
      try (final var git = git(WS1)) {
        git.add().addFilepattern(WorkspacePaths.toGitPath(fileA)).call();
      }
      restartServer();
      history.failMovesEndingWith = "files/a.txt";
      assertFailure(save(WS1, fileB, "next request", null), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(Files.exists(mutationMarker(WS1)));
      assertEquals("unfinished bytes", read(WS1, fileA));
      try (final var git = git(WS1)) {
        assertEquals(Set.of(WorkspacePaths.toGitPath(fileA)), git.status().call().getChanged());
      }
      assertEquals(List.of(), incidents(WS1));
      history.failMovesEndingWith = null;
      saveOk(WS1, fileB, "next request");
      assertEquals("unfinished bytes", preserved(onlyIncident(WS1), fileA));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertClean(WS1);
    }

    @Test
    void aConfigLockLeftBeforeMutationMarkingIsRemoved() throws Exception {
      saveOk(WS1, fileA, "original");
      final var configLock = wsRepo.workspaceRootPath(WS1).resolve(".git/config.lock");
      Files.writeString(configLock, "stale");

      restartServer();
      saveOk(WS1, fileB, "next request");

      assertFalse(Files.exists(configLock));
      assertEquals("original", read(WS1, fileA));
      assertClean(WS1);
    }

    @Test
    void anUncommittedMutationIsRolledBackBeforeTheNextWrite() throws Exception {
      saveOk(WS1, fileA, "original");
      createMarker(mutationMarker(WS1));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "partial");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("new.txt"), "partial new file");
      try (final var git = git(WS1)) {
        git.add().addFilepattern(".").call();
      }
      final var indexLock = wsRepo.workspaceRootPath(WS1).resolve(".git/index.lock");
      Files.writeString(indexLock, "stale");

      restartServer();
      saveOk(WS1, fileB, "next request");

      assertEquals("original", read(WS1, fileA));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("new.txt")));
      assertFalse(Files.exists(indexLock));
      assertFalse(Files.exists(mutationMarker(WS1)));
      final var incident = onlyIncident(WS1);
      assertEquals("partial", preserved(incident, fileA));
      assertEquals("partial new file", preserved(incident, Path.of("new.txt")));
      assertEquals("interrupted mutation recovery", incidentJson(incident).getString("reason"));
      assertClean(WS1);
    }

    @Test
    void aLandedCommitIsKeptAndItsUnfinishedWorkingTreeIsCleaned() throws Exception {
      saveOk(WS1, fileA, "original");
      createMarker(mutationMarker(WS1));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "committed");
      try (final var git = git(WS1)) {
        final var ident = new PersonIdent("bob", "");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("landed before crash").setAuthor(ident).setCommitter(ident).setSign(false).call();
      }
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "unfinished later bytes");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("untracked.txt"), "unfinished");
      final var landedHead = head(WS1);

      restartServer();
      saveOk(WS1, fileB, "next request");

      assertEquals("committed", read(WS1, fileA));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("untracked.txt")));
      assertEquals(landedHead, log(WS1).get(1));
      final var incident = onlyIncident(WS1);
      assertEquals("unfinished later bytes", preserved(incident, fileA));
      assertEquals("unfinished", preserved(incident, Path.of("untracked.txt")));
      assertEquals(landedHead.name(), incidentJson(incident).getString("head"));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertClean(WS1);
    }

    @Test
    void aCrossWorkspaceCrashAfterTheDestinationCommitRecoversAsACopy() throws Exception {
      init(WS1);
      init(WS2);
      saveOk(WS1, fileA, "content");
      final var sourceHead = head(WS1);
      createMarker(mutationMarker(WS1));
      createMarker(mutationMarker(WS2));

      Files.move(wsRepo.workspaceRootPath(WS1).resolve(fileA), wsRepo.workspaceRootPath(WS2).resolve(fileB));
      Files.move(wsRepo.workspaceRootPath(WS1).resolve(".a.txt.meta.seqdev"), wsRepo.workspaceRootPath(WS2).resolve(".b.txt.meta.seqdev"));
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

      assertEquals("content", read(WS1, fileA));
      assertEquals("content", read(WS2, fileB));
      assertEquals(sourceHead, head(WS1));
      assertEquals(destinationHead, head(WS2));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertFalse(Files.exists(mutationMarker(WS2)));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void anInterruptedAdoptionRemovesItsLockAndResumes() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("legacy.txt"), "legacy");
      createMarker(adoptionMarker(WS1));
      Git.init().setDirectory(wsRepo.workspaceRootPath(WS1).toFile()).setInitialBranch(WorkspaceGitService.BRANCH).call().close();
      final var indexLock = wsRepo.workspaceRootPath(WS1).resolve(".git/index.lock");
      Files.writeString(indexLock, "stale");

      restartServer();
      init(WS1);

      assertTrue(history.isManaged(wsRepo.workspaceRootPath(WS1)));
      assertTrue(headTree(WS1).contains("legacy.txt"));
      assertFalse(Files.exists(indexLock));
      assertFalse(Files.exists(adoptionMarker(WS1)));
      assertClean(WS1);
    }

    @Test
    void anAdoptionInterruptedAfterCreatingTheObjectDatabaseRestartsInitialization() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("legacy.txt"), "legacy");
      createMarker(adoptionMarker(WS1));
      final var partialGit = wsRepo.workspaceRootPath(WS1).resolve(".git");
      Files.createDirectories(partialGit.resolve("objects"));
      Files.writeString(partialGit.resolve("unfinished"), "partial initialization");

      restartServer();
      init(WS1);

      assertTrue(history.isManaged(wsRepo.workspaceRootPath(WS1)));
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
      saveOk(WS1, fileA, "content");
      assertSuccess(bindings.handleMove(fileA, fileB, WS1, WS1, false, USER));

      final var renames = headDiff(WS1).stream().filter(d -> d.getChangeType() == DiffEntry.ChangeType.RENAME).toList();
      assertTrue(renames.stream().anyMatch(d -> d.getOldPath().equals(fileA.toString()) && d.getNewPath().equals(fileB.toString())), renames::toString);

      final var head = headTree(WS1);
      assertTrue(Set.of(fileB.toString(), ".b.txt.meta.seqdev").containsAll(head));
      assertEquals(2, headTree(WS1).size());

      assertClean(WS1);
    }

    @Test
    void renameCarriesTheLock() throws Exception {
      saveOk(WS1, "dir/a.txt", "content");
      setReadOnly(WS1, Path.of("dir/a.txt"), true);
      assertSuccess(bindings.handleMove(Path.of("dir"), Path.of("renamed"), WS1, WS1, false, USER));
      assertTrue(isReadOnly(WS1, "renamed/a.txt"));
      assertClean(WS1);
    }

    @Test
    void copyLeavesTheRepoClean() throws Exception {
      saveOk(WS1, fileA, "content");
      assertSuccess(bindings.handleCopy(fileA, Path.of("copies/c.txt"), WS1, WS1, false, USER));
      final var expectedSet = Set.of(fileA.toString(), ".a.txt.meta.seqdev", "copies/c.txt", "copies/.c.txt.meta.seqdev");
      final var actual = headTree(WS1);
      assertTrue(expectedSet.containsAll(actual));
      assertEquals(expectedSet.size(), actual.size());
      assertEquals("Copy a.txt -> copies/c.txt", head(WS1).getFullMessage());
      assertClean(WS1);
    }

    @Test
    void deleteLeavesTheRepoClean() throws Exception {
      saveOk(WS1, fileA, "content");
      saveOk(WS1, fileB, "content");
      assertSuccess(bindings.handleDelete(WS1, fileA, USER));

      final var head = headTree(WS1);
      assertTrue(Set.of(fileB.toString(), ".b.txt.meta.seqdev").containsAll(head));
      assertEquals(2, headTree(WS1).size());
      assertClean(WS1);
    }
  }

  @Nested
  class DirectoryOperations {
    @BeforeEach
    void populate() {
      saveOk(WS1, fileX, "x");
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
      assertTrue(headTree(WS1).containsAll(Set.of(fileX.toString(), "d/sub/y.txt", "e/x.txt", "e/sub/y.txt", "e/sub/.y.txt.meta.seqdev")));
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
      assertTrue(Files.isDirectory(wsRepo.workspaceRootPath(WS1).resolve(".git")));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS2).resolve("x")));
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
      saveOk(WS1, fileA, "content");
      final var sourceHead = head(WS1);
      assertSuccess(bindings.handleCopy(fileA, Path.of("in/a.txt"), WS1, WS2, false, USER));
      assertEquals(Set.of("in/a.txt", "in/.a.txt.meta.seqdev"), headTree(WS2));
      assertEquals(sourceHead, head(WS1));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void moveUpdatesBothHistories() throws Exception {
      saveOk(WS1, fileA, "content");
      assertSuccess(bindings.handleMove(fileA, fileA, WS1, WS2, false, USER));

      final var head = headTree(WS2);
      assertTrue(Set.of(fileA.toString(), ".a.txt.meta.seqdev").containsAll(head));
      assertEquals(2, head.size());
      assertEquals(Set.of(), headTree(WS1));
      assertEquals("Move a.txt -> workspace 2:a.txt", head(WS1).getFullMessage());
      assertEquals("Move workspace 1:a.txt -> a.txt", head(WS2).getFullMessage());
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void directoryMoveUpdatesBothHistories() throws Exception {
      saveOk(WS1, fileX, "x");
      assertSuccess(bindings.handleMove(Path.of("d"), Path.of("d"), WS1, WS2, false, USER));
      assertTrue(Set.of(fileX.toString(), "d/.x.txt.meta.seqdev").containsAll(headTree(WS2)));
      assertEquals(Set.of(), headTree(WS1));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void sourceCommitFailureDegradesTheMoveToACopy() throws Exception {
      saveOk(WS1, fileA, "content");
      final var sourceHead = head(WS1);
      history.failCommitsIn.add(wsRepo.workspaceRootPath(WS1));

      final var result = bindings.handleMove(fileA, fileB, WS1, WS2, false, USER);
      assertFailure(result, 500, "WORKSPACE_MUTATION_PARTIAL");
      final var message = result.jsonResponse().asJsonObject().getString("message");
      assertTrue(message.contains("degraded to a copy") && message.contains(fileA.toString()) && message.contains(fileB.toString()));

      // Destination keeps the committed copy, with its metadata; the source is restored, with its metadata.
      final var head = headTree(WS2);
      assertTrue(Set.of(fileB.toString(), ".b.txt.meta.seqdev").containsAll(head));
      assertEquals(2, head.size());
      assertEquals("content", read(WS2, fileB));
      assertEquals("content", read(WS1, fileA));
      assertTrue(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(".a.txt.meta.seqdev")));
      assertEquals(sourceHead, head(WS1));
      assertClean(WS1);
      assertClean(WS2);
    }

    @Test
    void destinationCommitFailureRestoresBothWorkspaces() throws Exception {
      saveOk(WS1, fileA, "content");
      final var sourceHead = head(WS1);
      final var destHead = head(WS2);
      history.failCommitsIn.add(wsRepo.workspaceRootPath(WS2));

      assertFailure(bindings.handleMove(fileA, fileB, WS1, WS2, false, USER), 500, "HISTORY_NOT_RECORDED");
      assertEquals("content", read(WS1, fileA));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS2).resolve(fileB)));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS2).resolve(".b.txt.meta.seqdev")));
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
      try(final var pool = Executors.newFixedThreadPool(2)) {
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
      saveOk(WS1, fileA, "content");
    }

    @Test
    void readsOfGitInternalsAreRejected() {
      for (final var path : List.of(".git/HEAD", ".git/config", ".git/refs/heads/main", "foo/.git/config",
                                    ".GIT/HEAD", "a/../.git/HEAD", ".seqdev/state.json", ".gitignore")) {
        assertThrows(SecurityException.class, () -> fs.loadFile(WS1, Path.of(path)), path);
        assertThrows(SecurityException.class, () -> fs.checkFileExists(WS1, Path.of(path)), path);
      }
    }

    @Test
    void writesOfGitInternalsAreRejected() throws Exception {
      final var config = Files.readString(wsRepo.workspaceRootPath(WS1).resolve(".git/config"));
      for (final var path : List.of(".git/config", ".git/hooks/post-commit", "foo/.git/HEAD", ".gitattributes", ".gitignore", ".seqdev/state.json")) {
        assertFailure(save(WS1, Path.of(path), "evil", "*"), 400, "SECURITY_EXCEPTION");
      }
      assertFailure(bindings.handleCreateDirectory(WS1, Path.of("x/.git"), USER), 400, "SECURITY_EXCEPTION");
      assertEquals(config, Files.readString(wsRepo.workspaceRootPath(WS1).resolve(".git/config")));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(".gitignore")));
      assertClean(WS1);
    }

    @Test
    void moveCopyAndDeleteInvolvingGitInternalsAreRejected() throws Exception {
      final var head = head(WS1);
      assertFailure(bindings.handleMove(Path.of(".git/HEAD"), Path.of("HEAD"), WS1, WS1, false, USER), 400, "SECURITY_EXCEPTION");
      assertFailure(bindings.handleMove(fileA, Path.of(".git/a.txt"), WS1, WS1, true, USER), 400, "SECURITY_EXCEPTION");
      assertFailure(bindings.handleCopy(Path.of(".git"), Path.of("leak"), WS1, WS2, false, USER), 400, "SECURITY_EXCEPTION");
      assertFailure(bindings.handleCopy(fileA, Path.of(".git/objects/x"), WS1, WS1, true, USER), 400, "SECURITY_EXCEPTION");
      assertFailure(bindings.handleDelete(WS1, Path.of(".git"), USER), 400, "SECURITY_EXCEPTION");
      assertFailure(bindings.handleDelete(WS1, Path.of(".seqdev/state.json"), USER), 400, "SECURITY_EXCEPTION");
      assertEquals(head, head(WS1));
      assertTrue(Files.isRegularFile(wsRepo.workspaceRootPath(WS1).resolve(".git/HEAD")));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS2).resolve("leak")));
      assertClean(WS1);
    }

    @Test
    void listingsNeverIncludeInternalDirectories() throws IOException {
      final var listed = WorkspacePaths.walk(wsRepo.workspaceRootPath(WS1), Integer.MAX_VALUE).stream()
                                       .map(p -> wsRepo.workspaceRootPath(WS1).relativize(p).toString()).toList();
      assertTrue(listed.contains(fileA.toString()));
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
      try(final var pool = Executors.newFixedThreadPool(2)) {
        final var first = pool.submit(() -> history.mutate(WS1, "first", "first", () -> {
          Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "first");
          firstWrote.countDown();
          assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
          return true;
        }, r -> true));
        assertTrue(firstWrote.await(10, TimeUnit.SECONDS));
        final var second = pool.submit(() -> history.mutate(WS1, "second", "second", () -> {
          secondEntered.set(true);
          Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "second");
          return true;
        }, r -> true));

        Thread.sleep(300);
        assertFalse(secondEntered.get(), "the second mutation started before the first was committed");
        releaseFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
      }

      // A valid serial order: first's commit, then second's on top of it.
      final var commits = log(WS1);
      assertEquals("second", commits.get(0).getFullMessage());
      assertEquals("first", commits.get(1).getFullMessage());
      assertEquals("second", read(WS1, fileA));
      assertClean(WS1);
    }

    @Test
    void concurrentSavesAllLandInHistory() throws Exception {
      init(WS1);
      final var commitsBefore = log(WS1).size();
      try(final var pool = Executors.newFixedThreadPool(4)) {
        final var tasks = new ArrayList<Future<?>>();
        for (int t = 0; t < 4; t++) {
          final var thread = t;
          tasks.add(pool.submit(() -> {
            for (int i = 0; i < 10; i++) saveOk(WS1, "shared.txt", "t" + thread + "-" + i);
          }));
        }
        for (final var task : tasks) {
          task.get(60, TimeUnit.SECONDS);
        }
      }

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
      final var newRoot = wsRepo.createWorkspace("new");

      history.initializeNewWorkspace(newRoot);

      assertTrue(history.isManaged(newRoot));
      try (final var git = Git.open(newRoot.toFile())) {
        final var commits = new ArrayList<RevCommit>();
        git.log().call().forEach(commits::add);
        assertEquals(1, commits.size());
        assertEquals(WorkspaceGitService.INIT_MESSAGE, commits.getFirst().getFullMessage());
        assertEquals(Set.of(), WorkspaceGitService.dirtyPaths(git.status().call()));
      }
    }

    @Test
    void newWorkspaceInitializationDoesNotAdoptExistingFiles() throws Exception {
      final var newRoot = wsRepo.createWorkspace("not-new");
      Files.writeString(newRoot.resolve("existing.txt"), "existing");

      assertThrows(IOException.class, () -> history.initializeNewWorkspace(newRoot));

      assertFalse(Files.exists(newRoot.resolve(WorkspacePaths.GIT_DIR)));
      assertEquals("existing", Files.readString(newRoot.resolve("existing.txt")));
    }

    @Test
    void failedInitializationDoesNotMarkTheWorkspaceManaged() throws Exception {
      final var newRoot = wsRepo.createWorkspace("failed");
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
      Files.createDirectories(wsRepo.workspaceRootPath(WS1).resolve("seq"));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("seq/a.txt"), "legacy A");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("seq/.a.txt.meta.seqdev"), """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z",\
          "lastEditedBy":"dave","lastEditedAt":"2025-02-02T00:00:00Z","readOnly":true,"user":{"status":"final"}}""");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileB), "legacy B");
    }

    @Test
    void aWorkspaceWithoutGitIsInitializedOnItsFirstMutation() throws Exception {
      writeLegacyWorkspace();
      init(WS1);

      final var commits = log(WS1);
      assertEquals(1, commits.size(), "creating an empty directory adds nothing beyond the baseline");
      assertEquals(WorkspaceGitService.INIT_MESSAGE, commits.getFirst().getFullMessage());
      assertEquals("SeqDev", commits.getFirst().getAuthorIdent().getName());

      final var expectedSet = Set.of(fileB.toString(), "seq/a.txt", "seq/.a.txt.meta.seqdev");
      final var head = headTree(WS1);
      assertTrue(expectedSet.containsAll(head));
      assertEquals(expectedSet.size(), head.size());
      assertClean(WS1);
      assertTrue(history.isManaged(wsRepo.workspaceRootPath(WS1)));
      try (final var git = git(WS1)) {
        assertEquals("/dev/null", git.getRepository().getConfig().getString("core", null, "attributesFile"),
                     "global gitattributes must not apply to workspaces");
      }

      // Legacy metadata stays with its content file. The baseline commit does not replace its real last-edit data.
      final var sidecarJson = Files.readString(wsRepo.workspaceRootPath(WS1).resolve("seq/.a.txt.meta.seqdev"));
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
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("seq/.meta.seqdev"), "{\"readOnly\":true}");
      init(WS1);

      final var readOnly = fs.getReadOnlyFiles(WS1, Path.of("seq"));

      assertEquals(List.of(wsRepo.workspaceRootPath(WS1).resolve("seq/a.txt")), readOnly);
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
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("seq/.gitignore"), "*.txt\n");
      assertFailure(bindings.handleCreateDirectory(WS1, Path.of("x"), USER), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(".git")));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("x")));
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
      setReadOnly(WS1, Path.of("seq/a.txt"), false);
      assertEquals("dave", fs.getLastEditInfo(WS1, Path.of("seq/a.txt")).lastEditedBy(),
                   "changing metadata is not a content edit");

      saveOk(WS1, "seq/a.txt", "changed");

      assertEquals(USER_IDENT, fs.getLastEditInfo(WS1, Path.of("seq/a.txt")).lastEditedBy());
      assertClean(WS1);
    }
  }

  /** Managed Git structure must remain trusted before drift can be healed. */
  @Nested
  class Trust {
    private static final String INCONSISTENT = "WORKSPACE_REPOSITORY_INCONSISTENT";

    @BeforeEach
    void initWs() {
      saveOk(WS1, fileA, "v1");
    }

    /** Asserts a save is refused and changes nothing: no file, no commit. */
    private String assertRefused() throws Exception {
      final var commits = log(WS1).size();
      final var result = save(WS1, fileB, "v1", null);
      assertFailure(result, 500, INCONSISTENT);
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(fileB)));
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
        final var file = wsRepo.workspaceRootPath(WS1).resolve(".git").resolve(marker);
        Files.createDirectories(file.getParent());
        Files.writeString(file, head);
        assertTrue(assertRefused().contains("in progress"), marker);
        Files.delete(file);
      }
      Files.delete(wsRepo.workspaceRootPath(WS1).resolve(".git/rebase-merge"));
      saveOk(WS1, fileB, "v1");
    }

    @Test
    void internalDirectoryHasNoRequiredPersistentSchema() throws Exception {
      final var arbitrary = wsRepo.workspaceRootPath(WS1).resolve(".seqdev/state.json");
      Files.writeString(arbitrary, "no persistent schema");
      saveOk(WS1, fileB, "v1");
      assertEquals("no persistent schema", Files.readString(arbitrary));
      assertClean(WS1);
    }

    @Test
    void staleTempFilesAreRemovedAndNothingElseIs() throws Exception {
      final var seqdev = wsRepo.workspaceRootPath(WS1).resolve(".seqdev");
      final var stale = seqdev.resolve("tmp-" + UUID.randomUUID());
      Files.writeString(stale, "left by a crash");
      final var others = List.of(seqdev.resolve("tmp-notauuid"), seqdev.resolve("notes.tmp"));
      for (final var other : others) Files.writeString(other, "not ours");

      saveOk(WS1, fileB, "v1");
      assertFalse(Files.exists(stale));
      others.forEach(o -> assertTrue(Files.exists(o), o::toString));
      assertClean(WS1);
    }

    @Test
    void anAdoptionThatDidNotFinishIsRedoneNotRejected() throws Exception {
      try (final var git = git(WS1)) {
        final var cfg = git.getRepository().getConfig();
        cfg.unset("seqdev", null, "managed");
        cfg.save();
      }
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "written before the crash");
      saveOk(WS1, fileB, "v1");
      assertEquals(WorkspaceGitService.ADOPT_MESSAGE, log(WS1).get(1).getFullMessage());
      assertTrue(history.isManaged(wsRepo.workspaceRootPath(WS1)));
      assertClean(WS1);
    }
  }

  /** Recovery always heals the whole workspace, including drift unrelated to the upcoming mutation. */
  @Nested
  class Drift {
    @BeforeEach
    void initWs() {
      for (final var path : List.of(fileA, fileB, goneFile, fileX)) {
        saveOk(WS1, path, "v1");
      }
    }

    private void assertFailedWith(final HandlerResult result, final String message) {
      assertFailure(result, 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(result.jsonResponse().asJsonObject().getString("message").contains(message),
                 () -> result.jsonResponse().toString());
    }

    @Test
    void allDriftIsPreservedAndCleanBeforeTheMutationStarts() throws Exception {
      final var before = head(WS1);
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "external modified bytes");
      Files.createDirectories(wsRepo.workspaceRootPath(WS1).resolve("unrelated/nested"));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("unrelated/nested/new.txt"), "external untracked bytes");
      Files.delete(wsRepo.workspaceRootPath(WS1).resolve(goneFile));

      assertTrue(history.mutate(WS1, USER, "save b", () -> {
        assertClean(WS1);
        assertEquals(before, head(WS1));
        assertEquals("v1", read(WS1, fileA));
        assertEquals("v1", read(WS1, goneFile));
        assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("unrelated/nested/new.txt")));
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileB), "intended mutation");
        return true;
      }, r -> r));

      final var incident = onlyIncident(WS1);
      final var json = incidentJson(incident);
      assertEquals(1, json.getInt("version"));
      assertDoesNotThrow(() -> Instant.parse(json.getString("timestamp")));
      assertEquals(before.name(), json.getString("head"));
      assertEquals("out-of-band filesystem change", json.getString("reason"));
      assertTrue(incident.getFileName().toString().matches("[0-9]{8}T[0-9]{9}Z-[0-9a-f-]{36}"));

      final Map<String, String> expectedReturn = Map.of(fileA.toString(), "modified", goneFile.toString(), "deleted", "unrelated/nested/new.txt", "untracked");
      final var changes = changesIn(incident);
      assertTrue(expectedReturn.entrySet().containsAll(changes.entrySet()));
      assertEquals(expectedReturn.size(), changes.size());
      assertEquals("external modified bytes", preserved(incident, fileA));
      assertEquals("external untracked bytes", preserved(incident, Path.of("unrelated/nested/new.txt")));
      assertFalse(Files.exists(incident.resolve("files/gone.txt")));
      assertEquals(List.of(fileB.toString()), headDiff(WS1).stream().map(DiffEntry::getNewPath).toList());
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
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "external bytes");
      saveOk(WS1, fileA, "intended bytes");
      assertEquals("intended bytes", read(WS1, fileA));
      assertEquals("external bytes", preserved(onlyIncident(WS1), fileA));
      assertClean(WS1);
    }

    @Test
    void healingDoesNotChangeWholeWorkspaceRollback() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "external bytes");
      assertFalse(history.mutate(WS1, USER, "failing mutation", () -> {
        assertClean(WS1);
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileB), "partial mutation");
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve("created.txt"), "partial new file");
        return false;
      }, r -> r));
      assertEquals("v1", read(WS1, fileA));
      assertEquals("v1", read(WS1, fileB));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("created.txt")));
      assertEquals("external bytes", preserved(onlyIncident(WS1), fileA));
      assertClean(WS1);
    }

    @Test
    void symlinksFailClosedBeforeAnyBytesAreMoved() throws Exception {
      final var outside = tmp.resolve("outside.txt");
      Files.writeString(outside, "outside bytes");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "external bytes");
      Files.delete(wsRepo.workspaceRootPath(WS1).resolve(fileX));
      Files.delete(wsRepo.workspaceRootPath(WS1).resolve("d/.x.txt.meta.seqdev"));
      Files.delete(wsRepo.workspaceRootPath(WS1).resolve("d"));
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("d"), tmp);
      final var before = head(WS1);

      assertFailedWith(save(WS1, fileB, "intended bytes", "*"), "could not be preserved");
      assertEquals(before, head(WS1));
      assertEquals("external bytes", read(WS1, fileA));
      assertEquals("outside bytes", Files.readString(outside));
      assertTrue(Files.isSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("d")));
      assertEquals(List.of(), incidents(WS1));
    }

    @Test
    void aSymlinkedRecoveryDirectoryIsNeverFollowed() throws Exception {
      final var outside = Files.createDirectory(tmp.resolve("outside"));
      Files.createSymbolicLink(WorkspaceGitService.recoveryDir(wsRepo.workspaceRootPath(WS1)), outside);
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "external bytes");
      assertFailedWith(save(WS1, fileB, "intended bytes", "*"), "could not be preserved");
      assertEquals("external bytes", read(WS1, fileA));
      try (final var entries = Files.list(outside)) {
        assertEquals(0, entries.count());
      }
    }

    @Test
    void aSpecialFileIsRejectedWithoutMovingOtherDrift() throws Exception {
      assumeTrue(POSIX);
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "external bytes");
      final var socket = wsRepo.workspaceRootPath(WS1).resolve("special.sock");
      try (final var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
        server.bind(UnixDomainSocketAddress.of(socket));
        assertFailure(save(WS1, fileB, "intended bytes", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
        assertEquals("external bytes", read(WS1, fileA));
        assertTrue(Files.exists(socket));
        assertEquals(List.of(), incidents(WS1));
      }
    }

    @Test
    void movesFallBackWhenAtomicMoveIsUnsupported() throws Exception {
      final var source = tmp.resolve("source.txt");
      Files.writeString(source, "preserved bytes");
      final var uri = URI.create("jar:" + tmp.resolve("archive.zip").toUri());
      try (final var zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
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
      final var file = wsRepo.workspaceRootPath(WS1).resolve(fileA);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
      saveOk(WS1, fileB, "intended bytes");
      final var incident = onlyIncident(WS1);
      assertEquals("v1", preserved(incident, fileA));
      assertTrue(Files.isExecutable(incident.resolve("files/a.txt")));
      assertTrue(incidentJson(incident).getJsonArray("paths").getJsonObject(0).getBoolean("executable"));
      assertFalse(Files.isExecutable(file));
      assertClean(WS1);
    }

    @Test
    void stagedChangesStillBlockTheWholeWorkspaceWithoutAnIncident() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "X");
      try (final var git = git(WS1)) {
        git.add().addFilepattern(WorkspacePaths.toGitPath(fileA)).call();
      }
      assertFailedWith(save(WS1, fileA, "Y", "*"), "staged changes");
      assertFailedWith(save(WS1, fileB, "Y", "*"), "staged changes");
      assertEquals("X", read(WS1, fileA));
      assertFalse(Files.exists(WorkspaceGitService.recoveryDir(wsRepo.workspaceRootPath(WS1))));
    }

    @Test
    void ifTheBytesCannotBePreservedNothingIsTouched() throws Exception {
      final var commits = log(WS1).size();
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "X");
      Files.writeString(WorkspaceGitService.recoveryDir(wsRepo.workspaceRootPath(WS1)), "in the way");

      assertFailedWith(save(WS1, fileA, "Y", "*"), "could not be preserved");
      assertEquals("X", read(WS1, fileA));
      assertEquals(commits, log(WS1).size());
      assertFalse(Files.exists(mutationMarker(WS1)), "failed preservation must not authorize crash recovery");

      Files.delete(WorkspaceGitService.recoveryDir(wsRepo.workspaceRootPath(WS1)));
      saveOk(WS1, fileA, "Y");
      assertEquals("X", preserved(onlyIncident(WS1), fileA));
    }

    private WorkspaceHistoryException healFailingOnB() {
      history.failMovesEndingWith = "files/b.txt";
      try {
        return assertThrows(WorkspaceHistoryException.class, () ->
            history.mutate(WS1, USER, "x", () -> true, r -> r));
      } finally {
        history.failMovesEndingWith = null;
        history.failMovesTo.clear();
      }
    }

    @Test
    void aPartlyPreservedWorkspaceIsPutBack() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "X");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileB), "Y");

      assertTrue(healFailingOnB().getMessage().contains("left untouched"));
      assertEquals("X", read(WS1, fileA));
      assertEquals("Y", read(WS1, fileB));
      assertEquals(List.of(), incidents(WS1));
    }

    @Test
    void aFileThatCannotBeMovedBackStaysInTheIncident() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "X");
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileB), "Y");
      history.failMovesTo.add(wsRepo.workspaceRootPath(WS1).resolve(fileA));

      final var message = healFailingOnB().getMessage();
      assertTrue(message.contains("could not be moved back") && message.contains(fileA.toString()));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(fileA)));
      assertEquals("X", preserved(onlyIncident(WS1), fileA));
      assertEquals("Y", read(WS1, fileB));
      assertEquals(Map.of(fileA.toString(), "modified", fileB.toString(), "modified"), changesIn(onlyIncident(WS1)));
    }

    @Test
    void aPruneFailureDoesNotFailTheMutation() throws Exception {
      assumeTrue(POSIX);
      final var recovery = Files.createDirectories(WorkspaceGitService.recoveryDir(wsRepo.workspaceRootPath(WS1)));
      for (int i = 0; i < WorkspaceGitService.MAX_INCIDENTS; i++) {
        Files.createDirectories(recovery.resolve("00000000T000000000Z-old-%02d/files".formatted(i)));
        Files.writeString(recovery.resolve("00000000T000000000Z-old-%02d/files/f".formatted(i)), "old");
      }
      final var locked = recovery.resolve("00000000T000000000Z-old-00/files");
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
      try {
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "X");
        saveOk(WS1, fileA, "Y");
      } finally {
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
      assertEquals("Y", read(WS1, fileA));
      assertEquals("X", preserved(incidents(WS1).getLast(), fileA));
      assertTrue(Files.exists(locked.resolve("f")), "the old incident could not be pruned; that was only logged");
    }

    @Test
    void failedOobHealRestoreLeavesAMarkerAndTheNextRequestRecovers() throws Exception {
      assumeTrue(POSIX);
      final var commits = log(WS1).size();
      final var dir = wsRepo.workspaceRootPath(WS1).resolve("d");
      Files.delete(dir.resolve("x.txt"));
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x")); // x.txt cannot be rewritten
      try {
        assertFailedWith(save(WS1, fileX, "Y", "*"), "could not be restored");
      } finally {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
      assertEquals(commits, log(WS1).size());
      assertFalse(Files.exists(dir.resolve("x.txt")));
      final var incident = onlyIncident(WS1);
      assertEquals(Map.of(fileX.toString(), "deleted"), changesIn(incident));
      assertTrue(Files.exists(mutationMarker(WS1)), "restore failed after preservation; recovery must remain authorized");

      // Simulate the stale lock and index residue a crash during checkout can leave behind.
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "interrupted restore bytes");
      try (final var git = git(WS1)) {
        git.add().addFilepattern(WorkspacePaths.toGitPath(fileA)).call();
      }
      final var indexLock = wsRepo.workspaceRootPath(WS1).resolve(".git/index.lock");
      Files.writeString(indexLock, "stale");
      restartServer();
      saveOk(WS1, fileB, "next request");

      assertEquals("v1", read(WS1, fileA));
      assertEquals("v1", read(WS1, fileX));
      assertEquals("next request", read(WS1, fileB));
      assertFalse(Files.exists(indexLock));
      assertFalse(Files.exists(mutationMarker(WS1)));
      assertTrue(incidents(WS1).contains(incident));
      final var recovery = incidents(WS1).stream().filter(p -> !p.equals(incident)).toList();
      assertEquals(1, recovery.size());
      assertEquals("interrupted restore bytes", preserved(recovery.getFirst(), fileA));
      assertEquals("interrupted mutation recovery", incidentJson(recovery.getFirst()).getString("reason"));
      assertClean(WS1);
    }

    @Test
    void recoveryArtifactsAreInvisibleToGitAndTheFileApi() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "X");
      saveOk(WS1, fileA, "Y");
      final var incident = wsRepo.workspaceRootPath(WS1).relativize(onlyIncident(WS1));

      assertClean(WS1);
      assertTrue(headTree(WS1).stream().noneMatch(p -> p.startsWith(".seqdev")));
      assertTrue(WorkspacePaths.walk(wsRepo.workspaceRootPath(WS1), Integer.MAX_VALUE).stream()
                               .noneMatch(p -> wsRepo.workspaceRootPath(WS1).relativize(p).startsWith(".seqdev")));
      for (final var path : List.of(incident.resolve("incident.json"), incident.resolve("files/a.txt"))) {
        assertThrows(SecurityException.class, () -> fs.loadFile(WS1, path), path::toString);
        assertFailure(save(WS1, path, "evil", "*"), 400, "SECURITY_EXCEPTION");
      }
    }

    @Test
    void onlyTheNewestIncidentsAreKept() throws Exception {
      for (int i = 0; i < WorkspaceGitService.MAX_INCIDENTS + 3; i++) {
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "drift " + i);
        saveOk(WS1, fileA, "v1");
      }
      final var kept = incidents(WS1);
      assertEquals(WorkspaceGitService.MAX_INCIDENTS, kept.size());
      assertEquals("drift 3", preserved(kept.getFirst(), fileA));
      assertEquals("drift " + (WorkspaceGitService.MAX_INCIDENTS + 2), preserved(kept.getLast(), fileA));
    }

    @Test
    void theCurrentIncidentIsNeverPruned() throws Exception {
      final var recovery = Files.createDirectories(WorkspaceGitService.recoveryDir(wsRepo.workspaceRootPath(WS1)));
      for (int i = 0; i < WorkspaceGitService.MAX_INCIDENTS; i++) {
        Files.createDirectories(recovery.resolve("99991231T235959999Z-future-%02d".formatted(i)));
      }
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "X");
      saveOk(WS1, fileA, "Y");

      final var kept = incidents(WS1);
      assertEquals(WorkspaceGitService.MAX_INCIDENTS, kept.size());
      final var current = kept.stream().filter(p -> !p.getFileName().toString().startsWith("9999")).toList();
      assertEquals(1, current.size(), kept::toString);
      assertEquals("X", preserved(current.getFirst(), fileA));
    }
  }

  @Nested
  class Adoption {
    private Git foreignRepo(final int ws, final String branch) throws GitAPIException, IOException {
      final var git = Git.init().setDirectory(wsRepo.workspaceRootPath(ws).toFile()).setInitialBranch(branch).call();
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
        Files.createDirectories(wsRepo.workspaceRootPath(ws).resolve(name).getParent());
        try (final var git = foreignRepo(ws, "main")) {
          Files.writeString(wsRepo.workspaceRootPath(ws).resolve(fileA), "A");
          Files.writeString(wsRepo.workspaceRootPath(ws).resolve(name), "x\n");
          commitAll(git);
        }
        assertClean(ws);
        final var commits = log(ws).size();

        assertFailure(bindings.handleCreateDirectory(ws, Path.of("x"), USER), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
        assertEquals(commits, log(ws).size(), name);
        assertFalse(history.isManaged(wsRepo.workspaceRootPath(ws)), name);
      }
    }

    @Test
    void anExistingRepoIsAdoptedWithItsUncommittedState() throws Exception {
      try (final var git = foreignRepo(WS1, "main")) {
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "committed");
        commitAll(git);
      }
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "uncommitted");
      saveOk(WS1, fileB, "B");

      final var commits = log(WS1);
      assertEquals(List.of("Update b.txt", WorkspaceGitService.ADOPT_MESSAGE, "external"),
                   commits.stream().map(RevCommit::getFullMessage).toList());
      assertEquals("uncommitted", read(WS1, fileA));
      assertClean(WS1);
    }

    @Test
    void existingInternalScratchFilesDoNotBlockAdoption() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "A");
      Files.createDirectories(wsRepo.workspaceRootPath(WS1).resolve(".seqdev"));
      final var arbitrary = wsRepo.workspaceRootPath(WS1).resolve(".seqdev/state.json");
      Files.writeString(arbitrary, "no schema");

      init(WS1);

      assertTrue(history.isManaged(wsRepo.workspaceRootPath(WS1)));
      assertEquals("no schema", Files.readString(arbitrary));
      assertClean(WS1);
    }

    @Test
    void anExistingRepoOnAnotherBranchIsRejected() throws Exception {
      final var legacySidecar = """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z","readOnly":true}""";
      try (final var git = foreignRepo(WS1, "master")) {
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "A");
        Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(".a.txt.meta.seqdev"), legacySidecar);
        commitAll(git);
      }
      assertFailure(save(WS1, fileB, "B", null), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(fileB)));
      assertFalse(history.isManaged(wsRepo.workspaceRootPath(WS1)));
      assertEquals(legacySidecar, read(WS1, ".a.txt.meta.seqdev"), "nothing is migrated before validation passes");
      assertClean(WS1);
    }
  }

  @Nested
  class Symlinks {
    private Path outside;

    @BeforeEach
    void setUpLinks() throws IOException {
      outside = wsRepo.createWorkspace("outside");
      Files.writeString(outside.resolve("secret.txt"), "secret");
    }

    @Test
    void anUnmanagedWorkspaceWithASymlinkIsNotAdopted() throws Exception {
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileA), "A");
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("link"), outside);
      final var result = bindings.handleCreateDirectory(WS1, Path.of("x"), USER);
      assertFailure(result, 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(result.jsonResponse().asJsonObject().getString("message").contains("link (symbolic link)"));
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(".git")));
    }

    @Test
    void pathsThroughASymlinkCannotBeReadOrWritten() throws Exception {
      saveOk(WS1, fileX, "x");
      final var head = head(WS1);
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("toGit"), Path.of(".git"));
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("toOutside"), outside);
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("toInside"), Path.of("d"));

      for (final var path : List.of("toGit/config", "toOutside/secret.txt", "toInside/x.txt", "toGit", "toInside")) {
        assertThrows(SecurityException.class, () -> fs.loadFile(WS1, Path.of(path)), path);
        assertThrows(SecurityException.class, () -> fs.checkFileExists(WS1, Path.of(path)), path);
      }
      // The workspace is no longer as PlanDev left it, so nothing is written anywhere, inside or outside it.
      assertFailure(save(WS1, Path.of("toOutside/secret.txt"), "overwritten", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFailure(save(WS1, Path.of("toGit/config"), "overwritten", "*"), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
      assertEquals(head, head(WS1));
    }

    @Test
    void aSymlinkCommittedOutOfBandIsRejected() throws Exception {
      saveOk(WS1, fileA, "A");
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("link"), outside);
      try (final var git = git(WS1)) {
        git.add().addFilepattern("link").call();
        git.commit().setMessage("sneak").setSign(false).call();
      }
      assertClean(WS1);
      final var result = save(WS1, fileB, "B", null);
      assertFailure(result, 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertTrue(result.jsonResponse().asJsonObject().getString("message").contains("link"));
    }

    @Test
    void scansDoNotFollowOrReturnSymlinks() throws IOException {
      Files.createDirectories(wsRepo.workspaceRootPath(WS1).resolve("d"));
      Files.writeString(wsRepo.workspaceRootPath(WS1).resolve(fileX), "x");
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("toOutside"), outside);
      Files.createSymbolicLink(wsRepo.workspaceRootPath(WS1).resolve("toInside"), Path.of("d"));
      final var listed = WorkspacePaths.walk(wsRepo.workspaceRootPath(WS1), Integer.MAX_VALUE).stream()
                                       .map(p -> WorkspacePaths.key(wsRepo.workspaceRootPath(WS1), p)).toList();
      assertEquals(List.of("d", fileX.toString()), listed.stream().sorted().toList());
    }
  }

  /**
   * A copied file is a new file, whether it is copied on its own or inside a directory; a copied or moved file never
   * inherits metadata from a file it overwrites.
   */
  @Nested
  class CopySemantics {
    /** Destination has carol's sidecar with user metadata; source has no sidecar at all. */
    private void sourceWithoutSidecarAndDestinationWithOne() throws Exception {
      assertSuccess(bindings.handleFileUpload(WS1, Path.of("dest.txt"), upload("dest.txt", "old"), true, null, "carol"));
      setUserMetadata(WS1, "dest.txt", "final");
      saveOk(WS1, "src.txt", "new");
      deleteSidecar(WS1, "src.txt");
      assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve(".src.txt.meta.seqdev")));
    }

    @Test
    void aCopyDoesNotInheritTheOverwrittenFilesMetadata() throws Exception {
      sourceWithoutSidecarAndDestinationWithOne();
      assertSuccess(bindings.handleCopy(Path.of("src.txt"), Path.of("dest.txt"), WS1, WS1, true, USER));
      final var meta = metadata(WS1, "dest.txt");
      assertFalse(meta.containsKey("user"), meta::toString);
      assertEquals(USER, meta.getString("createdBy"));
      assertEquals("new", read(WS1, "dest.txt"));
      assertClean(WS1);
    }

    @Test
    void aMoveDoesNotInheritTheOverwrittenFilesMetadata() throws Exception {
      sourceWithoutSidecarAndDestinationWithOne();
      assertSuccess(bindings.handleMove(Path.of("src.txt"), Path.of("dest.txt"), WS1, WS1, true, USER));
      final var meta = metadata(WS1, "dest.txt");
      assertFalse(meta.containsKey("user"), meta::toString);
      assertEquals(USER, meta.getString("createdBy"));
      assertEquals("new", read(WS1, "dest.txt"));
      assertClean(WS1);
    }

    @Test
    void theExecutableBitSurvivesSavesAndCopies() throws Exception {
      assumeTrue(POSIX, "needs POSIX permissions");
      saveOk(WS1, "run.sh", "v1");
      Files.setPosixFilePermissions(wsRepo.workspaceRootPath(WS1).resolve("run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
      try (final var git = git(WS1)) { // record the mode as PlanDev would find it in a managed workspace
        git.add().addFilepattern("run.sh").call();
        git.commit().setMessage("chmod").setSign(false).call();
      }

      saveOk(WS1, "run.sh", "v2");
      assertTrue(Files.isExecutable(wsRepo.workspaceRootPath(WS1).resolve("run.sh")));
      final var diff = headDiff(WS1);
      assertEquals(1, diff.size(), diff::toString);
      assertEquals(FileMode.EXECUTABLE_FILE, diff.getFirst().getOldMode());
      assertEquals(FileMode.EXECUTABLE_FILE, diff.getFirst().getNewMode(), "a save changes content, not mode");

      assertSuccess(bindings.handleCopy(Path.of("run.sh"), Path.of("copy.sh"), WS1, WS1, false, USER));
      assertTrue(Files.isExecutable(wsRepo.workspaceRootPath(WS1).resolve("copy.sh")));
      assertClean(WS1);
    }

    @Test
    void fileAndDirectoryCopiesProduceTheSameMetadata() throws Exception {
      assertSuccess(bindings.handleFileUpload(WS1, fileX, upload("x.txt", "x"), true, null, "carol"));
      setUserMetadata(WS1, fileX.toString(), "final");
      setReadOnly(WS1, fileX, true);

      assertSuccess(bindings.handleCopy(fileX, Path.of("single.txt"), WS1, WS1, false, USER));
      assertSuccess(bindings.handleCopy(Path.of("d"), Path.of("e"), WS1, WS1, false, USER));

      final var single = metadata(WS1, "single.txt");
      final var inDir = metadata(WS1, "e/x.txt");
      for (final var copy : List.of(single, inDir)) {
        assertEquals(USER, copy.getString("createdBy"), copy::toString);
        assertEquals(USER_IDENT, copy.getString("lastEditedBy"), copy::toString);
        assertEquals("final", copy.getJsonObject("user").getString("status"), copy::toString);
        assertFalse(copy.getBoolean("readOnly"), copy::toString);
      }
      final var ignoringTimes = (Function<JsonObject, JsonObject>) o ->
          Json.createObjectBuilder(o).remove("createdAt").remove("lastEditedAt").build();
      assertEquals(ignoringTimes.apply(single), ignoringTimes.apply(inDir));
      assertTrue(isReadOnly(WS1, fileX.toString()), "the source keeps its lock");
      assertFalse(isReadOnly(WS1, "e/x.txt"));
      assertEquals("carol", metadata(WS1, fileX.toString()).getString("createdBy"));
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
    assertFalse(Files.exists(wsRepo.workspaceRootPath(WS1).resolve("x")));
  }
}
