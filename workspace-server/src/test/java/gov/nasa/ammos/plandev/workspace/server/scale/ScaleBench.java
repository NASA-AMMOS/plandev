package gov.nasa.ammos.plandev.workspace.server.scale;

import gov.nasa.ammos.plandev.workspace.server.GitRevisionsPrototypeDriver;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.stream.JsonGenerator;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Workspace versioning scale harness. Arguments are {@code key=value}; see docs/workspace-versioning-scale/README.md.
 * <pre>
 *   ./gradlew :workspace-server:workspaceScale --args='profile=quick'
 *   ./gradlew :workspace-server:workspaceScale --args='profile=scale scenarios=year'
 *   ./gradlew :workspace-server:workspaceScale --args='profile=quick scenarios=history-depth history.checkpoints=0,1000,5000'
 * </pre>
 * Exit status is non-zero if any scenario is incorrect, throws, or has failed operations.
 */
public final class ScaleBench {
  private static final Map<String, String> COMMON = Map.ofEntries(
      Map.entry("seed", "20261009"),
      Map.entry("reps", "5"),
      Map.entry("opCeilingSeconds", "600"),
      Map.entry("verify.revisionSamples", "200"),
      Map.entry("history.files", "50"),
      Map.entry("remote.files", "50"),
      Map.entry("remote.revisionEvery", "10"),
      Map.entry("storage.maintenance", "true"),
      Map.entry("year.filesPerDir", "50"),
      Map.entry("year.hotSaveShare", "0.5"),
      Map.entry("year.hotRevisionShare", "0.7"),
      Map.entry("year.mediumShare", "0.02"),
      Map.entry("year.mediumMin", "65536"),
      Map.entry("year.mediumMax", "1048576"),
      Map.entry("year.binaryShare", "0.003"),
      Map.entry("year.binarySize", "1048576"),
      Map.entry("year.moveRate", "0.003"),
      Map.entry("year.deleteRate", "0.002"),
      Map.entry("year.createRate", "0.01"),
      Map.entry("year.remote", "true"),
      Map.entry("year.maintenance", "true"),
      Map.entry("year.verifySamples", "500"));

