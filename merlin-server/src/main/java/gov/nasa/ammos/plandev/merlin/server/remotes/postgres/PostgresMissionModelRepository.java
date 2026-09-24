package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.model.InputType.Parameter;
import gov.nasa.ammos.plandev.merlin.protocol.model.Resource;
import gov.nasa.ammos.plandev.merlin.server.exceptions.InvalidMissionModelTypeException;
import gov.nasa.ammos.plandev.merlin.server.exceptions.InvalidMissionModelTypeException.ModelType;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityType;
import gov.nasa.ammos.plandev.merlin.server.models.ExecutableModel;
import gov.nasa.ammos.plandev.merlin.server.models.InsertModelInput;
import gov.nasa.ammos.plandev.merlin.server.models.MissionModelJar;
import gov.nasa.ammos.plandev.merlin.server.models.NonExecutableModel;
import gov.nasa.ammos.plandev.merlin.server.remotes.MissionModelRepository;
import gov.nasa.ammos.plandev.merlin.server.services.MissionModelService;
import gov.nasa.ammos.plandev.merlin.server.services.MissionModelService.NoSuchMissionModelException;
import gov.nasa.ammos.plandev.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;

import javax.json.Json;
import javax.json.stream.JsonParsingException;
import javax.sql.DataSource;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class PostgresMissionModelRepository implements MissionModelRepository {
  private final DataSource dataSource;

  public PostgresMissionModelRepository(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Map<MissionModelId, MissionModelJar> getAllMissionModels() {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getAllMissionModelsAction = new GetAllModelsAction(connection)) {
        return getAllMissionModelsAction
            .get()
            .entrySet()
            .stream()
            .collect(Collectors.toMap(
                e -> new MissionModelId(e.getKey()),
                e -> missionModelRecordToMissionModelJar(e.getValue())));
      }
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to retrieve all mission models", ex);
    }
  }

  @Override
  public MissionModelJar getMissionModel(final MissionModelId missionModelId) throws NoSuchMissionModelException {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getMissionModelAction = new GetModelAction(connection)) {
        return getMissionModelAction
            .get(missionModelId.id())
            .map(PostgresMissionModelRepository::missionModelRecordToMissionModelJar)
            .orElseThrow(() -> new NoSuchMissionModelException(missionModelId));
      }
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to retrieve mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public Map<String, ActivityType> getActivityTypes(final MissionModelId missionModelId) {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getActivityTypesAction = new GetActivityTypesAction(connection)) {
        final var id = missionModelId.id();
        final var result = new HashMap<String, ActivityType>();
        for (final var activityType: getActivityTypesAction.get(id)) {
          result.put(activityType.name(), activityType);
        }
        return result;
      }
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to retrieve activity types for mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public MissionModelId createMissionModel(final InsertModelInput modelInput, final Path missionModelDataPath)
  throws SQLException, NoSuchFileException, InvalidMissionModelTypeException
  {
    try (final var connection = this.dataSource.getConnection();
         final var getFileAction = new GetUploadedFileAction(connection);
         final var createModelAction = new CreateModelAction(connection)) {
      // Validate that the file being used as a mission model file is a non-executable model.json
      final var modelPath = missionModelDataPath.resolve(getFileAction.get(modelInput.uploadedFileId()));
      try (final var fileReader = new FileReader(modelPath.toString());
           final var parser = Json.createParser(fileReader)) {
        if(!parser.hasNext()) {
          throw new InvalidMissionModelTypeException(ModelType.JSON, ModelType.JAR);
        }
      } catch (final IOException e) {
        throw new NoSuchFileException("Mission Model file does not exist at %s".formatted(modelPath.toString()));
      } catch (final JsonParsingException e) {
        throw new InvalidMissionModelTypeException(ModelType.JSON, ModelType.JAR);
      }

      // Otherwise, allow the insert
      return createModelAction.apply(modelInput.modelName(), modelInput.requester(), modelInput.uploadedFileId());
    }
  }

  @Override
  public void updateModelParameters(final MissionModelId missionModelId, final List<Parameter> modelParameters)
  {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var createModelParametersAction = new CreateModelParametersAction(connection)) {
        final var id = missionModelId.id();
        createModelParametersAction.apply(id, modelParameters);
      }
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to update derived data for mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public void updateActivityTypes(final MissionModelId missionModelId, final Map<String, ActivityType> activityTypes, final List<String> subsystems)
  {
    try (final var connection = this.dataSource.getConnection()) {
      final Map<String, Integer> mapSubsystemsToIds;
      try (final var insertSubsystemsAction = new InsertSubsystemsAction(connection)) {
        mapSubsystemsToIds = insertSubsystemsAction.apply(subsystems);
      } catch (final SQLException ex) {
        throw new DatabaseException(
            "Failed to update derived data for mission model with id `%s`".formatted(missionModelId), ex);
      }

      try (final var insertActivityTypesAction = new InsertActivityTypesAction(connection)) {
        final var id = missionModelId.id();
        insertActivityTypesAction.apply((int) id, activityTypes.values(), mapSubsystemsToIds);
      }
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to update derived data for mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public void updateResourceTypes(final MissionModelId missionModelId, final Map<String, Resource<?>> resources)
  {
    final var resourceTypes = resources.entrySet()
                                       .stream()
                                       .collect(Collectors.toMap(
                                           Map.Entry::getKey,
                                           entry -> entry.getValue().getOutputType().getSchema()));
    try (final var connection = this.dataSource.getConnection()) {
      try (final var insertResourceTypesAction = new InsertResourceTypesAction(connection)) {
        final long id = missionModelId.id();
        insertResourceTypesAction.apply((int) id, resourceTypes);
      }
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to update derived data for mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public Map<MissionModelId, List<ActivityDirectiveForValidation>> getUnvalidatedDirectives() {
    try (final var connection = this.dataSource.getConnection();
         final var unvalidatedDirectivesAction = new GetUnvalidatedDirectivesAction(connection)) {
      return unvalidatedDirectivesAction.get();
    } catch (SQLException ex) {
      throw new DatabaseException("Failed to get unvalidated activity directives", ex);
    }
  }

  @Override
  public void updateDirectiveValidations(List<Pair<ActivityDirectiveForValidation, MissionModelService.BulkArgumentValidationResponse>> updates) {
    try (final var connection = this.dataSource.getConnection();
         final var updateAction = new UpdateActivityDirectiveValidationsAction(connection)) {
      updateAction.apply(updates);
    } catch (SQLException ex) {
      throw new DatabaseException("Failed to update activity directive validations", ex);
    }
  }

  private static MissionModelJar missionModelRecordToMissionModelJar(final MissionModelRecord record) {
    if(record.executable()) {
      return new ExecutableModel(record.mission(), record.name(), record.version(), record.owner(), record.path());
    }
    return new NonExecutableModel(record.mission(), record.name(), record.version(), record.owner(), record.path());
  }
}
