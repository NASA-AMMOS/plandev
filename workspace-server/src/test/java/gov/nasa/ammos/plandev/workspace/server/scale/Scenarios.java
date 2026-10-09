package gov.nasa.ammos.plandev.workspace.server.scale;

import gov.nasa.ammos.plandev.workspace.server.scale.ScaleBench.Params;
import gov.nasa.ammos.plandev.workspace.server.scale.Workload.Kind;
import gov.nasa.ammos.plandev.workspace.server.scale.WorkspaceVersioningDriver.Conflict;
import gov.nasa.ammos.plandev.workspace.server.scale.WorkspaceVersioningDriver.Feature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The workloads. Each takes a {@link Ctx} and speaks only {@link WorkspaceVersioningDriver}; sizes come from
 * {@link Params} (see {@link ScaleBench} for the profiles and keys).
 */
final class Scenarios {
  private Scenarios() {}

  interface Scenario {
    void run(Ctx c) throws Exception;
  }

  /** Scenario name, its parameter prefix, and the workload. Run in this order. */
  record Entry(String name, String prefix, Scenario body) {}

  static final List<Entry> ALL = List.of(
      new Entry("tree-size", "tree.", Scenarios::treeSize),
      new Entry("history-depth", "history.", Scenarios::historyDepth),
      new Entry("revisions-one-file", "revisions.one.", Scenarios::revisionsOneFile),
      new Entry("revisions-spread", "revisions.spread.", Scenarios::revisionsSpread),
      new Entry("storage-growth", "storage.", Scenarios::storageGrowth),
      new Entry("concurrency", "concurrency.", Scenarios::concurrency),
      new Entry("independent-workspaces", "independent.", Scenarios::independentWorkspaces),
      new Entry("remote", "remote.", Scenarios::remote),
      new Entry("year", "year.", Scenarios::year));

  record Ctx(WorkspaceVersioningDriver d, Params p, Run run, long seed) {
    boolean has(final Feature f) {
      return d.features().contains(f);
    }

    int reps() {
      return p.i("reps");
    }

    Workload workload(final String ws) throws Exception {
      d.createWorkspace(ws);
      return new Workload(d, run, ws, seed ^ ws.hashCode());
    }

    /** Probe the standard user operations at this state (and time a rebuild), as one checkpoint. */
    void measure(final Workload w, final String label, final String hot, final int reps, final boolean rebuild) throws Exception {
      run.begin(label, w.position());
      try {
        w.probe(hot, reps);
        if (rebuild && has(Feature.REBUILD)) {
          for (var i = 0; i < Math.max(1, reps / 2); i++) run.time("rebuild", () -> d.rebuild(w.ws));
          final var listing = d.listRevisions(w.ws, hot);
          run.check("revisions intact after rebuild", listing.revisions().size() == w.files.get(hot).revisions.size(), hot);
        }
      } finally {
        w.storage();
        run.end();
      }
    }

    /** Restart, then the first (cold) and subsequent (warm) user operations. */
    void afterRestart(final Workload w, final String label, final String hot) throws Exception {
      if (!has(Feature.RESTART)) return;
      run.begin(label + " restart", w.position());
      run.time("restart", d::restart);
      run.end();
      measure(w, label + " after restart (cold)", hot, 1, false);
      measure(w, label + " after restart (warm)", hot, reps(), false);
    }

    boolean stop(final String what) {
      if (!run.overBudget()) return false;
      run.note("Stopped " + what + ": scenario budget of " + p.d("budgetMinutes") + " min exceeded after "
               + Math.round(run.elapsedSeconds()) + " s");
      return true;
    }
  }

  //region Working-tree size
  /**
   * Files arrive in one import (isolating tree size from history depth), then the user operations. Two sweeps: file
   * count ({@code tree.files} small files) and content bytes ({@code tree.extraMiB} of 1 MiB text files added to 100
   * small ones, untouched afterwards).
   */
  static void treeSize(final Ctx c) throws Exception {
    for (final var n : c.p.ints("tree.files")) {
      if (c.stop("before files=" + n)) return;
      treeCase(c, "files=" + n, n, 0);
    }
    for (final var mib : c.p.ints("tree.extraMiB")) {
      if (c.stop("before extraMiB=" + mib)) return;
      treeCase(c, "files=100 extraMiB=" + mib, 100, mib);
    }
  }

