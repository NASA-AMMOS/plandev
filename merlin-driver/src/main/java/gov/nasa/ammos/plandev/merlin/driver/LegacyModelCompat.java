package gov.nasa.ammos.plandev.merlin.driver;


import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Loads mission model JARs built against the pre-rename SDK, in which the protocol lived
 * under {@code gov.nasa.jpl.aerie.merlin.protocol} rather than
 * {@code gov.nasa.ammos.plandev.merlin.protocol}.
 *
 * <p> The rename was shape-preserving: the SDK's 33 source files differ only in their
 * package declarations, so a legacy JAR's bytecode is byte-for-byte compatible with the
 * current protocol once its references to the old package are rewritten. This class does
 * that rewrite lazily, per class, as the model is loaded.
 *
 * <p> Only the SDK package is remapped. The SDK is {@code compileOnlyApi} in
 * merlin-framework and so is deliberately absent from model JARs — it is the one thing a
 * model expects the runtime to supply. Everything the JAR does carry keeps its original
 * name: the model's own classes, its bundled copies of merlin-framework and contrib, and
 * every resource. That matters, because those names are load-bearing elsewhere — a model
 * ships native libraries under paths like {@code gov/nasa/jpl/aerie/spice/libJNISpice.so}
 * that it looks up by string.
 *
 * <p> This compatibility path is correct only while the current protocol remains
 * structurally identical to the 4.3 protocol. Once a signature changes, a legacy JAR will
 * link and then fail with {@link AbstractMethodError} deep in a simulation. Guard that
 * with an API-compatibility check in CI, and retire this class at the next protocol change
 * rather than letting it silently promise more than it can deliver.
 */
public final class LegacyModelCompat {
  private LegacyModelCompat() {}

  private static final String LEGACY_PROTOCOL = "gov/nasa/jpl/aerie/merlin/protocol/";
  private static final String CURRENT_PROTOCOL = "gov/nasa/ammos/plandev/merlin/protocol/";

  private static final String LEGACY_SERVICE_PREFIX = "gov.nasa.jpl.aerie.merlin.protocol.";
  private static final String CURRENT_SERVICE_PREFIX = "gov.nasa.ammos.plandev.merlin.protocol.";

  /**
   * Whether this JAR declares its plugin under the pre-rename protocol package.
   *
   * <p> Answered from the service descriptor rather than the bytecode: the descriptor is
   * named after the interface's fully-qualified name, so its presence is exactly the
   * question being asked, and reading it costs one central-directory lookup.
   */
  public static boolean isLegacy(final Path jarPath) throws IOException {
    try (final var jarFile = new JarFile(jarPath.toFile())) {
      return jarFile.getEntry("META-INF/services/" + LEGACY_SERVICE_PREFIX + "model.MerlinPlugin") != null;
    }
  }

  /** The {@code META-INF/services} entry naming {@code serviceInterface} in a JAR of this vintage. */
  public static String serviceEntry(final Class<?> serviceInterface, final boolean legacy) {
    final var entry = "META-INF/services/" + serviceInterface.getCanonicalName();
    return legacy ? entry.replace(CURRENT_SERVICE_PREFIX, LEGACY_SERVICE_PREFIX) : entry;
  }

  /**
   * A class loader over {@code jarUrl}, remapping the protocol package on the way in when
   * {@code legacy}. Current JARs get a plain {@link URLClassLoader} and pay nothing.
   */
  public static ClassLoader classLoader(final URL jarUrl, final boolean legacy) {
    return legacy
        ? new RemappingJarClassLoader(jarUrl, PROTOCOL_REDIRECT)
        : new URLClassLoader(new URL[] {jarUrl});
  }

  /**
   * Unlike a procedure JAR, a model JAR never bundles the protocol -- merlin-framework
   * marks the SDK compileOnlyApi precisely so the runtime supplies it -- so the redirect
   * is unconditional and needs no classpath probe.
   */
  private static final java.util.function.UnaryOperator<String> PROTOCOL_REDIRECT = internalName ->
      internalName.startsWith(LEGACY_PROTOCOL)
          ? CURRENT_PROTOCOL + internalName.substring(LEGACY_PROTOCOL.length())
          : internalName;

}
