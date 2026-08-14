package gov.nasa.ammos.plandev.permissions.exceptions;

import gov.nasa.ammos.plandev.permissions.gql.PlanId;
public final class NoSuchPlanException extends Exception {
  public final PlanId id;

  public NoSuchPlanException(final PlanId id) {
    super("No plan exists with id '%s'".formatted(id.id()));
    this.id = id;
  }
}