  static final Map<String, Map<String, String>> PROFILES = Map.of(
      "smoke", Map.ofEntries(
          Map.entry("budgetMinutes", "5"),
          Map.entry("reps", "2"),
          Map.entry("tree.files", "20"),
          Map.entry("tree.extraMiB", "2"),
          Map.entry("history.files", "5"),
          Map.entry("history.checkpoints", "0,30"),
          Map.entry("revisions.one.checkpoints", "0,5"),
          Map.entry("revisions.spread.files", "5"),
          Map.entry("revisions.spread.checkpoints", "10"),
          Map.entry("storage.kinds", "small-text:text:4096:20,binary-64KiB:binary:65536:5"),
          Map.entry("concurrency.writers", "1,3"),
          Map.entry("concurrency.ops", "5"),
          Map.entry("independent.workspaces", "1,3"),
          Map.entry("independent.ops", "5"),
          Map.entry("remote.files", "5"),
          Map.entry("remote.checkpoints", "20,40"),
          Map.entry("year.files", "20"),
          Map.entry("year.hotFiles", "3"),
          Map.entry("year.saves", "150"),
          Map.entry("year.revisions", "15"),
          Map.entry("year.checkpointEvery", "60"),
          Map.entry("year.restartEvery", "90"),
          Map.entry("year.verifySamples", "20")),
      "quick", Map.ofEntries(
          Map.entry("budgetMinutes", "10"),
          Map.entry("reps", "3"),
          Map.entry("tree.files", "100,1000"),
          Map.entry("tree.extraMiB", "8,32"),
          Map.entry("history.checkpoints", "0,100,1000"),
          Map.entry("revisions.one.checkpoints", "0,10,100,300"),
          Map.entry("revisions.spread.files", "50"),
          Map.entry("revisions.spread.checkpoints", "100,300"),
          Map.entry("storage.kinds", "small-text:text:4096:500,medium-text:text:1048576:50,"
                                     + "binary-1MiB:binary:1048576:20,binary-10MiB:binary:10485760:5"),
          Map.entry("concurrency.writers", "1,4,10"),
          Map.entry("concurrency.ops", "40"),
          Map.entry("independent.workspaces", "1,4,10"),
          Map.entry("independent.ops", "40"),
          Map.entry("remote.checkpoints", "200,1000"),
          Map.entry("year.files", "150"),
          Map.entry("year.hotFiles", "10"),
          Map.entry("year.saves", "1000"),
          Map.entry("year.revisions", "100"),
          Map.entry("year.checkpointEvery", "500"),
          Map.entry("year.restartEvery", "600")),
      "scale", Map.ofEntries(
          Map.entry("budgetMinutes", "240"),
          Map.entry("tree.files", "100,1000,10000,50000"),
          Map.entry("tree.extraMiB", "8,32,128"),
          Map.entry("history.checkpoints", "0,100,1000,5000,10000,25000,50000"),
          Map.entry("revisions.one.checkpoints", "0,100,1000,10000"),
          Map.entry("revisions.spread.files", "1000"),
          Map.entry("revisions.spread.checkpoints", "100,1000,10000"),
          Map.entry("storage.kinds", "small-text:text:4096:20000,medium-text:text:1048576:2000,"
                                     + "binary-1MiB:binary:1048576:1000,binary-10MiB:binary:10485760:200"),
          Map.entry("concurrency.writers", "1,4,10,20"),
          Map.entry("concurrency.ops", "200"),
          Map.entry("independent.workspaces", "1,4,10,20"),
          Map.entry("independent.ops", "200"),
          Map.entry("remote.checkpoints", "1000,10000,50000"),
          Map.entry("year.files", "3000"),
          Map.entry("year.hotFiles", "30"),
          Map.entry("year.saves", "50000"),
          Map.entry("year.revisions", "5000"),
          Map.entry("year.checkpointEvery", "10000"),
          Map.entry("year.restartEvery", "10000")));

  private ScaleBench() {}

  public static void main(final String[] args) throws Exception {
    System.exit(run(args) ? 0 : 1);
  }