  private static void treeCase(final Ctx c, final String label, final int n, final int extraMiB) throws Exception {
    final var ws = "tree-" + label.replaceAll("[^a-zA-Z0-9]+", "-");
    final var w = new Workload(c.d, c.run, ws, c.seed + n + extraMiB);
    final var content = new LinkedHashMap<String, byte[]>();
    for (var i = 0; i < n; i++) {
      final var path = "d%03d/f%05d.txt".formatted(i / 100, i);
      content.put(path, w.track(path, w.newSpec(Kind.TEXT, 2048)));
    }
    for (var i = 0; i < extraMiB; i++) {
      final var path = "large/l%04d.txt".formatted(i);
      content.put(path, w.track(path, w.newSpec(Kind.TEXT, 1 << 20)));
    }
    if (c.has(Feature.IMPORT)) {
      c.run.time("import " + label, () -> c.d.importWorkspace(ws, content));
    } else {
      c.d.createWorkspace(ws);
      for (final var e : content.entrySet()) c.run.time("populate.save " + label, () -> c.d.write(ws, e.getKey(), e.getValue(), null));
    }
    final var hot = content.keySet().iterator().next();
    c.measure(w, label, hot, c.reps(), true);
    c.afterRestart(w, label, hot);
    w.verify(label, c.p.i("verify.revisionSamples"), true);
  }
  //endregion

  //region History depth
  /** Many saves over a small set of files; the user operations at increasing history depth. */
  static void historyDepth(final Ctx c) throws Exception {
    final var w = c.workload("history");
    final var paths = new ArrayList<String>();
    for (var i = 0; i < c.p.i("history.files"); i++) {
      paths.add("f%03d.txt".formatted(i));
      w.create(paths.getLast(), Kind.TEXT, 4096, "populate.create");
    }
    final var hot = "hot.txt";
    w.create(hot, Kind.TEXT, 4096, "populate.create");
    for (var i = 0; i < 5; i++) w.saveAndRevise(hot, "populate.save", "populate.revision");

    var depth = 0;
    for (final var target : c.p.ints("history.checkpoints")) {
      for (; depth < target && !c.run.overBudget(); depth++) w.save(paths.get(w.rnd.nextInt(paths.size())), "populate.save");
      if (depth < target) {
        c.stop("history at " + depth + " saves (target " + target + ")");
        break;
      }
      c.measure(w, "saves=" + target, hot, c.reps(), true);
    }
    c.afterRestart(w, "saves=" + depth, hot);
    w.verify("history", c.p.i("verify.revisionSamples"), true);
  }
  //endregion

  //region Revisions
  /** Every revision on one file. */
  static void revisionsOneFile(final Ctx c) throws Exception {
    final var w = c.workload("rev-one");
    for (var i = 0; i < 20; i++) w.create("f%02d.txt".formatted(i), Kind.TEXT, 4096, "populate.create");
    final var hot = "hot.seq.txt";
    w.create(hot, Kind.TEXT, 4096, "populate.create");
    revisionLoop(c, w, List.of(hot), hot, c.p.ints("revisions.one.checkpoints"));
  }

  /** The same totals spread round-robin over many files. */
  static void revisionsSpread(final Ctx c) throws Exception {
    final var w = c.workload("rev-spread");
    final var paths = new ArrayList<String>();
    for (var i = 0; i < c.p.i("revisions.spread.files"); i++) {
      paths.add("d%02d/f%04d.txt".formatted(i / 100, i));
      w.create(paths.getLast(), Kind.TEXT, 4096, "populate.create");
    }
    revisionLoop(c, w, paths, paths.getFirst(), c.p.ints("revisions.spread.checkpoints"));
  }

