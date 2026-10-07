package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceGitRemoteService.Outcome;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceGitRemoteService.RemoteRejectedException;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistoryIntegrationTest.MemoryRevisionStore;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceRevisionStore.Revision;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.Part;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.json.Json;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips workspaces through an ordinary bare Git repository standing in for an external remote, edited by plain
 * Git clones (the "external user"). Below HTTP, real JGit repositories on disk, in-memory revision catalog.
 */
class WorkspaceGitRemoteIntegrationTest {
  private static final String USER = "alice";
  private static final int WS1 = 1;
  private static final int WS2 = 2;

  @TempDir Path tmp;
  private Path base;
  private Path bare;
  private WorkspaceFileSystemService fs;
  private WorkspaceBindings bindings;
  private MemoryRevisionStore store;
  private WorkspaceRevisionService revisions;
  private FlakyRemote remote;
  private int clones;

  /** A remote service whose tag promotion can be made to fail, after main has advanced. */
  static final class FlakyRemote extends WorkspaceGitRemoteService {
    volatile boolean failPromote;

    FlakyRemote(final WorkspaceRoots roots, final WorkspaceHistory history, final WorkspaceRevisionService revisions) {
      super(roots, history, revisions);
    }

    @Override
    protected void promote(final Repository repo, final Map<String, Ref> newRevisions) throws IOException {
      if (failPromote) throw new IOException("injected promotion failure");
      super.promote(repo, newRevisions);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    base = tmp.toRealPath();
    Files.createDirectories(base.resolve("ws1"));
    Files.createDirectories(base.resolve("ws2"));
    final WorkspaceRoots roots = id -> base.resolve("ws" + id);
    final var history = new WorkspaceHistory(roots);
    fs = new WorkspaceFileSystemService(roots, null, history);
    bindings = new WorkspaceBindings(null, fs, history, null, null, "");
    store = new MemoryRevisionStore();
    revisions = new WorkspaceRevisionService(roots, history, fs, store);
    remote = new FlakyRemote(roots, history, revisions);
    bare = base.resolve("remote.git");
    Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
  }

  //region Helpers
  private Path root(final int ws) {
    return base.resolve("ws" + ws);
  }

  private void save(final int ws, final String path, final byte[] bytes) {
    final var p = Path.of(path);
    final var part = (Part) Proxy.newProxyInstance(Part.class.getClassLoader(), new Class<?>[]{Part.class}, (x, m, a) ->
        switch (m.getName()) {
          case "getInputStream" -> new ByteArrayInputStream(bytes);
          case "getSubmittedFileName", "getName" -> p.getFileName().toString();
          case "getSize" -> (long) bytes.length;
          case "getContentType" -> "application/octet-stream";
          default -> null;
        });
    final var result = bindings.handleFileUpload(ws, p, new UploadedFile(part), true, null, USER);
    assertInstanceOf(HandlerResult.Success.class, result, () -> result.jsonResponse().toString());
  }

  private void save(final int ws, final String path, final String content) {
    save(ws, path, content.getBytes(StandardCharsets.UTF_8));
  }

  private String read(final int ws, final String path) throws IOException {
    return Files.readString(root(ws).resolve(path));
  }

  private Revision revise(final int ws, final String path) throws Exception {
    return revisions.create(ws, Path.of(path), USER);
  }

  private List<Revision> revisionsOf(final int ws, final String path) throws Exception {
    return revisions.list(ws, Path.of(path)).revisions();
  }

  private static String sidecar(final String path) {
    return GitFileRevisions.sidecarKey(path);
  }

  /** A workspace with a.seq and b.seq, a revision of a.seq, linked to the bare remote and pushed. */
  private Revision publishedWorkspace() throws Exception {
    save(WS1, "a.seq", "a1");
    save(WS1, "b.seq", "b1");
    final var a = revise(WS1, "a.seq");
    remote.linkRemote(WS1, bare.toString());
    remote.push(WS1);
    return a;
  }

  /** A fresh ordinary clone of the remote, with its tags, as an external Git user would have. */
  private Git external() throws Exception {
    final var git = Git.cloneRepository().setURI(bare.toUri().toString())
                       .setDirectory(base.resolve("ext" + clones++).toFile()).call();
    git.fetch().setRefSpecs(new RefSpec("+refs/tags/*:refs/tags/*")).call();
    return git;
  }

