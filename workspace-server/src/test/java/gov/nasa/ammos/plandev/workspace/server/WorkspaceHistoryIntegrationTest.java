package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRevisionStore.Revision;
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
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
    bindings = new WorkspaceBindings(null, fs, history, null, null, "");
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

  private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

  private static void deleteRecursively(final Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (final var paths = Files.walk(path)) {
      for (final var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  /** Leave a file without a sidecar, as a pre-metadata file would be (the metadata API keeps an identity's sidecar). */
  private void deleteSidecar(final int ws, final String path) throws Exception {
    history.mutate(ws, USER, "drop metadata", () -> Files.deleteIfExists(root(ws).resolve(sidecar(path))), r -> r);
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
    /**
     * Deterministic injection at the mutation boundary: every kind of file change is rolled back. Empty directories
     * are not versioned state, so the layout of empty directories is not part of what rollback guarantees.
     */
    @Test
    void injectedCommitFailureRestoresThePreOperationState() throws Exception {
      saveOk(WS1, "keep.txt", "original");
      saveOk(WS1, "gone.txt", "will be deleted");
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
            final var state = WorkspaceState.load(root(WS1));
            state.setReadOnly("keep.txt", true);
            state.save();
            return true;
          }, r -> true));
      assertEquals(WorkspaceHistory.Kind.HISTORY_NOT_RECORDED, ex.kind);

      assertEquals("original", read(WS1, "keep.txt"));
      assertEquals("will be deleted", read(WS1, "gone.txt"));
      assertFalse(Files.exists(root(WS1).resolve("new")), "folders left empty by removing created files are pruned");
      assertFalse(Files.exists(root(WS1).resolve("untracked.txt")));
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
      try (final var internal = Files.list(root(WS1).resolve(".seqdev"))) {
        assertEquals(List.of("state.json"), internal.map(p -> p.getFileName().toString()).toList(), "no upload temp files left");
      }

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
      assertTrue(history.isManaged(root(WS1)));
      try (final var git = git(WS1)) {
        assertEquals("/dev/null", git.getRepository().getConfig().getString("core", null, "attributesFile"),
                     "global gitattributes must not apply to workspaces");
      }

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

    /** Interrupted after the sidecars' runtime fields reached state.json but before every sidecar was rewritten. */
    @Test
    void anInterruptedMigrationIsFinished() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "A");
      Files.writeString(root(WS1).resolve(".a.txt.meta.seqdev"), """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z"}""");
      Files.writeString(root(WS1).resolve("b.txt"), "B");
      Files.writeString(root(WS1).resolve(".b.txt.meta.seqdev"), """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z",\
          "lastEditedBy":"erin","lastEditedAt":"2025-03-03T00:00:00Z","readOnly":true}""");
      Files.createDirectories(root(WS1).resolve(".seqdev"));
      Files.writeString(WorkspaceState.file(root(WS1)), """
          {"version":1,"files":{"a.txt":{"readOnly":true,"legacyLastEditedBy":"dave","legacyLastEditedAt":"2025-02-02T00:00:00Z"}}}""");

      init(WS1);

      final var sidecarB = Files.readString(root(WS1).resolve(".b.txt.meta.seqdev"));
      assertFalse(sidecarB.contains("readOnly") || sidecarB.contains("lastEdited"), sidecarB);
      assertTrue(isReadOnly(WS1, "a.txt"), "already-migrated state survives");
      assertTrue(isReadOnly(WS1, "b.txt"), "the remaining legacy sidecar is migrated");
      assertEquals("dave", fs.getLastEditInfo(WS1, Path.of("a.txt")).lastEditedBy());
      assertEquals("erin", fs.getLastEditInfo(WS1, Path.of("b.txt")).lastEditedBy());
      assertClean(WS1);
    }

    /**
     * A real I/O failure while replacing one sidecar (its directory is not writable): the sidecar is either fully
     * legacy or fully migrated, never partial, and the next adoption finishes the job without losing metadata.
     */
    @Test
    void aFailedSidecarReplacementIsAtomicAndResumable() throws Exception {
      assumeTrue(POSIX && !"root".equals(System.getProperty("user.name")), "needs POSIX permissions enforced");
      final var legacy = """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z",\
          "lastEditedBy":"dave","lastEditedAt":"2025-02-02T00:00:00Z","readOnly":true}""";
      for (final var dir : List.of("a", "b")) {
        Files.createDirectories(root(WS1).resolve(dir));
        Files.writeString(root(WS1).resolve(dir + "/x.txt"), dir);
        Files.writeString(root(WS1).resolve(dir + "/.x.txt.meta.seqdev"), legacy);
      }
      final var locked = root(WS1).resolve("b");
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
      try {
        assertFailure(bindings.handleCreateDirectory(WS1, Path.of("z"), USER), 500, null);
      } finally {
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
      assertFalse(read(WS1, "a/.x.txt.meta.seqdev").contains("readOnly"), "a was migrated");
      assertEquals(legacy, read(WS1, "b/.x.txt.meta.seqdev"), "b is untouched, not partially written");
      assertEquals(Optional.of(true), WorkspaceState.load(root(WS1)).readOnly("b/x.txt"));
      try (final var internal = Files.list(root(WS1).resolve(".seqdev"))) {
        assertEquals(List.of("state.json"), internal.map(p -> p.getFileName().toString()).toList());
      }
      assertFalse(history.isManaged(root(WS1)));

      init(WS1);
      assertFalse(read(WS1, "b/.x.txt.meta.seqdev").contains("readOnly"));
      for (final var file : List.of("a/x.txt", "b/x.txt")) {
        assertTrue(isReadOnly(WS1, file), file);
        assertEquals("dave", fs.getLastEditInfo(WS1, Path.of(file)).lastEditedBy(), file);
      }
      assertClean(WS1);
    }

    /** A sidecar whose runtime fields cannot be read might be a lock; adoption refuses rather than unlocking it. */
    @Test
    void unmigratableLegacySidecarsFailAdoptionClosed() throws Exception {
      writeLegacyWorkspace();
      final var goodSidecar = read(WS1, "seq/.a.txt.meta.seqdev");
      Files.writeString(root(WS1).resolve(".b.txt.meta.seqdev"), "{\"readOnly\": tru");
      Files.writeString(root(WS1).resolve("c.txt"), "C");
      Files.writeString(root(WS1).resolve(".c.txt.meta.seqdev"), "{\"version\":\"1\",\"readOnly\":\"true\"}");

      final var result = bindings.handleCreateDirectory(WS1, Path.of("x"), USER);
      assertFailure(result, 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      final var message = result.jsonResponse().asJsonObject().getString("message");
      assertTrue(message.contains(".b.txt.meta.seqdev") && message.contains(".c.txt.meta.seqdev") && message.contains("readOnly"), message);
      assertFalse(Files.exists(root(WS1).resolve(".git")), "nothing was changed");
      assertFalse(Files.exists(WorkspaceState.file(root(WS1))));
      assertEquals(goodSidecar, read(WS1, "seq/.a.txt.meta.seqdev"));

      // Once repaired explicitly, adoption succeeds and every lock survives
      Files.writeString(root(WS1).resolve(".b.txt.meta.seqdev"), "{\"readOnly\": true}");
      Files.writeString(root(WS1).resolve(".c.txt.meta.seqdev"), "{\"version\":\"1\",\"readOnly\":true}");
      init(WS1);
      for (final var file : List.of("seq/a.txt", "b.txt", "c.txt")) assertTrue(isReadOnly(WS1, file), file);
      assertClean(WS1);
    }

    @Test
    void migrationKeepsEverythingButTheRuntimeFields() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "A");
      Files.writeString(root(WS1).resolve(".a.txt.meta.seqdev"), """
          {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z","user":{"status":"final"},\
          "custom":{"x":1},"readOnly":true,"lastEditedBy":"dave","lastEditedAt":"2025-02-02T00:00:00Z"}""");
      WorkspaceState.migrateFromSidecars(root(WS1));
      try (final var reader = Json.createReader(Files.newBufferedReader(root(WS1).resolve(".a.txt.meta.seqdev")))) {
        assertEquals(Json.createReader(new java.io.StringReader("""
            {"version":"1","createdBy":"carol","createdAt":"2025-01-01T00:00:00Z","user":{"status":"final"},"custom":{"x":1}}"""))
                         .readObject(), reader.readObject());
      }
    }

    @Test
    void migrationIsIdempotent() throws Exception {
      writeLegacyWorkspace();
      WorkspaceState.migrateFromSidecars(root(WS1));
      final var state = Files.readAllBytes(WorkspaceState.file(root(WS1)));
      final var sidecarA = Files.readAllBytes(root(WS1).resolve("seq/.a.txt.meta.seqdev"));
      WorkspaceState.migrateFromSidecars(root(WS1));
      assertArrayEquals(state, Files.readAllBytes(WorkspaceState.file(root(WS1))));
      assertArrayEquals(sidecarA, Files.readAllBytes(root(WS1).resolve("seq/.a.txt.meta.seqdev")));
    }
  }

  /** A managed workspace must be exactly as PlanDev left it before anything is mutated. */
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
    void outOfBandChangesAreRejectedNotCommitted() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "edited on disk");
      Files.writeString(root(WS1).resolve("dropped-in.txt"), "new on disk");

      final var message = assertRefused();
      assertTrue(message.contains("a.txt") && message.contains("dropped-in.txt"), message);
      assertEquals("edited on disk", read(WS1, "a.txt"), "left untouched, not reset");
      assertTrue(Files.exists(root(WS1).resolve("dropped-in.txt")));
      assertTrue(log(WS1).stream().noneMatch(c -> c.getAuthorIdent().getName().equals("PlanDev") && c.getParentCount() > 0));
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

    interface Corruption {
      void apply(Path seqdev, Path state) throws IOException;
    }

    /** Everything under .seqdev, so a test can tell the corruption was left exactly as it was. */
    private static String fingerprint(final Path seqdev) throws IOException {
      if (!Files.exists(seqdev, LinkOption.NOFOLLOW_LINKS)) return "absent";
      try (final var paths = Files.walk(seqdev)) {
        final var out = new StringBuilder();
        for (final var p : paths.sorted().toList()) {
          out.append(seqdev.relativize(p)).append(Files.isDirectory(p) ? "/" : "=" + Files.readString(p)).append('\n');
        }
        return out.toString();
      }
    }

    @Test
    void invalidRuntimeStateIsRejectedAndLeftUntouched() throws Exception {
      final var seqdev = root(WS1).resolve(".seqdev");
      final var state = WorkspaceState.file(root(WS1));
      final var good = Files.readAllBytes(state);
      final var cases = new LinkedHashMap<String, Corruption>();
      cases.put("missing .seqdev", (d, f) -> deleteRecursively(d));
      cases.put(".seqdev is a file", (d, f) -> { deleteRecursively(d); Files.writeString(d, "x"); });
      cases.put("missing state.json", (d, f) -> Files.delete(f));
      cases.put("state.json is a directory", (d, f) -> { Files.delete(f); Files.createDirectory(f); });
      cases.put("malformed JSON", (d, f) -> Files.writeString(f, "{\"version\":1,"));
      cases.put("unsupported version", (d, f) -> Files.writeString(f, "{\"version\":2,\"files\":{}}"));
      cases.put("missing version", (d, f) -> Files.writeString(f, "{\"files\":{}}"));
      cases.put("files is not an object", (d, f) -> Files.writeString(f, "{\"version\":1,\"files\":[]}"));
      cases.put("readOnly is not a boolean", (d, f) ->
          Files.writeString(f, "{\"version\":1,\"files\":{\"a.txt\":{\"readOnly\":\"no\"}}}"));

      for (final var c : cases.entrySet()) {
        c.getValue().apply(seqdev, state);
        final var corrupted = fingerprint(seqdev);
        assertTrue(assertRefused().contains("invalid runtime state"), c.getKey());
        assertEquals(corrupted, fingerprint(seqdev), c.getKey() + ": must not be repaired");

        deleteRecursively(seqdev);
        Files.createDirectories(seqdev);
        Files.write(state, good);
      }
      saveOk(WS1, "b.txt", "v1");
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

  @Nested
  class Adoption {
    private Git foreignRepo(final int ws, final String branch) throws GitAPIException {
      return Git.init().setDirectory(root(ws).toFile()).setInitialBranch(branch).call();
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
    void invalidExistingRuntimeStateBlocksAdoption() throws Exception {
      Files.writeString(root(WS1).resolve("a.txt"), "A");
      Files.createDirectories(root(WS1).resolve(".seqdev"));
      Files.writeString(WorkspaceState.file(root(WS1)), "{\"version\":7,\"files\":{}}");
      assertFailure(bindings.handleCreateDirectory(WS1, Path.of("x"), USER), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFalse(Files.exists(root(WS1).resolve(".git")));
      assertEquals("{\"version\":7,\"files\":{}}", Files.readString(WorkspaceState.file(root(WS1))));

      Files.delete(WorkspaceState.file(root(WS1)));
      Files.delete(root(WS1).resolve(".seqdev"));
      Files.writeString(root(WS1).resolve(".seqdev"), "not a directory");
      assertFailure(bindings.handleCreateDirectory(WS1, Path.of("x"), USER), 500, "WORKSPACE_REPOSITORY_INCONSISTENT");
      assertFalse(Files.exists(root(WS1).resolve(".git")));
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
      assertFalse(Files.exists(WorkspaceState.file(root(WS1))));
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
      final var ignoringTimesAndIdentity = (java.util.function.Function<JsonObject, JsonObject>) o ->
          Json.createObjectBuilder(o).remove("createdAt").remove("lastEditedAt").remove("fileId").build();
      assertEquals(ignoringTimesAndIdentity.apply(single), ignoringTimesAndIdentity.apply(inDir));
      assertEquals(3, Set.of(metadata("d/x.txt").getString("fileId"), single.getString("fileId"), inDir.getString("fileId")).size(),
                   "every copy is a new file");
      assertTrue(isReadOnly(WS1, "d/x.txt"), "the source keeps its lock");
      assertFalse(isReadOnly(WS1, "e/x.txt"));
      assertEquals("carol", metadata("d/x.txt").getString("createdBy"));
      assertClean(WS1);
    }
  }

  /**
   * The revision catalog in memory: same contract as PostgresRevisionStore, including its keys (a revision id is
   * unique within a workspace, an ordinal within a file), plus injectable failures.
   */
  static final class MemoryRevisionStore implements WorkspaceRevisionStore {
    final List<Revision> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean failInsert;
    volatile boolean failReplace;

    @Override
    public synchronized void insert(final Revision revision) throws Exception {
      if (failInsert) throw new java.sql.SQLException("injected insert failure");
      requireUnique(rows, List.of(revision));
      rows.add(revision);
    }

    @Override
    public synchronized void replaceWorkspaceRevisions(final int workspaceId, final List<Revision> revisions) throws Exception {
      if (failReplace) throw new java.sql.SQLException("injected replace failure");
      final var others = rows.stream().filter(r -> r.workspaceId() != workspaceId).toList();
      requireUnique(others, revisions);
      rows.removeIf(r -> r.workspaceId() == workspaceId);
      rows.addAll(revisions);
    }

    private static void requireUnique(final List<Revision> existing, final List<Revision> added) throws java.sql.SQLException {
      final var ids = new java.util.HashSet<List<Object>>();
      final var ordinals = new java.util.HashSet<List<Object>>();
      for (final var r : java.util.stream.Stream.concat(existing.stream(), added.stream()).toList()) {
        if (!ids.add(List.of(r.workspaceId(), r.id()))) throw new java.sql.SQLException("duplicate key " + r.id());
        if (!ordinals.add(List.of(r.workspaceId(), r.fileId(), r.ordinal()))) {
          throw new java.sql.SQLException("duplicate ordinal " + r.ordinal() + " of " + r.fileId());
        }
      }
    }

    @Override
    public List<Revision> list(final int workspaceId, final UUID fileId) {
      return rows.stream()
                 .filter(r -> r.workspaceId() == workspaceId && r.fileId().equals(fileId))
                 .sorted(Comparator.comparingLong(Revision::ordinal))
                 .toList();
    }

    @Override
    public Optional<Revision> get(final int workspaceId, final UUID revisionId) {
      return rows.stream().filter(r -> r.workspaceId() == workspaceId && r.id().equals(revisionId)).findFirst();
    }
  }

  /** A revision service whose tag creation can be made to fail. */
  static final class FlakyRevisions extends WorkspaceRevisionService {
    volatile boolean failTag;

    FlakyRevisions(final WorkspaceRoots roots, final WorkspaceHistory history, final WorkspaceFileSystemService files,
                   final WorkspaceRevisionStore store) {
      super(roots, history, files, store);
    }

    @Override
    protected void createTag(final Git git, final Revision revision, final ObjectId commit) throws Exception {
      if (failTag) throw new IOException("injected tag failure");
      super.createTag(git, revision, commit);
    }
  }

  @Test
  void revisionNamesAreBijectiveBase26() {
    final var expected = new LinkedHashMap<Long, String>();
    expected.put(1L, "a");
    expected.put(2L, "b");
    expected.put(26L, "z");
    expected.put(27L, "aa");
    expected.put(28L, "ab");
    expected.put(52L, "az");
    expected.put(53L, "ba");
    expected.put(702L, "zz");
    expected.put(703L, "aaa");
    expected.forEach((ordinal, name) -> assertEquals(name, WorkspaceRevisionStore.revisionName(ordinal), "ordinal " + ordinal));
    assertThrows(IllegalArgumentException.class, () -> WorkspaceRevisionStore.revisionName(0));
  }

  @Nested
  class Revisions {
    private MemoryRevisionStore store;
    private FlakyRevisions revisions;

    @BeforeEach
    void setUpRevisions() {
      store = new MemoryRevisionStore();
      revisions = new FlakyRevisions(WorkspaceHistoryIntegrationTest.this::root, history, fs, store);
    }

    //region Helpers
    private Revision create(final int ws, final String path) throws Exception {
      return revisions.create(ws, Path.of(path), USER);
    }

    private List<String> names(final int ws, final String path) throws Exception {
      return revisions.list(ws, Path.of(path)).revisions().stream().map(Revision::name).toList();
    }

    private String fileId(final int ws, final String path) throws Exception {
      return metadata(ws, path).getString("fileId");
    }

    /** Whether the file's state differs from its latest revision; empty when it has none. */
    private Optional<Boolean> changed(final String path) throws Exception {
      final var list = revisions.list(WS1, Path.of(path));
      if (list.revisions().isEmpty()) return Optional.empty();
      return Optional.of(!list.matching().equals(Optional.of(list.revisions().getLast())));
    }

    /** The name of the newest revision the file's state matches. */
    private Optional<String> matching(final String path) throws Exception {
      return revisions.list(WS1, Path.of(path)).matching().map(Revision::name);
    }

    private Map<String, RevTag> tags(final int ws) throws Exception {
      try (final var git = git(ws); final var walk = new RevWalk(git.getRepository())) {
        final var tags = new java.util.TreeMap<String, RevTag>();
        for (final var ref : git.getRepository().getRefDatabase().getRefsByPrefix(Constants.R_TAGS)) {
          tags.put(ref.getName().substring(Constants.R_TAGS.length()), walk.parseTag(ref.getObjectId()));
        }
        return tags;
      }
    }

    /** The working-copy ETag restore checks If-Match against (content plus versioned metadata). */
    private String etag(final int ws, final String path) throws Exception {
      return revisions.list(ws, Path.of(path)).workingCopyETag();
    }

    private void writeSidecar(final int ws, final String path, final String sidecar) throws Exception {
      history.mutate(ws, USER, "hand-written sidecar", () -> {
        Files.writeString(root(ws).resolve(sidecar(path)), sidecar);
        return true;
      }, r -> true);
    }

    /** Give a file a committed sidecar from before file identities existed. */
    private void makeLegacy(final int ws, final String path) throws Exception {
      final var legacy = "{\n    \"version\": \"1\",\n    \"createdBy\": \"carol\",\n    \"createdAt\": \"2025-01-01T00:00:00Z\"\n}";
      history.mutate(ws, USER, "legacy sidecar", () -> {
        Files.writeString(root(ws).resolve(sidecar(path)), legacy);
        return true;
      }, r -> true);
    }

    /** HEAD, the index file and the working tree's status, for "nothing was touched" assertions. */
    private List<Object> repositoryState(final int ws) throws Exception {
      try (final var git = git(ws)) {
        final var repo = git.getRepository();
        return List.of(repo.resolve(Constants.HEAD), repo.exactRef(Constants.HEAD).getTarget().getName(),
                       Arrays.hashCode(Files.readAllBytes(repo.getIndexFile().toPath())),
                       WorkspaceHistory.dirtyPaths(git.status().call()));
      }
    }
    //endregion

    @Nested
    class Identity {
      @Test
      void aNewFileGetsAStableIdentity() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var id = fileId(WS1, "a.seq");
        assertDoesNotThrow(() -> UUID.fromString(id));
        saveOk(WS1, "a.seq", "v2");
        setUserMetadata(WS1, "a.seq", "draft");
        assertEquals(id, fileId(WS1, "a.seq"));
      }

      @Test
      void identityIsNotUserEditable() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var id = fileId(WS1, "a.seq");
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.MalformedRequest.class,
                     () -> MetadataUpdates.fromEndpointBodyJson(USER, Json.createObjectBuilder().add("fileId", "x").build()));
        // Deleting the metadata of a file with an identity clears user metadata but keeps the identity
        setUserMetadata(WS1, "a.seq", "draft");
        history.mutate(WS1, USER, "drop", () -> fs.deleteMetadataFile(WS1, Path.of("a.seq")), r -> r);
        assertEquals(id, fileId(WS1, "a.seq"));
        assertFalse(metadata(WS1, "a.seq").containsKey("user"));
        assertClean(WS1);
      }

      @Test
      void aRenameKeepsTheIdentityAndACopyGetsANewOne() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var id = fileId(WS1, "a.seq");
        assertSuccess(bindings.handleCreateDirectory(WS1, Path.of("dir"), USER));
        assertSuccess(bindings.handleMove(Path.of("a.seq"), Path.of("dir/b.seq"), WS1, WS1, false, USER));
        assertEquals(id, fileId(WS1, "dir/b.seq"));
        assertSuccess(bindings.handleMove(Path.of("dir"), Path.of("moved"), WS1, WS1, false, USER));
        assertEquals(id, fileId(WS1, "moved/b.seq"));
        assertSuccess(bindings.handleCopy(Path.of("moved/b.seq"), Path.of("c.seq"), WS1, WS1, false, USER));
        assertNotEquals(id, fileId(WS1, "c.seq"));
      }

      @Test
      void aRecreatedPathIsANewFile() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var id = fileId(WS1, "a.seq");
        assertSuccess(bindings.handleDelete(WS1, Path.of("a.seq"), USER));
        saveOk(WS1, "a.seq", "v1");
        assertNotEquals(id, fileId(WS1, "a.seq"));
      }

      @Test
      void filesArrivingFromAnotherWorkspaceAreNewFiles() throws Exception {
        saveOk(WS1, "a.seq", "a");
        saveOk(WS1, "b.seq", "b");
        saveOk(WS1, "d/c.seq", "c");
        init(WS2);
        final var a = fileId(WS1, "a.seq");
        final var b = fileId(WS1, "b.seq");
        final var c = fileId(WS1, "d/c.seq");

        assertSuccess(bindings.handleCopy(Path.of("a.seq"), Path.of("a.seq"), WS1, WS2, false, USER));
        assertSuccess(bindings.handleMove(Path.of("b.seq"), Path.of("b.seq"), WS1, WS2, false, USER));
        assertSuccess(bindings.handleMove(Path.of("d"), Path.of("d"), WS1, WS2, false, USER));
        assertNotEquals(a, fileId(WS2, "a.seq"));
        assertNotEquals(b, fileId(WS2, "b.seq"));
        assertNotEquals(c, fileId(WS2, "d/c.seq"));
        assertEquals(a, fileId(WS1, "a.seq"), "the source of a copy keeps its identity");
        assertClean(WS1);
        assertClean(WS2);
      }

      @Test
      void listingALegacyFileWritesNothing() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        makeLegacy(WS1, "a.seq");
        final var before = repositoryState(WS1);
        final var list = revisions.list(WS1, Path.of("a.seq"));
        assertEquals(Optional.empty(), list.fileId());
        assertEquals(List.of(), list.revisions());
        assertEquals(Optional.empty(), list.matching());
        assertEquals(before, repositoryState(WS1));
      }

      @Test
      void theFirstRevisionOfALegacyFileAssignsItsIdentityInOneInternalCommit() throws Exception {
        saveOk(WS1, "a.seq", "content");
        makeLegacy(WS1, "a.seq");
        final var commitsBefore = log(WS1).size();

        final var a = create(WS1, "a.seq");

        final var log = log(WS1);
        assertEquals(commitsBefore + 1, log.size());
        assertEquals("Assign file identity a.seq", log.getFirst().getFullMessage());
        assertEquals("PlanDev", log.getFirst().getAuthorIdent().getName());
        assertEquals(Set.of(".a.seq.meta.seqdev"), headDiff(WS1).stream().map(DiffEntry::getNewPath).collect(Collectors.toSet()));
        assertEquals(log.getFirst().getName(), a.commitSha());
        final var meta = metadata(WS1, "a.seq");
        assertEquals(a.fileId().toString(), meta.getString("fileId"));
        assertEquals("carol", meta.getString("createdBy"), "assigning an identity changes nothing else");
        assertEquals("2025-01-01T00:00:00Z", meta.getString("createdAt"));
        assertEquals("content", read(WS1, "a.seq"));

        saveOk(WS1, "a.seq", "content 2");
        create(WS1, "a.seq");
        assertEquals(commitsBefore + 2, log(WS1).size(), "only the first revision needs an identity");
        assertClean(WS1);
      }

      @Test
      void runtimeOnlyChangesToALegacyFileAssignNoIdentity() throws Exception {
        saveOk(WS1, "a.seq", "content");
        makeLegacy(WS1, "a.seq");
        saveOk(WS1, "a.seq", "edited"); // saving an existing file is not an identity boundary either
        final var commitsBefore = log(WS1).size();

        setReadOnly(WS1, "a.seq", true);
        assertTrue(isReadOnly(WS1, "a.seq"), "the lock still works");
        assertFalse(metadata(WS1, "a.seq").containsKey("fileId"));
        assertEquals(commitsBefore, log(WS1).size(), "a lock is runtime state, not a commit");

        final var a = create(WS1, "a.seq");
        assertEquals(commitsBefore + 1, log(WS1).size());
        assertEquals("Assign file identity a.seq", head(WS1).getFullMessage());
        assertEquals(a.fileId().toString(), fileId(WS1, "a.seq"));
        assertTrue(isReadOnly(WS1, "a.seq"));
        assertClean(WS1);
      }
    }

    @Nested
    class Creation {
      @Test
      void aRevisionIsACatalogRowAndAnAnnotatedTagOnHeadWithoutACommit() throws Exception {
        saveOk(WS1, "dir/a.seq", "v1");
        final var head = head(WS1);
        final var commits = log(WS1).size();

        final var a = create(WS1, "dir/a.seq");

        assertEquals(commits, log(WS1).size(), "making a revision makes no commit");
        assertEquals(head.getName(), head(WS1).getName());
        assertEquals("a", a.name());
        assertEquals(1, a.ordinal());
        assertEquals("dir/a.seq", a.pathAtRevision());
        assertEquals(head.getName(), a.commitSha());
        assertEquals(USER, a.createdBy());
        assertEquals(List.of(a), store.rows);

        final var tags = tags(WS1);
        assertEquals(Set.of("plandev/revisions/" + a.id()), tags.keySet());
        final var tag = tags.values().iterator().next();
        assertEquals(head.getId(), tag.getObject().getId());
        assertEquals(USER, tag.getTaggerIdent().getName());
        try (final var reader = Json.createReader(new java.io.StringReader(tag.getFullMessage()))) {
          final var annotation = reader.readObject();
          assertEquals("plandev-file-revision", annotation.getString("type"));
          assertEquals(1, annotation.getInt("version"));
          assertEquals(a.id().toString(), annotation.getString("revisionId"));
          assertEquals(a.fileId().toString(), annotation.getString("fileId"));
          assertEquals(1, annotation.getInt("ordinal"));
          assertEquals("dir/a.seq", annotation.getString("path"));
          assertEquals("a", annotation.getString("name"));
          assertEquals(USER, annotation.getString("createdBy"));
          assertEquals(a.createdAt().toString(), annotation.getString("createdAt"));
        }
        assertEquals("v1", read(WS1, "dir/a.seq"));
        assertClean(WS1);
      }

      @Test
      void revisionsAreNumberedPerFileAndMayShareACommit() throws Exception {
        saveOk(WS1, "a.seq", "a");
        saveOk(WS1, "b.seq", "b");
        final var a = create(WS1, "a.seq");
        final var b = create(WS1, "b.seq");
        saveOk(WS1, "a.seq", "a2");
        create(WS1, "a.seq");
        saveOk(WS1, "a.seq", "a3");
        create(WS1, "a.seq");

        assertEquals(List.of("a", "b", "c"), names(WS1, "a.seq"));
        assertEquals(List.of("a"), names(WS1, "b.seq"));
        assertEquals(a.commitSha(), b.commitSha());
        assertNotEquals(a.id(), b.id());
        assertEquals(4, tags(WS1).size());
        assertClean(WS1);
      }

      @Test
      void savesNeverCreateRevisions() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        saveOk(WS1, "a.seq", "v2");
        assertSuccess(bindings.handleMove(Path.of("a.seq"), Path.of("b.seq"), WS1, WS1, false, USER));
        assertEquals(List.of(), store.rows);
        assertEquals(Map.of(), tags(WS1));
        assertEquals(List.of(), names(WS1, "b.seq"));
      }

      @Test
      void onlyExistingRegularFilesHaveRevisions() throws Exception {
        saveOk(WS1, "d/a.seq", "v1");
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException.class, () -> create(WS1, "missing.seq"));
        assertThrows(WorkspaceFileOpException.class, () -> create(WS1, "d"));
        assertThrows(WorkspaceFileOpException.class, () -> create(WS1, "d/.a.seq.meta.seqdev"));
        assertThrows(ReservedPathException.class, () -> create(WS1, ".git/config"));
        assertEquals(List.of(), store.rows);
        assertEquals(Map.of(), tags(WS1));
      }

      @Test
      void aRevisionCanBeMadeOfAReadOnlyFile() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        setReadOnly(WS1, "a.seq", true);
        assertEquals("a", create(WS1, "a.seq").name());
        assertTrue(isReadOnly(WS1, "a.seq"));
      }

      @Test
      void anUntrustedWorkspaceIsRejected() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        Files.writeString(root(WS1).resolve("a.seq"), "edited outside PlanDev");
        final var e = assertThrows(WorkspaceHistory.WorkspaceHistoryException.class, () -> create(WS1, "a.seq"));
        assertEquals(WorkspaceHistory.Kind.REPOSITORY_INCONSISTENT, e.kind);
        assertEquals(List.of(), store.rows);
        assertEquals(Map.of(), tags(WS1));
      }
    }

    @Nested
    class FailureConsistency {
      @BeforeEach
      void file() {
        saveOk(WS1, "a.seq", "v1");
      }

      @Test
      void aTagFailureLeavesNoRevision() throws Exception {
        revisions.failTag = true;
        assertThrows(IOException.class, () -> create(WS1, "a.seq"));
        assertEquals(List.of(), store.rows);
        assertEquals(Map.of(), tags(WS1));
        assertClean(WS1);

        revisions.failTag = false;
        assertEquals("a", create(WS1, "a.seq").name(), "the failed attempt used no name");
      }

      @Test
      void aProjectionFailureKeepsTheTagAndAReindexMakesTheRevisionVisible() throws Exception {
        store.failInsert = true;
        final var e = assertThrows(WorkspaceRevisionService.RevisionNotIndexedException.class, () -> create(WS1, "a.seq"));
        assertEquals(WorkspaceHistory.Kind.REVISION_NOT_INDEXED, e.kind);
        assertInstanceOf(java.sql.SQLException.class, e.getCause());
        assertTrue(e.getMessage().contains(e.revisionId.toString()), e.getMessage());
        assertEquals(Set.of("plandev/revisions/" + e.revisionId), tags(WS1).keySet(), "the revision exists");
        assertEquals(List.of(), names(WS1, "a.seq"), "but is not indexed yet");
        assertClean(WS1);

        store.failInsert = false;
        saveOk(WS1, "a.seq", "v2");
        assertEquals("b", create(WS1, "a.seq").name(), "the unindexed revision still holds its ordinal");
        revisions.reindexRevisionsFromGit(WS1);
        assertEquals(List.of("a", "b"), names(WS1, "a.seq"));
        assertEquals(e.revisionId, revisions.list(WS1, Path.of("a.seq")).revisions().getFirst().id());
      }
    }

    @Nested
    class Reading {
      @Test
      void aRevisionIsReadFromItsCommitAfterTheFileIsRenamedAndChanged() throws Exception {
        saveOk(WS1, "foo.seq", "first");
        setUserMetadata(WS1, "foo.seq", "draft");
        final var a = create(WS1, "foo.seq");
        assertSuccess(bindings.handleCreateDirectory(WS1, Path.of("sequences"), USER)); // file moves need the folder
        assertSuccess(bindings.handleMove(Path.of("foo.seq"), Path.of("sequences/foo.seq"), WS1, WS1, false, USER));
        saveOk(WS1, "sequences/foo.seq", "second");
        setUserMetadata(WS1, "sequences/foo.seq", "final");
        final var b = create(WS1, "sequences/foo.seq");

        assertEquals(List.of("a", "b"), names(WS1, "sequences/foo.seq"));
        assertEquals("foo.seq", a.pathAtRevision());
        assertEquals("sequences/foo.seq", b.pathAtRevision());

        final var before = repositoryState(WS1);
        final var old = revisions.read(WS1, a.id());
        assertEquals("first", new String(old.content(), StandardCharsets.UTF_8));
        assertEquals("draft", old.metadata().getJsonObject("user").getString("status"));
        assertEquals(a.fileId().toString(), old.metadata().getString("fileId"));
        assertEquals(USER, old.metadata().getString("createdBy"));
        assertTrue(old.metadata().containsKey("createdAt"));
        assertFalse(old.metadata().containsKey("readOnly"));
        assertEquals("second", new String(revisions.read(WS1, b.id()).content(), StandardCharsets.UTF_8));
        assertEquals(before, repositoryState(WS1), "reading a revision touches neither HEAD, the index nor the working tree");
        assertEquals("second", read(WS1, "sequences/foo.seq"));
        assertFalse(Files.exists(root(WS1).resolve("foo.seq")));
      }

      @Test
      void unknownRevisionsAndWorkspacesAreNotFound() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchRevisionException.class,
                     () -> revisions.get(WS1, UUID.randomUUID()));
        init(WS2);
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchRevisionException.class,
                     () -> revisions.get(WS2, a.id()), "revisions are scoped to their workspace");
      }

      @Test
      void aRevisionWhoseCommitIsMissingIsAnInconsistencyNotAMissingRevision() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        store.rows.set(0, new Revision(a.id(), WS1, a.fileId(), 1, "a", a.pathAtRevision(),
                                       "0123456789012345678901234567890123456789", USER, a.createdAt()));
        final var e = assertThrows(WorkspaceHistory.WorkspaceHistoryException.class, () -> revisions.read(WS1, a.id()));
        assertEquals(WorkspaceHistory.Kind.REVISION_CATALOG_INCONSISTENT, e.kind);
      }

      @Test
      void changesSinceTheLatestRevisionConcernOnlyThisFile() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        saveOk(WS1, "other.seq", "o");
        assertEquals(Optional.empty(), changed("a.seq"));
        create(WS1, "a.seq");
        assertEquals(Optional.of(false), changed("a.seq"));

        saveOk(WS1, "other.seq", "o2");
        assertEquals(Optional.of(false), changed("a.seq"), "another file's changes do not count");
        assertSuccess(bindings.handleMove(Path.of("a.seq"), Path.of("renamed.seq"), WS1, WS1, false, USER));
        assertEquals(Optional.of(false), changed("renamed.seq"), "a rename alone is not a change to the file");

        setUserMetadata(WS1, "renamed.seq", "final");
        assertEquals(Optional.of(true), changed("renamed.seq"), "versioned metadata is part of the file's state");
        create(WS1, "renamed.seq");
        saveOk(WS1, "renamed.seq", "v2");
        assertEquals(Optional.of(true), changed("renamed.seq"));
        setReadOnly(WS1, "renamed.seq", true);
        saveOk(WS1, "other.seq", "o3");
        assertEquals(Optional.of(true), changed("renamed.seq"));
      }
    }

    /** Which revision, if any, holds the file's saved state; and no revision may duplicate the latest. */
    @Nested
    class Matching {
      @Test
      void theSavedStateMatchesTheLatestRevisionOrNone() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        assertEquals(Optional.empty(), matching("a.seq"));
        create(WS1, "a.seq");
        assertEquals(Optional.of("a"), matching("a.seq"));
        saveOk(WS1, "a.seq", "v2");
        assertEquals(Optional.empty(), matching("a.seq"));
        saveOk(WS1, "a.seq", "v1");
        assertEquals(Optional.of("a"), matching("a.seq"), "saving back to a revision's state matches it again");
      }

      @Test
      void restoringAnOlderRevisionMatchesIt() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        for (final var v : List.of("v2", "v3")) {
          saveOk(WS1, "a.seq", v);
          create(WS1, "a.seq");
        }
        revisions.restore(WS1, Path.of("a.seq"), a.id(), "*", USER);
        assertEquals(Optional.of("a"), matching("a.seq"));
        assertEquals(Optional.of(true), changed("a.seq"));
      }

      @Test
      void theNewestOfSeveralIdenticalRevisionsMatches() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        create(WS1, "a.seq");                             // a = v1
        saveOk(WS1, "a.seq", "v2");
        create(WS1, "a.seq");                             // b = v2
        saveOk(WS1, "a.seq", "v1");
        create(WS1, "a.seq");                             // c = v1 again, allowed: it differs from b
        saveOk(WS1, "a.seq", "v3");
        create(WS1, "a.seq");                             // d
        saveOk(WS1, "a.seq", "v1");
        assertEquals(Optional.of("c"), matching("a.seq"));
      }

      @Test
      void readOnlyIsNotPartOfTheStateButVersionedMetadataIs() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        create(WS1, "a.seq");
        setReadOnly(WS1, "a.seq", true);
        assertEquals(Optional.of("a"), matching("a.seq"), "readOnly is runtime state");
        setReadOnly(WS1, "a.seq", false);

        setUserMetadata(WS1, "a.seq", "reviewed");
        assertEquals(Optional.empty(), matching("a.seq"), "same content, different versioned metadata");
        assertEquals("b", create(WS1, "a.seq").name(), "a metadata change is worth a revision");
      }

      @Test
      void aDuplicateOfTheLatestRevisionIsRejected() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        create(WS1, "a.seq");
        setReadOnly(WS1, "a.seq", true); // runtime state does not make it different
        final var before = repositoryState(WS1);
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.RevisionUnchangedException.class,
                     () -> create(WS1, "a.seq"));
        assertEquals(List.of("a"), names(WS1, "a.seq"));
        assertEquals(1, tags(WS1).size());
        assertEquals(before, repositoryState(WS1));
      }

      @Test
      void returningToAnOlderRevisionCanBeRecorded() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        saveOk(WS1, "a.seq", "v2");
        create(WS1, "a.seq");
        revisions.restore(WS1, Path.of("a.seq"), a.id(), "*", USER);
        assertEquals("c", create(WS1, "a.seq").name());
        assertEquals(Optional.of("c"), matching("a.seq"));
      }
    }

    @Nested
    class Deletion {
      @Test
      void revisionsOutliveTheirFileAndAreNotInheritedByARecreatedOrCopiedOne() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        assertSuccess(bindings.handleCopy(Path.of("a.seq"), Path.of("copy.seq"), WS1, WS1, false, USER));
        assertEquals(List.of(), names(WS1, "copy.seq"));

        assertSuccess(bindings.handleDelete(WS1, Path.of("a.seq"), USER));
        assertEquals(List.of(a), store.rows);
        assertEquals(Set.of("plandev/revisions/" + a.id()), tags(WS1).keySet());
        assertEquals("v1", new String(revisions.read(WS1, a.id()).content(), StandardCharsets.UTF_8));

        saveOk(WS1, "a.seq", "new file");
        assertEquals(List.of(), names(WS1, "a.seq"));
        assertEquals("a", create(WS1, "a.seq").name());
      }

      @Test
      void aFileFromAnotherWorkspaceArrivesWithoutRevisions() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        saveOk(WS1, "b.seq", "v1");
        create(WS1, "a.seq");
        create(WS1, "b.seq");
        init(WS2);
        assertSuccess(bindings.handleCopy(Path.of("a.seq"), Path.of("a.seq"), WS1, WS2, false, USER));
        assertSuccess(bindings.handleMove(Path.of("b.seq"), Path.of("b.seq"), WS1, WS2, false, USER));
        assertEquals(List.of(), names(WS2, "a.seq"));
        assertEquals(List.of(), names(WS2, "b.seq"));
        assertEquals(List.of("a"), names(WS1, "a.seq"));
      }
    }

    /** Git is the authority on revisions; the catalog is a projection that can be rebuilt from it. */
    @Nested
    class GitAuthority {
      private List<Revision> fromGit(final Path repoDir, final int ws) throws Exception {
        try (final var repo = Git.open(repoDir.toFile()).getRepository()) {
          return GitFileRevisions.readAll(repo, ws);
        }
      }

      /** Tag HEAD (or its tree) directly, bypassing PlanDev. A null message makes a lightweight tag. */
      private void rawTag(final String name, final String message, final boolean atTree) throws Exception {
        try (final var git = git(WS1); final var walk = new RevWalk(git.getRepository())) {
          final var head = walk.parseCommit(git.getRepository().resolve(Constants.HEAD));
          final var tag = git.tag().setName(name).setObjectId(atTree ? walk.parseTree(head.getTree()) : head).setSigned(false);
          if (message == null) tag.setAnnotated(false);
          else tag.setAnnotated(true).setMessage(message);
          tag.call();
        }
      }

      private void deleteTag(final String name) throws Exception {
        try (final var git = git(WS1)) {
          git.tagDelete().setTags(name).call();
        }
      }

      private static String edited(final Revision revision, final String key, final javax.json.JsonValue value) {
        try (final var reader = Json.createReader(new java.io.StringReader(GitFileRevisions.annotation(revision)))) {
          final var json = Json.createObjectBuilder(reader.readObject());
          return (value == null ? json.remove(key) : json.add(key, value)).build().toString();
        }
      }

      private static Revision withId(final Revision r, final UUID id) {
        return new Revision(id, r.workspaceId(), r.fileId(), r.ordinal(), r.name(), r.pathAtRevision(), r.commitSha(),
                            r.createdBy(), r.createdAt());
      }

      @Test
      void onlyPlanDevRevisionTagsAreRevisions() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        rawTag("v1.0", "a release", false);
        rawTag("plandev/other", null, false);
        assertEquals(List.of(a), fromGit(root(WS1), WS1));
      }

      @Test
      void invalidRevisionTagsAreRejectedWithTheirProblem() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        final var other = withId(a, UUID.randomUUID());
        final var name = "plandev/revisions/" + other.id();
        final var fileId = Json.createValue(other.fileId().toString());

        final var cases = new LinkedHashMap<String, String>(); // annotation -> expected problem
        cases.put("not json", "annotation is not a JSON object");
        cases.put(edited(other, "type", Json.createValue("something-else")), "type is not plandev-file-revision");
        cases.put(edited(other, "version", Json.createValue(2)), "unsupported annotation version 2");
        cases.put(edited(other, "fileId", null), "fileId must be a non-empty string");
        cases.put(edited(other, "ordinal", Json.createValue(0)), "ordinal must be positive");
        cases.put(edited(other, "ordinal", Json.createValue("1")), "ordinal must be an integer");
        cases.put(edited(other, "createdAt", Json.createValue("yesterday")), "createdAt is not an ISO-8601 instant");
        cases.put(edited(other, "createdAt", Json.createValue("2026-01-01T00:00:00.123456789Z")),
                  "createdAt is finer than microseconds");
        cases.put(edited(other, "createdBy", Json.createValue(7)), "createdBy must be a string or null");
        cases.put(edited(other, "revisionId", Json.createValue(a.id().toString())), "does not match its revisionId");
        cases.put(edited(other, "path", Json.createValue("missing.seq")), "missing.seq is not a file");
        cases.put(edited(other, "fileId", Json.createValue(UUID.randomUUID().toString())), "does not have fileId");
        cases.put(edited(other, "fileId", fileId), "both claim ordinal 1 of file " + a.fileId());

        for (final var c : cases.entrySet()) {
          rawTag(name, c.getKey(), false);
          final var e = assertThrows(GitFileRevisions.InvalidRevisionTagsException.class, () -> fromGit(root(WS1), WS1));
          assertEquals(WorkspaceHistory.Kind.REVISION_TAG_INVALID, e.kind);
          assertEquals(1, e.problems.size(), e.getMessage());
          assertTrue(e.problems.getFirst().contains(c.getValue()), c.getValue() + " not in " + e.getMessage());
          deleteTag(name);
        }

        rawTag(name, null, false);
        assertTrue(assertThrows(GitFileRevisions.InvalidRevisionTagsException.class, () -> fromGit(root(WS1), WS1))
                       .getMessage().contains("not an annotated tag"));
        deleteTag(name);
        rawTag(name, GitFileRevisions.annotation(withId(other, other.id())), true);
        assertTrue(assertThrows(GitFileRevisions.InvalidRevisionTagsException.class, () -> fromGit(root(WS1), WS1))
                       .getMessage().contains("does not point at a commit"));
        deleteTag(name);
        assertEquals(List.of(a), fromGit(root(WS1), WS1));
      }

      @Test
      void theWholeCatalogIsRebuiltExactlyFromGitAcrossARename() throws Exception {
        saveOk(WS1, "foo.seq", "first");
        setUserMetadata(WS1, "foo.seq", "draft");
        final var a = create(WS1, "foo.seq");
        saveOk(WS1, "foo.seq", "second");
        final var b = revisions.create(WS1, Path.of("foo.seq"), "bob");
        assertSuccess(bindings.handleCreateDirectory(WS1, Path.of("sequences"), USER));
        assertSuccess(bindings.handleMove(Path.of("foo.seq"), Path.of("sequences/foo.seq"), WS1, WS1, false, USER));
        saveOk(WS1, "sequences/foo.seq", "third");
        final var c = revisions.create(WS1, Path.of("sequences/foo.seq"), null);
        saveOk(WS1, "other.seq", "other");
        create(WS1, "other.seq");
        saveOk(WS1, "sequences/foo.seq", "fourth");

        final var expected = Set.copyOf(store.rows);
        final var before = revisions.list(WS1, Path.of("sequences/foo.seq"));
        assertEquals(List.of(a, b, c), before.revisions());

        // Lose the catalog, and leave a row no tag backs
        store.rows.clear();
        store.rows.add(withId(c, UUID.randomUUID()));
        assertTrue(revisions.reindexRevisionsFromGit(WS1).containsAll(expected));

        assertEquals(expected, Set.copyOf(store.rows), "ids, files, ordinals, names, paths, commits, creators, times");
        final var after = revisions.list(WS1, Path.of("sequences/foo.seq"));
        assertEquals(before, after, "same revisions in the same order, same change state and ETag");
        assertEquals(List.of("foo.seq", "foo.seq", "sequences/foo.seq"),
                     after.revisions().stream().map(Revision::pathAtRevision).toList());
        assertEquals(Arrays.asList(USER, "bob", null), after.revisions().stream().map(Revision::createdBy).toList());
        assertEquals(Optional.empty(), after.matching());

        final var preview = revisions.read(WS1, a.id());
        assertEquals("first", new String(preview.content(), StandardCharsets.UTF_8));
        assertEquals("draft", preview.metadata().getJsonObject("user").getString("status"));
        revisions.restore(WS1, Path.of("sequences/foo.seq"), a.id(), after.workingCopyETag(), USER);
        assertEquals("first", read(WS1, "sequences/foo.seq"));
        assertEquals("d", create(WS1, "sequences/foo.seq").name());
        assertClean(WS1);
      }

      @Test
      void aFailedRebuildLeavesTheCatalogUntouched() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        final var a = create(WS1, "a.seq");
        final var catalog = List.copyOf(store.rows);
        rawTag("plandev/revisions/" + UUID.randomUUID(), "not json", false);
        assertThrows(GitFileRevisions.InvalidRevisionTagsException.class, () -> revisions.reindexRevisionsFromGit(WS1));
        assertEquals(catalog, store.rows);
        assertEquals(List.of(a), revisions.list(WS1, Path.of("a.seq")).revisions());
      }

      @Test
      void theNextOrdinalComesFromTagsNotTheCatalog() throws Exception {
        saveOk(WS1, "a.seq", "v1");
        create(WS1, "a.seq");
        saveOk(WS1, "a.seq", "v2");
        create(WS1, "a.seq");
        store.rows.clear();
        saveOk(WS1, "a.seq", "v3");
        final var c = create(WS1, "a.seq");
        assertEquals(3, c.ordinal());
        assertEquals("c", c.name());
      }

      @Test
      void aClonedRepositoryCarriesItsRevisions() throws Exception {
        saveOk(WS1, "foo.seq", "first");
        create(WS1, "foo.seq");
        assertSuccess(bindings.handleMove(Path.of("foo.seq"), Path.of("bar.seq"), WS1, WS1, false, USER));
        saveOk(WS1, "bar.seq", "second");
        create(WS1, "bar.seq");

        final var clone = base.resolve("clone");
        Git.cloneRepository().setURI(root(WS1).toUri().toString()).setDirectory(clone.toFile()).call().close();

        final var original = fromGit(root(WS1), WS1);
        assertEquals(2, original.size());
        assertEquals(original, fromGit(clone, WS1));
      }
    }

    @Nested
    class Restore {
      private Revision a;

      @BeforeEach
      void twoVersions() throws Exception {
        saveOk(WS1, "foo.seq", "first");
        setUserMetadata(WS1, "foo.seq", "draft");
        a = create(WS1, "foo.seq");
        saveOk(WS1, "foo.seq", "second");
        setUserMetadata(WS1, "foo.seq", "final");
      }

      private WorkspaceRevisionService.Restored restore(final String path, final String ifMatch) throws Exception {
        return revisions.restore(WS1, Path.of(path), a.id(), ifMatch, USER);
      }

      @Test
      void restoreIsAnOrdinaryCommitOfContentAndVersionedMetadata() throws Exception {
        final var id = fileId(WS1, "foo.seq");
        final var commits = log(WS1).size();

        final var restored = restore("foo.seq", etag(WS1, "foo.seq"));

        assertEquals("first", read(WS1, "foo.seq"));
        assertEquals(fs.getETag(WS1, Path.of("foo.seq")), restored.etag());
        final var meta = metadata(WS1, "foo.seq");
        assertEquals("draft", meta.getJsonObject("user").getString("status"));
        assertEquals(id, meta.getString("fileId"));
        assertEquals(commits + 1, log(WS1).size());
        assertEquals("Restore foo.seq to revision a", head(WS1).getFullMessage());
        assertEquals(USER, head(WS1).getAuthorIdent().getName());
        assertEquals(List.of("a"), names(WS1, "foo.seq"), "a restore is not a revision");
        assertEquals(1, tags(WS1).size());
        assertEquals(Optional.of(false), changed("foo.seq"));
        assertClean(WS1);
      }

      @Test
      void restoreWritesToTheFilesCurrentPath() throws Exception {
        assertSuccess(bindings.handleCreateDirectory(WS1, Path.of("sequences"), USER)); // file moves need the folder
        assertSuccess(bindings.handleMove(Path.of("foo.seq"), Path.of("sequences/foo.seq"), WS1, WS1, false, USER));
        restore("sequences/foo.seq", etag(WS1, "sequences/foo.seq"));
        assertEquals("first", read(WS1, "sequences/foo.seq"));
        assertFalse(Files.exists(root(WS1).resolve("foo.seq")));
        assertEquals("Restore sequences/foo.seq to revision a", head(WS1).getFullMessage());
        assertClean(WS1);
      }

      @Test
      void aStaleETagIsRejectedAndStarForces() throws Exception {
        final var stale = etag(WS1, "foo.seq");
        saveOk(WS1, "foo.seq", "third");
        final var before = repositoryState(WS1);
        final var e = assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.StaleFileException.class,
                                   () -> restore("foo.seq", stale));
        assertEquals(etag(WS1, "foo.seq"), e.currentETag);
        assertEquals(USER, e.lastEditedBy);
        assertEquals(before, repositoryState(WS1));
        assertEquals("third", read(WS1, "foo.seq"));
        assertThrows(IllegalArgumentException.class, () -> restore("foo.seq", null));

        restore("foo.seq", "*");
        assertEquals("first", read(WS1, "foo.seq"));
      }

      @Test
      void aMetadataOnlyChangeMakesTheETagStale() throws Exception {
        final var stale = etag(WS1, "foo.seq");
        final var contentETag = fs.getETag(WS1, Path.of("foo.seq"));
        setUserMetadata(WS1, "foo.seq", "reviewed");
        assertEquals(contentETag, fs.getETag(WS1, Path.of("foo.seq")), "the save ETag only covers content");
        assertNotEquals(stale, etag(WS1, "foo.seq"));
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.StaleFileException.class,
                     () -> restore("foo.seq", stale));
        assertEquals("reviewed", metadata(WS1, "foo.seq").getJsonObject("user").getString("status"));
      }

      @Test
      void aReadOnlyChangeLeavesTheETagAlone() throws Exception {
        final var before = etag(WS1, "foo.seq");
        setReadOnly(WS1, "foo.seq", true);
        assertEquals(before, etag(WS1, "foo.seq"));
        setReadOnly(WS1, "foo.seq", false);
        restore("foo.seq", before);
        assertEquals("first", read(WS1, "foo.seq"));
      }

      @Test
      void unknownVersionedMetadataSurvivesRestore() throws Exception {
        saveOk(WS1, "fut.seq", "v1");
        final var id = fileId(WS1, "fut.seq");
        writeSidecar(WS1, "fut.seq", """
            {"version": "1", "fileId": "%s", "createdBy": "carol", "createdAt": "2025-01-01T00:00:00Z",
             "user": {}, "futureField": {"x": 1}}""".formatted(id));
        final var revision = create(WS1, "fut.seq");
        saveOk(WS1, "fut.seq", "v2");
        writeSidecar(WS1, "fut.seq", "{\"version\": \"1\", \"fileId\": \"%s\"}".formatted(id));
        setReadOnly(WS1, "fut.seq", true);
        setReadOnly(WS1, "fut.seq", false);

        revisions.restore(WS1, Path.of("fut.seq"), revision.id(), etag(WS1, "fut.seq"), USER);

        final var meta = metadata(WS1, "fut.seq");
        assertEquals(1, meta.getJsonObject("futureField").getInt("x"));
        assertEquals(id, meta.getString("fileId"));
        assertEquals("carol", meta.getString("createdBy"));
        assertFalse(isReadOnly(WS1, "fut.seq"), "runtime readOnly stays current");
        assertEquals("v1", read(WS1, "fut.seq"));
        assertClean(WS1);
      }

      @Test
      void aReadOnlyFileCannotBeRestoredAndRestoreLeavesTheLockAlone() throws Exception {
        setReadOnly(WS1, "foo.seq", true);
        final var before = repositoryState(WS1);
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.FileLockedException.class,
                     () -> restore("foo.seq", "*"));
        assertEquals(before, repositoryState(WS1));
        assertEquals("second", read(WS1, "foo.seq"));

        // The lock is runtime policy, not part of a revision: restoring one made while locked does not re-lock
        final var madeWhileLocked = create(WS1, "foo.seq");
        setReadOnly(WS1, "foo.seq", false);
        revisions.restore(WS1, Path.of("foo.seq"), madeWhileLocked.id(), "*", USER);
        assertFalse(isReadOnly(WS1, "foo.seq"));
        assertClean(WS1);
      }

      @Test
      void onlyTheFilesOwnRevisionsCanBeRestored() throws Exception {
        saveOk(WS1, "other.seq", "other");
        final var before = repositoryState(WS1);
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchRevisionException.class,
                     () -> restore("other.seq", "*"));
        assertThrows(gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException.class,
                     () -> restore("gone.seq", "*"));
        assertEquals(before, repositoryState(WS1));
      }

      @Test
      void restoringTheCurrentStateMakesNoCommit() throws Exception {
        restore("foo.seq", "*");
        final var commits = log(WS1).size();
        restore("foo.seq", "*");
        assertEquals(commits, log(WS1).size());
        assertClean(WS1);
      }
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
}
