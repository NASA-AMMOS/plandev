package gov.nasa.ammos.plandev.workspace.server.scale;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One scenario's measurements: timed operations (scenario-level, or grouped under the current checkpoint), storage
 * snapshots, correctness checks, failures and notes. Thread-safe for concurrent {@link #time} calls.
 */
final class Run {
  /** Latency samples of one operation. */
  static final class Samples {
    private long[] nanos = new long[64];
    private int n;
    private int failures;
    private int slow;
    private final List<String> errors = new ArrayList<>();

    synchronized void add(final long ns, final long ceilingNs) {
      if (n == nanos.length) nanos = Arrays.copyOf(nanos, n * 2);
      nanos[n++] = ns;
      if (ns > ceilingNs) slow++;
    }

    synchronized void fail(final Throwable t) {
      failures++;
      if (errors.size() < 5) errors.add(t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    synchronized long[] raw() {
      return Arrays.copyOf(nanos, n);
    }

    synchronized JsonObject summary() {
      final var b = Json.createObjectBuilder().add("count", n);
      if (failures > 0) b.add("failures", failures).add("errors", Json.createArrayBuilder(errors));
      if (slow > 0) b.add("overCeiling", slow);
      if (n == 0) return b.build();
      final var s = Arrays.copyOf(nanos, n);
      Arrays.sort(s);
      final var total = Arrays.stream(s).sum();
      b.add("totalMs", ms(total)).add("minMs", ms(s[0])).add("medianMs", ms(pct(s, 50))).add("p95Ms", ms(pct(s, 95)));
      if (n >= 100) b.add("p99Ms", ms(pct(s, 99)));
      b.add("maxMs", ms(s[n - 1])).add("opsPerSec", round(n / (total / 1e9)));
      if (n >= 200) b.add("series", series(Arrays.copyOf(nanos, n)));
      return b.build();
    }

    /** Latency over the sequence (by operation index), in 20 buckets: how cost changes as state grows. */
    private static JsonArrayBuilder series(final long[] inOrder) {
      final var out = Json.createArrayBuilder();
      final var buckets = 20;
      for (var i = 0; i < buckets; i++) {
        final var from = (int) ((long) inOrder.length * i / buckets);
        final var to = (int) ((long) inOrder.length * (i + 1) / buckets);
        final var s = Arrays.copyOfRange(inOrder, from, to);
        Arrays.sort(s);
        out.add(Json.createObjectBuilder().add("from", from).add("to", to)
                    .add("medianMs", ms(pct(s, 50))).add("p95Ms", ms(pct(s, 95))).add("maxMs", ms(s[s.length - 1])));
      }
      return out;
    }

    static long pct(final long[] sorted, final int p) {
      return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)];
    }
  }

  static final class Checkpoint {
    final String label;
    final Map<String, Object> position;
    final Map<String, Samples> ops = new ConcurrentHashMap<>();
    final Map<String, Object> storage = new LinkedHashMap<>();

    Checkpoint(final String label, final Map<String, Object> position) {
      this.label = label;
      this.position = position;
    }
  }

  @FunctionalInterface
  interface Action {
    void run() throws Exception;
  }

  final String scenario;
  final Map<String, Object> params;
  private final long ceilingNs;
  private final long deadlineNanos;
  final Map<String, Samples> ops = new ConcurrentHashMap<>();
  final List<Checkpoint> checkpoints = new ArrayList<>();
  final List<JsonObject> checks = new ArrayList<>();
  final List<String> notes = new ArrayList<>();
  final Map<String, Object> results = new LinkedHashMap<>();
  private volatile Checkpoint current;
  int failedChecks;
  String error;
  long elapsedNanos;
  private final long started = System.nanoTime();

  Run(final String scenario, final Map<String, Object> params, final double opCeilingSeconds, final double budgetSeconds) {
    this.scenario = scenario;
    this.params = params;
    this.ceilingNs = (long) (opCeilingSeconds * 1e9);
    this.deadlineNanos = started + (long) (budgetSeconds * 1e9);
  }

  /** Time one operation. Failures are counted under the operation and rethrown. */
  <T> T time(final String op, final Callable<T> call) throws Exception {
    final var cp = current;
    final var samples = (cp == null ? ops : cp.ops).computeIfAbsent(op, k -> new Samples());
    final var start = System.nanoTime();
    try {
      final var result = call.call();
      samples.add(System.nanoTime() - start, ceilingNs);
      return result;
    } catch (Exception | Error e) {
      samples.fail(e);
      throw e;
    }
  }

  void time(final String op, final Action action) throws Exception {
    time(op, () -> {
      action.run();
      return null;
    });
  }

  void begin(final String label, final Map<String, Object> position) {
    final var cp = new Checkpoint(label, position);
    synchronized (checkpoints) {
      checkpoints.add(cp);
    }
    current = cp;
  }

  void storage(final String key, final Object value) {
    final var cp = current;
    if (cp != null) cp.storage.put(key, value);
    else results.put(key, value);
  }

  void end() {
    current = null;
  }

  void check(final String name, final boolean ok, final String detail) {
    if (!ok) failedChecks++;
    final var b = Json.createObjectBuilder().add("check", name).add("ok", ok);
    if (detail != null) b.add("detail", detail);
    if (current != null) b.add("at", current.label);
    synchronized (checks) {
      checks.add(b.build());
    }
    if (!ok) System.out.println("  CHECK FAILED [" + scenario + "] " + name + (detail == null ? "" : ": " + detail));
  }

  void note(final String note) {
    notes.add(note);
    System.out.println("  note [" + scenario + "] " + note);
  }

  boolean overBudget() {
    return System.nanoTime() > deadlineNanos;
  }

  double elapsedSeconds() {
    return (System.nanoTime() - started) / 1e9;
  }

  String status() {
    if (error != null) return "ERROR";
    if (failedChecks > 0) return "INCORRECT";
    final var failedOps = allSamples().stream().anyMatch(s -> s.failures > 0 || s.slow > 0);
    return failedOps ? "OPERATION_FAILURES" : "OK";
  }

  private List<Samples> allSamples() {
    final var all = new ArrayList<>(ops.values());
    synchronized (checkpoints) {
      checkpoints.forEach(cp -> all.addAll(cp.ops.values()));
    }
    return all;
  }

  JsonObject toJson() {
    final var b = Json.createObjectBuilder()
                      .add("scenario", scenario)
                      .add("status", status())
                      .add("seconds", round(elapsedNanos / 1e9))
                      .add("params", json(params));
    if (error != null) b.add("error", error);
    b.add("ops", opsJson(ops));
    final var cps = Json.createArrayBuilder();
    for (final var cp : checkpoints) {
      cps.add(Json.createObjectBuilder().add("label", cp.label).add("position", json(cp.position))
                  .add("storage", json(cp.storage)).add("ops", opsJson(cp.ops)));
    }
    b.add("checkpoints", cps).add("results", json(results)).add("notes", Json.createArrayBuilder(notes))
     .add("checks", Json.createArrayBuilder(checks.stream().filter(c -> !c.getBoolean("ok")).toList()))
     .add("checksPassed", checks.size() - failedChecks).add("checksFailed", failedChecks);
    return b.build();
  }

  /** Raw per-operation latencies in microseconds, for later plotting. */
  JsonObject rawJson() {
    final var b = Json.createObjectBuilder();
    ops.forEach((op, s) -> b.add(op, micros(s)));
    final var cps = Json.createObjectBuilder();
    for (final var cp : checkpoints) {
      final var o = Json.createObjectBuilder();
      cp.ops.forEach((op, s) -> o.add(op, micros(s)));
      cps.add(cp.label, o);
    }
    return Json.createObjectBuilder().add("ops", b).add("checkpoints", cps).build();
  }

  private static JsonArrayBuilder micros(final Samples s) {
    final var a = Json.createArrayBuilder();
    for (final var ns : s.raw()) a.add(ns / 1000);
    return a;
  }

  private static JsonObjectBuilder opsJson(final Map<String, Samples> ops) {
    final var b = Json.createObjectBuilder();
    new java.util.TreeMap<>(ops).forEach((op, s) -> b.add(op, s.summary()));
    return b;
  }

  static double ms(final long ns) {
    return round(ns / 1e6);
  }

  static double round(final double d) {
    return Math.round(d * 1000) / 1000.0;
  }

  static JsonValue json(final Object o) {
    return switch (o) {
      case null -> JsonValue.NULL;
      case JsonValue v -> v;
      case Boolean b -> b ? JsonValue.TRUE : JsonValue.FALSE;
      case Integer i -> Json.createValue(i);
      case Long l -> Json.createValue(l);
      case Number n -> Json.createValue(round(n.doubleValue()));
      case Map<?, ?> m -> {
        final var b = Json.createObjectBuilder();
        m.forEach((k, v) -> b.add(String.valueOf(k), json(v)));
        yield b.build();
      }
      case Collection<?> c -> {
        final var b = Json.createArrayBuilder();
        c.forEach(v -> b.add(json(v)));
        yield b.build();
      }
      default -> Json.createValue(o.toString());
    };
  }
}
