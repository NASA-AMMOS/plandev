package gov.nasa.jpl.aerie.permissions.exceptions;

import gov.nasa.jpl.aerie.permissions.gql.PlanId;
public final class NoSuchPlanException extends Exception {
  public final PlanId id;

  public NoSuchPlanException(final PlanId id) {
    super("No plan exists with id '%s'".formatted(id.id()));
    this.id = id;
  }
}
