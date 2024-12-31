package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.types.MissionModelId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.Timestamp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record HasuraAction<I extends HasuraAction.Input>(String name, I input, Session session)
{
  public record Session(String hasuraRole, String hasuraUserId) { }

  public sealed interface Input { }

  public record MissionModelInput(MissionModelId missionModelId) implements Input { }
  public record PlanInput(PlanId planId) implements Input { }
  public record SimulateInput(PlanId planId, Optional<Boolean> force) implements Input {}
  public record ConstraintViolationsInput(PlanId planId,
                                          Optional<SimulationDatasetId> simulationDatasetId,
                                          Optional<Boolean> force) implements Input {}
  public record ActivityInput(MissionModelId missionModelId,
                              String activityTypeName,
                              Map<String, SerializedValue> arguments) implements Input {}
  public record ActivityBulkInput(MissionModelId missionModelId, List<SerializedActivity> activities) implements Input {}
  public record MissionModelArgumentsInput(MissionModelId missionModelId,
                              Map<String, SerializedValue> arguments) implements Input {}
  public record UploadExternalDatasetInput(PlanId planId,
                                           Optional<SimulationDatasetId> simulationDatasetId,
                                           Timestamp datasetStart,
                                           ProfileSet profileSet) implements Input {}
  public record ExtendExternalDatasetInput(DatasetId datasetId,
                                           ProfileSet profileSet) implements Input {}

  public record ConstraintsInput(MissionModelId missionModelId, Optional<PlanId> planId) implements Input {}
}
