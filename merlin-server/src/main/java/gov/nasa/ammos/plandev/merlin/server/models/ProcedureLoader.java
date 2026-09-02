package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.procedural.constraints.ProcedureMapper;

import gov.nasa.ammos.plandev.merlin.driver.LegacyProcedureCompat;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.JarFile;

public final class ProcedureLoader {
  public static ProcedureMapper<?> loadProcedure(final Path path)
  throws ProcedureLoadException
  {
    // Both answers come from one open: reading a JAR's central directory costs far more
    // than either question, and this runs per procedure evaluation.
    final String className;
    final boolean legacy;
    try (final var jarFile = new JarFile(path.toFile())) {
      className = Objects.requireNonNull(jarFile.getManifest().getMainAttributes().getValue("Main-Class"));
      // A procedure built before the rename bundles a whole stale runtime that used to sit
      // shadowed behind ours; loading it needs that shadowing put back.
      legacy = LegacyProcedureCompat.isLegacy(jarFile);
    } catch (final IOException | NullPointerException ex) {
      throw new ProcedureLoadException(path, ex instanceof IOException io ? io : new IOException(ex));
    }

    final var classLoader = LegacyProcedureCompat.classLoader(
        pathToUrl(path), legacy, ProcedureLoader.class.getClassLoader());

    try {
      final var pluginClass$ = classLoader.loadClass(className);
      if (!ProcedureMapper.class.isAssignableFrom(pluginClass$)) {
        throw new ProcedureLoadException(path);
      }

      return (ProcedureMapper<?>) pluginClass$.getConstructor().newInstance();
    } catch (final ReflectiveOperationException ex) {
      throw new ProcedureLoadException(path, ex);
    }
  }


  private static URL pathToUrl(final Path path) {
    try {
      return path.toUri().toURL();
    } catch (final MalformedURLException ex) {
      // This exception only happens if there is no URL protocol handler available to represent a Path.
      // This is highly unexpected, and indicates a fundamental problem with the system environment.
      throw new Error(ex);
    }
  }

  public static class ProcedureLoadException extends Exception {
    private ProcedureLoadException(final Path path) {
      this(path, null);
    }

    private ProcedureLoadException(final Path path, final Throwable cause) {
      super(
          String.format(
              "No implementation found for `%s` at path `%s`",
              ProcedureMapper.class.getSimpleName(),
              path),
          cause);
    }
  }
}
