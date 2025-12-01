package gov.nasa.jpl.plandev.permissions.exceptions;

import gov.nasa.jpl.plandev.permissions.gql.PlanId;
public final class NoSuchPlanException extends Exception {
  public final PlanId id;

  public NoSuchPlanException(final PlanId id) {
    super("No plan exists with id '%s'".formatted(id));
    this.id = id;
  }
}
