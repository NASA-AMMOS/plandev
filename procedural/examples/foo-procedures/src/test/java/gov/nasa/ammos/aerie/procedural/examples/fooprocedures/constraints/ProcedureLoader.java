package gov.nasa.ammos.aerie.procedural.examples.fooprocedures.constraints;

import gov.nasa.jpl.aerie.merlin.protocol.model.MerlinPlugin;
import gov.nasa.ammos.aerie.procedural.constraints.Constraint;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.JarFile;

public final class ProcedureLoader {
  public static Constraint loadProcedure(final Path path, final String name, final String version)
  throws ProcedureLoadException
  {
    final var className = getImplementingClassName(path, name, version);
    final var classLoader = new URLClassLoader(new URL[] {pathToUrl(path)});

    try {
      final var pluginClass$ = classLoader.loadClass(className);
      if (!Constraint.class.isAssignableFrom(pluginClass$)) {
        throw new ProcedureLoadException(path, name, version);
      }

      return (Constraint) pluginClass$.getConstructor().newInstance();
    } catch (final ReflectiveOperationException ex) {
      throw new ProcedureLoadException(path, name, version, ex);
    }
  }

  private static String getImplementingClassName(final Path jarPath, final String name, final String version)
  throws ProcedureLoadException {
    try (final var jarFile = new JarFile(jarPath.toFile())) {
      return Objects.requireNonNull(jarFile.getManifest().getMainAttributes().getValue("Main-Class"));
    } catch (final IOException ex) {
      throw new ProcedureLoadException(jarPath, name, version, ex);
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
    private ProcedureLoadException(final Path path, final String name, final String version) {
      this(path, name, version, null);
    }

    private ProcedureLoadException(final Path path, final String name, final String version, final Throwable cause) {
      super(
          String.format(
              "No implementation found for `%s` at path `%s` wih name \"%s\" and version \"%s\"",
              MerlinPlugin.class.getSimpleName(),
              path,
              name,
              version),
          cause);
    }
  }
}
