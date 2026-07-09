package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResultsInterface;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.ValidationNotice;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import gov.nasa.jpl.aerie.merlin.server.models.MissionModelJar;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface MissionModelService {
  Map<MissionModelId, MissionModelJar> getMissionModels();

  MissionModelJar getMissionModelById(MissionModelId missionModelId)
  throws NoSuchMissionModelException;

  Map<String, ValueSchema> getResourceSchemas(MissionModelId missionModelId)
  throws NoSuchMissionModelException;

  /**
   * getActivityTypes uses the cached result of refreshActivityTypes. For this reason, refreshActivityTypes
   * should be called first.
   */
  Map<String, ActivityType> getActivityTypes(MissionModelId missionModelId)
  throws NoSuchMissionModelException;
  // TODO: Provide a finer-scoped validation return type. Mere strings make all validations equally severe.
  List<ValidationNotice> validateActivityArguments(MissionModelId missionModelId, SerializedActivity activity)
  throws NoSuchMissionModelException, InstantiationException;

  Map<ActivityDirectiveId, ActivityInstantiationFailure> validateActivityInstantiations(
      MissionModelId missionModelId,
      Map<ActivityDirectiveId,
      SerializedActivity> activities
  ) throws NoSuchMissionModelException, LocalMissionModelService.MissionModelLoadException;

  List<BulkEffectiveArgumentResponse> getActivityEffectiveArgumentsBulk(
      MissionModelId missionModelId,
      List<SerializedActivity> serializedActivities)
  throws NoSuchMissionModelException;

  List<ValidationNotice> validateModelArguments(MissionModelId missionModelId, Map<String, SerializedValue> arguments)
  throws NoSuchMissionModelException,
         LocalMissionModelService.MissionModelLoadException,
         InstantiationException;

  List<Parameter> getModelParameters(MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoader.MissionModelLoadException;

  Map<String, SerializedValue> getModelEffectiveArguments(MissionModelId missionModelId, Map<String, SerializedValue> arguments)
  throws NoSuchMissionModelException,
         LocalMissionModelService.MissionModelLoadException,
         InstantiationException;

  SimulationResultsInterface runSimulation(
      final Plan plan,
      final Consumer<Duration> writer,
      final Supplier<Boolean> canceledListener,
      final SimulationResourceManager resourceManager,
      final SimulationReuseStrategy simulationReuseStrategy
  ) throws NoSuchMissionModelException, MissionModelService.NoSuchActivityTypeException;

  void refreshModelParameters(MissionModelId missionModelId) throws NoSuchMissionModelException;
  void refreshActivityTypes(MissionModelId missionModelId) throws NoSuchMissionModelException;
  void refreshResourceTypes(MissionModelId missionModelId) throws NoSuchMissionModelException;

  sealed interface ActivityInstantiationFailure {
    record NoSuchActivityType(NoSuchActivityTypeException ex) implements ActivityInstantiationFailure { }
    record InstantiationFailure(InstantiationException ex) implements ActivityInstantiationFailure { }
  }

  final class NoSuchMissionModelException extends Exception {
    public final MissionModelId missionModelId;

    public NoSuchMissionModelException(final MissionModelId missionModelId, final Throwable cause) {
      super("No mission model exists with id `" + missionModelId + "`", cause);
      this.missionModelId = missionModelId;
    }

    public NoSuchMissionModelException(final MissionModelId missionModelId) { this(missionModelId, null); }
  }

  final class NoSuchActivityTypeException extends Exception {
    public final String activityTypeId;

    public NoSuchActivityTypeException(final String activityTypeId, final Throwable cause) {
      super(cause);
      this.activityTypeId = activityTypeId;
    }

    public NoSuchActivityTypeException(final String activityTypeId) { this(activityTypeId, null); }
  }

  sealed interface BulkEffectiveArgumentResponse {
    record Success(SerializedActivity activity) implements  BulkEffectiveArgumentResponse { }
    record TypeFailure(NoSuchActivityTypeException ex) implements  BulkEffectiveArgumentResponse { }
    record InstantiationFailure(InstantiationException ex) implements  BulkEffectiveArgumentResponse { }
  }

  sealed interface BulkArgumentValidationResponse {
    record Success() implements BulkArgumentValidationResponse { }
    record Validation(List<ValidationNotice> notices) implements BulkArgumentValidationResponse { }
    record NoSuchMissionModelError(NoSuchMissionModelException ex) implements BulkArgumentValidationResponse { }
    record NoSuchActivityError(NoSuchActivityTypeException ex) implements BulkArgumentValidationResponse { }
    record InstantiationError(InstantiationException ex) implements BulkArgumentValidationResponse { }
  }
}
