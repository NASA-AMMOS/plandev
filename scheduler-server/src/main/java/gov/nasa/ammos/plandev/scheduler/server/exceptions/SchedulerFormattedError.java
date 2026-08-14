package gov.nasa.jpl.aerie.scheduler.server.exceptions;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import gov.nasa.jpl.aerie.json.FormattedError;
import gov.nasa.jpl.aerie.scheduler.server.http.InvalidJsonEntityException;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.DatabaseException;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinServiceException;

import javax.json.Json;

@JsonSerialize(using = FormattedError.FormattedErrorSerializer.class)
public class SchedulerFormattedError extends FormattedError {
  //region NO SUCH X
  public SchedulerFormattedError(NoSuchSpecificationException ex) {
    super(
        AerieService.SCHEDULER_SERVER,
        "NO_SUCH_SCHEDULING_SPECIFICATION",
        ex,
        Json.createObjectBuilder()
            .add("specification_id", ex.specificationId.id())
            .build()
    );
  }

  public SchedulerFormattedError(NoSuchPlanException npe) {
    super(
        AerieService.SCHEDULER_SERVER,
        "NO_SUCH_PLAN",
        npe,
        Json.createObjectBuilder()
            .add("plan_id", npe.getInvalidPlanId().id())
            .build()
    );
  }
  //endregion

  public SchedulerFormattedError(InvalidJsonEntityException ex) {
    super(AerieService.SCHEDULER_SERVER, "JSON_PARSING_EXCEPTION", ex);
  }

  public SchedulerFormattedError(MerlinServiceException ex) {
    super(AerieService.SCHEDULER_SERVER, "PLAN_SERVICE_EXCEPTION", ex);
  }

  public SchedulerFormattedError(SpecificationLoadException ex) {
    super(
        AerieService.SCHEDULER_SERVER,
        "SPECIFICATION_LOAD_EXCEPTION",
        ex,
        SchedulingCompilationError.schedulingErrorJsonP.unparse(ex.errors));
  }

  public SchedulerFormattedError(DatabaseException ex) {
    super(AerieService.SCHEDULER_SERVER, "DATABASE_EXCEPTION", ex);
  }
}