  private static void revisionLoop(
      final Ctx c, final Workload w, final List<String> paths, final String hot, final List<Integer> checkpoints)
  throws Exception
  {
    var made = 0;
    for (final var target : checkpoints) {
      for (; made < target && !c.run.overBudget(); made++) {
        w.saveAndRevise(paths.get(made % paths.size()), "populate.save", "populate.revision");
      }
      if (made < target) {
        c.stop("at " + made + " revisions (target " + target + ")");
        break;
      }
      c.measure(w, "revisions=" + target, hot, c.reps(), true);
    }
    c.afterRestart(w, "revisions=" + made, hot);
    w.verify(w.ws, c.p.i("verify.revisionSamples"), true);
  }
  //endregion

  //region Storage growth
  /**
   * One file per kind saved over and over: small text with small diffs, compressible medium text, incompressible
   * binary replacement. Disk at checkpoints, then (diagnostic) maintenance and its effect.
   * Kinds: {@code name:text|binary:sizeBytes:saves}, comma-separated.
   */
  static void storageGrowth(final Ctx c) throws Exception {
    for (final var kind : c.p.list("storage.kinds")) {
      final var parts = kind.split(":");
      final var name = parts[0];
      final var spec = Kind.valueOf(parts[1].toUpperCase());
      final var size = Integer.parseInt(parts[2]);
      final var count = Integer.parseInt(parts[3]);
      if (c.stop("before " + name)) break;

      final var w = c.workload("storage-" + name);
      final var path = "churn." + (spec == Kind.BINARY ? "bin" : "txt");
      w.create(path, spec, size, name + ".save");
      w.create("other.txt", Kind.TEXT, 4096, "populate.create");
      c.run.results.put(name + ".deflateRatio", Workload.compressionRatio(w.files.get(path).spec));
      c.run.begin(name + " saves=1", w.position());
      w.storage();
      c.run.end();
      final var start = (long) c.d.diagnostics(w.ws).getOrDefault("diskBytes", 0L);

      final var step = Math.max(1, count / 10);
      var done = 1;
      while (done < count && !c.run.overBudget()) {
        final var next = Math.min(count, done + step);
        for (; done < next; done++) w.save(path, name + ".save");
        c.run.begin(name + " saves=" + done, w.position());
        for (var i = 0; i < c.reps(); i++) c.run.time("read", () -> c.d.read(w.ws, path));
        w.storage();
        c.run.end();
      }
      if (done < count) c.stop(name + " at " + done + " of " + count + " saves");

      final var end = (long) c.d.diagnostics(w.ws).getOrDefault("diskBytes", 0L);
      final var perSave = (double) (end - start) / Math.max(1, done - 1);
      c.run.results.put(name + ".saves", done);
      c.run.results.put(name + ".diskBytesPerSave", Math.round(perSave));
      c.run.results.put(name + ".projectedDiskBytesAt10kSaves", Math.round(start + perSave * 10_000));
      w.verify(name, 10, true);
      if (c.p.b("storage.maintenance")) {
        c.run.results.put(name + ".maintenance", c.d.maintenance(w.ws));
        w.verify(name + " after maintenance", 10, true);
      }
    }
  }
  //endregion

