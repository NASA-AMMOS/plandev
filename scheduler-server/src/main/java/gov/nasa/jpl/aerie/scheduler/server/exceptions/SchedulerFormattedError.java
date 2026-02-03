package gov.nasa.jpl.aerie.scheduler.server.exceptions;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import gov.nasa.jpl.aerie.json.FormattedError;
import gov.nasa.jpl.aerie.scheduler.server.http.InvalidJsonEntityException;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinServiceException;

@JsonSerialize(using = FormattedError.FormattedErrorSerializer.class)
public class SchedulerFormattedError extends FormattedError {
  //region NO SUCH X
  public SchedulerFormattedError(NoSuchSpecificationException ex) {
    super(AerieService.SCHEDULER_SERVER, "NO_SUCH_SCHEDULING_SPECIFICATION", ex);
  }

  public SchedulerFormattedError(NoSuchPlanException npe) {
    super(AerieService.SCHEDULER_SERVER, "NO_SUCH_PLAN", npe);
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
}