  private static void write(final Git git, final String path, final String content) throws IOException {
    final var file = git.getRepository().getWorkTree().toPath().resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static RevCommit commit(final Git git, final String message) throws Exception {
    git.add().addFilepattern(".").call();
    git.add().addFilepattern(".").setUpdate(true).call();
    return git.commit().setMessage(message).setAuthor("bob", "bob@example.com").setSign(false).setNoVerify(true).call();
  }

  /** Push from an external clone; the remote must accept every ref. */
  private static void push(final Git git, final String... specs) throws Exception {
    for (final var result : git.push().setRefSpecs(java.util.Arrays.stream(specs).map(RefSpec::new).toList()).call()) {
      for (final var u : result.getRemoteUpdates()) {
        assertTrue(Set.of(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE).contains(u.getStatus()),
                   u.getRemoteName() + " " + u.getStatus());
      }
    }
  }

  private static void tag(final Git git, final String name, final ObjectId commit, final String message) throws Exception {
    try (final var walk = new RevWalk(git.getRepository())) {
      git.tag().setName(name).setObjectId(walk.parseCommit(commit)).setAnnotated(true).setSigned(false)
         .setMessage(message).setForceUpdate(true).call();
    }
  }

  private ObjectId remoteRef(final String name) throws IOException {
    try (final var repo = Git.open(bare.toFile()).getRepository()) {
      return repo.resolve(name);
    }
  }

  private String head(final int ws) throws IOException {
    try (final var repo = Git.open(root(ws).toFile()).getRepository()) {
      return repo.resolve(Constants.HEAD).name();
    }
  }

  private Map<String, Ref> revisionRefs(final int ws) throws IOException {
    try (final var repo = Git.open(root(ws).toFile()).getRepository()) {
      return GitFileRevisions.refs(repo, GitFileRevisions.REF_PREFIX);
    }
  }

  private void assertClean(final int ws) throws Exception {
    try (final var git = Git.open(root(ws).toFile())) {
      assertEquals(Set.of(), WorkspaceHistory.dirtyPaths(git.status().call()));
    }
  }

  /** HEAD, every byte of the working tree (runtime state included), canonical revision refs, and the catalog. */
  private record Snapshot(String head, Map<String, String> files, Map<String, ObjectId> refs, List<Revision> catalog) {}

  private Snapshot snapshot(final int ws) throws Exception {
    final var files = new TreeMap<String, String>();
    try (final var paths = Files.walk(root(ws))) {
      for (final var p : paths.toList()) {
        final var key = WorkspacePaths.key(root(ws), p);
        if (key.equals(".git") || key.startsWith(".git/")) continue;
        files.put(key, Files.isDirectory(p) ? "/" : new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
      }
    }
    final var refs = new TreeMap<String, ObjectId>();
    revisionRefs(ws).forEach((k, v) -> refs.put(k, v.getObjectId()));
    return new Snapshot(head(ws), files, refs, store.rows.stream().filter(r -> r.workspaceId() == ws).toList());
  }

  private void assertRejected(final int ws, final String problem) throws Exception {
    final var before = snapshot(ws);
    final var e = assertThrows(Exception.class, () -> remote.fetchAndIntegrate(ws, USER));
    assertTrue(e.getMessage().contains(problem), problem + " not in " + e.getMessage());
    assertEquals(before, snapshot(ws), "the workspace changed although the import was rejected");
    assertClean(ws);
  }

  private static byte[] randomBytes(final long seed, final int size) {
    final var bytes = new byte[size];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }
  //endregion

  @Test
  void aPushedWorkspaceIsAnOrdinaryGitRepositoryWithItsRevisionsAsTags() throws Exception {
    save(WS1, "dir/a.seq", "first");
    final var a = revise(WS1, "dir/a.seq");
    save(WS1, "dir/a.seq", "second");
    final var b = revise(WS1, "dir/a.seq");
    save(WS1, "dir/a.seq", "third");
    remote.linkRemote(WS1, bare.toString());
    remote.push(WS1);

    // Nothing but ordinary Git from here on: the git CLI, no PlanDev code
    final var clone = base.resolve("cli-clone");
    run(base, "git", "clone", "--quiet", bare.toString(), clone.toString());
    assertEquals("third", Files.readString(clone.resolve("dir/a.seq")));
    assertTrue(Files.isRegularFile(clone.resolve("dir/.a.seq.meta.seqdev")));
    try (final var git = Git.open(root(WS1).toFile())) {
      final var ours = new ArrayList<String>();
      git.log().call().forEach(c -> ours.add(c.name() + " " + c.getShortMessage()));
      assertEquals(String.join("\n", ours), run(clone, "git", "log", "--format=%H %s"), "the same commits, in order");
    }
    for (final var r : List.of(a, b)) {
      final var tag = "plandev/revisions/" + r.id();
      assertEquals("tag", run(clone, "git", "cat-file", "-t", tag));
      assertEquals(r.commitSha(), run(clone, "git", "rev-parse", tag + "^{commit}"), "tag points at its historical commit");
      assertEquals(GitFileRevisions.annotation(r).strip(), run(clone, "git", "tag", "-l", "--format=%(contents)", tag).strip());
      assertEquals("first second".split(" ")[(int) r.ordinal() - 1], run(clone, "git", "show", tag + ":dir/a.seq"));
    }
    try (final var repo = Git.open(clone.toFile()).getRepository()) {
      assertEquals(List.of(a, b), GitFileRevisions.readAll(repo, WS1), "the clone carries the exact revision catalog");
    }
  }

  private static String run(final Path dir, final String... command) throws Exception {
    final var process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    final var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    assertEquals(0, process.waitFor(), out);
    return out;
  }

  @Test
  void integrationIsForwardOnly() throws Exception {
    publishedWorkspace();
    assertEquals(Outcome.UP_TO_DATE, remote.fetchAndIntegrate(WS1, USER).outcome());

    save(WS1, "a.seq", "local");
    final var local = head(WS1);
    assertEquals(Outcome.LOCAL_AHEAD, remote.fetchAndIntegrate(WS1, USER).outcome());
    assertEquals(local, head(WS1));
    remote.push(WS1);
    assertEquals(local, remoteRef("refs/heads/main").name());

    // A remote main with unrelated history is refused, not adopted
    try (final var ext = external()) {
      ext.checkout().setOrphan(true).setName("other").call();
      write(ext, "x.seq", "unrelated");
      commit(ext, "unrelated root");
      push(ext, "+refs/heads/other:refs/heads/main");
    }
    assertRejected(WS1, "unrelated");
  }

  @Test
  void anExternalCommitUpdatesTheWorkingCopyWithoutMakingARevision() throws Exception {
    final var a = publishedWorkspace();
    try (final var ext = external()) {
      write(ext, "a.seq", "edited outside");
      commit(ext, "external edit");
      push(ext, "refs/heads/main");
    }
    final var integration = remote.fetchAndIntegrate(WS1, USER);
    assertEquals(Outcome.FAST_FORWARD, integration.outcome());
    assertEquals(List.of(), integration.importedRevisions());
    assertEquals("edited outside", read(WS1, "a.seq"));
    assertEquals(List.of(a), revisionsOf(WS1, "a.seq"));
    assertEquals(1, revisionRefs(WS1).size());
    assertTrue(revisions.list(WS1, Path.of("a.seq")).matching().isEmpty(), "the file has changed since revision a");
    assertClean(WS1);
  }

  @Test
  void anExternallyAddedFileHasNoIdentityUntilItsFirstRevision() throws Exception {
    publishedWorkspace();
    try (final var ext = external()) {
      write(ext, "new.seq", "from git");
      commit(ext, "add new.seq");
      push(ext, "refs/heads/main");
    }
    remote.fetchAndIntegrate(WS1, USER);
    assertEquals("from git", read(WS1, "new.seq"));
    assertFalse(Files.exists(root(WS1).resolve(sidecar("new.seq"))));
    assertTrue(revisions.list(WS1, Path.of("new.seq")).fileId().isEmpty());
    final var first = revise(WS1, "new.seq");
    assertEquals("a", first.name());
    assertEquals(List.of(first), revisionsOf(WS1, "new.seq"));
  }

  @Test
  void anExternalRenameOfFileAndSidecarKeepsTheFilesRevisions() throws Exception {
    final var a = publishedWorkspace();
    try (final var ext = external()) {
      final var wt = ext.getRepository().getWorkTree().toPath();
      Files.createDirectories(wt.resolve("seq"));
      Files.move(wt.resolve("a.seq"), wt.resolve("seq/renamed.seq"));
      Files.move(wt.resolve(sidecar("a.seq")), wt.resolve(sidecar("seq/renamed.seq")));
      commit(ext, "git mv a.seq seq/renamed.seq");
      push(ext, "refs/heads/main");
    }
    remote.fetchAndIntegrate(WS1, USER);
    assertFalse(Files.exists(root(WS1).resolve("a.seq")));
    assertEquals(a.fileId(), revisions.list(WS1, Path.of("seq/renamed.seq")).fileId().orElseThrow());
    assertEquals(List.of(a), revisionsOf(WS1, "seq/renamed.seq"));
    assertEquals("a1", new String(revisions.read(WS1, a.id()).content(), StandardCharsets.UTF_8));

    save(WS1, "seq/renamed.seq", "a2");
    final var b = revise(WS1, "seq/renamed.seq");
    assertEquals(2, b.ordinal());
    assertEquals(a.fileId(), b.fileId());
  }

  @Test
  void anExternalCopyThatDuplicatesAFileIdIsRejected() throws Exception {
    publishedWorkspace();
    try (final var ext = external()) {
      final var wt = ext.getRepository().getWorkTree().toPath();
      Files.copy(wt.resolve("a.seq"), wt.resolve("copy.seq"));
      Files.copy(wt.resolve(sidecar("a.seq")), wt.resolve(sidecar("copy.seq")));
      commit(ext, "cp a.seq copy.seq");
      push(ext, "refs/heads/main");
    }
    assertRejected(WS1, "[a.seq, copy.seq] all claim fileId");
  }

  @Test
  void anIncomingWorkspaceThatBreaksWorkspaceRulesIsRejected() throws Exception {
    publishedWorkspace();
    final var baseline = remoteRef("refs/heads/main");
    final var fileId = Json.createValue(UUID.randomUUID().toString());
    final var cases = new LinkedHashMap<String, Map<String, String>>(); // problem -> files to write
    cases.put(".gitignore (reserved path)", Map.of(".gitignore", "*.seq\n"));
    cases.put("dir/.gitattributes (reserved path)", Map.of("dir/.gitattributes", "* text\n"));
    cases.put(".seqdev/state.json (reserved path)", Map.of(".seqdev/state.json", "{}"));
    cases.put(sidecar("a.seq") + " (metadata is not a JSON object)", Map.of(sidecar("a.seq"), "{not json"));
    cases.put(sidecar("a.seq") + " (fileId is not a UUID)", Map.of(sidecar("a.seq"), "{\"fileId\":\"nope\"}"));
    cases.put(sidecar("a.seq") + " (metadata has runtime field readOnly)",
              Map.of(sidecar("a.seq"), Json.createObjectBuilder().add("fileId", fileId).add("readOnly", true).build().toString()));
    cases.put("link.seq (symbolic link)", Map.of());

    for (final var c : cases.entrySet()) {
      try (final var ext = external()) {
        ext.reset().setMode(ResetCommand.ResetType.HARD).setRef(baseline.name()).call();
        for (final var f : c.getValue().entrySet()) write(ext, f.getKey(), f.getValue());
        if (c.getValue().isEmpty()) {
          Files.createSymbolicLink(ext.getRepository().getWorkTree().toPath().resolve("link.seq"), Path.of("a.seq"));
        }
        commit(ext, "bad: " + c.getKey());
        push(ext, "+refs/heads/main");
      }
      assertRejected(WS1, c.getKey());
    }
  }

  @Test
  void cleanlyDivergedHistoriesAreMergedAndBothArePreserved() throws Exception {
    publishedWorkspace();
    final RevCommit theirs;
    try (final var ext = external()) {
      write(ext, "b.seq", "b from git");
      theirs = commit(ext, "external edit of b");
      push(ext, "refs/heads/main");
    }
    save(WS1, "a.seq", "a from PlanDev");
    final var ours = head(WS1);

    assertEquals(Outcome.MERGED, remote.fetchAndIntegrate(WS1, USER).outcome());
    assertEquals("a from PlanDev", read(WS1, "a.seq"));
    assertEquals("b from git", read(WS1, "b.seq"));
    try (final var git = Git.open(root(WS1).toFile())) {
      final var merge = git.log().setMaxCount(1).call().iterator().next();
      assertEquals(List.of(ours, theirs.name()), java.util.Arrays.stream(merge.getParents()).map(RevCommit::name).toList());
    }
    assertClean(WS1);
    remote.push(WS1);
    assertEquals(head(WS1), remoteRef("refs/heads/main").name());
  }

  @Test
  void aConflictAbortsTheImportAndChangesNothing() throws Exception {
    publishedWorkspace();
    try (final var ext = external()) {
      write(ext, "a.seq", "theirs");
      write(ext, "new.seq", "would arrive with the merge");
      commit(ext, "conflicting edit");
      push(ext, "refs/heads/main");
    }
    save(WS1, "a.seq", "ours");
    assertRejected(WS1, "a.seq (conflict)");
    assertEquals("ours", read(WS1, "a.seq"));
    assertFalse(Files.exists(root(WS1).resolve("new.seq")));
  }

  @Test
  void aFailureAfterTheWorkingTreeAdvancedRestoresEverything() throws Exception {
    publishedWorkspace();
    save(WS1, "c.seq", "c1");
    remote.push(WS1);
    try (final var ext = external()) {
      write(ext, "a.seq", "modified");
      write(ext, "dir/sub/added.seq", "added");
      Files.delete(ext.getRepository().getWorkTree().toPath().resolve("c.seq"));
      commit(ext, "modify, add, delete");
      push(ext, "refs/heads/main");
    }
    remote.failPromote = true;
    assertRejected(WS1, "injected promotion failure");
    assertFalse(Files.exists(root(WS1).resolve("dir")), "directories the import created are removed");
    remote.failPromote = false;
    remote.fetchAndIntegrate(WS1, USER);
    assertEquals("modified", read(WS1, "a.seq"));
  }

  @Test
  void aRevisionMadeInAnotherCloneIsImportedWithItsId() throws Exception {
    final var a = publishedWorkspace();
    remote.cloneInto(WS2, bare.toString(), USER);
    save(WS2, "a.seq", "a2 in ws2");
    final var b = revise(WS2, "a.seq");
    remote.push(WS2);

    final var integration = remote.fetchAndIntegrate(WS1, USER);
    assertEquals(Outcome.FAST_FORWARD, integration.outcome());
    assertEquals(List.of(b.id()), integration.importedRevisions());
    final var imported = revisionsOf(WS1, "a.seq");
    assertEquals(List.of(a.id(), b.id()), imported.stream().map(Revision::id).toList());
    assertEquals(new Revision(b.id(), WS1, b.fileId(), 2, "b", "a.seq", b.commitSha(), USER, b.createdAt()), imported.get(1));
    assertEquals("a2 in ws2", new String(revisions.read(WS1, b.id()).content(), StandardCharsets.UTF_8));
    assertEquals(imported.get(1), revisions.list(WS1, Path.of("a.seq")).matching().orElseThrow());

    save(WS1, "a.seq", "a3");
    assertEquals("c", revise(WS1, "a.seq").name());
  }

  @Test
  void anInvalidIncomingRevisionTagRejectsTheWholeImport() throws Exception {
    final var a = publishedWorkspace();
    final var baseline = remoteRef("refs/heads/main");
    final var forged = "plandev/revisions/" + UUID.randomUUID();

    // Malformed annotation, alongside an otherwise acceptable commit
    try (final var ext = external()) {
      write(ext, "b.seq", "fine");
      final var x = commit(ext, "ordinary");
      tag(ext, forged, x, "not json");
      push(ext, "refs/heads/main", "refs/tags/" + forged);
    }
    assertRejected(WS1, "annotation is not a JSON object");

    // A well-formed revision on a commit that main will not contain
    try (final var ext = external()) {
      ext.reset().setMode(ResetCommand.ResetType.HARD).setRef(baseline.name()).call();
      ext.checkout().setCreateBranch(true).setName("side").call();
      write(ext, "a.seq", "side");
      final var side = commit(ext, "side branch");
      final var id = UUID.fromString(forged.substring(forged.lastIndexOf('/') + 1));
      tag(ext, forged, side, GitFileRevisions.annotation(new Revision(
          id, WS1, a.fileId(), 2, "b", "a.seq", side.name(), "mallory", Instant.now().truncatedTo(ChronoUnit.MICROS))));
      push(ext, "+refs/tags/" + forged);
    }
    assertRejected(WS1, "not in the integrated history");
  }

  @Test
  void anExistingRevisionCannotBeMovedOrRewrittenByTheRemote() throws Exception {
    final var a = publishedWorkspace();
    final var name = "plandev/revisions/" + a.id();
    final var rewritten = new Revision(a.id(), WS1, a.fileId(), 1, "renamed", "a.seq", a.commitSha(), USER, a.createdAt());
    final var cases = new LinkedHashMap<String, Boolean>(); // description -> move to a new commit
    cases.put("moved to a new commit", true);
    cases.put("re-annotated in place", false);
    for (final var c : cases.entrySet()) {
      try (final var ext = external()) {
        ObjectId target = ObjectId.fromString(a.commitSha());
        if (c.getValue()) {
          write(ext, "a.seq", "different");
          target = commit(ext, c.getKey());
          push(ext, "refs/heads/main");
        }
        tag(ext, name, target, GitFileRevisions.annotation(c.getValue() ? a : rewritten));
        push(ext, "+refs/tags/" + name);
      }
      assertRejected(WS1, "revision " + a.id() + " differs from the existing one");
    }
  }

  @Test
  void aRevisionMissingFromTheRemoteIsNotDeleted() throws Exception {
    final var a = publishedWorkspace();
    final var name = "refs/tags/plandev/revisions/" + a.id();
    try (final var ext = external()) {
      write(ext, "b.seq", "b2");
      commit(ext, "edit");
      push(ext, "refs/heads/main", ":" + name);
    }
    assertNull(remoteRef(name));
    assertEquals(Outcome.FAST_FORWARD, remote.fetchAndIntegrate(WS1, USER).outcome());
    assertTrue(revisionRefs(WS1).containsKey(a.id().toString()));
    assertEquals(List.of(a), revisionsOf(WS1, "a.seq"));
    remote.push(WS1);
    assertNotNull(remoteRef(name), "the next push publishes it again");
  }

  @Test
  void ordinaryTagsAreNeverRevisions() throws Exception {
    final var a = publishedWorkspace();
    try (final var ext = external()) {
      final var head = ext.getRepository().resolve(Constants.HEAD);
      // Even an annotation shaped exactly like a revision's does not make a tag a revision outside the namespace
      final var lookalike = GitFileRevisions.annotation(new Revision(
          UUID.randomUUID(), WS1, a.fileId(), 2, "b", "a.seq", head.name(), "bob", Instant.now().truncatedTo(ChronoUnit.MICROS)));
      tag(ext, "release-test", head, lookalike);
      tag(ext, "experiment/foo", head, lookalike);
      push(ext, "refs/tags/release-test", "refs/tags/experiment/foo");
    }
    final var integration = remote.fetchAndIntegrate(WS1, USER);
    assertEquals(Outcome.UP_TO_DATE, integration.outcome());
    assertEquals(List.of(), integration.importedRevisions());
    assertEquals(List.of(a), store.rows);
    try (final var repo = Git.open(root(WS1).toFile()).getRepository()) {
      assertNull(repo.exactRef("refs/tags/release-test"));
      assertEquals(List.of(a), GitFileRevisions.readAll(repo, WS1));
    }
  }

  @Test
  void aPushIsNeverForced() throws Exception {
    publishedWorkspace();
    final RevCommit theirs;
    try (final var ext = external()) {
      write(ext, "b.seq", "theirs");
      theirs = commit(ext, "remote moved on");
      push(ext, "refs/heads/main");
    }
    save(WS1, "a.seq", "ours");
    final var local = revise(WS1, "a.seq");

    final var e = assertThrows(RemoteRejectedException.class, () -> remote.push(WS1));
    assertTrue(e.getMessage().contains("REJECTED_NONFASTFORWARD"), e.getMessage());
    assertEquals(theirs, remoteRef("refs/heads/main"), "the remote's history was not rewritten");
    assertNull(remoteRef("refs/tags/plandev/revisions/" + local.id()), "atomic: no revision tag went out either");

    remote.fetchAndIntegrate(WS1, USER);
    remote.push(WS1);
    assertEquals(head(WS1), remoteRef("refs/heads/main").name());
    assertNotNull(remoteRef("refs/tags/plandev/revisions/" + local.id()));
  }

  @Test
  void aSecondWorkspaceClonedFromTheRemoteRebuildsTheSameRevisions() throws Exception {
    save(WS1, "foo.seq", "first");
    revise(WS1, "foo.seq");
    final var move = bindings.handleMove(Path.of("foo.seq"), Path.of("bar.seq"), WS1, WS1, false, USER);
    assertInstanceOf(HandlerResult.Success.class, move);
    save(WS1, "bar.seq", "second");
    revise(WS1, "bar.seq");
    remote.linkRemote(WS1, bare.toString());
    remote.push(WS1);

    final var integration = remote.cloneInto(WS2, bare.toString(), USER);
    assertEquals(head(WS1), integration.head());
    final var inWs1 = revisionsOf(WS1, "bar.seq");
    final var inWs2 = revisionsOf(WS2, "bar.seq");
    assertEquals(inWs1.stream().map(Revision::id).toList(), inWs2.stream().map(Revision::id).toList(), "same revision ids");
    assertEquals(inWs1.stream().map(r -> new Revision(r.id(), WS2, r.fileId(), r.ordinal(), r.name(), r.pathAtRevision(),
                                                      r.commitSha(), r.createdBy(), r.createdAt())).toList(), inWs2);
    assertEquals(4, store.rows.size(), "both workspaces hold the same two revision ids side by side");
    assertEquals("first", new String(revisions.read(WS2, inWs2.getFirst().id()).content(), StandardCharsets.UTF_8));
    assertClean(WS2);

    save(WS2, "bar.seq", "third"); // the clone is an ordinary managed workspace
    assertEquals("c", revise(WS2, "bar.seq").name());
  }

  @Test
  void aRejectedCloneLeavesTheWorkspaceEmpty() throws Exception {
    publishedWorkspace();
    try (final var ext = external()) {
      write(ext, ".gitignore", "*.seq\n");
      commit(ext, "bad");
      push(ext, "refs/heads/main");
    }
    assertThrows(RemoteRejectedException.class, () -> remote.cloneInto(WS2, bare.toString(), USER));
    try (final var entries = Files.list(root(WS2))) {
      assertEquals(List.of(), entries.toList());
    }
  }

  @Test
  void aFewMegabyteFileRoundTripsByteForByte() throws Exception {
    final var ours = randomBytes(1, 3 * 1024 * 1024);
    save(WS1, "big.bin", ours);
    remote.linkRemote(WS1, bare.toString());
    remote.push(WS1);

    final var theirs = randomBytes(2, 3 * 1024 * 1024 + 17);
    try (final var ext = external()) {
      final var file = ext.getRepository().getWorkTree().toPath().resolve("big.bin");
      assertArrayEquals(ours, Files.readAllBytes(file));
      Files.write(file, theirs);
      commit(ext, "replace big.bin");
      push(ext, "refs/heads/main");
    }
    remote.fetchAndIntegrate(WS1, USER);
    assertArrayEquals(theirs, Files.readAllBytes(root(WS1).resolve("big.bin")));
    assertClean(WS1);
  }

  /**
   * Ordinals are assigned per repository, so two clones that each make "the next" revision of one file produce two
   * revisions claiming the same ordinal; even when the content merges cleanly, the import is rejected.
   */
  @Test
  void concurrentRevisionsOfOneFileInTwoClonesCollide() throws Exception {
    publishedWorkspace();
    remote.cloneInto(WS2, bare.toString(), USER);
    save(WS2, "a.seq", "a2");
    revise(WS2, "a.seq");
    remote.push(WS2);

    save(WS1, "a.seq", "a2"); // the same edit, so the content merges cleanly
    revise(WS1, "a.seq");
    assertRejected(WS1, "both claim ordinal 2");
  }
}
