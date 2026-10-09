package gov.nasa.ammos.plandev.workspace.server.scale;

import gov.nasa.ammos.plandev.workspace.server.scale.WorkspaceVersioningDriver.Conflict;
import gov.nasa.ammos.plandev.workspace.server.scale.WorkspaceVersioningDriver.Feature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;

/**
 * One workspace as a user sees it: the driver handle plus the expected state (every live file's content and its
 * revisions, by generation key and version), and the operations scenarios build workloads from. Content is
 * regenerated from (key, version), so nothing large is kept in memory and every run produces identical bytes.
 */
final class Workload {
  enum Kind { TEXT, BINARY }

  /** How a file's bytes are generated. Survives moves: the content follows the file, not the path. */
  record Spec(long key, Kind kind, int size) {}

  record Rev(String id, int version) {}

  static final class FileModel {
    final Spec spec;
    int current;
    int max;
    final List<Rev> revisions = new ArrayList<>();

    FileModel(final Spec spec) {
      this.spec = spec;
    }

    FileModel copy() {
      final var f = new FileModel(spec);
      f.current = current;
      f.max = max;
      f.revisions.addAll(revisions);
      return f;
    }
  }

  final WorkspaceVersioningDriver driver;
  final Run run;
  final String ws;
  final SplittableRandom rnd;
  final Map<String, FileModel> files = new LinkedHashMap<>();
  final Set<String> deleted = new HashSet<>();
  private final long seed;
  private long nextKey;
  long saves;
  long revisionsMade;

  Workload(final WorkspaceVersioningDriver driver, final Run run, final String ws, final long seed) {
    this.driver = driver;
    this.run = run;
    this.ws = ws;
    this.seed = seed;
    this.rnd = new SplittableRandom(seed);
  }

  //region Content
  private static final String[] WORDS = ("command sequence activity downlink uplink spacecraft instrument power thermal "
      + "attitude telemetry fault protection heater wheel momentum star tracker camera target observe slew wait "
      + "relative absolute epoch duration parameter enable disable").split(" ");
  private static final int LINE = 64;
  private static final Map<Long, byte[]> baseCache = new ConcurrentHashMap<>();

  /** Text: fixed 64-byte lines of words; version v rewrites line v mod n, so consecutive versions differ by two lines. */
  static byte[] content(final Spec spec, final int version) {
    if (spec.kind == Kind.BINARY) {
      final var bytes = new byte[spec.size];
      new SplittableRandom(spec.key * 0x9E3779B97F4A7C15L + version).nextBytes(bytes);
      return bytes;
    }
    final var base = spec.size >= 65536 ? baseCache.computeIfAbsent(spec.key, k -> textBase(spec)) : textBase(spec);
    final var out = base.clone();
    final var lines = out.length / LINE;
    final var marker = String.format("edit %010d of %016x ", version, spec.key);
    final var line = (marker + ".".repeat(LINE)).substring(0, LINE - 1) + "\n";
    System.arraycopy(line.getBytes(StandardCharsets.US_ASCII), 0, out, (version % lines) * LINE, LINE);
    return out;
  }

  private static byte[] textBase(final Spec spec) {
    final var r = new SplittableRandom(spec.key);
    final var lines = Math.max(1, spec.size / LINE);
    final var sb = new StringBuilder(lines * LINE);
    for (var i = 0; i < lines; i++) {
      final var line = new StringBuilder();
      while (line.length() < LINE - 1) line.append(WORDS[r.nextInt(WORDS.length)]).append(' ');
      sb.append(line, 0, LINE - 1).append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.US_ASCII);
  }

  /** Deflate ratio of a generated sample, recorded so "incompressible" is demonstrated, not assumed. */
  static double compressionRatio(final Spec spec) {
    final var in = content(spec, 1);
    final var d = new Deflater();
    d.setInput(in);
    d.finish();
    final var buf = new byte[in.length + 1024];
    final var out = d.deflate(buf);
    d.end();
    return (double) in.length / out;
  }

  Spec newSpec(final Kind kind, final int size) {
    return new Spec(seed * 1_000_003L + nextKey++, kind, size);
  }
  //endregion

  //region Operations (each timed under the given op name)
  /** Expect a file that was put in place by an import rather than a save. */
  byte[] track(final String path, final Spec spec) {
    final var f = new FileModel(spec);
    f.current = f.max = 1;
    files.put(path, f);
    return content(spec, 1);
  }

  String create(final String path, final Kind kind, final int size, final String op) throws Exception {
    return create(path, newSpec(kind, size), op);
  }

  String create(final String path, final Spec spec, final String op) throws Exception {
    final var f = new FileModel(spec);
    f.current = f.max = 1;
    final var bytes = content(spec, 1);
    final var token = run.time(op, () -> driver.write(ws, path, bytes, null));
    files.put(path, f);
    saves++;
    return token;
  }

  String save(final String path, final String op) throws Exception {
    final var f = files.get(path);
    final var v = f.max + 1;
    final var bytes = content(f.spec, v);
    final var token = run.time(op, () -> driver.write(ws, path, bytes, null));
    f.max = f.current = v;
    saves++;
    return token;
  }

