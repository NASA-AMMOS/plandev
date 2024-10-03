package gov.nasa.jpl.aerie.orchestration;

import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelBuilder;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerPlugin;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class ModelUtility {
  /**
   * Load and instantiate a Mission Model from a JAR on the file system.
   *
   * @param modelJarPath Path to the JAR
   * @param simulationStartTime The time the loaded model expects to be simulated starting at.
   *     Necessary to correctly instantiate internal resources.
   * @param modelConfiguration The configuration to be used while instantiating the model.
   *     Expected contents defined by the Model's Configuration.
   * @return An instantiated MissionModel
   * @throws MissionModelLoader.MissionModelLoadException If there is an issue while loading the JAR,
   *     such as the JAR not existing at the specified path.
   * @throws MissionModelLoader.MissionModelInstantiationException If there is an issue while instantiating the
   *     Model,
   *     such as a invalid configuration or simulationStartTime.
   */
  public static MissionModel<?> instantiateMissionModel(
      Path modelJarPath,
      Instant simulationStartTime,
      Map<String, SerializedValue> modelConfiguration
  ) throws MissionModelLoader.MissionModelLoadException, MissionModelLoader.MissionModelInstantiationException {
    return MissionModelLoader.loadMissionModel(
        simulationStartTime,
        SerializedValue.of(modelConfiguration),
        modelJarPath,
        modelJarPath.getFileName().toString(),
        ""
    );
  }

  /**
   * Instantiate a Mission Model using the generated Java code
   *
   * @param modelType An instance of the GeneratedModelType class created for the mission model by the merlin
   *     processor
   * @param simulationStartTime The time the loaded model expects to be simulated starting at.
   *     Necessary to correctly instantiate internal resources.
   * @param modelConfiguration The configuration to be used while instantiating the mission model.
   * @param <Config> The mission model's Configuration class, as defined by the @WithConfiguration tag within its
   *     package-info.java
   * @param <Model> The mission model's Model class, as defined by the @MissionModel tag within its
   *     package-info.java
   * @return An instantiated MissionModel
   */
  public static <Config, Model> MissionModel<Model> instantiateMissionModel(
      ModelType<Config, Model> modelType,
      Instant simulationStartTime,
      Config modelConfiguration
  )
  {
    final var modelBuilder = new MissionModelBuilder();
    final var registry = DirectiveTypeRegistry.extract(modelType);

    // TODO: [AERIE-1516] Teardown the model to release any system resources (e.g. threads).
    final var model = modelType.instantiate(simulationStartTime, modelConfiguration, modelBuilder);
    return modelBuilder.build(model, registry);
  }


  /**
   * TODO
   * @param modelJarPath
   * @return
   * @throws MissionModelLoader.MissionModelLoadException
   */
  public static SchedulerModel instantiateSchedulerModel(final Path modelJarPath)
  throws MissionModelLoader.MissionModelLoadException, SchedulerModelLoadException
  {
    return loadSchedulerModelProvider(modelJarPath, modelJarPath.getFileName().toString(), "").getSchedulerModel();

  }

  public static SchedulerPlugin loadSchedulerModelProvider(final Path path, final String name, final String version)
  throws SchedulerModelLoadException
  {
    // Look for a MerlinMissionModel implementor in the mission model. For correctness, we're assuming there's
    // only one matching MerlinMissionModel in any given mission model.
    final var className = getImplementingClassName(path, name, version);

    // Construct a ClassLoader with access to classes in the mission model location.
    final var parentClassLoader = Thread.currentThread().getContextClassLoader();
    final URLClassLoader classLoader;
    try {
      classLoader = new URLClassLoader(new URL[] {path.toUri().toURL()}, parentClassLoader);
    } catch (MalformedURLException ex) {
      throw new Error(ex);
    }

    try {
      final var factoryClass$ = classLoader.loadClass(className);
      if (!SchedulerPlugin.class.isAssignableFrom(factoryClass$)) {
        throw new SchedulerModelLoadException(path, name, version);
      }

      // SAFETY: We checked above that SchedulerPlugin is assignable from this type.
      @SuppressWarnings("unchecked")
      final var factoryClass = (Class<? extends SchedulerPlugin>) factoryClass$;

      return factoryClass.getConstructor().newInstance();
    } catch (final ClassNotFoundException | NoSuchMethodException | InstantiationException
                   | IllegalAccessException | InvocationTargetException ex)
    {
      throw new SchedulerModelLoadException(path, name, version, ex);
    }
  }

  public static String getImplementingClassName(final Path jarPath, final String name, final String version)
  throws SchedulerModelLoadException
  {
    try {
      final var jarFile = new JarFile(jarPath.toFile());
      final var jarEntry = jarFile.getEntry("META-INF/services/" + SchedulerPlugin.class.getCanonicalName());
      if (jarEntry == null) {
        throw new Error("JAR file `" + jarPath + "` did not declare a service called " + SchedulerPlugin.class.getCanonicalName());
      }
      final var inputStream = jarFile.getInputStream(jarEntry);

      final var classPathList = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.toList());

      if (classPathList.size() != 1) {
        throw new SchedulerModelLoadException(jarPath, name, version);
      }

      return classPathList.get(0);
    } catch (final IOException ex) {
      throw new SchedulerModelLoadException(jarPath, name, version, ex);
    }
  }

  public static class SchedulerModelLoadException extends Exception {
    private SchedulerModelLoadException(final Path path, final String name, final String version) {
      this(path, name, version, null);
    }

    private SchedulerModelLoadException(final Path path, final String name, final String version, final Throwable cause) {
      super(
          String.format(
              "No implementation found for `%s` at path `%s` wih name \"%s\" and version \"%s\"",
              SchedulerPlugin.class.getSimpleName(),
              path,
              name,
              version),
          cause);
    }
  }
}
