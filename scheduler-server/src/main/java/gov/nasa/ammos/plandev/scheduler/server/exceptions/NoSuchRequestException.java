package gov.nasa.jpl.aerie.scheduler.server.exceptions;

import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;

public final class NoSuchRequestException extends Exception {
  public NoSuchRequestException(final SpecificationId specificationId, final long specificationRevision) {
    super(String.format(
        "No scheduling request exists with id `%d` and revision `%d`",
        specificationId.id(),
        specificationRevision));
  }
}
