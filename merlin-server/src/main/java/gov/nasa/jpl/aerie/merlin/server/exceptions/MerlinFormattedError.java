package gov.nasa.jpl.aerie.merlin.server.exceptions;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.json.FormattedError;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader.MissionModelLoadException;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonEntityException;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.NoSuchMissionModelException;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.NoSuchActivityTypeException;

import javax.json.Json;

/**
 * Class for formatting exceptions thrown into JSON objects that meet the Aerie HTTP endpoint error message format
 * Relevant ticket going over said format: https://github.com/NASA-AMMOS/aerie/issues/1732
 */
@JsonSerialize(using = FormattedError.FormattedErrorSerializer.class)
public class MerlinFormattedError extends FormattedError {
  // region "NO SUCH X" Exceptions
  public MerlinFormattedError(NoSuchPlanException npe) {
    super(
        AerieService.MERLIN_SERVER,
        "NO_SUCH_PLAN",
        npe,
        Json.createObjectBuilder()
            .add("plan_id", npe.id.id())
            .build()
        );
  }

  public MerlinFormattedError(NoSuchPlanDatasetException npe) {
    super(
        AerieService.MERLIN_SERVER,
        "NO_SUCH_PLAN_DATASET",
        npe,
        Json.createObjectBuilder()
            .add("dataset_id", npe.id.id())
            .build()
    );
  }

  public MerlinFormattedError(NoSuchMissionModelException nme) {
    super(
        AerieService.MERLIN_SERVER,
        "NO_SUCH_MISSION_MODEL",
        nme,
        Json.createObjectBuilder()
            .add("mission_model_id", nme.missionModelId.id())
            .build()
    );
  }

  public MerlinFormattedError(NoSuchActivityTypeException nae) {
    super(
        AerieService.MERLIN_SERVER,
        "NO_SUCH_ACTIVITY_TYPE",
        nae,
        Json.createObjectBuilder()
            .add("activity_type", nae.activityTypeId)
            .build()
    );
  }

  public MerlinFormattedError(NoSuchActivityTypeException nae, String message) {
    super(
        AerieService.MERLIN_SERVER,
        "NO_SUCH_ACTIVITY_TYPE",
        message,
        nae,
        Json.createObjectBuilder()
            .add("activity_type", nae.activityTypeId)
            .build()
    );
  }
  // endregion

  public MerlinFormattedError(MissionModelLoadException mle) {
    super(AerieService.MERLIN_SERVER, "MISSION_MODEL_LOAD_EXCEPTION", mle);
  }

  public MerlinFormattedError(InvalidJsonEntityException ex) {
    super(AerieService.MERLIN_SERVER, "JSON_PARSING_EXCEPTION", ex);
  }

  public MerlinFormattedError(InputMismatchException ex) {
    super(AerieService.MERLIN_SERVER, "INPUT_MISMATCH_EXCEPTION", ex);
  }

  public MerlinFormattedError(SimulationDatasetMismatchException ex) {
    super(AerieService.MERLIN_SERVER, "SIM_DATASET_MISMATCH_EXCEPTION", ex);
  }
}