  //region Concurrency
  /**
   * W writers against one workspace: each on its own file (unconditional saves), then all on one file through the
   * read/If-Match contract. Throughput, latency, and whether every acknowledged save is observable afterwards.
   */
  static void concurrency(final Ctx c) throws Exception {
    for (final var writers : c.p.ints("concurrency.writers")) {
      if (c.stop("before writers=" + writers)) break;
      final var w = c.workload("conc-" + writers);
      final var paths = new ArrayList<String>();
      for (var i = 0; i < writers; i++) {
        paths.add("writer-%02d.txt".formatted(i));
        w.create(paths.getLast(), Kind.TEXT, 4096, "populate.create");
      }
      w.create("shared.txt", Kind.TEXT, 4096, "populate.create");
      final var ops = c.p.i("concurrency.ops");

      c.run.begin("distinct files, writers=" + writers, w.position());
      final var wall = parallel(writers, t -> {
        final var f = w.files.get(paths.get(t));
        for (var v = 2; v <= ops + 1; v++) {
          final var bytes = Workload.content(f.spec, v);
          try {
            c.run.time("save", () -> c.d.write(w.ws, paths.get(t), bytes, null));
            f.current = f.max = v;
          } catch (Exception ignored) {
            // counted as a failure of "save"; the model keeps the last acknowledged version
          }
        }
      });
      c.run.results.put("distinct.writers=" + writers + ".opsPerSec", Run.round(writers * ops / wall));
      c.run.results.put("distinct.writers=" + writers + ".wallSeconds", Run.round(wall));
      w.storage();
      c.run.end();
      w.verify("distinct writers=" + writers, 0, true);

      c.run.begin("one file via If-Match, writers=" + writers, w.position());
      final var chain = new ConcurrentHashMap<String, String>(); // acknowledged: token written over -> new token
      final var written = new ConcurrentHashMap<String, byte[]>();
      final var conflicts = new AtomicInteger();
      final var initial = c.d.read(w.ws, "shared.txt").token();
      final var wall2 = parallel(writers, t -> {
        for (var i = 0; i < ops; i++) {
          final var bytes = ("writer %d attempt %d\n".formatted(t, i)).repeat(64).getBytes(StandardCharsets.UTF_8);
          try {
            final var base = c.run.time("read", () -> c.d.read(w.ws, "shared.txt")).token();
            final var token = c.run.time("save.ifMatch", () -> {
              try {
                return c.d.write(w.ws, "shared.txt", bytes, base);
              } catch (Conflict e) {
                return null;
              }
            });
            if (token == null) conflicts.incrementAndGet();
            else if (chain.putIfAbsent(base, token) != null) c.run.check("two saves acknowledged over one token", false, base);
            else written.put(token, bytes);
          } catch (Exception ignored) {
            // counted as a failure of the op
          }
        }
      });
      // Every acknowledged save must be one link of a single chain from the initial token to the current state.
      final var current = c.d.read(w.ws, "shared.txt");
      var at = initial;
      var links = 0;
      while (chain.containsKey(at) && links <= chain.size()) {
        at = chain.get(at);
        links++;
      }
      c.run.check("every acknowledged If-Match save is in one history chain (" + chain.size() + " saves)",
                  links == chain.size() && at.equals(current.token()), links + " linked of " + chain.size());
      c.run.check("the current bytes are the last acknowledged save",
                  chain.isEmpty() || Arrays.equals(current.content(), written.get(at)), null);
      c.run.results.put("ifMatch.writers=" + writers + ".acknowledged", chain.size());
      c.run.results.put("ifMatch.writers=" + writers + ".conflicts", conflicts.get());
      c.run.results.put("ifMatch.writers=" + writers + ".acknowledgedPerSec", Run.round(chain.size() / wall2));
      c.run.end();
    }
  }

  /** One writer in each of W workspaces at once: separates per-workspace serialization from global contention. */
  static void independentWorkspaces(final Ctx c) throws Exception {
    for (final var count : c.p.ints("independent.workspaces")) {
      if (c.stop("before workspaces=" + count)) break;
      final var ops = c.p.i("independent.ops");
      final var ws = new ArrayList<Workload>();
      for (var i = 0; i < count; i++) {
        final var w = c.workload("indep-%d-%02d".formatted(count, i));
        w.create("a.txt", Kind.TEXT, 4096, "populate.create");
        w.create("b.txt", Kind.TEXT, 4096, "populate.create");
        ws.add(w);
      }
      c.run.begin("workspaces=" + count, Map.of("workspaces", count, "opsPerWorkspace", ops));
      final var wall = parallel(count, t -> {
        final var w = ws.get(t);
        final var f = w.files.get("a.txt");
        for (var v = 2; v <= ops + 1; v++) {
          final var bytes = Workload.content(f.spec, v);
          try {
            c.run.time("save", () -> c.d.write(w.ws, "a.txt", bytes, null));
            f.current = f.max = v;
          } catch (Exception ignored) {
            // counted as a failure of "save"
          }
        }
      });
      c.run.results.put("workspaces=" + count + ".opsPerSec", Run.round(count * ops / wall));
      c.run.results.put("workspaces=" + count + ".wallSeconds", Run.round(wall));
      c.run.end();
      for (final var w : ws) w.verify(w.ws, 0, true);
    }
  }

