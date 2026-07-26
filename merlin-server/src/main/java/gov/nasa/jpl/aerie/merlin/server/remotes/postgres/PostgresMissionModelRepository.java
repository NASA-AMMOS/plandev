package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import gov.nasa.jpl.aerie.merlin.server.models.MissionModelJar;
import gov.nasa.jpl.aerie.merlin.server.remotes.MissionModelRepository;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;

import javax.sql.DataSource;
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
            .orElseThrow(NoSuchMissionModelException::new);
      }
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to retrieve mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public Map<String, ActivityType> getActivityTypes(final MissionModelId missionModelId) throws NoSuchMissionModelException {
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
  public List<Parameter> getModelParameters(final MissionModelId missionModelId) throws NoSuchMissionModelException {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getModelParametersAction = new GetModelParametersAction(connection)) {
        return getModelParametersAction.get(missionModelId.id());
      }
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to retrieve stored model parameters for mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public void updateModelParameters(final MissionModelId missionModelId, final List<Parameter> modelParameters)
  throws NoSuchMissionModelException {
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
  public boolean updateExternalIdentityHash(final MissionModelId missionModelId, final String identityHash) {
    try (final var connection = this.dataSource.getConnection();
         final var action = new UpdateExternalIdentityHashAction(connection)) {
      return action.apply(missionModelId.id(), identityHash);
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to record external identity hash for mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public void updateActivityTypes(final MissionModelId missionModelId, final Map<String, ActivityType> activityTypes, final List<String> subsystems)
  throws NoSuchMissionModelException {
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
  throws NoSuchMissionModelException {
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
  public Map<String, gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema> getResourceTypes(final MissionModelId missionModelId)
  throws NoSuchMissionModelException {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getResourceTypesAction = new GetResourceTypesAction(connection)) {
        return getResourceTypesAction.get(missionModelId.id());
      }
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to retrieve resource types for mission model with id `%s`".formatted(missionModelId), ex);
    }
  }

  @Override
  public void updateResourceTypeSchemas(
      final MissionModelId missionModelId,
      final Map<String, gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema> resourceTypes)
  throws NoSuchMissionModelException {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var insertResourceTypesAction = new InsertResourceTypesAction(connection)) {
        insertResourceTypesAction.apply((int) missionModelId.id(), resourceTypes);
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
    final var model = new MissionModelJar();
    model.mission = record.mission();
    model.name = record.name();
    model.version = record.version();
    model.owner = record.owner();
    model.modelType = record.modelType();
    model.externalBackend = record.externalBackend();
    model.externalModelKey = record.externalModelKey();
    model.externalIdentityHash = record.externalIdentityHash();
    model.path = record.path();

    return model;
  }
}
