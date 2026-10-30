package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.utils.WorkspaceTestRepository;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revision and last-edit timings over generated workspace histories of mostly small files, before and after a Git GC
 * that must keep every revision and commit. Each scenario prints a table.
 *
 * In CI (no SCALE set) the histories are small and every timing must stay under a generous budget, so only an
 * order-of-magnitude regression (say, a cache that stopped working, or a walk that became per-path) fails it.
 * Locally, {@code SCALE=10 ./gradlew :workspace-server:test --tests '*ScaleTest*' -i} multiplies every history length
 * and only reports.
 */
class WorkspaceRevisionsScaleTest {
  private static Workspace WS;
  private static final int SCALE = Integer.parseInt(System.getenv().getOrDefault("SCALE", "1"));
  private static final Path TARGET = Path.of("seq/target.seq");
  private static final UUID TARGET_FILE_ID = UUID.nameUUIDFromBytes(TARGET.toString().getBytes());
  private static final Path OTHER = Path.of("d01/f001.seq");
  private static final UUID OTHER_FILE_ID = UUID.nameUUIDFromBytes(OTHER.toString().getBytes());

  @TempDir Path tmp;

  /**
   * commits: history length (times SCALE). files: other files, one edited per commit, round robin. targetEvery: the
   * target is edited every N commits. revisions: on the target, spread evenly. otherTags: revisions on another file.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      // name,         commits, files, targetEvery, revisions, otherTags
      "typical,        1000,    100,   20,          10,        20",
      "rarely-edited,  1000,    100,   500,         2,         0",
      "many-files,     1000,    1000,  20,          10,        20",
      "many-tags,      1000,    100,   20,          100,       1000",
  })
  void scenario(String name, int commits, int files, int targetEvery, int revisions, int otherTags) throws Throwable {
    commits *= SCALE;
    final var repository = new WorkspaceTestRepository(tmp.toRealPath());
    WS = repository.createWorkspace("ws1");
    final var root = WS.rootPath();
    final var start = System.nanoTime();
    generate(root, commits, files, targetEvery, revisions, otherTags, 16 * 1024);
    final var git = new WorkspaceGitService();
    final var fs = new WorkspaceFileSystemService(repository, git);
    final var bindings = new WorkspaceBindings(null, fs, git, null, "");
    save(git, root, OTHER); // adopts the generated repository, as a legacy workspace's first mutation would
    final Set<Path> all = new HashSet<>();
    try (final var paths = Files.walk(root)) {
      paths.filter(p -> Files.isRegularFile(p) && !root.relativize(p).toString().matches("(.*/)?\\..*")) // no dot files or dirs
           .forEach(all::add); // lastEdits takes absolute paths
    }

    final var oldest = WorkspaceRevisions.convention.name(1);
    System.out.printf("%s: %d commits, %d files, target every %d, %d revisions, %d other tags (setup %.1fs)%n",
        name, commits, all.size(), targetEvery, revisions, otherTags, (System.nanoTime() - start) / 1e9);
    // Budgets (ms, SCALE=1) are ~10x a laptop run, to absorb slow CI runners.
    time("list cold", 2000, () -> new WorkspaceFileSystemService(repository, git).listRevisions(WS, TARGET));
    time("list warm", 300, () -> fs.listRevisions(WS, TARGET));
    final var listed = fs.listRevisions(WS, TARGET);
    // Matches no revision unless the target is unchanged since the latest one, so this is usually a scan of all of them.
    time("matchingRevision", 300, () -> fs.matchingRevision(WS, TARGET, listed));
    time("save other + list", 1500, () -> { save(git, root, OTHER); fs.listRevisions(WS, TARGET); });
    time("read oldest cold", 2000, () -> new WorkspaceFileSystemService(repository, git).readRevision(WS, TARGET, oldest));
    time("lastEdits all cold", 10000, () -> new WorkspaceGitService().lastEdits(WS, all));
    time("save other + lastEdits all", 10000, () -> { save(git, root, OTHER); git.lastEdits(WS, all); });
    time("create", 1000, () -> assertInstanceOf(HandlerResult.Success.class,
        bindings.handleCreateRevision(WS.id(), TARGET, "*", "", null, "bench")));
    time("restore oldest", 1000, () -> assertInstanceOf(HandlerResult.Success.class,
        bindings.handleRestoreRevision(WS.id(), TARGET, oldest, "*", "bench")));

    // GC, pruning everything unreachable now rather than after two weeks: every revision and commit survives, warm
    // caches stay right, and the history reads are timed again on the packed repository.
    final var revisionsBefore = everyRevision(root);
    final var commitsBefore = mainCommits(root);
    final var bytesBefore = bytes(root.resolve(".git"));
    final var gcStart = System.nanoTime();
    try (final var g = Git.open(root.toFile())) {
      g.gc().setExpire(new Date()).call();
    }
    System.out.printf("  %-28s %9.1f ms (.git %.1f -> %.1f MB)%n", "gc",
                      (System.nanoTime() - gcStart) / 1e6, bytesBefore / 1e6, bytes(root.resolve(".git")) / 1e6);
    assertEquals(revisionsBefore, everyRevision(root));
    assertEquals(commitsBefore, mainCommits(root));
    assertEquals(new WorkspaceFileSystemService(repository, git).listRevisions(WS, TARGET), fs.listRevisions(WS, TARGET));
    time("list cold after gc", 2000, () -> new WorkspaceFileSystemService(repository, git).listRevisions(WS, TARGET));
    time("read oldest cold after gc", 2000, () -> new WorkspaceFileSystemService(repository, git).readRevision(WS, TARGET, oldest));
    final var count = fs.listRevisions(WS, TARGET).size();
    time("create after gc", 1000, () -> assertInstanceOf(HandlerResult.Success.class, // a loose tag among packed ones
        bindings.handleCreateRevision(WS.id(), TARGET, "*", "", null, "bench")));
    assertEquals(count + 4, new WorkspaceFileSystemService(repository, git).listRevisions(WS, TARGET).size()); // time runs 4
  }

  /** Revision name -> its JSON, commit, content hash and metadata, for every file with revisions, read cold. */
  private static Map<String, String> everyRevision(Path root) throws Exception {
    final var revisions = new WorkspaceRevisions();
    final var all = new TreeMap<String, String>();
    for (final var path : List.of(TARGET, OTHER)) {
      for (final var revision : revisions.list(root, path.toString())) {
        final var file = WorkspaceRevisions.read(root, revision);
        final var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.content()));
        all.put(path + "@" + revision.name(), revision.toJson() + " " + revision.commit().name() + " " + hash + " " + file.metadata());
      }
    }
    return all;
  }

  private static List<ObjectId> mainCommits(Path root) throws Exception {
    try (final var g = Git.open(root.toFile())) {
      final var ids = new ArrayList<ObjectId>();
      g.log().call().forEach(c -> ids.add(c.copy()));
      return ids;
    }
  }

  private static long bytes(Path dir) throws Exception {
    try (final var files = Files.walk(dir)) {
      return files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
    }
  }

  /**
   * Median of 3 runs after a warm-up. Every run, the warm-up included, must be under budget, so a regression fails after
   * one slow call rather than four.
   */
  private static void time(String op, int budgetMs, Executable action) throws Throwable {
    final var ms = new double[4]; // ms[0] is the warm-up
    for (var i = 0; i < ms.length; i++) {
      final var start = System.nanoTime();
      action.execute();
      ms[i] = (System.nanoTime() - start) / 1e6;
      if (SCALE == 1) assertTrue(ms[i] < budgetMs, "%s took %.1f ms, budget %d ms".formatted(op, ms[i], budgetMs));
    }
    Arrays.sort(ms, 1, ms.length);
    System.out.printf("  %-28s %9.1f ms%n", op, ms[2]);
  }

  private static int saves = 0;

  private static void save(WorkspaceGitService git, Path root, Path path) throws Exception {
    git.mutate(WS, "Update " + path, "bench", () -> {
      Files.writeString(root.resolve(path), "save " + saves++ + "\n");
      return true;
    }, result -> result);
  }

  /**
   * Writes the history directly as Git objects (a save through the API takes ~25 ms) and checks out its tip. Files in
   * d00 get the same largeBytes of random text appended, so versions of them differ by a few bytes, as edits to a large
   * sequence would.
   */
  static void generate(Path root, int commits, int files, int targetEvery, int revisions, int otherTags, int largeBytes)
  throws Exception
  {
    final var blobs = new TreeMap<String, ObjectId>(); // workspace-relative path -> blob
    final var revisionAt = new ArrayList<ObjectId>();
    final var otherTagAt = new ArrayList<ObjectId>();
    final var random = new Random(42);
    final var padding = new StringBuilder();
    while (padding.length() < largeBytes) padding.append(Long.toHexString(random.nextLong()));
    try (final var git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call();
         final var inserter = git.getRepository().newObjectInserter()) {
      final var sidecar = blob(inserter, "{}");
      ObjectId parent = null;
      var other = 0;
      for (var c = 0; c < commits; c++) {
        final var path = c % targetEvery == 0 ? TARGET.toString() : "d%02d/f%03d.seq".formatted(other % 20, other++ % files);
        final var content = "%s @ %d%n".formatted(path, c);
        blobs.put(path, blob(inserter, path.startsWith("d00/") ? content + padding : content));
        blobs.putIfAbsent(WorkspaceRevisions.sidecarKey(path), sidecar);
        final var commit = new CommitBuilder();
        commit.setTreeId(tree(inserter, blobs));
        if (parent != null) commit.setParentId(parent);
        final var who = new PersonIdent("u" + c % 3, "u" + c % 3 + "@bench", Instant.ofEpochSecond(1_700_000_000L + c), ZoneOffset.UTC);
        commit.setAuthor(who);
        commit.setCommitter(who);
        commit.setMessage("c" + c);
        parent = inserter.insert(commit);
        if (revisions > 0 && (c + 1) % Math.max(1, commits / revisions) == 0 && revisionAt.size() < revisions) revisionAt.add(parent);
        // Only on commits that hold the file, as create would tag (the other file is created partway through).
        if (otherTags > 0 && c % Math.max(1, commits / otherTags) == 0 && otherTagAt.size() < otherTags
            && blobs.containsKey(OTHER.toString())) otherTagAt.add(parent);
      }
      inserter.flush();
      final var update = git.getRepository().updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(parent);
      update.forceUpdate();
      git.reset().setMode(ResetCommand.ResetType.HARD).call();
      tag(git, revisionAt, TARGET.toString(), TARGET_FILE_ID);
      tag(git, otherTagAt, OTHER.toString(), OTHER_FILE_ID);
    }
  }

  private static ObjectId blob(ObjectInserter inserter, String content) throws Exception {
    return inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
  }

  /** A two-level tree: every path is "dir/name". */
  private static ObjectId tree(ObjectInserter inserter, TreeMap<String, ObjectId> blobs) throws Exception {
    final var dirs = new TreeMap<String, TreeFormatter>();
    blobs.forEach((path, id) -> dirs.computeIfAbsent(path.substring(0, path.indexOf('/')), d -> new TreeFormatter())
                                    .append(path.substring(path.indexOf('/') + 1), FileMode.REGULAR_FILE, id));
    final var top = new TreeFormatter();
    for (final var dir : dirs.entrySet()) top.append(dir.getKey(), FileMode.TREE, inserter.insert(dir.getValue()));
    return inserter.insert(top);
  }

  /** Revisions 1..n of path on the given commits, written as create would write them. */
  private static void tag(Git git, List<ObjectId> commits, String path, UUID fileId) throws Exception {
    try (final var walk = new RevWalk(git.getRepository())) {
      for (var i = 0; i < commits.size(); i++) {
        final var revision = new WorkspaceRevisions.Revision(
            i + 1, path, "", "bench", Instant.now(), commits.get(i), null, fileId);
        git.tag()
           .setName(WorkspaceRevisions.TAG_PREFIX + UUID.randomUUID())
           .setObjectId(walk.parseCommit(commits.get(i)))
           .setAnnotated(true)
           .setTagger(new PersonIdent("bench", ""))
           .setMessage(WorkspaceRevisions.annotation(revision))
           .call();
      }
    }
  }
}
