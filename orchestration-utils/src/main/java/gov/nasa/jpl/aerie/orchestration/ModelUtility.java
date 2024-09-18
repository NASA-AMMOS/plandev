package gov.nasa.jpl.aerie.orchestration;

import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelBuilder;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

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
}
