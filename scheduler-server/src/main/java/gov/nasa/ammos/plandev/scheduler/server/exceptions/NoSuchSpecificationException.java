package gov.nasa.ammos.plandev.scheduler.server.exceptions;

import gov.nasa.ammos.plandev.scheduler.server.models.SpecificationId;

public final class NoSuchSpecificationException extends Exception {
  public final SpecificationId specificationId;

  public NoSuchSpecificationException(final SpecificationId specificationId) {
    super("No scheduling specification exists with id `" + specificationId.id() + "`");
    this.specificationId = specificationId;
  }
}
