package gov.nasa.jpl.aerie.permissions.exceptions;


import gov.nasa.jpl.aerie.permissions.gql.SchedulingSpecificationId;

public final class NoSuchSchedulingSpecificationException extends Exception {
  public final SchedulingSpecificationId id;

  public NoSuchSchedulingSpecificationException(final SchedulingSpecificationId specificationId) {
    super("No scheduling specification exists with id '%s'".formatted(specificationId.id()));
    this.id = specificationId;
  }
}