  interface Worker {
    void run(int index) throws Exception;
  }

  /** Run n workers released together; returns wall-clock seconds. */
  private static double parallel(final int n, final Worker worker) throws Exception {
    final var pool = Executors.newFixedThreadPool(n);
    final var go = new CountDownLatch(1);
    final var futures = new ArrayList<java.util.concurrent.Future<?>>();
    for (var i = 0; i < n; i++) {
      final var index = i;
      futures.add(pool.submit(() -> {
        go.await();
        worker.run(index);
        return null;
      }));
    }
    final var start = System.nanoTime();
    go.countDown();
    try {
      for (final var f : futures) f.get();
    } finally {
      pool.shutdown();
    }
    return (System.nanoTime() - start) / 1e9;
  }
  //endregion

  //region Remote round trip
  /**
   * A workspace grows; at each checkpoint it publishes, a fresh clone is made, and a long-lived follower clone
   * synchronizes incrementally. The follower also publishes its own work back, which the origin integrates (a merge
   * when both moved).
   */
  static void remote(final Ctx c) throws Exception {
    if (!c.has(Feature.REMOTE)) {
      c.run.note("Adapter has no remote support; skipped.");
      return;
    }
    final var w = c.workload("remote-origin");
    final var paths = new ArrayList<String>();
    for (var i = 0; i < c.p.i("remote.files"); i++) {
      paths.add("d%02d/f%03d.txt".formatted(i / 50, i));
      w.create(paths.getLast(), Kind.TEXT, 4096, "populate.create");
    }
    final var hot = paths.getFirst();
    final var remote = c.d.createRemote("remote");
    final var every = c.p.i("remote.revisionEvery");
    Workload follower = null;
    var saves = 0;
    for (final var target : c.p.ints("remote.checkpoints")) {
      for (; saves < target && !c.run.overBudget(); saves++) {
        final var path = saves % 4 == 0 ? hot : paths.get(w.rnd.nextInt(paths.size()));
        w.save(path, "populate.save");
        if (saves % every == 0) w.revise(path, "populate.revision");
      }
      if (saves < target) {
        c.stop("remote at " + saves + " saves");
        break;
      }
      c.run.begin("saves=" + target, w.position());
      try {
        c.run.time("publish", () -> c.d.publish(w.ws, remote));
        c.run.time("publish.upToDate", () -> c.d.publish(w.ws, remote));
        final var cloneName = "remote-clone-" + target;
        c.run.time("clone", () -> c.d.cloneWorkspace(cloneName, remote));
        final var clone = w.mirror(cloneName);
        c.run.time("clone.firstRead", () -> c.d.read(cloneName, hot));
        c.run.time("clone.firstList", () -> c.d.listRevisions(cloneName, hot));
        clone.verify("fresh clone at " + target, c.p.i("verify.revisionSamples"), false);
        clone.storage();

        if (follower == null) {
          follower = w.mirror(cloneName);
        } else {
          final var f = follower;
          c.run.time("sync.incremental", () -> c.d.synchronize(f.ws, remote));
          follower = w.mirror(f.ws);
          follower.verify("follower after sync at " + target, c.p.i("verify.revisionSamples"), false);
        }
        final var f = follower;
        c.run.time("sync.upToDate", () -> c.d.synchronize(f.ws, remote));

        // The follower's own work flows back; the origin saves too, so integrating it is a merge.
        final var added = new ArrayList<String>();
        for (var i = 0; i < 5; i++) {
          added.add("from-follower/%d/n%d.txt".formatted(target, i));
          f.create(added.getLast(), Kind.TEXT, 4096, "follower.save");
        }
        c.run.time("follower.publish", () -> c.d.publish(f.ws, remote));
        w.save(hot, "populate.save");
        c.run.time("sync.merge", () -> c.d.synchronize(w.ws, remote));
        for (final var p : added) w.files.put(p, f.files.get(p).copy());
        w.verify("origin after merging follower at " + target, 20, true);
        w.storage();
      } finally {
        c.run.end();
      }
    }
  }
  //endregion

