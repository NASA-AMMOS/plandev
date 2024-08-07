package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;

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
    final var className = getImplementingClassName(path);
    final var classLoader = new URLClassLoader(new URL[] {pathToUrl(path)});

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

  private static String getImplementingClassName(final Path jarPath)
  throws ProcedureLoadException {
    try (final var jarFile = new JarFile(jarPath.toFile())) {
      return Objects.requireNonNull(jarFile.getManifest().getMainAttributes().getValue("Main-Class"));
    } catch (final IOException ex) {
      throw new ProcedureLoadException(jarPath, ex);
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