  Rev revise(final String path, final String op) throws Exception {
    final var f = files.get(path);
    final var info = run.time(op, () -> driver.createRevision(ws, path));
    final var rev = new Rev(info.id(), f.current);
    f.revisions.add(rev);
    revisionsMade++;
    return rev;
  }

  /** A new version, then a revision of it (a revision must differ from the file's latest one). */
  Rev saveAndRevise(final String path, final String saveOp, final String revOp) throws Exception {
    save(path, saveOp);
    return revise(path, revOp);
  }

  void move(final String from, final String to, final String op) throws Exception {
    run.time(op, () -> driver.move(ws, from, to));
    files.put(to, files.remove(from));
  }

  void delete(final String path, final String op) throws Exception {
    run.time(op, () -> driver.delete(ws, path));
    files.remove(path);
    deleted.add(path);
  }

  String randomFile(final java.util.function.Predicate<String> filter) {
    final var candidates = files.keySet().stream().filter(filter).toList();
    return candidates.get(rnd.nextInt(candidates.size()));
  }

  long contentBytes() {
    return files.values().stream().mapToLong(f -> f.spec.size()).sum();
  }

  long revisionCount() {
    return files.values().stream().mapToLong(f -> f.revisions.size()).sum();
  }

  Map<String, Object> position() {
    final var p = new LinkedHashMap<String, Object>();
    p.put("files", files.size());
    p.put("saves", saves);
    p.put("revisions", revisionCount());
    p.put("contentBytes", contentBytes());
    return p;
  }

  /** A copy of the expected state, for a workspace that should end up identical (a clone). */
  Workload mirror(final String otherWs) {
    final var w = new Workload(driver, run, otherWs, seed);
    files.forEach((p, f) -> w.files.put(p, f.copy()));
    w.deleted.addAll(deleted);
    return w;
  }
  //endregion

  //region Probes: the user-visible operations measured at a given state
  private int probes;

  /**
   * Run the standard set of user operations against the current state (callers group them under a checkpoint). Reads
   * come first, so after a restart the first sample of each read op is the cold one. {@code hot} must exist; it gets
   * revisions (and a restore) as part of the probe.
   */
  void probe(final String hot, final int reps) throws Exception {
    final var probe = probes++;
    final var smalls = files.keySet().stream().filter(p -> !p.equals(hot) && files.get(p).spec.size() <= 65536).toList();
    for (var i = 0; i < reps; i++) {
      final var path = smalls.get(rnd.nextInt(smalls.size()));
      final var state = run.time("read", () -> driver.read(ws, path));
      check("read returns the latest bytes", path, state.content());
    }
    if (driver.features().contains(Feature.LAST_EDIT)) {
      for (var i = 0; i < reps; i++) {
        final var path = smalls.get(rnd.nextInt(smalls.size()));
        final var edit = run.time("lastEdit", () -> driver.lastEdit(ws, path));
        if (i == 0) run.check("last edit is known", edit.by() != null && edit.at() != null, path);
      }
    }
    final var f = files.get(hot);
    if (f.revisions.isEmpty()) saveAndRevise(hot, "save.hot", "revision.create.first");
    if (!f.revisions.isEmpty() && f.current != f.revisions.getLast().version()) saveAndRevise(hot, "save.hot", "revision.create");

    for (var i = 0; i < reps; i++) {
      final var listing = run.time("revision.list.matchingLatest", () -> driver.listRevisions(ws, hot));
      if (i == 0) {
        checkListing(hot, listing);
        run.check("list reports the latest revision as matching",
                  listing.matchingId().equals(java.util.Optional.of(listing.revisions().getLast().id())), hot);
      }
    }
    for (var i = 0; i < reps; i++) {
      final var oldest = f.revisions.getFirst();
      final var bytes = run.time("revision.preview.oldest", () -> driver.readRevision(ws, oldest.id()));
      if (i == 0) run.check("oldest revision content", Arrays.equals(bytes, content(f.spec, oldest.version())), hot);
    }
    for (var i = 0; i < reps; i++) {
      final var newest = f.revisions.getLast();
      final var bytes = run.time("revision.preview.newest", () -> driver.readRevision(ws, newest.id()));
      if (i == 0) run.check("newest revision content", Arrays.equals(bytes, content(f.spec, newest.version())), hot);
    }

    for (var i = 0; i < reps; i++) save(smalls.get(rnd.nextInt(smalls.size())), "save.existing");
    for (var i = 0; i < reps; i++) {
      create("probe/" + probe + "/new-" + i + ".txt", Kind.TEXT, 4096, "save.new");
    }

    save(hot, "save.hot");
    for (var i = 0; i < reps; i++) {
      final var listing = run.time("revision.list.unmatched", () -> driver.listRevisions(ws, hot));
      if (i == 0) run.check("an unsaved-as-revision state matches no revision", listing.matchingId().isEmpty(), hot);
    }
    for (var i = 0; i < reps; i++) {
      if (i > 0) save(hot, "save.hot");
      revise(hot, "revision.create");
    }

    final var oldest = f.revisions.getFirst();
    for (var i = 0; i < Math.max(1, reps / 2); i++) {
      final var token = driver.listRevisions(ws, hot).restoreToken();
      run.time("revision.restore.oldest", () -> driver.restoreRevision(ws, hot, oldest.id(), token));
      f.current = oldest.version();
      check("restore makes the revision current", hot, driver.read(ws, hot).content());
      save(hot, "save.hot");
    }
    rejectsStaleTokens(hot);
  }

