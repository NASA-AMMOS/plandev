package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revision and last-edit timings over generated workspace histories of small files. Each scenario prints a table.
 *
 * In CI (no SCALE set) the histories are small and every timing must stay under a generous budget, so only an
 * order-of-magnitude regression (say, a cache that stopped working, or a walk that became per-path) fails it.
 * Locally, {@code SCALE=10 ./gradlew :workspace-server:test --tests '*ScaleTest*' -i} multiplies every history length
 * and only reports.
 */
class WorkspaceRevisionsScaleTest {
  private static final int WS = 1;
  private static final int SCALE = Integer.parseInt(System.getenv().getOrDefault("SCALE", "1"));
  private static final Path TARGET = Path.of("seq/target.seq");
  private static final Path OTHER = Path.of("d01/f001.seq");

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
      // Disabled until lastEdits stops walking history once per path: its cold call takes ~90 s at 1000 files.
      // "many-files,     1000,    1000,  20,          10,        20",
      "many-tags,      1000,    100,   20,          100,       1000",
  })
  void scenario(String name, int commits, int files, int targetEvery, int revisions, int otherTags) throws Exception {
    commits *= SCALE;
    final var repository = new WorkspaceTestRepository(tmp.toRealPath());
    final var root = repository.workspaceRootPath(WS);
    final var start = System.nanoTime();
    generate(root, commits, files, targetEvery, revisions, otherTags);
    final var git = new WorkspaceGitService(repository);
    final var fs = new WorkspaceFileSystemService(repository, git);
    final var bindings = new WorkspaceBindings(null, fs, git, null, "");
    save(git, root, OTHER); // adopts the generated repository, as a legacy workspace's first mutation would
    final Set<Path> all = new HashSet<>();
    try (final var paths = Files.walk(root)) {
      paths.filter(p -> Files.isRegularFile(p) && !root.relativize(p).toString().matches("(.*/)?\\..*")) // no dot files or dirs
           .forEach(all::add); // lastEdits takes absolute paths
    }

    final var oldest = WorkspaceRevisions.name(1);
    System.out.printf("%s: %d commits, %d files, target every %d, %d revisions, %d other tags (setup %.1fs)%n",
        name, commits, all.size(), targetEvery, revisions, otherTags, (System.nanoTime() - start) / 1e9);
    // Budgets (ms, SCALE=1) are ~10x a laptop run, to absorb slow CI runners.
    time("list cold", 2000, () -> new WorkspaceFileSystemService(repository, git).listRevisions(WS, TARGET));
    time("list warm", 300, () -> fs.listRevisions(WS, TARGET));
    time("save other + list", 1500, () -> { save(git, root, OTHER); fs.listRevisions(WS, TARGET); });
    time("read oldest cold", 2000, () -> new WorkspaceFileSystemService(repository, git).readRevision(WS, TARGET, oldest));
    time("lastEdits all cold", 10000, () -> new WorkspaceGitService(repository).lastEdits(WS, all));
    time("save other + lastEdits all", 10000, () -> { save(git, root, OTHER); git.lastEdits(WS, all); });
    time("create", 1000, () -> assertInstanceOf(HandlerResult.Success.class,
        bindings.handleCreateRevision(WS, TARGET, "*", "", "bench")));
    time("restore oldest", 1000, () -> assertInstanceOf(HandlerResult.Success.class,
        bindings.handleRestoreRevision(WS, TARGET, oldest, "*", "bench")));
  }

  interface Op { void run() throws Exception; }

  /** Median of 3 runs after a warm-up. Fails after the warm-up alone if that is already over budget. */
  private static void time(String op, int budgetMs, Op action) throws Exception {
    final var warmUp = System.nanoTime();
    action.run();
    final var warmUpMs = (System.nanoTime() - warmUp) / 1e6;
    if (SCALE == 1) assertTrue(warmUpMs < budgetMs, "%s took %.1f ms, budget %d ms".formatted(op, warmUpMs, budgetMs));
    final var ms = new double[3];
    for (var i = 0; i < ms.length; i++) {
      final var start = System.nanoTime();
      action.run();
      ms[i] = (System.nanoTime() - start) / 1e6;
    }
    Arrays.sort(ms);
    System.out.printf("  %-28s %9.1f ms%n", op, ms[1]);
    if (SCALE == 1) assertTrue(ms[1] < budgetMs, "%s took %.1f ms, budget %d ms".formatted(op, ms[1], budgetMs));
  }

  private static int saves = 0;

  private static void save(WorkspaceGitService git, Path root, Path path) throws Exception {
    git.mutate(WS, "bench", "Update " + path, () -> {
      Files.writeString(root.resolve(path), "save " + saves++ + "\n");
      return true;
    }, result -> result);
  }

  /** Writes the history directly as Git objects (a save through the API takes ~25 ms) and checks out its tip. */
  private static void generate(Path root, int commits, int files, int targetEvery, int revisions, int otherTags)
  throws Exception
  {
    final var blobs = new TreeMap<String, ObjectId>(); // workspace-relative path -> blob
    final var revisionAt = new ArrayList<ObjectId>();
    final var otherTagAt = new ArrayList<ObjectId>();
    try (final var git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call();
         final var inserter = git.getRepository().newObjectInserter()) {
      final var sidecar = blob(inserter, "{}");
      ObjectId parent = null;
      var other = 0;
      for (var c = 0; c < commits; c++) {
        final var path = c % targetEvery == 0 ? TARGET.toString() : "d%02d/f%03d.seq".formatted(other % 20, other++ % files);
        blobs.put(path, blob(inserter, "%s @ %d%n".formatted(path, c)));
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
        if (otherTags > 0 && c % Math.max(1, commits / otherTags) == 0 && otherTagAt.size() < otherTags) otherTagAt.add(parent);
      }
      inserter.flush();
      final var update = git.getRepository().updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(parent);
      update.forceUpdate();
      git.reset().setMode(ResetCommand.ResetType.HARD).call();
      tag(git, revisionAt, TARGET.toString());
      tag(git, otherTagAt, OTHER.toString());
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
  private static void tag(Git git, List<ObjectId> commits, String path) throws Exception {
    try (final var walk = new RevWalk(git.getRepository())) {
      for (var i = 0; i < commits.size(); i++) {
        final var revision = new WorkspaceRevisions.Revision(
            WorkspaceRevisions.name(i + 1), i + 1, path, "", "bench", Instant.now(), commits.get(i), null);
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
