package gov.nasa.jpl.aerie.e2e.utils;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Reads an external-model backend's own HTTP surface directly, so a test can compare what PlanDev stored
 * against what the adapter actually said.
 *
 * <p>The adapters are deliberately <em>in-cluster</em>: they join PlanDev's docker network as peers of
 * {@code aerie_merlin} and publish no host ports, because an adapter is an operator-run component inside
 * the trust boundary rather than a third-party endpoint. That means the test host cannot reach
 * {@code blackbird-adapter:5011} at all. The request is therefore issued from inside the adapter's own
 * container -- real HTTP against the real server, just originated on the right side of the network
 * boundary. Nothing here mocks or reimplements the adapter.
 */
public final class ExternalAdapterProbe {
  /** The Blackbird backend declared to merlin as {@code blackbird-lab}. */
  public static final ExternalAdapterProbe BLACKBIRD = new ExternalAdapterProbe("blackbird-adapter", 5011);
  /** The pure-Python backend declared to merlin as {@code python-lab}. */
  public static final ExternalAdapterProbe PYTHON = new ExternalAdapterProbe("python-adapter", 5002);

  private final String container;
  private final int port;

  private ExternalAdapterProbe(final String container, final int port) {
    this.container = container;
    this.port = port;
  }

  /** {@code GET /introspect?model=<key>}: the activity types, resource types, parameters and identity hash. */
  public JsonObject introspect(final String modelKey) throws IOException, InterruptedException {
    return get("/introspect?model=" + modelKey);
  }

  /** {@code GET /models}: the backend's discovery listing. */
  public JsonObject models() throws IOException, InterruptedException {
    return get("/models");
  }

  private JsonObject get(final String path) throws IOException, InterruptedException {
    final var script =
        "import urllib.request,sys;"
        + "sys.stdout.write(urllib.request.urlopen('http://localhost:%d%s', timeout=300).read().decode())"
            .formatted(this.port, path);

    final var process = new ProcessBuilder("docker", "exec", this.container, "python3", "-c", script)
        .redirectErrorStream(false)
        .start();

    final String stdout;
    final String stderr;
    try (final var out = process.getInputStream(); final var err = process.getErrorStream()) {
      stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
      stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IOException("Timed out probing " + this.container + path);
    }
    if (process.exitValue() != 0) {
      throw new IOException("Probing " + this.container + path + " failed (exit " + process.exitValue() + "):\n" + stderr);
    }
    try (final var reader = Json.createReader(new StringReader(stdout))) {
      return reader.readObject();
    }
  }
}