  //region Year-equivalent
  /**
   * A synthetic long-lived workspace: thousands of files, tens of thousands of saves, thousands of revisions split
   * between hot and cold files, mostly small text edits with occasional medium and binary files, some moves, deletes
   * and additions, periodic restarts and (optionally) remote synchronization. Then the normal user operations against
   * the aged workspace, and a full correctness check.
   */
  static void year(final Ctx c) throws Exception {
    final var p = c.p;
    final var w = c.workload("year");
    final var targetSaves = p.i("year.saves");
    final var targetRevisions = p.i("year.revisions");
    final var perDir = p.i("year.filesPerDir");
    final var hot = new ArrayList<String>();
    final var cold = new ArrayList<String>();
    final var names = new AtomicInteger();

    final java.util.function.Supplier<String> newPath = () -> {
      final var n = names.getAndIncrement();
      return "seq/d%03d/f%05d".formatted(n / perDir, n);
    };
    final java.util.function.Function<Boolean, String> createFile = isHot -> {
      try {
        final var r = w.rnd.nextDouble();
        final String path;
        if (!isHot && r < p.d("year.binaryShare")) {
          path = newPath.get() + ".bin";
          w.create(path, Kind.BINARY, p.i("year.binarySize"), "build.create");
        } else if (!isHot && r < p.d("year.binaryShare") + p.d("year.mediumShare")) {
          path = newPath.get() + ".txt";
          final var size = p.i("year.mediumMin") + w.rnd.nextInt(p.i("year.mediumMax") - p.i("year.mediumMin"));
          w.create(path, Kind.TEXT, size, "build.create");
        } else {
          path = newPath.get() + ".txt";
          w.create(path, Kind.TEXT, 1024 + w.rnd.nextInt(7 * 1024), "build.create");
        }
        (isHot ? hot : cold).add(path);
        return path;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    };

    for (var i = 0; i < p.i("year.files") && !c.run.overBudget(); i++) createFile.apply(i < p.i("year.hotFiles"));
    final var remote = p.b("year.remote") && c.has(Feature.REMOTE) ? c.d.createRemote("year") : null;
    Workload follower = null;
    var nextCheckpoint = p.i("year.checkpointEvery");
    var nextRestart = p.i("year.restartEvery");
    final var counts = new HashMap<String, Integer>();

    while (w.saves < targetSaves && !c.run.overBudget()) {
      final var r = w.rnd.nextDouble();
      final var all = hot.size() + cold.size();
      if (r < p.d("year.moveRate")) {
        final var fromHot = w.rnd.nextInt(all) < hot.size();
        final var list = fromHot ? hot : cold;
        final var i = w.rnd.nextInt(list.size());
        final var from = list.get(i);
        // Into the directory of some other live file: moves need an existing destination directory
        final var near = list.get(w.rnd.nextInt(list.size()));
        final var to = near.substring(0, near.lastIndexOf('/')) + "/moved-" + names.getAndIncrement() + from.substring(from.lastIndexOf('.'));
        w.move(from, to, "build.move");
        list.set(i, to);
        counts.merge("moves", 1, Integer::sum);
      } else if (r < p.d("year.moveRate") + p.d("year.deleteRate") && cold.size() > 10) {
        w.delete(cold.remove(w.rnd.nextInt(cold.size())), "build.delete");
        counts.merge("deletes", 1, Integer::sum);
      } else if (r < p.d("year.moveRate") + p.d("year.deleteRate") + p.d("year.createRate")) {
        createFile.apply(false);
        counts.merge("creates", 1, Integer::sum);
      } else {
        final var list = w.rnd.nextDouble() < p.d("year.hotSaveShare") ? hot : cold;
        w.save(list.get(w.rnd.nextInt(list.size())), "build.save");
      }

      // Revisions keep pace with saves
      while (w.revisionsMade < (long) targetRevisions * w.saves / targetSaves) {
        final var list = w.rnd.nextDouble() < p.d("year.hotRevisionShare") ? hot : cold;
        final var path = list.get(w.rnd.nextInt(list.size()));
        final var f = w.files.get(path);
        if (!f.revisions.isEmpty() && f.revisions.getLast().version() == f.current) w.save(path, "build.save");
        w.revise(path, "build.revision");
      }

      if (w.saves >= nextRestart) {
        nextRestart += p.i("year.restartEvery");
        if (c.has(Feature.RESTART)) {
          c.run.time("build.restart", c.d::restart);
          c.run.begin("saves=" + w.saves + " first ops after restart", w.position());
          c.run.time("read", () -> c.d.read(w.ws, hot.getFirst()));
          c.run.time("lastEdit", () -> c.d.lastEdit(w.ws, hot.getFirst()));
          c.run.time("revision.list", () -> c.d.listRevisions(w.ws, hot.getFirst()));
          w.save(hot.getFirst(), "save");
          w.storage();
          c.run.end();
        }
      }
      if (w.saves >= nextCheckpoint) {
        nextCheckpoint += p.i("year.checkpointEvery");
        final var label = "saves=" + w.saves;
        c.measure(w, label, heaviest(w, hot), c.reps(), true);
        if (remote != null) follower = synchronize(c, w, remote, follower, label);
      }
    }
    if (w.saves < targetSaves) c.stop("year at " + w.saves + " of " + targetSaves + " saves");
    c.run.results.put("mix", new LinkedHashMap<>(Map.of(
        "files", w.files.size(), "saves", w.saves, "revisions", w.revisionCount(), "moves", counts.getOrDefault("moves", 0),
        "deletes", counts.getOrDefault("deletes", 0), "creates", counts.getOrDefault("creates", 0),
        "contentBytes", w.contentBytes(), "heaviestFileRevisions", w.files.get(heaviest(w, hot)).revisions.size())));

    final var heavy = heaviest(w, hot);
    c.measure(w, "final", heavy, c.reps(), true);
    c.afterRestart(w, "final", heavy);
    if (remote != null) {
      synchronize(c, w, remote, follower, "final");
      c.run.begin("final fresh clone", w.position());
      c.run.time("clone", () -> c.d.cloneWorkspace("year-clone-final", remote));
      final var clone = w.mirror("year-clone-final");
      clone.verify("final fresh clone", p.i("year.verifySamples"), false);
      clone.storage();
      c.run.end();
    }
    w.verify("final", p.i("year.verifySamples"), true);
    if (p.b("year.maintenance")) {
      c.run.results.put("maintenance", c.d.maintenance(w.ws));
      c.measure(w, "after maintenance", heavy, c.reps(), false);
      w.verify("after maintenance", 50, true);
    }
  }

  private static String heaviest(final Workload w, final List<String> hot) {
    return hot.stream().max(java.util.Comparator.comparingInt(h -> w.files.get(h).revisions.size())).orElseThrow();
  }

  private static Workload synchronize(final Ctx c, final Workload w, final String remote, final Workload follower, final String label)
  throws Exception
  {
    c.run.begin(label + " remote", w.position());
    try {
      c.run.time("publish", () -> c.d.publish(w.ws, remote));
      if (follower == null) {
        c.run.time("clone", () -> c.d.cloneWorkspace("year-follower", remote));
      } else {
        c.run.time("sync.incremental", () -> c.d.synchronize(follower.ws, remote));
      }
      final var next = w.mirror("year-follower");
      next.verify(label + " follower", 50, false);
      next.storage();
      return next;
    } finally {
      c.run.end();
    }
  }
  //endregion
}
