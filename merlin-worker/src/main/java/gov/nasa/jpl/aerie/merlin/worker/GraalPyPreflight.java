package gov.nasa.jpl.aerie.merlin.worker;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.python.embedding.GraalPyResources;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Standalone self-check that the GraalPy embedding is actually usable *in this image*.
 *
 * <p>This is the executable form of the roadmap's Phase 1 exit criteria ("creates a
 * {@code Context} and evaluates {@code 1+1} in the worker") and the permanent CI
 * regression for Gate A ("Gate A is now permanently verified in CI, not just once on a
 * laptop"). It is deliberately a {@code main} class rather than a JUnit test, because the
 * property under test is a property of the built container image -- its JDK, its
 * classpath, and its {@code python-resources} venv -- not of the Gradle build. Running it
 * under {@code ./gradlew test} on a CI runner's stock temurin JDK would assert the
 * opposite of what Gate A concluded (see {@link #checkRuntimeTier}).
 *
 * <p>Usage inside the image:
 * <pre>{@code
 *   docker run --rm --entrypoint java aerie_merlin_worker_1 \
 *     -cp '/usr/src/app/lib/*' \
 *     gov.nasa.jpl.aerie.merlin.worker.GraalPyPreflight
 * }</pre>
 *
 * <p>Exits 0 if every check passes, 1 otherwise, printing a summary. It is safe to run
 * against a production image; it allocates one Context, evaluates a few expressions, and
 * exits.
 *
 * <p>Checks, and the roadmap item each one holds down:
 * <ol>
 *   <li>{@code python-resources} layout   -- §2 / §4.2 (external-directory mode)</li>
 *   <li>Runtime tier is not the fallback  -- Gate A-1</li>
 *   <li>{@code 1+1} evaluates to 2        -- §4.3 exit criteria</li>
 *   <li>venv packages import              -- §4.2 / Gate D</li>
 *   <li>Context creation from a child classloader -- Gate A-2</li>
 * </ol>
 */
public final class GraalPyPreflight {

  /** Matches the default in roadmap §2 and §5's {@code pymerlin.resources} fallback. */
  private static final String DEFAULT_RESOURCES = "/opt/pymerlin/python-resources";

  /**
   * The value {@code Engine.getImplementationName()} reports when Truffle is running on
   * its interpreter-only fallback runtime -- i.e. the ~10x-slower tier Gate A-1 exists to
   * catch. On a JVM with the Graal compiler enabled this reads "Optimized" instead.
   */
  private static final String FALLBACK_IMPLEMENTATION = "Interpreted";

  private final Path resourcesRoot;
  private final List<String> failures = new ArrayList<>();

  private GraalPyPreflight(final Path resourcesRoot) {
    this.resourcesRoot = resourcesRoot;
  }

  public static void main(final String[] args) {
    final Path root = Path.of(resolveResourcesRoot(args));

    System.out.println("=== pymerlin GraalPy preflight ===");
    System.out.println("  java.vendor      : " + System.getProperty("java.vendor"));
    System.out.println("  java.version     : " + System.getProperty("java.version"));
    System.out.println("  java.vm.name     : " + System.getProperty("java.vm.name"));
    System.out.println("  os.arch          : " + System.getProperty("os.arch"));
    System.out.println("  python-resources : " + root);
    System.out.println();

    final int exitCode = new GraalPyPreflight(root).run();

    System.out.println();
    if (exitCode == 0) {
      System.out.println("PREFLIGHT PASSED");
    } else {
      System.out.println("PREFLIGHT FAILED");
    }
    System.exit(exitCode);
  }

  private static String resolveResourcesRoot(final String[] args) {
    for (int i = 0; i < args.length - 1; i++) {
      if ("--resources".equals(args[i])) return args[i + 1];
    }
    return System.getProperty("pymerlin.resources", DEFAULT_RESOURCES);
  }

  private int run() {
    checkResourcesLayout();

    // Every remaining check needs a Context. If we cannot build one there is nothing
    // further to learn, and the stack trace is the whole finding.
    try (final Context context = buildContext(this.resourcesRoot)) {
      checkRuntimeTier(context);
      checkEvalOnePlusOne(context);
      checkVenvPackages(context);
    } catch (final Exception e) {
      fail("context", "could not build or use a GraalPy Context: " + e);
      e.printStackTrace(System.out);
    }

    checkChildClassloader();

    return this.failures.isEmpty() ? 0 : 1;
  }

  /**
   * Mirrors roadmap §5's {@code PyContext.build()}. Kept intentionally identical so that
   * the preflight validates the configuration Phase 2 will actually ship, rather than a
   * more permissive one that happens to pass.
   */
  private static Context buildContext(final Path resourcesRoot) {
    return GraalPyResources
        .contextBuilder(resourcesRoot)
        .allowAllAccess(true)
        .allowCreateThread(true)
        .build();
  }

  // --- Check 1: external-directory layout (roadmap §2, §4.2) ---------------------------

  private void checkResourcesLayout() {
    final Path venv = this.resourcesRoot.resolve("venv");
    final Path src = this.resourcesRoot.resolve("src");

    if (!Files.isDirectory(this.resourcesRoot)) {
      fail("layout", "python-resources root does not exist: " + this.resourcesRoot);
      return;
    }
    // GraalPyResources.contextBuilder(root) fixes these two names by convention; a typo
    // in the Dockerfile shows up here rather than as a mystifying ImportError later.
    if (!Files.isDirectory(venv)) fail("layout", "missing venv directory: " + venv);
    else pass("layout", "venv present at " + venv);

    if (!Files.isDirectory(src)) fail("layout", "missing src directory: " + src);
    else pass("layout", "src present at " + src);
  }

  // --- Check 2: Gate A-1, runtime tier ------------------------------------------------

  /**
   * Gate A-1. Stock OpenJDK gives Truffle's interpreter-only fallback runtime rather than
   * the Graal-compiled one -- roughly an order of magnitude slower. Gate A hit exactly
   * this on {@code eclipse-temurin:21-jre-jammy} ("JVMCI is not enabled for this JVM") and
   * resolved it by rebasing onto {@code ghcr.io/graalvm/jdk-community:21}.
   *
   * <p>The spike detected this by grepping stderr for the warning banner. This asserts on
   * {@code Engine.getImplementationName()} instead: it is the same signal without
   * depending on the exact wording of a log line, and it cannot be silenced by someone
   * setting {@code -Dpolyglot.engine.WarnInterpreterOnly=false} to quiet the logs.
   */
  private void checkRuntimeTier(final Context context) {
    final String implementation = context.getEngine().getImplementationName();
    final String version = context.getEngine().getVersion();

    if (FALLBACK_IMPLEMENTATION.equalsIgnoreCase(implementation)) {
      fail("gate-a-1", String.format(
          "polyglot engine is on the FALLBACK runtime (implementation=%s, version=%s). "
              + "Guest code runs interpreted-only, ~10x slower. This image's JDK does not have "
              + "the Graal compiler enabled -- check that the base image is still "
              + "ghcr.io/graalvm/jdk-community:21 and was not reverted to a stock OpenJDK.",
          implementation, version));
    } else {
      pass("gate-a-1", String.format(
          "polyglot engine implementation=%s, version=%s (not the fallback runtime)",
          implementation, version));
    }
  }

  // --- Check 3: Phase 1 exit criteria -------------------------------------------------

  private void checkEvalOnePlusOne(final Context context) {
    final Value result = context.eval("python", "1+1");
    if (result.isNumber() && result.asInt() == 2) {
      pass("eval", "context.eval(\"python\", \"1+1\") == 2");
    } else {
      fail("eval", "expected 2, got: " + result);
    }
  }

  // --- Check 4: venv contents (roadmap §4.2, Gate D) ----------------------------------

  /**
   * Confirms the venv the Dockerfile built is the venv this Context resolves imports
   * against. Gate D established that numpy and spiceypy genuinely work under GraalPy, but
   * that finding is about the packages; this is about whether *this image* wired them up.
   *
   * <p>Import failures are reported per-package rather than aborting, so one broken
   * package does not mask the state of the others.
   */
  private void checkVenvPackages(final Context context) {
    context.eval("python", """
        import importlib

        def _probe(module, distribution):
            importlib.import_module(module)
            try:
                from importlib.metadata import version
                return str(version(distribution))
            except Exception:
                return "unknown"
        """);

    final Value probe = context.getBindings("python").getMember("_probe");

    // getBindings("python") is a scope object, so members are the right accessor here --
    // but Gate D-2 was bitten by getMember() silently returning null for a shape that
    // needed getHashValue() instead (dict entries use a separate interop protocol). Fail
    // with the reason rather than a bare NPE three lines later if that ever recurs.
    if (probe == null || probe.isNull()) {
      fail("venv", "could not resolve the _probe function from the python bindings; "
          + "cannot report on installed packages");
      return;
    }

    // (module to import, distribution name to read a version from)
    final String[][] packages = {
        {"pymerlin", "pymerlin"},
        {"numpy", "numpy"},
        {"spiceypy", "spiceypy"},
    };

    for (final String[] pkg : packages) {
      try {
        final String version = probe.execute(pkg[0], pkg[1]).asString();
        pass("venv", pkg[0] + " " + version);
      } catch (final Exception e) {
        fail("venv", "could not import " + pkg[0] + ": " + e.getMessage());
      }
    }
  }

  // --- Check 5: Gate A-2, classloader -------------------------------------------------

  /**
   * Gate A-2. PlanDev loads mission models through its own child classloader, while the
   * polyglot/python jars sit on the worker's parent classpath; Truffle locates language
   * implementations via {@link java.util.ServiceLoader}, which is sensitive to exactly
   * that split.
   *
   * <p>Gate A tested this with a plain {@code URLClassLoader} and explicitly carried the
   * caveat forward: "this used a plain URLClassLoader as a stand-in, not PlanDev's actual
   * model loader". This check inherits the same caveat -- it reproduces the *shape* of the
   * split (probe class defined in the child; polyglot resolved from the parent) but not
   * PlanDev's real {@code MissionModelLoader}. Closing that caveat needs a real model-JAR
   * upload, which is Phase 2 work once the shim actually builds a Context.
   */
  private void checkChildClassloader() {
    try {
      final ClassLoader parent = GraalPyPreflight.class.getClassLoader();
      final ClassLoader child = new ProbeClassLoader(parent);

      final Class<?> probeClass = child.loadClass(ContextProbe.class.getName());

      if (probeClass.getClassLoader() != child) {
        // Parent-first delegation won; the split we meant to test never happened, so a
        // "pass" here would be vacuous.
        fail("gate-a-2", "probe was loaded by the parent loader, so the parent/child "
            + "split under test was not actually exercised");
        return;
      }

      @SuppressWarnings("unchecked")
      final Callable<Integer> probe = (Callable<Integer>) probeClass
          .getDeclaredConstructor(String.class)
          .newInstance(this.resourcesRoot.toString());

      final int result = probe.call();

      if (result == 2) {
        pass("gate-a-2", "Context created and evaluated 1+1 from a child classloader");
      } else {
        fail("gate-a-2", "child-classloader Context returned " + result + ", expected 2");
      }
    } catch (final Exception e) {
      fail("gate-a-2", "Context creation from a child classloader failed: " + e);
      e.printStackTrace(System.out);
    }
  }

  /**
   * Loads {@link ContextProbe} itself instead of delegating, so the probe genuinely lives
   * in the child loader. Everything else -- {@code org.graalvm.polyglot.*},
   * {@code java.util.concurrent.Callable} -- still delegates to the parent, which is
   * precisely the arrangement Gate A-2 is about.
   */
  private static final class ProbeClassLoader extends ClassLoader {
    private ProbeClassLoader(final ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      if (!name.equals(ContextProbe.class.getName())) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          final byte[] bytes = readClassBytes(name);
          loaded = defineClass(name, bytes, 0, bytes.length);
        }
        if (resolve) resolveClass(loaded);
        return loaded;
      }
    }

    private byte[] readClassBytes(final String name) throws ClassNotFoundException {
      final String resource = name.replace('.', '/') + ".class";
      try (final InputStream in = getParent().getResourceAsStream(resource)) {
        if (in == null) throw new ClassNotFoundException("no bytes for " + resource);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
      } catch (final Exception e) {
        throw new ClassNotFoundException("could not read " + resource, e);
      }
    }
  }

  /**
   * Runs in the child loader. Must stay free of references to its enclosing class, since
   * {@link ProbeClassLoader} only redefines this one class -- a reference to
   * {@code GraalPyPreflight} would silently resolve back through the parent and blur the
   * split being tested.
   */
  public static final class ContextProbe implements Callable<Integer> {
    private final String resourcesRoot;

    public ContextProbe(final String resourcesRoot) {
      this.resourcesRoot = resourcesRoot;
    }

    @Override
    public Integer call() {
      try (final Context context = GraalPyResources
          .contextBuilder(Path.of(this.resourcesRoot))
          .allowAllAccess(true)
          .allowCreateThread(true)
          .build()) {
        return context.eval("python", "1+1").asInt();
      }
    }
  }

  // --- reporting ----------------------------------------------------------------------

  private void pass(final String check, final String detail) {
    System.out.println("  [PASS] " + check + ": " + detail);
  }

  private void fail(final String check, final String detail) {
    System.out.println("  [FAIL] " + check + ": " + detail);
    this.failures.add(check + ": " + detail);
  }
}
