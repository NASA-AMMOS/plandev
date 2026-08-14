package gov.nasa.jpl.aerie.scheduler.server.exceptions;

import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;

import java.util.List;

public final class SpecificationLoadException extends Exception {
  public List<SchedulingCompilationError.UserCodeError> errors;
  public SpecificationLoadException(final SpecificationId specificationId, final List<SchedulingCompilationError.UserCodeError> errors) {
    super("Failed to load specification with id `" + specificationId.id());
    this.errors = errors;
  }
}
