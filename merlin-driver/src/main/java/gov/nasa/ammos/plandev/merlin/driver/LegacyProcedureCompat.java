package gov.nasa.ammos.plandev.merlin.driver;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Loads procedural constraint and goal JARs built before the packages were renamed.
 *
 * <p> A procedure JAR is shaped very differently from a mission model JAR: it bundles the
 * whole runtime it compiled against — protocol, driver, framework, timeline, and the
 * procedural API itself. Those copies were never used. Parent-first delegation meant the
 * server's classes always won and the bundled ones sat inert. Renaming the packages did
 * not break the JAR so much as un-shadow it: its references now miss the runtime and find
 * its own stale copies, so it hands back a {@code ProcedureMapper} the server does not
 * recognise as one.
 *
 * <p> So the fix is to restore the shadowing, and the rule that does it is: rewrite a
 * reference only when this runtime actually supplies the renamed class. Nothing else is
 * touched — not the procedure's own code, not kotlin-stdlib, not third-party libraries.
 *
 * <p> Deciding against the live classpath rather than a fixed package list is deliberate,
 * and not merely tidier. The services do not ship the same libraries: merlin-server has no
 * {@code contrib} on its runtime classpath, so a list that redirected {@code contrib}
 * references would turn a working JAR into {@link NoClassDefFoundError}. The classpath is
 * the only honest source for what "this runtime supplies" means, and it answers per
 * service, which is what the JARs saw before the rename anyway.
 */
public final class LegacyProcedureCompat {
  private LegacyProcedureCompat() {}

  private static final String CURRENT_ROOT = "gov/nasa/ammos/plandev/";

  /** The two roots the rebrand collapsed into {@link #CURRENT_ROOT}. */
  private static final String[] LEGACY_ROOTS = {"gov/nasa/jpl/aerie/", "gov/nasa/ammos/aerie/"};

  /**
   * A class every procedure JAR bundles, under its pre-rename name. Checking for it is a
   * single hash lookup in the central directory, where scanning entry names is a walk over
   * a couple of thousand of them -- and this runs once per constraint evaluation, not once
   * at startup.
   */
  private static final String LEGACY_MARKER = "gov/nasa/jpl/aerie/merlin/protocol/types/Duration.class";

  /** Whether this JAR carries classes under a pre-rename root. */
  public static boolean isLegacy(final Path jarPath) throws IOException {
    try (final var jarFile = new JarFile(jarPath.toFile())) {
      return isLegacy(jarFile);
    }
  }

  /**
   * As {@link #isLegacy(Path)}, for a caller that already has the JAR open. Opening one
   * means reading its whole central directory, which for a procedure JAR is a couple of
   * thousand entries and dwarfs the check itself -- so the loaders read the manifest and
   * ask this in the same visit.
   */
  public static boolean isLegacy(final JarFile jarFile) {
    if (jarFile.getEntry(LEGACY_MARKER) != null) return true;
    // Fall back to the general question for anything shaped unlike the JARs we know.
    return jarFile.stream().anyMatch(entry -> {
      final var name = entry.getName();
      for (final var root : LEGACY_ROOTS) if (name.startsWith(root)) return true;
      return false;
    });
  }

  /**
   * A loader for {@code jar}, redirecting to this runtime's classes where it has them.
   * A current JAR gets a plain loader and pays nothing.
   */
  public static ClassLoader classLoader(final URL jar, final boolean legacy, final ClassLoader runtime) {
    if (!legacy) return new java.net.URLClassLoader(new URL[] {jar});

    return new RemappingJarClassLoader(jar, redirectsFor(runtime)::get);
  }

  /**
   * Redirect decisions for one runtime, cached for its lifetime.
   *
   * <p> A probe is a {@code getResource} walk over every JAR on the classpath, and the
   * answer cannot change while the process runs -- so caching it per loader instance, as
   * the obvious implementation does, throws the cache away exactly when it would start
   * paying: these loaders are built fresh for every procedure evaluation. Weak keys keep
   * this from pinning a class loader that does go away.
   */
  private static final Map<ClassLoader, Map<String, String>> REDIRECTS =
      Collections.synchronizedMap(new WeakHashMap<>());

  private static Map<String, String> redirectsFor(final ClassLoader runtime) {
    final var cache = REDIRECTS.computeIfAbsent(runtime, ignored -> new ConcurrentHashMap<>());
    return new AbstractMap<>() {
      @Override public String get(final Object key) {
        final var name = (String) key;
        return cache.computeIfAbsent(name, n -> {
          final var renamed = rename(n);
          if (renamed.equals(n)) return n;
          return runtime.getResource(renamed + ".class") != null ? renamed : n;
        });
      }
      @Override public Set<Entry<String, String>> entrySet() { return cache.entrySet(); }
    };
  }

  private static String rename(final String internalName) {
    for (final var root : LEGACY_ROOTS) {
      if (internalName.startsWith(root)) return CURRENT_ROOT + internalName.substring(root.length());
    }
    return internalName;
  }
}
