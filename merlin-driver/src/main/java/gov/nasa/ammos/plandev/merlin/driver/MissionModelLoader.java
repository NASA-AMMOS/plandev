package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.protocol.model.MerlinPlugin;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.jar.JarFile;

public final class MissionModelLoader {
    public static ModelType<?, ?> loadModelType(final Path path, final String name, final String version)
    throws MissionModelLoadException
    {
        final var service = loadMissionModelProvider(path, name, version);
        return service.getModelType();
    }

    public static MissionModel<?> loadMissionModel(
        final Instant planStart,
        final SerializedValue missionModelConfig,
        final Path path,
        final String name,
        final String version)
    throws MissionModelLoadException
    {
        final var service = loadMissionModelProvider(path, name, version);
        final var modelType = service.getModelType();
        final var builder = new MissionModelBuilder();
        return loadMissionModel(planStart, missionModelConfig, modelType, builder);
    }

    private static <Config, Model>
    MissionModel<Model> loadMissionModel(
        final Instant planStart,
        final SerializedValue missionModelConfig,
        final ModelType<Config, Model> modelType,
        final MissionModelBuilder builder)
    {
        try {
            final var serializedConfigMap = missionModelConfig.asMap().orElseThrow(() ->
                new InstantiationException.Builder("Configuration").build());

            final var config = modelType.getConfigurationType().instantiate(serializedConfigMap);
            final var registry = DirectiveTypeRegistry.extract(modelType);
            final var model = modelType.instantiate(planStart, config, builder);
            return builder.build(model, registry);
        } catch (final InstantiationException ex) {
            throw new MissionModelInstantiationException(ex);
        }
    }

    public static MerlinPlugin loadMissionModelProvider(final Path path, final String name, final String version)
    throws MissionModelLoadException
    {
        // Look for a MerlinPlugin implementor in the mission model. For correctness, we're assuming there's
        // only one matching MerlinMissionModel in any given mission model.
        final var className = getImplementingClassName(path, name, version);

        // Construct a ClassLoader with access to classes in the mission model location.
        final var classLoader = new URLClassLoader(new URL[] {missionModelPathToUrl(path)});

        try {
            final var pluginClass$ = classLoader.loadClass(className);
            if (!MerlinPlugin.class.isAssignableFrom(pluginClass$)) {
                throw new MissionModelLoadException(path, name, version);
            }

            return (MerlinPlugin) pluginClass$.getConstructor().newInstance();
        } catch (final ReflectiveOperationException ex) {
            throw new MissionModelLoadException(path, name, version, ex);
        }
    }

    private static String getImplementingClassName(final Path jarPath, final String name, final String version)
    throws MissionModelLoadException {
        try (final var jarFile = new JarFile(jarPath.toFile())) {
            final var jarEntry = jarFile.getEntry("META-INF/services/" + MerlinPlugin.class.getCanonicalName());
            final var inputStream = jarFile.getInputStream(jarEntry);

            final var classPathList = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .toList();

            if (classPathList.size() != 1) {
                throw new MissionModelLoadException(jarPath, name, version);
            }

            return classPathList.get(0);
        } catch (final IOException ex) {
            throw new MissionModelLoadException(jarPath, name, version, ex);
        }
    }

    private static URL missionModelPathToUrl(final Path path) {
        try {
            return path.toUri().toURL();
        } catch (final MalformedURLException ex) {
            // This exception only happens if there is no URL protocol handler available to represent a Path.
            // This is highly unexpected, and indicates a fundamental problem with the system environment.
            throw new Error(ex);
        }
    }

    public static class MissionModelLoadException extends Exception {
        private MissionModelLoadException(final Path path, final String name, final String version) {
            this(path, name, version, null);
        }

        private MissionModelLoadException(final Path path, final String name, final String version, final Throwable cause) {
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

    public static final class MissionModelInstantiationException extends RuntimeException {
        public MissionModelInstantiationException(final Throwable cause) {
            super(cause);
        }
    }
}
