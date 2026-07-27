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
 * {@code blackbird-adapter:5011} at all. The request is therefore issued from a throwaway container that
 * <em>shares the adapter's network namespace</em> ({@code --network=container:<adapter>}), so
 * {@code localhost:<port>} resolves to the adapter itself -- real HTTP against the real server, just
 * originated on the right side of the network boundary. Nothing here mocks or reimplements the adapter.
 *
 * <p>The sidecar exists because the obvious approach -- {@code docker exec <adapter> python3} -- probes
 * the adapter through a tool that happens to be installed in it. Both shipped adapters are Python, so
 * that held; a {@code scratch} or distroless adapter has no interpreter and no shell, and the probe
 * fails with no HTTP ever attempted. Language-neutrality is the point of the external-backend contract,
 * so the test harness must not require the backend to be written in any particular language either.
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

    final var stdout = run(
        "Probing " + this.container + path,
        "docker", "run", "--rm", "--network=container:" + this.container,
        "--entrypoint", "python3", sidecarImage(), "-c", script);

    try (final var reader = Json.createReader(new StringReader(stdout))) {
      return reader.readObject();
    }
  }

  /**
   * Image for the sidecar. Any image with a python3 on its PATH works; we ask docker which image the
   * Python adapter is running rather than naming one, because that image is necessarily present on the
   * host already -- so the probe never pulls from a registry, including in CI with no egress.
   */
  private static synchronized String sidecarImage() throws IOException, InterruptedException {
    if (sidecarImage == null) {
      final var override = System.getProperty(PROBE_IMAGE_PROPERTY);
      sidecarImage = (override != null && !override.isBlank())
          ? override.strip()
          : run("Resolving the probe sidecar image",
                "docker", "inspect", "--format={{.Config.Image}}", PYTHON.container).strip();
    }
    return sidecarImage;
  }

  private static String run(final String what, final String... command)
  throws IOException, InterruptedException
  {
    final var process = new ProcessBuilder(command).redirectErrorStream(false).start();

    final String stdout;
    final String stderr;
    try (final var out = process.getInputStream(); final var err = process.getErrorStream()) {
      stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
      stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IOException(what + " timed out");
    }
    if (process.exitValue() != 0) {
      throw new IOException(what + " failed (exit " + process.exitValue() + "):\n" + stderr);
    }
    return stdout;
  }

  /** Override the sidecar image, for a stack that does not run the Python adapter. */
  private static final String PROBE_IMAGE_PROPERTY = "aerie.e2e.probeImage";

  private static String sidecarImage = null;
}