  /** Run the harness; true if every scenario was correct and had no failed operations. */
  public static boolean run(final String... args) throws Exception {
    final var overrides = new LinkedHashMap<String, String>();
    for (final var a : args) {
      final var eq = a.indexOf('=');
      if (eq < 1) throw new IllegalArgumentException("Arguments are key=value, got: " + a);
      overrides.put(a.substring(0, eq), a.substring(eq + 1));
    }
    final var profile = overrides.getOrDefault("profile", "quick");
    if (!PROFILES.containsKey(profile)) throw new IllegalArgumentException("Unknown profile " + profile + "; one of " + PROFILES.keySet());
    final var all = new TreeMap<>(COMMON);
    all.putAll(PROFILES.get(profile));
    all.putAll(overrides);
    final var p = new Params(all);

    final var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    final var out = Files.createDirectories(Path.of(p.s("out", "build/scale-results/" + stamp + "-" + profile)));
    final var data = Path.of(p.s("root", out.resolve("data").toString()));
    final var jdbc = p.s("jdbc", System.getenv("WSBENCH_JDBC"));
    final var selected = p.s("scenarios", "all").equals("all")
        ? Scenarios.ALL
        : Arrays.stream(p.s("scenarios", "").split(",")).map(n -> Scenarios.ALL.stream().filter(e -> e.name().equals(n))
              .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown scenario " + n + "; one of "
                  + Scenarios.ALL.stream().map(Scenarios.Entry::name).toList()))).toList();

    System.out.println("Workspace versioning scale harness: profile=" + profile + ", output " + out.toAbsolutePath());
    final var runs = new ArrayList<Run>();
    final Map<String, Object> driverInfo;
    try (final var driver = new GitRevisionsPrototypeDriver(data, jdbc)) {
      driverInfo = driver.describe();
      for (final var entry : selected) {
        final var scenarioParams = new TreeMap<String, Object>();
        all.forEach((k, v) -> {
          if (k.startsWith(entry.prefix()) || k.equals("reps") || k.equals("seed") || k.equals("budgetMinutes")) scenarioParams.put(k, v);
        });
        final var run = new Run(entry.name(), scenarioParams, p.d("opCeilingSeconds"), p.d("budgetMinutes") * 60);
        runs.add(run);
        System.out.println("\n== " + entry.name() + " " + scenarioParams);
        final var executor = Executors.newSingleThreadExecutor();
        final var future = executor.submit(() -> {
          entry.body().run(new Scenarios.Ctx(driver, p, run, p.l("seed")));
          return null;
        });
        try {
          future.get((long) (p.d("budgetMinutes") * 60 * 3), TimeUnit.SECONDS); // hard ceiling; the budget is soft
        } catch (TimeoutException e) {
          future.cancel(true);
          run.error = "timed out at the hard ceiling of 3x the scenario budget";
        } catch (java.util.concurrent.ExecutionException e) {
          final var sw = new StringWriter();
          e.getCause().printStackTrace(new PrintWriter(sw));
          run.error = sw.toString();
          System.out.println("  ERROR " + e.getCause());
        } finally {
          executor.shutdownNow();
        }
        run.elapsedNanos = (long) (run.elapsedSeconds() * 1e9);
        System.out.println(summary(run));
        write(out, profile, all, driverInfo, runs); // after every scenario, so a long run leaves partial results
      }
    }
    if (!p.b("keep")) deleteTree(data);
    final var ok = runs.stream().allMatch(r -> r.status().equals("OK"));
    System.out.println("\n" + (ok ? "ALL OK" : "PROBLEMS: " + runs.stream().filter(r -> !r.status().equals("OK"))
        .map(r -> r.scenario + "=" + r.status()).toList()) + "  results: " + out.resolve("results.json").toAbsolutePath());
    return ok;
  }

  //region Output
  private static void write(
      final Path out, final String profile, final Map<String, String> params, final Map<String, Object> driver,
      final List<Run> runs) throws IOException
  {
    final var scenarios = Json.createArrayBuilder();
    runs.forEach(r -> scenarios.add(r.toJson()));
    final var result = Json.createObjectBuilder()
                           .add("profile", profile)
                           .add("environment", environment(out))
                           .add("driver", Run.json(driver))
                           .add("params", Run.json(params))
                           .add("scenarios", scenarios)
                           .build();
    Files.writeString(out.resolve("results.json"), pretty(result));
    final var raw = Json.createObjectBuilder();
    runs.forEach(r -> raw.add(r.scenario, r.rawJson()));
    try (final var gz = new GZIPOutputStream(Files.newOutputStream(out.resolve("raw-timings-us.json.gz")))) {
      gz.write(raw.build().toString().getBytes());
    }
    Files.writeString(out.resolve("summary.txt"), String.join("\n", runs.stream().map(ScaleBench::summary).toList()));
  }

  private static String pretty(final JsonObject json) {
    final var sw = new StringWriter();
    try (final var w = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true)).createWriter(sw)) {
      w.writeObject(json);
    }
    return sw.toString();
  }

  /** The console summary: per checkpoint, each op's count, median, p95 and max (ms), plus storage. */
  static String summary(final Run r) {
    final var sb = new StringBuilder();
    sb.append("-- ").append(r.scenario).append(": ").append(r.status()).append(" in ").append(Math.round(r.elapsedNanos / 1e9))
      .append(" s, checks ").append(r.checks.size() - r.failedChecks).append(" passed / ").append(r.failedChecks).append(" failed\n");
    if (r.error != null) sb.append("   error: ").append(r.error.lines().limit(3).toList()).append('\n');
    ops(sb, "", r.ops);
    for (final var cp : r.checkpoints) {
      sb.append("   [").append(cp.label).append("] ").append(cp.position).append('\n');
      ops(sb, "     ", cp.ops);
      cp.storage.forEach((ws, d) -> sb.append("     storage ").append(ws).append(": ").append(d).append('\n'));
    }
    r.results.forEach((k, v) -> sb.append("   ").append(k).append(" = ").append(v).append('\n'));
    r.notes.forEach(n -> sb.append("   note: ").append(n).append('\n'));
    return sb.toString();
  }

  private static void ops(final StringBuilder sb, final String indent, final Map<String, Run.Samples> ops) {
    new TreeMap<>(ops).forEach((op, s) -> {
      final var j = s.summary();
      sb.append(indent).append("   ").append(String.format("%-32s", op)).append(String.format(" n=%-6d", j.getInt("count")));
      if (j.containsKey("medianMs")) {
        sb.append(String.format(" med %9.2f  p95 %9.2f  max %9.2f ms", j.getJsonNumber("medianMs").doubleValue(),
                                j.getJsonNumber("p95Ms").doubleValue(), j.getJsonNumber("maxMs").doubleValue()));
      }
      if (j.containsKey("failures")) sb.append("  FAILURES ").append(j.getInt("failures")).append(' ').append(j.get("errors"));
      sb.append('\n');
    });
  }

  private static JsonObject environment(final Path out) {
    final var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    final var env = new LinkedHashMap<String, Object>();
    env.put("timestamp", Instant.now().toString());
    env.put("gitCommit", git("rev-parse", "HEAD"));
    env.put("gitBranch", git("rev-parse", "--abbrev-ref", "HEAD"));
    env.put("gitDirty", !git("status", "--porcelain", "--untracked-files=no").isBlank());
    env.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ", "
                    + System.getProperty("java.vendor") + ")");
    env.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
    env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
    env.put("cpus", Runtime.getRuntime().availableProcessors());
    env.put("physicalMemoryBytes", os.getTotalMemorySize());
    env.put("freeMemoryBytes", os.getFreeMemorySize());
    env.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    try {
      final var store = Files.getFileStore(out);
      env.put("filesystem", store.type() + " " + store.name());
      env.put("filesystemFreeBytes", store.getUsableSpace());
    } catch (IOException e) {
      env.put("filesystem", "unknown: " + e.getMessage());
    }
    return Run.json(env).asJsonObject();
  }

  private static String git(final String... args) {
    try {
      final var cmd = new ArrayList<>(List.of("git"));
      cmd.addAll(List.of(args));
      final var proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      final var text = new String(proc.getInputStream().readAllBytes()).trim();
      return proc.waitFor() == 0 ? text : "unknown";
    } catch (Exception e) {
      return "unknown";
    }
  }

  private static void deleteTree(final Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (final Stream<Path> s = Files.walk(dir)) {
      for (final var p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }
  //endregion

  /** Scenario parameters: profile defaults overlaid with command-line overrides. */
  record Params(Map<String, String> values) {
    String s(final String key, final String fallback) {
      return values.getOrDefault(key, fallback);
    }

    private String require(final String key) {
      final var v = values.get(key);
      if (v == null) throw new IllegalArgumentException("Missing parameter " + key);
      return v;
    }

    int i(final String key) {
      return Integer.parseInt(require(key));
    }

    long l(final String key) {
      return Long.parseLong(require(key));
    }

    double d(final String key) {
      return Double.parseDouble(require(key));
    }

    boolean b(final String key) {
      return Boolean.parseBoolean(values.getOrDefault(key, "false"));
    }

    List<String> list(final String key) {
      return Arrays.stream(require(key).split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    List<Integer> ints(final String key) {
      return list(key).stream().map(Integer::parseInt).toList();
    }
  }
}