  /** A save or restore with a stale token must be refused and change nothing. */
  void rejectsStaleTokens(final String path) throws Exception {
    final var f = files.get(path);
    final var stale = "\"0000000000000000000000000000000000000000000000000000000000000000\"";
    var refused = false;
    try {
      driver.write(ws, path, "stale".getBytes(StandardCharsets.UTF_8), stale);
    } catch (Conflict c) {
      refused = true;
    }
    run.check("a stale save is refused", refused, path);
    if (!f.revisions.isEmpty()) {
      refused = false;
      try {
        driver.restoreRevision(ws, path, f.revisions.getFirst().id(), stale);
      } catch (Conflict c) {
        refused = true;
      }
      run.check("a stale restore is refused", refused, path);
    }
    check("refused operations leave the file unchanged", path, driver.read(ws, path).content());
  }

  /** Record implementation diagnostics plus model-derived numbers on the current checkpoint (or the run). */
  void storage() throws Exception {
    final var d = new LinkedHashMap<String, Object>(driver.diagnostics(ws));
    d.put("contentBytes", contentBytes());
    run.storage(ws, d);
  }
  //endregion

  //region Verification (through the driver only)
  private void check(final String what, final String path, final byte[] actual) {
    final var f = files.get(path);
    run.check(what, Arrays.equals(actual, content(f.spec, f.current)), path);
  }

  private void checkListing(final String path, final WorkspaceVersioningDriver.RevisionListing listing) {
    final var expected = files.get(path).revisions.stream().map(Rev::id).toList();
    final var actual = listing.revisions().stream().map(WorkspaceVersioningDriver.RevisionInfo::id).toList();
    run.check("revision list is complete and ordered", expected.equals(actual),
              expected.equals(actual) ? path : path + ": expected " + expected.size() + ", got " + actual.size());
  }

  /**
   * Full check of what a user would observe: every live file's bytes, deleted files absent, every revised file's
   * revision count and order, and the content of a sample of revisions. With {@code sameIds} false (a clone) revisions
   * are matched by position rather than id.
   */
  void verify(final String label, final int revisionSamples, final boolean sameIds) throws Exception {
    var wrongBytes = 0;
    for (final var e : files.entrySet()) {
      final var actual = driver.read(ws, e.getKey()).content();
      if (!Arrays.equals(actual, content(e.getValue().spec, e.getValue().current))) {
        if (wrongBytes++ < 3) run.note(label + ": wrong bytes in " + e.getKey());
      }
    }
    run.check(label + ": every file has its latest bytes (" + files.size() + " files)", wrongBytes == 0,
              wrongBytes + " wrong");
    var present = 0;
    for (final var p : deleted) if (!files.containsKey(p) && driver.exists(ws, p)) present++;
    run.check(label + ": deleted files stay deleted", present == 0, present + " present");

    final var revised = files.entrySet().stream().filter(e -> !e.getValue().revisions.isEmpty()).toList();
    var badLists = 0;
    var badContent = 0;
    var sampled = 0;
    var observed = 0L;
    final var perFile = Math.max(2, revisionSamples / Math.max(1, revised.size()));
    for (final var e : revised) {
      final var f = e.getValue();
      final var listing = driver.listRevisions(ws, e.getKey()).revisions();
      observed += listing.size();
      final var expectedIds = f.revisions.stream().map(Rev::id).toList();
      final var actualIds = listing.stream().map(WorkspaceVersioningDriver.RevisionInfo::id).toList();
      if (listing.size() != f.revisions.size() || (sameIds && !expectedIds.equals(actualIds))) {
        if (badLists++ < 3) run.note(label + ": revisions of " + e.getKey() + " differ (" + listing.size() + " vs " + f.revisions.size() + ")");
        continue;
      }
      if (sampled >= revisionSamples) continue;
      final var indexes = new java.util.TreeSet<Integer>(List.of(0, listing.size() - 1));
      while (indexes.size() < Math.min(perFile, listing.size())) indexes.add(rnd.nextInt(listing.size()));
      for (final var i : indexes) {
        sampled++;
        final var bytes = driver.readRevision(ws, listing.get(i).id());
        if (!Arrays.equals(bytes, content(f.spec, f.revisions.get(i).version()))) badContent++;
      }
    }
    run.check(label + ": revision lists complete and ordered (" + revised.size() + " files)", badLists == 0, badLists + " differ");
    run.check(label + ": revision count via API = expected (" + revisionCount() + ")", observed == revisionCount(),
              "observed " + observed);
    run.check(label + ": sampled revision contents (" + sampled + ")", badContent == 0, badContent + " wrong");
  }
  //endregion
}
