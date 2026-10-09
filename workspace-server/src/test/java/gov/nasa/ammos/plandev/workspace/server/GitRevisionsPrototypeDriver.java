package gov.nasa.ammos.plandev.workspace.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistoryIntegrationTest.MemoryRevisionStore;
import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.StaleFileException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;
import gov.nasa.ammos.plandev.workspace.server.postgres.PostgresRevisionStore;
import gov.nasa.ammos.plandev.workspace.server.scale.WorkspaceVersioningDriver;
import gov.nasa.ammos.plandev.workspace.server.types.HandlerResult;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.Part;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Adapter for the {@code prototype/git-authoritative-revisions} backend: Git-on-save history, revisions as annotated
 * tags projected into a revision catalog, fileId sidecars, controlled Git remotes. Drives the same entry points the
 * HTTP layer uses ({@link WorkspaceBindings} handlers, {@link WorkspaceRevisionService},
 * {@link WorkspaceGitRemoteService}), below HTTP and without auth, on real repositories on disk.
 *
 * <p>The revision catalog is Postgres when {@code jdbc} is given (a scratch database is created and dropped), otherwise
 * the in-memory test store, whose {@code list} scans every row.
 *
 * <p>{@link #restart} rebuilds every service and clears JGit's in-process caches; the catalog persists, as a database
 * would. The JVM (JIT) and the OS page cache stay warm.
 */
public final class GitRevisionsPrototypeDriver implements WorkspaceVersioningDriver {
  private static final String USER = "bench";

  private record Services(
      WorkspaceHistory history,
      WorkspaceFileSystemService fs,
      WorkspaceBindings bindings,
      WorkspaceRevisionService revisions,
      WorkspaceGitRemoteService remote) {}

  private final Path base;
  private final Map<Integer, Path> roots = new ConcurrentHashMap<>();
  private final Map<String, Integer> ids = new ConcurrentHashMap<>();
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Set<String> linked = ConcurrentHashMap.newKeySet();
  private final String jdbc;
  private final String database;
  private HikariDataSource dataSource;
  private final WorkspaceRevisionStore memoryStore;
  private volatile Services services;

  /** @param jdbc a Postgres server's admin JDBC URL (with credentials) to hold a scratch catalog database, or null */
  public GitRevisionsPrototypeDriver(final Path base, final String jdbc) throws Exception {
    this.base = Files.createDirectories(base).toRealPath();
    this.jdbc = jdbc;
    if (jdbc == null) {
      database = null;
      memoryStore = new MemoryRevisionStore();
    } else {
      database = "wsbench_" + ProcessHandle.current().pid();
      memoryStore = null;
      try (final var c = DriverManager.getConnection(jdbc); final var s = c.createStatement()) {
        s.execute("drop database if exists " + database);
        s.execute("create database " + database);
      }
      openPool();
      try (final var c = dataSource.getConnection(); final var s = c.createStatement()) {
        s.execute("create schema sequencing; create table sequencing.workspace (id integer primary key);");
        s.execute(Files.readString(revisionTableDdl()));
      }
    }
    services = newServices();
  }

  private void openPool() {
    final var config = new HikariConfig();
    config.setJdbcUrl(jdbc.replaceFirst("/[^/?]*(\\?|$)", "/" + database + "$1"));
    config.setMaximumPoolSize(32);
    dataSource = new HikariDataSource(config);
  }

  /** The production DDL for the catalog table, so the harness tracks schema changes. */
  private static Path revisionTableDdl() {
    final var rel = Path.of("deployment/postgres-init-db/sql/tables/sequencing/workspace_file_revision.sql");
    for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.exists(dir.resolve(rel))) return dir.resolve(rel);
    }
    throw new IllegalStateException("Cannot find " + rel + " above " + Path.of("").toAbsolutePath());
  }

  private Services newServices() {
    final WorkspaceRoots r = id -> {
      final var root = roots.get(id);
      if (root == null) throw new NoSuchWorkspaceException(id);
      return root;
    };
    final var history = new WorkspaceHistory(r);
    final var fs = new WorkspaceFileSystemService(r, null, history);
    final var store = memoryStore != null ? memoryStore : new PostgresRevisionStore(dataSource);
    final var revisions = new WorkspaceRevisionService(r, history, fs, store);
    return new Services(history, fs, new WorkspaceBindings(null, fs, history, revisions, null, ""), revisions,
                        new WorkspaceGitRemoteService(r, history, revisions));
  }

  @Override
  public Map<String, Object> describe() {
    final var d = new LinkedHashMap<String, Object>();
    d.put("adapter", "git-revisions-prototype");
    d.put("revisionCatalog", jdbc == null ? "memory (list scans all rows)" : "postgres");
    if (jdbc != null) d.put("postgres", jdbc.replaceAll("(password=)[^&]*", "$1***"));
    d.put("jgit", Git.class.getPackage().getImplementationVersion());
    d.put("workspaceRoot", base.toString());
    return d;
  }

  @Override
  public Set<Feature> features() {
    return EnumSet.allOf(Feature.class);
  }

  //region Workspaces
  @Override
  public String createWorkspace(final String name) throws Exception {
    final var id = register(name);
    Files.createDirectories(roots.get(id));
    return name;
  }

  @Override
  public String importWorkspace(final String name, final Map<String, byte[]> files) throws Exception {
    final var id = register(name);
    final var root = Files.createDirectories(roots.get(id));
    for (final var f : files.entrySet()) {
      final var p = root.resolve(f.getKey());
      Files.createDirectories(p.getParent());
      Files.write(p, f.getValue());
    }
    services.history.withTrustedWorkspace(id, () -> null); // adopts it: one commit of everything already there
    return name;
  }

  private int register(final String name) throws Exception {
    final var id = nextId.getAndIncrement();
    if (ids.putIfAbsent(name, id) != null) throw new IllegalArgumentException("Workspace " + name + " exists");
    roots.put(id, base.resolve("ws").resolve(name));
    if (dataSource != null) {
      try (final var c = dataSource.getConnection(); final var s = c.prepareStatement("insert into sequencing.workspace values (?)")) {
        s.setInt(1, id);
        s.executeUpdate();
      }
    }
    return id;
  }

  private int id(final String ws) {
    final var id = ids.get(ws);
    if (id == null) throw new IllegalArgumentException("No workspace " + ws);
    return id;
  }

  private Path root(final String ws) {
    return roots.get(id(ws));
  }
  //endregion

  //region Files
  @Override
  public String write(final String ws, final String path, final byte[] content, final String ifMatch) throws Exception {
    final var p = Path.of(path);
    final var result = services.bindings.handleFileUpload(id(ws), p, upload(p.getFileName().toString(), content),
                                                          true, ifMatch, USER);
    if (result instanceof HandlerResult.Success s) return s.etag().orElseThrow();
    if (result.status() == 412) throw new Conflict(result.jsonResponse().toString());
    throw failure("write " + path, result);
  }

  @Override
  public FileState read(final String ws, final String path) throws Exception {
    final var stream = services.fs.loadFile(id(ws), Path.of(path));
    try (final var in = stream.readingStream()) {
      return new FileState(in.readAllBytes(), stream.etag());
    }
  }

  @Override
  public boolean exists(final String ws, final String path) throws Exception {
    return services.fs.checkFileExists(id(ws), Path.of(path));
  }

  @Override
  public void delete(final String ws, final String path) throws Exception {
    final var result = services.bindings.handleDelete(id(ws), Path.of(path), USER);
    if (!(result instanceof HandlerResult.Success)) throw failure("delete " + path, result);
  }

  @Override
  public void move(final String ws, final String from, final String to) throws Exception {
    final var id = id(ws);
    final var result = services.bindings.handleMove(Path.of(from), Path.of(to), id, id, false, USER);
    if (!(result instanceof HandlerResult.Success)) throw failure("move " + from, result);
  }

  @Override
  public LastEdit lastEdit(final String ws, final String path) throws Exception {
    final var info = services.fs.getLastEditInfo(id(ws), Path.of(path));
    return new LastEdit(info.lastEditedBy(), info.lastEditedAt());
  }

  private static IllegalStateException failure(final String what, final HandlerResult result) {
    return new IllegalStateException(what + " failed: " + result.status() + " " + result.jsonResponse());
  }

  private static UploadedFile upload(final String name, final byte[] bytes) {
    final var part = (Part) Proxy.newProxyInstance(Part.class.getClassLoader(), new Class<?>[]{Part.class}, (x, m, a) ->
        switch (m.getName()) {
          case "getInputStream" -> new ByteArrayInputStream(bytes);
          case "getSubmittedFileName", "getName" -> name;
          case "getSize" -> (long) bytes.length;
          case "getContentType" -> "application/octet-stream";
          default -> null;
        });
    return new UploadedFile(part);
  }
  //endregion

  //region Revisions
  @Override
  public RevisionInfo createRevision(final String ws, final String path) throws Exception {
    return info(services.revisions.create(id(ws), Path.of(path), USER));
  }

  @Override
  public RevisionListing listRevisions(final String ws, final String path) throws Exception {
    final var list = services.revisions.list(id(ws), Path.of(path));
    return new RevisionListing(list.revisions().stream().map(GitRevisionsPrototypeDriver::info).toList(),
                               list.matching().map(r -> r.id().toString()), list.workingCopyETag());
  }

  @Override
  public byte[] readRevision(final String ws, final String revisionId) throws Exception {
    return services.revisions.read(id(ws), UUID.fromString(revisionId)).content();
  }

  @Override
  public String restoreRevision(final String ws, final String path, final String revisionId, final String ifMatch)
  throws Exception
  {
    try {
      return services.revisions.restore(id(ws), Path.of(path), UUID.fromString(revisionId), ifMatch, USER).etag();
    } catch (StaleFileException e) {
      throw new Conflict(e.getMessage());
    }
  }

  @Override
  public void rebuild(final String ws) throws Exception {
    services.revisions.reindexRevisionsFromGit(id(ws));
  }

  private static RevisionInfo info(final WorkspaceRevisionStore.Revision r) {
    return new RevisionInfo(r.id().toString(), r.name(), r.createdAt());
  }
  //endregion

  //region Lifecycle and remotes
  @Override
  public void restart() {
    RepositoryCache.clear();
    new WindowCacheConfig().install(); // empties JGit's process-wide pack/object cache
    if (dataSource != null) {
      dataSource.close();
      openPool();
    }
    services = newServices();
  }

  @Override
  public String createRemote(final String name) throws Exception {
    final var dir = base.resolve("remotes").resolve(name + ".git");
    Git.init().setBare(true).setDirectory(dir.toFile()).setInitialBranch(WorkspaceHistory.BRANCH).call().close();
    return dir.toUri().toString();
  }

  @Override
  public void publish(final String ws, final String remote) throws Exception {
    link(ws, remote);
    services.remote.push(id(ws));
  }

  @Override
  public String cloneWorkspace(final String name, final String remote) throws Exception {
    createWorkspace(name);
    services.remote.cloneInto(id(name), remote, USER);
    linked.add(name + " " + remote);
    return name;
  }

  @Override
  public void synchronize(final String ws, final String remote) throws Exception {
    link(ws, remote);
    services.remote.fetchAndIntegrate(id(ws), USER);
  }

  private void link(final String ws, final String remote) throws Exception {
    if (linked.add(ws + " " + remote)) services.remote.linkRemote(id(ws), remote);
  }
  //endregion

  //region Diagnostics (implementation-specific; never used to decide anything)
  @Override
  public Map<String, Object> diagnostics(final String ws) throws Exception {
    final var root = root(ws);
    final var git = root.resolve(".git");
    final var d = new LinkedHashMap<String, Object>();
    d.put("diskBytes", bytes(root));
    d.put("gitBytes", bytes(git));
    d.put("looseObjects", count(git.resolve("objects"), p -> p.getParent().getFileName().toString().length() == 2));
    d.put("packs", count(git.resolve("objects/pack"), p -> p.toString().endsWith(".pack")));
    d.put("packBytes", bytes(git.resolve("objects/pack")));
    d.put("looseRefs", count(git.resolve("refs"), p -> true));
    try (final var repo = Git.open(root.toFile()).getRepository(); final var walk = new RevWalk(repo)) {
      d.put("revisionTags", repo.getRefDatabase().getRefsByPrefix(GitFileRevisions.REF_PREFIX).size());
      walk.markStart(walk.parseCommit(repo.resolve(Constants.HEAD)));
      var commits = 0;
      for (final var ignored : walk) commits++;
      d.put("commits", commits);
    }
    return d;
  }

  /** JGit gc, run by hand. The prototype has no production maintenance; this only shows what packing would do. */
  @Override
  public Map<String, Object> maintenance(final String ws) throws Exception {
    final var d = new LinkedHashMap<String, Object>();
    d.put("method", "manual JGit gc (diagnostic only; no production maintenance exists)");
    d.put("before", diagnostics(ws));
    final var start = System.nanoTime();
    try (final var git = Git.open(root(ws).toFile())) {
      git.gc().setExpire(null).call(); // expire null: prune unreachable loose objects immediately
    }
    d.put("seconds", (System.nanoTime() - start) / 1e9);
    d.put("after", diagnostics(ws));
    return d;
  }

  static long bytes(final Path dir) throws IOException {
    if (!Files.exists(dir)) return 0;
    try (final Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
    }
  }

  private static long count(final Path dir, final java.util.function.Predicate<Path> filter) throws IOException {
    if (!Files.exists(dir)) return 0;
    try (final Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).filter(filter).count();
    }
  }
  //endregion

  @Override
  public void close() throws Exception {
    if (dataSource == null) return;
    dataSource.close();
    try (final var c = DriverManager.getConnection(jdbc); final var s = c.createStatement()) {
      s.execute("drop database if exists " + database + " with (force)");
    }
  }
}
