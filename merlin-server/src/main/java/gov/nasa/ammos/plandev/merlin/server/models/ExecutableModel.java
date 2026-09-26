package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.ammos.plandev.merlin.driver.MissionModel;
import gov.nasa.ammos.plandev.merlin.driver.MissionModelLoader;
import gov.nasa.ammos.plandev.merlin.protocol.model.InputType;
import gov.nasa.ammos.plandev.merlin.protocol.model.ModelType;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record ExecutableModel(
    String name,
    String version,
    String mission,
    String owner,
    Path definitionFile
) implements MissionModelFile {

  ModelType<?, ?> loadMissionModelType()
  throws MissionModelLoader.MissionModelLoadException
  {
    return MissionModelLoader.loadModelType(definitionFile, name, version);
  }

  /**
   * Load and instantiate the {@link MissionModel} defined by this {@link ExecutableModel}.
   *
   * @param configuration The mission model configuration to load the mission model with.
   * @return A {@link MissionModel} domain object allowing use of the loaded mission model.
   * @throws MissionModelLoader.MissionModelLoadException If the mission model cannot be loaded -- the JAR may be invalid, or the mission model
   * it contains may not abide by the expected contract at load time.
   */
  MissionModel<?> loadAndInstantiateMissionModel(
      final Instant planStart,
      final SerializedValue configuration
  ) throws MissionModelLoader.MissionModelLoadException {
    // TODO: [AERIE-1516] Teardown the missionModel after use to release any system resources (e.g. threads).
    return MissionModelLoader.loadMissionModel(
        planStart,
        configuration,
        definitionFile,
        name,
        version());
  }

  @Override
  public List<InputType.Parameter> extractModelParameters() throws MissionModelLoader.MissionModelLoadException {
    return loadMissionModelType().getConfigurationType().getParameters();
  }

  @Override
  public Pair<Map<String, ActivityType>, List<String>> extractActivityTypesAndSubsystems() throws MissionModelLoader.MissionModelLoadException {
    final var modelType = this.loadMissionModelType();
    final var registry = DirectiveTypeRegistry.extract(modelType);

    final var activityTypes = registry
        .directiveTypes()
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> {
              final var name = entry.getKey();
              final var directiveType = entry.getValue();
              final var inputType = directiveType.getInputType();
              final var outputType = directiveType.getOutputType();

              return new ActivityType(
                  name,
                  inputType.getParameters(),
                  inputType.getRequiredParameters(),
                  outputType.getSchema(),
                  directiveType.getSubsystem(),
                  directiveType.getDescription()
              );
            }));

    final var subsystems = modelType.getSubsystems();
    return Pair.of(activityTypes, subsystems);
  }

  @Override
  public Map<String, ValueSchema> extractResourceSchemas(
      final Instant planStart,
      final SerializedValue configuration
  ) throws MissionModelLoader.MissionModelLoadException {
    return loadAndInstantiateMissionModel(planStart, configuration)
        .getResources()
        .entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry-> entry.getValue().getOutputType().getSchema()));
  }

  @Override
  public boolean equals(final Object object) {
    if (object.getClass() != ExecutableModel.class) {
      return false;
    }

    final ExecutableModel other = (ExecutableModel) object;
    return
        (Objects.equals(this.name(), other.name())
         && Objects.equals(this.version, other.version)
         && Objects.equals(this.mission, other.mission)
         && Objects.equals(this.owner, other.owner)
         && Objects.equals(this.definitionFile, other.definitionFile)
        );
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name,
        version,
        mission,
        owner,
        definitionFile
    );
  }
}
